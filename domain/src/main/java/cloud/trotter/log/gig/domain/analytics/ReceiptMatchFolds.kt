package cloud.trotter.log.gig.domain.analytics

import cloud.trotter.log.gig.domain.model.event.SequencedAppEvent
import cloud.trotter.log.gig.domain.model.event.payload.JobReceiptSettledPayload
import cloud.trotter.log.gig.domain.model.event.payload.JobSettlementKind
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchRole
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchUndonePayload
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchedPayload

/** A dasher's receipt match (#1599) as the pure fold reads it; the projector applies it against the stored rows. */
data class ReceiptMatchFold(
    val commandId: String,
    val sessionId: String,
    val evidenceId: String,
    val receiptCents: Long,
    /** COVERED lines in payload order: delivery PK to cents. */
    val covered: List<Pair<Long, Long>>,
    /** The CLEARED delivery PKs the preview showed; the projector derives its own set and compares. */
    val previewCleared: Set<Long>,
    /** The event's `occurredAt`: the rows' `driverAdjustedAt`. */
    val matchedAt: Long,
    /** The event's own sequence id: the apply reads only delivery rows sequenced before it. */
    val sequenceId: Long,
)

/** A dasher's undo of the receipt match [matchCommandId] (#1599). */
data class ReceiptMatchUndoFold(val sessionId: String, val matchCommandId: String, val undoneAt: Long, val sequenceId: Long)

/** One validated whole-job settlement revision (#1601) as the projector applies it. */
data class JobSettlementFold(
    /** `settle:<jobId>`, shared by every revision. */
    val commandId: String,
    val sessionId: String,
    val jobId: String,
    val revision: Int,
    val kind: JobSettlementKind,
    val evidenceId: String,
    val evidenceRevision: Int,
    val receiptCents: Long,
    /** SETTLE: taskId to cents, summing to [receiptCents]. WITHDRAW: empty. */
    val shares: List<Pair<String, Long>>,
    val settledAt: Long,
    val sequenceId: Long,
)

/**
 * The `RECEIPT_MATCHED` / `RECEIPT_MATCH_UNDONE` folds (#1599) and the machine `JOB_RECEIPT_SETTLED` fold (#1601), the [CorrectionFolds.foldSessionReportCorrection]
 * shape: the target must be an ENDED shift in [SessionFoldContext] at the event's log position, so one batch or many
 * give the same answer, and the outcome carries no context (no liveness advance, no session re-upsert). Pure.
 */
internal object ReceiptMatchFolds {

    fun foldMatched(event: SequencedAppEvent, context: SessionFoldContext?): FoldOutcome {
        val e = event.event
        val p = e.payload as? ReceiptMatchedPayload
            ?: return FoldOutcome(context = null, skip = "RECEIPT_MATCHED: missing/malformed payload")
        if (e.sessionId != p.sessionId || context == null || context.sessionId != p.sessionId || context.endedAt == null) {
            return FoldOutcome(context = null, skip = "RECEIPT_MATCHED: target shift unknown or not ended at this log position")
        }
        val covered = p.lines.filter { it.role == ReceiptMatchRole.COVERED }
        val cleared = p.lines.filter { it.role == ReceiptMatchRole.CLEARED }
        val malformed = p.commandId.isBlank() || p.receiptCents <= 0 || covered.isEmpty() ||
            p.lines.map { it.deliveryEventSequenceId }.distinct().size != p.lines.size ||
            covered.any { (it.newPayCents ?: -1) < 0 } || cleared.any { it.newPayCents != null } ||
            covered.sumOf { it.newPayCents ?: 0 } != p.receiptCents
        if (malformed) return FoldOutcome(context = null, skip = "RECEIPT_MATCHED: malformed match")
        return FoldOutcome(
            context = null,
            receiptMatch = ReceiptMatchFold(
                commandId = p.commandId,
                sessionId = p.sessionId,
                evidenceId = p.evidenceId,
                receiptCents = p.receiptCents,
                covered = covered.map { it.deliveryEventSequenceId to it.newPayCents!! },
                previewCleared = cleared.map { it.deliveryEventSequenceId }.toSet(),
                matchedAt = e.occurredAt,
                sequenceId = event.sequenceId,
            ),
        )
    }

