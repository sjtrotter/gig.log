package cloud.trotter.log.gig.core.data.analytics

import cloud.trotter.log.gig.core.database.analytics.AnalyticsDao
import cloud.trotter.log.gig.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.log.gig.core.database.analytics.ReceiptDao
import cloud.trotter.log.gig.core.database.analytics.ReceiptMatchDao
import cloud.trotter.log.gig.core.database.analytics.ReceiptMatchRowEntity
import cloud.trotter.log.gig.domain.analytics.JobSettlementFold
import cloud.trotter.log.gig.domain.analytics.receipts.MatchOrigin
import cloud.trotter.log.gig.domain.analytics.receipts.ReceiptMatch
import cloud.trotter.log.gig.domain.analytics.receipts.SettlementPlan
import cloud.trotter.log.gig.domain.model.event.payload.JobSettlementKind
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchRole
import cloud.trotter.log.gig.domain.state.ReceiptScope
import timber.log.Timber

private const val TAG = "Projector"

/** #1601: why a settlement's rows went inactive (`receipt_match_rows.endReason`). An undo leaves it null. */
internal object SettlementEnd {
    const val WITHDRAWN = "WITHDRAWN"
    const val CONFLICT = "CONFLICT"
}

/**
 * #1601: apply one whole-job settlement revision (`JOB_RECEIPT_SETTLED`) inside the projector's batch transaction, in
 * event order. Every check runs before any write, so a revision writes every row or none; each refusal is a counted
 * skip with a WARN of ids only. A settlement is identified by its command id `settle:<jobId>`; its rows live in
 * `receipt_match_rows` with origin APP and keep the ORIGINAL priors its first revision found.
 *
 * - **Initial** (no row yet for the command): the receipt exists, is of this shift and job, unproven, at exactly the
 *   payload's evidence revision and total; [ReceiptMatch.settlement] (the strict whole-group owner) passes. Writes
 *   `DeliveryCorrections.applySettlementShare` per row (DROP_SHARE, no `driverAdjustedAt`) and one APP row each.
 * - **Replacement** (a later revision while one is active): the same receipt checks; the revision is past the active
 *   one; the payload names exactly the settlement's rows; each still holds the active allocation
 *   ([ReceiptMatch.holdsWritten]) and no other active match names it. Rewrites the shares; the priors stay.
 * - **Withdraw** (A2): while every row holds the active allocation, restore the original priors and end the rows
 *   WITHDRAWN; otherwise write nothing and end them CONFLICT.
 *
 * An ended settlement (undone, withdrawn or in conflict) takes no further revision. Only rows sequenced before the
 * event take part (the #1517 batch-boundary rule), so one batch, many batches and a rebuild agree.
 */
