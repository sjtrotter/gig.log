package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.domain.model.offer.OfferQuoteBasis
import cloud.trotter.log.gig.domain.model.offer.OfferScope
import cloud.trotter.log.gig.domain.model.pay.ParsedPay
import cloud.trotter.log.gig.domain.state.DropPayApportioner
import cloud.trotter.log.gig.domain.state.Job
import cloud.trotter.log.gig.domain.state.JobReceiptRead
import cloud.trotter.log.gig.domain.state.JobSettlementGap
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.PooledOffer
import cloud.trotter.log.gig.domain.state.ReceiptCoverage
import cloud.trotter.log.gig.domain.state.ReceiptProofGap
import cloud.trotter.log.gig.domain.state.ReceiptScope
import cloud.trotter.log.gig.domain.state.StoreKeys
import cloud.trotter.log.gig.domain.state.Task
import cloud.trotter.log.gig.domain.state.TaskPhase
import cloud.trotter.log.gig.domain.state.hasCustomerIdentity

/**
 * The drops a receipt read on THIS frame may pay (#1598) — see [ReceiptCoverage].
 *
 * A receipt carries no job identity, so the proof is the platform's own count plus the job's facts. The clauses, in
 * order; the first that fails is the [ReceiptCoverage.gap]:
 *  1. a subject drop was named (`NO_SUBJECT`);
 *  2. a job is live (`NO_LIVE_JOB`). After the close only the SAME receipt episode, already proven while its job was
 *     live, stays proven — the collapsed → commit → late-expand shape. A new episode after the close is never proven:
 *     with a job's task screens all missed the subject falls back to the previous job's last drop;
 *  3. the job holds exactly one accepted offer (`OFFER_COUNT`);
 *  4. that offer's card showed exactly one drop (`CARD_COUNT_MISSING`, `CARD_COUNT_NOT_ONE`);
 *  5. the subject is an accountable drop of the job (`SUBJECT_NOT_ACCOUNTABLE`) and no other drop of the job is
 *     (`OTHER_DROPS`). A never-activated placeholder is not accountable, so it does not block proof: accept mints one
 *     placeholder per merchant row, and a one-customer two-store card would otherwise never prove;
 *  6. the subject's store matches the offer's quoted store through [StoreKeys.normalizedChain] (`STORE_MISSING`,
 *     `STORE_MISMATCH`).
 *
 * Proven covers `{subject}`; anything else covers nothing. Stated residual: a hidden offer whose single drop stands
 * in for this offer's drop after its order vanished unseen. Narrow, and visible through the per-receipt check.
 * Everything unproven is the dasher's to match (#1599) or whole-job settlement's (#1601).
 *
 * Pure: region records only — no wall clock, no `Platform` literal.
 */
internal fun receiptCoverageAt(region: PlatformRegion, subjectTaskId: String?): ReceiptCoverage {
    if (subjectTaskId == null) return unproven(ReceiptProofGap.NO_SUBJECT)
    val job = region.activeJob
    if (job == null) {
        val open = region.openReceiptEvidence
        val sameProvenEpisode =
            open != null && open.subjectTaskId == subjectTaskId && open.lastScope == ReceiptScope.PROVEN_SINGLE
        return if (sameProvenEpisode) proven(subjectTaskId) else unproven(ReceiptProofGap.NO_LIVE_JOB)
    }
    val offer = job.acceptedOffers.singleOrNull() ?: return unproven(ReceiptProofGap.OFFER_COUNT)
    val cardCount = offer.cardDropoffCount ?: return unproven(ReceiptProofGap.CARD_COUNT_MISSING)
    if (cardCount != 1) return unproven(ReceiptProofGap.CARD_COUNT_NOT_ONE)

    // Every copy of the job's tasks: the active task, then the recent list, then the job's own.
    val copies: Map<String, List<Task>> = (
        listOfNotNull(region.activeTask?.takeIf { it.jobId == job.jobId }) +
            region.recentTasks.filter { it.jobId == job.jobId } +
            job.tasks
        ).groupBy { it.taskId }
    val accountable = copies.filterValues { it.isAccountableDrop() }.keys
    if (subjectTaskId !in accountable) return unproven(ReceiptProofGap.SUBJECT_NOT_ACCOUNTABLE)
    if (accountable.any { it != subjectTaskId }) return unproven(ReceiptProofGap.OTHER_DROPS)

    val store = copies.getValue(subjectTaskId).firstNotNullOfOrNull { it.storeName }
        ?: return unproven(ReceiptProofGap.STORE_MISSING)
    val quoted = offer.storeHints.filter(String::isNotBlank).map(StoreKeys::normalizedChain)
    if (StoreKeys.normalizedChain(store) !in quoted) return unproven(ReceiptProofGap.STORE_MISMATCH)
    return proven(subjectTaskId)
}

