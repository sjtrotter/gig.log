package cloud.trotter.log.gig.core.state.model

import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.DeclineEvidence
import cloud.trotter.log.gig.domain.model.event.payload.JobSettlementKind
import cloud.trotter.log.gig.domain.model.pay.ParsedPay
import cloud.trotter.log.gig.domain.state.PendingOffer
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.ReceiptProofGap
import cloud.trotter.log.gig.domain.state.ReceiptScope

/** Decisions made by one step that its before/after states cannot show. Never persisted. */
data class StepDecisions(val byPlatform: Map<Platform, RegionDecisions> = emptyMap()) {
    fun of(p: Platform): RegionDecisions = byPlatform[p] ?: RegionDecisions.NONE
    companion object { val NONE = StepDecisions() }
}

data class RegionDecisions(
    /** Last re-price written in the step wins. */
    val receiptReprice: ReceiptRepriceDecision? = null,
    /** The active task this step retired as a delivery. */
    val retiredTaskId: String? = null,
    /** #1598: the receipt evidence revision this step recorded; last wins. */
    val receiptEvidence: ReceiptEvidenceDecision? = null,
    /** #1119 S2: every offer outcome this step decided, in order. */
    val offerOutcomes: List<OfferOutcomeDecision> = emptyList(),
    /** #1601: every whole-job settlement revision this step decided, in order. */
    val jobSettlements: List<JobSettlementDecision> = emptyList(),
) {
    companion object { val NONE = RegionDecisions() }
}

/** #1119 S2: one offer instance's outcome, decided by the stepper; `EffectMap` only emits it. */
data class OfferOutcomeDecision(
    val instanceKey: String,
    val offer: PendingOffer,
    /** `OFFER_ACCEPTED`, `OFFER_DECLINED` or `OFFER_NOT_TAKEN`. */
    val outcome: AppEventType,
    /** `OfferPayload.decidedAt`. */
    val decidedAt: Long,
    /** The `LogEvent`'s occurredAt: the deciding `obs.timestamp`. */
    val occurredAt: Long,
    val sessionId: String?,
    val description: String?,
    val linkedJobId: String? = null,
    val ambiguousJobIds: List<String> = emptyList(),
    /** Set on a decline inferred from the confirm sheet (a demoted pool entry at shift end). */
    val declineEvidence: DeclineEvidence? = null,
    /** Post the outcome chat line. */
    val announce: Boolean,
    /** Suffix ", replaced by a new offer" on the chat line. */
    val replaced: Boolean = false,
    /** A direct offer took the slot of a match display (#881): no chat line. */
    val supersededByDirect: Boolean = false,
)

data class ReceiptRepriceDecision(
    val jobId: String,
    val parsedPay: ParsedPay,
    val shares: Map<String, Double>,
    val decidedAt: Long,
    val revision: Int,
    val sourceCaptureId: String? = null,
    /** #1598: the evidence record of the receipt this decision priced from. */
    val evidenceId: String? = null,
)

/** #1598: one read of a post-delivery receipt, saved as evidence (`RECEIPT_OBSERVED`). Moves no money. */
data class ReceiptEvidenceDecision(
    val evidenceId: String,
    val revision: Int,
    val platformWire: String,
    val jobId: String,
    val subjectTaskId: String,
    val firstSeenAt: Long,
    val observedAt: Long,
    val totalPay: Double,
    val parsedPay: ParsedPay?,
    val isExpanded: Boolean,
    val scope: ReceiptScope,
    val gap: ReceiptProofGap?,
    val sourceCaptureId: String?,
)

/** #1601: one whole-job settlement revision, decided by the stepper; `EffectMap` only emits it (`JOB_RECEIPT_SETTLED`). */
data class JobSettlementDecision(
    val jobId: String,
    val sessionId: String?,
    val revision: Int,
    val kind: JobSettlementKind,
    val evidenceId: String,
    val evidenceRevision: Int,
    val receiptCents: Long,
    val cardDropoffCount: Int,
    /** SETTLE: taskId to cents, ascending taskId. WITHDRAW: empty. */
    val shares: List<Pair<String, Long>>,
    val decidedAt: Long,
)

/** Invocation-local accumulator, explicitly passed through the decision-producing paths. */
internal class DecisionSink {
    private var receiptReprice: ReceiptRepriceDecision? = null
    private var retiredTaskId: String? = null
    private var receiptEvidence: ReceiptEvidenceDecision? = null
    private val offerOutcomes = mutableListOf<OfferOutcomeDecision>()
    private val jobSettlements = mutableListOf<JobSettlementDecision>()

    fun reprice(d: ReceiptRepriceDecision) { receiptReprice = d }
    fun retired(taskId: String) { retiredTaskId = taskId }
    fun receiptObserved(d: ReceiptEvidenceDecision) { receiptEvidence = d }
    fun offerOutcome(d: OfferOutcomeDecision) { offerOutcomes += d }
    /** #1601: one decision per job per step; a later one replaces an earlier one (the step's last word). */
    fun jobSettled(d: JobSettlementDecision) {
        jobSettlements.removeAll { it.jobId == d.jobId }
        jobSettlements += d
    }

    /**
     * #1601: an unchanged allocation decided nothing, but if this step already decided [jobId] from an earlier revision of
     * [evidenceId], that decision names [evidenceRevision] instead: the step's `RECEIPT_OBSERVED` carries the latest
     * revision, and the projector applies a settlement only at the receipt's current revision.
     */
    fun refreshJobSettlementEvidence(jobId: String, evidenceId: String, evidenceRevision: Int) {
        val i = jobSettlements.indexOfFirst { it.jobId == jobId && it.evidenceId == evidenceId }
        if (i >= 0) jobSettlements[i] = jobSettlements[i].copy(evidenceRevision = evidenceRevision)
    }
    fun build(): RegionDecisions =
        RegionDecisions(receiptReprice, retiredTaskId, receiptEvidence, offerOutcomes.toList(), jobSettlements.toList())
}

data class PlatformStep(val region: PlatformRegion, val decisions: RegionDecisions)