internal class JobSettlementApply(
    private val analyticsDao: AnalyticsDao,
    private val receiptDao: ReceiptDao,
    private val matchDao: ReceiptMatchDao,
) {

    /** Returns true for a counted skip. */
    suspend fun apply(fold: JobSettlementFold): Boolean {
        fun refuse(reason: String): Boolean {
            Timber.tag(TAG).w("JOB_RECEIPT_SETTLED %s r%d refused: %s", fold.commandId, fold.revision, reason)
            return true
        }
        val existing = matchDao.rowsForCommand(fold.commandId)
        if (existing.any { it.origin != MatchOrigin.APP.name }) return refuse("command is a dasher's match")
        val active = existing.filter { it.undoneAt == null }
        if (existing.isNotEmpty() && active.isEmpty()) return refuse("settlement already ended")
        val activeRevision = active.mapNotNull { it.settlementRevision }.maxOrNull()
        if (activeRevision != null && fold.revision <= activeRevision) return refuse("revision not past the active one")

        if (fold.kind == JobSettlementKind.WITHDRAW) {
            if (active.isEmpty()) return refuse("no active settlement")
            return withdraw(fold, active)
        }

        val receipt = receiptDao.receipt(fold.evidenceId) ?: return refuse("receipt not found")
        if (receipt.sessionId != fold.sessionId) return refuse("receipt of another shift")
        if (receipt.jobId != fold.jobId) return refuse("receipt of another job")
        if (receipt.scope != ReceiptScope.UNPROVEN.name) return refuse("receipt is proven")
        if (receipt.lastEventSequenceId > fold.sequenceId) return refuse("receipt revised after the settlement")
        if (receipt.revision != fold.evidenceRevision) return refuse("receipt revision is not the settled one")
        if (receipt.totalCents != fold.receiptCents) return refuse("receipt total changed")

        val entities = analyticsDao.deliveryRecordsInSessionBefore(fold.sessionId, fold.sequenceId)
        val rows = entities.map { it.toDomain() }
        val sessionActive = matchDao.activeRowsInSession(fold.sessionId).map { it.toActive() }
        if (active.isEmpty()) {
            val saved = receiptDao.receiptsInSession(fold.sessionId).map { it.toSaved() }
            val plan = ReceiptMatch.settlement(fold.jobId, fold.evidenceId, fold.shares, rows, sessionActive, saved)
            if (plan is SettlementPlan.Refuse) return refuse(plan.reason)
            val byId = entities.associateBy { it.eventSequenceId }
            val written = (plan as SettlementPlan.Write).rows.map { (row, cents) ->
                val prior = byId.getValue(row.eventSequenceId)
                analyticsDao.upsertDelivery(DeliveryCorrections.applySettlementShare(prior, cents))
                settlementRow(fold, prior, cents)
            }
            matchDao.insertAll(written)
            Timber.tag(TAG).d("JOB_RECEIPT_SETTLED %s r%d: %d rows, %d¢", fold.commandId, fold.revision, written.size, fold.receiptCents)
            return false
        }

        // Replacement: the payload must name exactly the settlement's rows, each still holding the active allocation.
        val byTask = rows.filter { it.jobId == fold.jobId }.groupBy { it.taskId }
        val activeIds = active.map { it.deliveryEventSequenceId }.toSet()
        if (byTask.values.flatten().any { it.eventSequenceId !in activeIds }) return refuse("the job has a delivery the settlement does not hold")
        val updates = ArrayList<Pair<ReceiptMatchRowEntity, Long>>(fold.shares.size)
        for ((taskId, cents) in fold.shares) {
            val row = byTask[taskId]?.singleOrNull() ?: return refuse("drop $taskId has no single delivery row")
            val mine = active.firstOrNull { it.deliveryEventSequenceId == row.eventSequenceId }
                ?: return refuse("delivery ${row.eventSequenceId} is not the settlement's")
            if (!ReceiptMatch.holdsWritten(mine.toActive(), row)) return refuse("delivery ${row.eventSequenceId} changed since the settlement")
            if (sessionActive.any { it.deliveryEventSequenceId == row.eventSequenceId && it.commandId != fold.commandId }) {
                return refuse("delivery ${row.eventSequenceId} is named by another match")
            }
            updates += mine to cents
        }
        if (updates.size != active.size) return refuse("the settlement holds a delivery the revision does not name")
        val byId = entities.associateBy { it.eventSequenceId }
        updates.forEach { (m, cents) ->
            analyticsDao.upsertDelivery(DeliveryCorrections.applySettlementShare(byId.getValue(m.deliveryEventSequenceId), cents))
        }
        matchDao.updateAll(updates.map { (m, cents) -> m.copy(newPayCents = cents, settlementRevision = fold.revision) })
        Timber.tag(TAG).d("JOB_RECEIPT_SETTLED %s r%d replaced r%d: %d¢", fold.commandId, fold.revision, activeRevision, fold.receiptCents)
        return false
    }

    /** A2: restore the original priors whole while every row holds the active allocation, else mark CONFLICT. */
    private suspend fun withdraw(fold: JobSettlementFold, active: List<ReceiptMatchRowEntity>): Boolean {
        val deliveries = active.map { analyticsDao.deliveryRecord(it.deliveryEventSequenceId) }
        val intact = active.zip(deliveries).all { (m, d) -> ReceiptMatch.holdsWritten(m.toActive(), d?.toDomain()) }
        if (intact) {
            active.zip(deliveries).forEach { (m, d) -> analyticsDao.upsertDelivery(DeliveryCorrections.restoreBeforeMatch(d!!, m)) }
            matchDao.markEnded(fold.commandId, fold.settledAt, fold.sequenceId, SettlementEnd.WITHDRAWN)
            Timber.tag(TAG).d("JOB_RECEIPT_SETTLED %s r%d: withdrawn, %d rows restored", fold.commandId, fold.revision, active.size)
        } else {
            matchDao.markEnded(fold.commandId, fold.settledAt, fold.sequenceId, SettlementEnd.CONFLICT)
            Timber.tag(TAG).w("JOB_RECEIPT_SETTLED %s r%d: withdrawal found an edited row, marked conflict", fold.commandId, fold.revision)
        }
        return false
    }

    private fun settlementRow(fold: JobSettlementFold, prior: DeliveryRecordEntity, cents: Long) = ReceiptMatchRowEntity(
        commandId = fold.commandId,
        deliveryEventSequenceId = prior.eventSequenceId,
        sessionId = fold.sessionId,
        evidenceId = fold.evidenceId,
        role = ReceiptMatchRole.COVERED.name,
        matchEventSequenceId = fold.sequenceId,
        matchedAt = fold.settledAt,
        priorRealizedPay = prior.realizedPay,
        priorPayBasis = prior.payBasis,
        priorNetProfit = prior.netProfit,
        priorTip = prior.tip,
        priorBasePay = prior.basePay,
        priorDriverAdjustedAt = prior.driverAdjustedAt,
        newPayCents = cents,
        origin = MatchOrigin.APP.name,
        settlementRevision = fold.revision,
    )
}