/** Accountable across copies: a dropoff, identity on some copy, unassigned on none (`Task.isAccountableDropoff`). */
private fun List<Task>.isAccountableDrop(): Boolean =
    any { it.phase == TaskPhase.DROPOFF } && any { it.hasCustomerIdentity } && none { it.unassignedAt != null }

private fun proven(subjectTaskId: String) =
    ReceiptCoverage(taskIds = setOf(subjectTaskId), scope = ReceiptScope.PROVEN_SINGLE)

private fun unproven(gap: ReceiptProofGap) = ReceiptCoverage(scope = ReceiptScope.UNPROVEN, gap = gap)

/** #1601: the answer of [jobSettlementAt]. */
internal sealed interface JobSettlementProof {
    /** The job is proven: [paying] paid it, split into [shares] (taskId to cents, ascending taskId) over [cardCount] drops. */
    data class Proven(val paying: JobReceiptRead, val shares: List<Pair<String, Long>>, val cardCount: Int) : JobSettlementProof

    data class Refused(val gap: JobSettlementGap) : JobSettlementProof
}

/**
 * #1601 — may a one-offer job whose card showed N ≥ 2 stops take its receipt as the whole job's pay? The clauses, in
 * order (design D3 with amendments A1, A2, A5); the first that fails is the [JobSettlementGap]:
 *  1. reads were tracked from the job's mint (`READS_UNTRACKED`: a job minted by an older build);
 *  2. exactly one accepted offer, TOTAL basis (`OFFER_COUNT`);
 *  3. the card's count was parsed and is at least 2 (`CARD_COUNT_MISSING`, `CARD_COUNT_ONE`; one stop is #1598's);
 *  4. no drop of the job was unassigned on any copy (`UNASSIGNED`); exactly N accountable drops (`DROP_COUNT`), each
 *     with a minted completion (`DROP_NOT_MINTED`);
 *  5. each has a positive delivery witness, arrival or the completion steps (`NO_DELIVERY_WITNESS`, the #1081 class);
 *  6. each drop's store is one the offer quoted, by [StoreKeys.normalizedChain] (`STORE_MISSING`, `STORE_MISMATCH`);
 *  7. every tracked read's rule declared its total is the whole offer's pay (`SCOPE_UNDECLARED`; principle 8: a
 *     platform that declares nothing never settles);
 *  8. a read names the last of the N drops to complete (`NO_TERMINAL_READ`); the latest such record is the PAYING read;
 *  9. every tracked record agrees on the total (`READS_DISAGREE`: a part receipt or a tip change refuses, never picks);
 *  10. no other work could own the paying read, so every window ends at the paying record's FIRST sighting (work that
 *     came after a read was on screen cannot own it): no accept resolved after the job's own resolution stamp and
 *     before the paying read (`OTHER_ACCEPT`, the read's [JobReceiptRead.acceptSeqAtFirstSeen]); no non-demoted pool
 *     entry presented after the job's accept and before the paying read (`POOLED_OFFER`); no non-demoted pool entry
 *     presented before the paying read, whenever before, still waiting at a store of the job's drops, nor an ADD_ON
 *     entry with no candidate store presented after the accept and before the read (`RIVAL_POOLED`, A1);
 *  11. no fresh presentation between the terminal drop's activation and the paying read (`LATE_FRAME`), which also
 *     covers the #1598 late-frame guard for a revision read after the close.
 *
 * The shares come from the paying revision alone (A3): its itemization through [DropPayApportioner.apportion] when the
 * items sum to its own total in cents, else [DropPayApportioner.evenSplitCents] in ascending taskId order. No
 * arithmetic of its own. Pure: the job's records, the pool and the counters — no wall clock, no `Platform` literal.
 */