    /**
     * #1601: a machine settlement folds while its shift is live, so the target shift must EXIST at this log position,
     * not have ended (the #1599 folds require ENDED). Every malformed shape is a counted skip: a blank id, a
     * command id that is not the job's, a revision below 1; and on SETTLE a receipt of 0 or less, a card count below 2,
     * lines that are not exactly that many distinct drops, a negative share, shares not summing to the receipt, or a
     * tip/base split stated on some lines and not others, or not summing to its line.
     */
    fun foldJobSettled(event: SequencedAppEvent, context: SessionFoldContext?): FoldOutcome {
        val e = event.event
        val p = e.payload as? JobReceiptSettledPayload
            ?: return FoldOutcome(context = null, skip = "JOB_RECEIPT_SETTLED: missing/malformed payload")
        if (e.sessionId != p.sessionId || context == null || context.sessionId != p.sessionId) {
            return FoldOutcome(context = null, skip = "JOB_RECEIPT_SETTLED: target shift unknown at this log position")
        }
        if (!wellFormed(p)) return FoldOutcome(context = null, skip = "JOB_RECEIPT_SETTLED: malformed settlement")
        return FoldOutcome(
            context = null,
            jobSettlement = JobSettlementFold(
                commandId = p.commandId,
                sessionId = p.sessionId,
                jobId = p.jobId,
                revision = p.revision,
                kind = p.kind,
                evidenceId = p.evidenceId,
                evidenceRevision = p.evidenceRevision,
                receiptCents = p.receiptCents,
                shares = p.lines.map { it.taskId to it.cents },
                settledAt = e.occurredAt,
                sequenceId = event.sequenceId,
            ),
        )
    }

    private fun wellFormed(p: JobReceiptSettledPayload): Boolean {
        if (p.commandId.isBlank() || p.sessionId.isBlank() || p.jobId.isBlank() || p.evidenceId.isBlank()) return false
        if (p.commandId != JobReceiptSettledPayload.commandIdFor(p.jobId) || p.revision < 1 || p.evidenceRevision < 1) return false
        if (p.kind == JobSettlementKind.WITHDRAW) return p.lines.isEmpty()
        if (p.receiptCents <= 0 || p.cardDropoffCount < 2) return false
        val lines = p.lines
        if (lines.size != p.cardDropoffCount || lines.any { it.taskId.isBlank() }) return false
        if (lines.map { it.taskId }.distinct().size != lines.size) return false
        if (lines.any { it.cents < 0 } || lines.sumOf { it.cents } != p.receiptCents) return false
        val split = lines.count { it.tipCents != null || it.basePayCents != null }
        if (split != 0) {
            if (split != lines.size) return false
            if (lines.any { (it.tipCents ?: -1) < 0 || (it.basePayCents ?: -1) < 0 || it.tipCents!! + it.basePayCents!! != it.cents }) return false
        }
        return true
    }

    fun foldUndone(event: SequencedAppEvent, context: SessionFoldContext?): FoldOutcome {
        val e = event.event
        val p = e.payload as? ReceiptMatchUndonePayload
            ?: return FoldOutcome(context = null, skip = "RECEIPT_MATCH_UNDONE: missing/malformed payload")
        if (e.sessionId != p.sessionId || context == null || context.sessionId != p.sessionId || context.endedAt == null) {
            return FoldOutcome(context = null, skip = "RECEIPT_MATCH_UNDONE: target shift unknown or not ended at this log position")
        }
        if (p.matchCommandId.isBlank()) return FoldOutcome(context = null, skip = "RECEIPT_MATCH_UNDONE: malformed undo")
        return FoldOutcome(
            context = null,
            receiptMatchUndo = ReceiptMatchUndoFold(p.sessionId, p.matchCommandId, e.occurredAt, event.sequenceId),
        )
    }
}
