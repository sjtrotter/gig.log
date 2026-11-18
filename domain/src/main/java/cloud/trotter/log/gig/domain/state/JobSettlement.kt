package cloud.trotter.log.gig.domain.state

import cloud.trotter.log.gig.domain.model.pay.ParsedPay
import kotlinx.serialization.Serializable

/**
 * #1601: one pay-bearing receipt read attributed to a job — the LATEST revision of one evidence record
 * (`RECEIPT_OBSERVED`), tracked on [Job.receiptReads] while the job is live and on [ClosedJobSettlement] for the same
 * episode after its close. Whole-job settlement's proof reads these; it never reads a cached receipt.
 */
@Serializable
data class JobReceiptRead(
    val evidenceId: String,
    val revision: Int,
    val subjectTaskId: String,
    val totalCents: Long,
    /** THIS revision's own itemization, null when it was collapsed; never one carried from an earlier revision. */
    val parsedPay: ParsedPay? = null,
    /** The read's rule declared its total is the whole offer's pay ([ParsedFields.PostTaskFields.receiptTotalIsOffer]). */
    val totalIsOffer: Boolean,
    /** [PlatformRegion.possibleWorkSeq] when this revision was read: the quiet-window reference (clause 11). */
    val possibleWorkSeq: Long,
    val observedAt: Long,
    /** `obs.timestamp` when this record was FIRST seen: where clause 10's windows end (work after it cannot own it). */
    val firstSeenAt: Long = observedAt,
    /** [PlatformRegion.acceptResolveSeq] when this record was FIRST seen; kept across its revisions (clause 10). */
    val acceptSeqAtFirstSeen: Long = 0,
)

/** The first clause of whole-job settlement's proof a job failed (#1601, `jobSettlementAt`), for the DEBUG line. */
@Serializable
enum class JobSettlementGap {
    READS_UNTRACKED, OFFER_COUNT, CARD_COUNT_MISSING, CARD_COUNT_ONE,
    UNASSIGNED, DROP_COUNT, DROP_NOT_MINTED, NO_DELIVERY_WITNESS, STORE_MISSING, STORE_MISMATCH,
    SCOPE_UNDECLARED, NO_TERMINAL_READ, READS_DISAGREE, OTHER_ACCEPT, POOLED_OFFER, RIVAL_POOLED, LATE_FRAME,
}

/**
 * #1601: what the stepper last emitted for a settlement — the `lastDecidedPay` pattern. An unchanged decision (same
 * kind, same cents, same shares) emits nothing.
 */
@Serializable
data class JobSettlementDecided(
    val withdrawn: Boolean,
    val evidenceId: String,
    val receiptCents: Long,
    /** taskId to cents. */
    val shares: Map<String, Long>,
)

/**
 * #1601: the closed-job settlement marker. Written by the job's close with the job as it closed, it lets a changed read
 * of the same receipt episode re-decide after the close (a late expansion, a disagreeing record). Cleared when another
 * job becomes active and at the session end.
 */
@Serializable
data class ClosedJobSettlement(
    /** The job as it closed; its [Job.receiptReads] keep taking revisions of their own records. */
    val job: Job,
    /** Every copy of the job's tasks at the close (the active task, the recent list, the job's own). */
    val copies: List<Task>,
    /** The drops whose `DELIVERY_COMPLETED` the close (or an earlier step) minted. */
    val mintedTaskIds: Set<String>,
    val sessionId: String?,
    /** The last emitted decision revision; 0 = none yet. */
    val revision: Int = 0,
    val decided: JobSettlementDecided? = null,
)