internal fun jobSettlementAt(
    job: Job,
    /** Every copy of the job's tasks at the close. */
    copies: List<Task>,
    mintedTaskIds: Set<String>,
    /** The pool as the decision sees it; at a teardown, BEFORE the shift-end flush (A5). */
    offerPool: List<PooledOffer>,
): JobSettlementProof {
    val reads = job.receiptReads ?: return refused(JobSettlementGap.READS_UNTRACKED)
    val offer = job.acceptedOffers.singleOrNull()?.takeIf { it.quoteBasis == OfferQuoteBasis.TOTAL }
        ?: return refused(JobSettlementGap.OFFER_COUNT)
    val n = offer.cardDropoffCount ?: return refused(JobSettlementGap.CARD_COUNT_MISSING)
    if (n < 2) return refused(JobSettlementGap.CARD_COUNT_ONE)

    val dropCopies = copies.filter { it.jobId == job.jobId && it.phase == TaskPhase.DROPOFF }.groupBy { it.taskId }
    if (dropCopies.values.any { c -> c.any { it.unassignedAt != null } }) return refused(JobSettlementGap.UNASSIGNED)
    val drops = dropCopies.filterValues { c -> c.any { it.hasCustomerIdentity } }
    if (drops.size != n) return refused(JobSettlementGap.DROP_COUNT)
    if (drops.keys.any { it !in mintedTaskIds }) return refused(JobSettlementGap.DROP_NOT_MINTED)
    if (drops.values.any { c -> c.none { it.arrivedAt != null || it.completionStepsAt != null } }) {
        return refused(JobSettlementGap.NO_DELIVERY_WITNESS)
    }
    val quoted = offer.storeHints.filter(String::isNotBlank).map(StoreKeys::normalizedChain).toSet()
    val stores = LinkedHashMap<String, String>()
    for ((taskId, c) in drops) {
        val store = c.firstNotNullOfOrNull { it.storeName?.takeIf(String::isNotBlank) }
            ?: return refused(JobSettlementGap.STORE_MISSING)
        if (StoreKeys.normalizedChain(store) !in quoted) return refused(JobSettlementGap.STORE_MISMATCH)
        stores[taskId] = store
    }
    if (reads.any { !it.totalIsOffer }) return refused(JobSettlementGap.SCOPE_UNDECLARED)

    // The drop that completed last; one still active (its completion not yet stamped) is the latest. A tie names none.
    val finishedAt = drops.mapValues { (_, c) -> c.mapNotNull { it.completedAt }.maxOrNull() ?: Long.MAX_VALUE }
    val latest = finishedAt.values.max()
    val terminal = finishedAt.filterValues { it == latest }.keys.singleOrNull()
        ?: return refused(JobSettlementGap.NO_TERMINAL_READ)
    val paying = reads.filter { it.subjectTaskId == terminal }.maxByOrNull { it.observedAt }
        ?: return refused(JobSettlementGap.NO_TERMINAL_READ)
    if (reads.any { it.totalCents != paying.totalCents }) return refused(JobSettlementGap.READS_DISAGREE)

    val ownAccept = job.ownAcceptSeq ?: return refused(JobSettlementGap.OTHER_ACCEPT)
    if (paying.acceptSeqAtFirstSeen > ownAccept) return refused(JobSettlementGap.OTHER_ACCEPT)
    // Only work presented before the paying read was first seen can own it.
    val live = offerPool.filter { !it.demoted && it.offer.presentedAt < paying.firstSeenAt }
    if (live.any { it.offer.presentedAt > offer.acceptedAt }) return refused(JobSettlementGap.POOLED_OFFER)
    val chains = stores.values.map(StoreKeys::normalizedChain).toSet()
    val rival = live.any { e ->
        e.candidateStoreKeys.any { it in chains } ||
            (e.candidateStoreKeys.isEmpty() && e.offer.presentedAt > offer.acceptedAt &&
                e.offer.offerFields.parsedOffer.effectiveScope == OfferScope.ADD_ON)
    }
    if (rival) return refused(JobSettlementGap.RIVAL_POOLED)
    val activated = job.dropoffActivatedSeq[terminal] ?: return refused(JobSettlementGap.LATE_FRAME)
    if (paying.possibleWorkSeq != activated) return refused(JobSettlementGap.LATE_FRAME)

    val ids = drops.keys.sorted()
    val itemized = paying.parsedPay?.takeIf { it.sumsTo(paying.totalCents) }
    val apportioned = itemized?.let { pay ->
        val tasks = ids.map { id -> drops.getValue(id).first().copy(storeName = stores.getValue(id)) }
        DropPayApportioner.apportion(pay, tasks).mapValues { Formats.cents(it.value) }
    }
    val shares = if (apportioned != null && apportioned.size == n && apportioned.values.sum() == paying.totalCents) {
        ids.map { it to apportioned.getValue(it) }
    } else {
        ids.zip(DropPayApportioner.evenSplitCents(paying.totalCents, n))
    }
    return JobSettlementProof.Proven(paying, shares, n)
}

/** A3: an itemization is used only when its items sum, in cents, to the revision's own total. */
private fun ParsedPay.sumsTo(totalCents: Long): Boolean =
    (appPayComponents + customerTips).sumOf { Formats.cents(it.amount) } == totalCents

private fun refused(gap: JobSettlementGap) = JobSettlementProof.Refused(gap)
