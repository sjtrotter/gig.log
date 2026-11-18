package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.core.state.model.DecisionSink
import cloud.trotter.log.gig.core.state.model.JobSettlementDecision
import cloud.trotter.log.gig.core.state.model.RegionDecisions
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.JobReceiptSettledPayload
import cloud.trotter.log.gig.domain.model.event.payload.JobSettlementKind
import cloud.trotter.log.gig.domain.model.event.payload.JobSettlementLine
import cloud.trotter.log.gig.domain.state.ClosedJobSettlement
import cloud.trotter.log.gig.domain.state.Job
import cloud.trotter.log.gig.domain.state.JobReceiptRead
import cloud.trotter.log.gig.domain.state.JobSettlementDecided
import cloud.trotter.log.gig.domain.state.JobSettlementGap
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.Task
import cloud.trotter.log.gig.domain.state.TaskPhase
import timber.log.Timber

/**
 * #1601 — whole-job settlement's decision points and their emission. The proof is [jobSettlementAt]; this file asks it
 * at the job's close (`completeActiveJob`: the receipt exit, the T1 retire, the T2 close, an abandon) and at a
 * teardown (`endSession`, on the pool BEFORE the shift-end flush), and again on every changed read of the same receipt
 * episode after the close ([trackJobReceiptRead]). Never mid-job.
 *
 * One settlement per job, identity `settle:<jobId>`, revisioned by the stepper:
 * - proven and nothing active, or proven with another allocation than the active one → SETTLE at the next revision;
 * - an unchanged allocation decides nothing (the `lastDecidedPay` pattern);
 * - refused with the records now disagreeing while a settlement is active → WITHDRAW (A2), after which nothing more is
 *   decided for the job;
 * - refused for any other reason → nothing; a settlement already applied stands.
 *
 * Pure: region records only, `obs.timestamp` only, no `Platform` literal. Revisions travel in [RegionDecisions], never
 * in region state, so a replay rebuilds them with the transition.
 */

/** The marker for [job] closing at [at] out of [region] (pre-close). [honored]: a teardown's honored active drop. */
internal fun closedJobMarker(region: PlatformRegion, job: Job, at: Long, honored: Task? = null): ClosedJobSettlement {
    val active = region.activeTask?.takeIf { it.jobId == job.jobId }
    val activeCopy = if (honored != null && active?.taskId == honored.taskId) honored else active
    val recent = region.recentTasks.filter { it.jobId == job.jobId }
    val copies = listOfNotNull(activeCopy) + recent + job.tasks
    // What has (or, on this very step, will have) a `DELIVERY_COMPLETED`: a completion committed before, a stale-close
    // stamp, a PostTask-exit mint, or a teardown's honored retire. A teardown's force-stamp is none of these. The
    // projector's apply refuses a settlement whose rows are not all there, whatever this says.
    val exitMinted = region.jobReceiptAnchors?.takeIf { it.jobId == job.jobId }?.exitMintedTaskIds.orEmpty()
    val minted = recent.filter { it.completedAt != null }.map { it.taskId }.toSet() +
        copies.filter { it.staleCloseCompletedAt != null }.map { it.taskId } +
        exitMinted + listOfNotNull(honored?.taskId)
    return ClosedJobSettlement(
        job = job,
        copies = copies,
        mintedTaskIds = minted,
        sessionId = region.session?.sessionId,
        closedAt = at,
    )
}

/**
 * Decide [marker]'s settlement against [region]'s pool and accept counter at [decidedAt]; returns the marker with the
 * decision recorded. [atClose] marks the close-time ask, the one place a refusal is logged (D12).
 */
internal fun decideJobSettlement(
    sink: DecisionSink,
    marker: ClosedJobSettlement,
    region: PlatformRegion,
    decidedAt: Long,
    atClose: Boolean,
): ClosedJobSettlement {
    val prior = marker.decided
    if (prior?.withdrawn == true) return marker
    val jobId = marker.job.jobId
    return when (val proof = jobSettlementAt(
        marker.job, marker.copies, marker.mintedTaskIds, region.offerPool, region.acceptResolveSeq, marker.closedAt,
    )) {
        is JobSettlementProof.Proven -> {
            val decided = JobSettlementDecided(
                withdrawn = false,
                evidenceId = proof.paying.evidenceId,
                receiptCents = proof.paying.totalCents,
                shares = proof.shares.toMap(),
            )
            if (decided == prior) return marker
            val revision = marker.revision + 1
            sink.jobSettled(
                JobSettlementDecision(
                    jobId = jobId,
                    sessionId = marker.sessionId,
                    revision = revision,
                    kind = JobSettlementKind.SETTLE,
                    evidenceId = proof.paying.evidenceId,
                    evidenceRevision = proof.paying.revision,
                    receiptCents = proof.paying.totalCents,
                    cardDropoffCount = proof.cardCount,
                    shares = proof.shares,
                    decidedAt = decidedAt,
                ),
            )
            marker.copy(revision = revision, decided = decided)
        }
        is JobSettlementProof.Refused -> {
            if (prior != null && proof.gap == JobSettlementGap.READS_DISAGREE) {
                val revision = marker.revision + 1
                val read = marker.job.receiptReads.orEmpty().firstOrNull { it.evidenceId == prior.evidenceId }
                sink.jobSettled(
                    JobSettlementDecision(
                        jobId = jobId,
                        sessionId = marker.sessionId,
                        revision = revision,
                        kind = JobSettlementKind.WITHDRAW,
                        evidenceId = prior.evidenceId,
                        evidenceRevision = read?.revision ?: 1,
                        receiptCents = prior.receiptCents,
                        cardDropoffCount = marker.job.acceptedOffers.singleOrNull()?.cardDropoffCount ?: 0,
                        shares = emptyList(),
                        decidedAt = decidedAt,
                    ),
                )
                return marker.copy(revision = revision, decided = prior.copy(withdrawn = true))
            }
            // D12: one DEBUG line per refused settlement at the close, so the desk can count which clause blocks
            // real stacks. Ids and the clause only (P7). A one-drop or multi-offer job is not a candidate: silent.
            if (atClose && proof.gap !in NOT_CANDIDATE) {
                Timber.tag("StateMachine").d("#1601 job %s not settled: %s", jobId, proof.gap.name)
            }
            marker
        }
    }
}

/** Gaps that say the job was never a whole-job settlement candidate, so the close says nothing about them. */
private val NOT_CANDIDATE = setOf(
    JobSettlementGap.READS_UNTRACKED, JobSettlementGap.OFFER_COUNT, JobSettlementGap.CARD_COUNT_ONE,
)

/** The close-time ask (`completeActiveJob`): decide and return the marker the closed region keeps. */
internal fun settleAtClose(sink: DecisionSink, region: PlatformRegion, job: Job, at: Long): ClosedJobSettlement =
    decideJobSettlement(sink, closedJobMarker(region, job, at), region, at, atClose = true)

/**
 * #1601: record one receipt revision ([read], of [jobId]) on the job it is attributed to. While the job is live the
 * read joins [Job.receiptReads]; after its close, a revision of a record the marker already tracks updates it there
 * and re-decides (the post-close decision point). Anything else is not this job's evidence and changes nothing.
 */
internal fun trackJobReceiptRead(
    sink: DecisionSink,
    region: PlatformRegion,
    jobId: String,
    read: JobReceiptRead,
): PlatformRegion {
    val job = region.activeJob
    if (job != null) {
        if (job.jobId != jobId) return region
        val reads = job.receiptReads ?: return region
        return region.copy(activeJob = job.copy(receiptReads = reads.upsert(read)))
    }
    val marker = region.closedJobSettlement?.takeIf { it.job.jobId == jobId } ?: return region
    val reads = marker.job.receiptReads ?: return region
    if (reads.none { it.evidenceId == read.evidenceId }) return region
    val updated = marker.copy(job = marker.job.copy(receiptReads = reads.upsert(read)))
    return region.copy(closedJobSettlement = decideJobSettlement(sink, updated, region, read.observedAt, atClose = false))
}

private fun List<JobReceiptRead>.upsert(read: JobReceiptRead): List<JobReceiptRead> =
    filterNot { it.evidenceId == read.evidenceId } + read

/**
 * #1601: stamp [PlatformRegion.possibleWorkSeq] the first time each dropoff of the live job becomes the active task —
 * clause 11's quiet-window reference. Run from the step wrapper after `stepCore`, so no early return skips it.
 */
internal fun stampDropoffActivation(region: PlatformRegion): PlatformRegion {
    val job = region.activeJob ?: return region
    val task = region.activeTask?.takeIf { it.jobId == job.jobId && it.phase == TaskPhase.DROPOFF } ?: return region
    if (task.taskId in job.dropoffActivatedSeq) return region
    return region.copy(activeJob = job.copy(dropoffActivatedSeq = job.dropoffActivatedSeq + (task.taskId to region.possibleWorkSeq)))
}

/** #1601: the marker belongs to its job's episode only; another active job drops it (the R1 rule of the re-price marker). */
internal fun closeSettlementWindow(region: PlatformRegion): PlatformRegion {
    val active = region.activeJob ?: return region
    val marker = region.closedJobSettlement ?: return region
    return if (marker.job.jobId != active.jobId) region.copy(closedJobSettlement = null) else region
}

/**
 * Emit what the stepper decided: one `JOB_RECEIPT_SETTLED` per revision, keyed `log:JOB_RECEIPT_SETTLED:<jobId>:r<n>`
 * by DECISION revision, never by content (an X → Y → X allocation keeps three keys). Diffed after the step's
 * `DELIVERY_COMPLETED`s and `RECEIPT_OBSERVED`s, so the event sequences after the rows and the receipt it names.
 */
internal fun EffectMap.diffJobSettlement(decisions: RegionDecisions): List<AppEffect> =
    decisions.jobSettlements.mapNotNull { d ->
        val sessionId = d.sessionId ?: return@mapNotNull null
        // D12 / P7: ids, the drop count, cents and the revision only.
        Timber.tag("StateMachine").i(
            "#1601 job %s %s r%d: %d drop(s), %d¢",
            d.jobId, if (d.kind == JobSettlementKind.SETTLE) "settled" else "settlement withdrawn",
            d.revision, d.shares.size, d.receiptCents,
        )
        logEffect(
            sessionId,
            AppEventType.JOB_RECEIPT_SETTLED,
            d.decidedAt,
            JobReceiptSettledPayload(
                commandId = JobReceiptSettledPayload.commandIdFor(d.jobId),
                sessionId = sessionId,
                jobId = d.jobId,
                revision = d.revision,
                kind = d.kind,
                policyVersion = JobReceiptSettledPayload.JOB_SETTLEMENT_POLICY_VERSION,
                evidenceId = d.evidenceId,
                evidenceRevision = d.evidenceRevision,
                receiptCents = d.receiptCents,
                cardDropoffCount = d.cardDropoffCount,
                lines = d.shares.map { (taskId, cents) -> JobSettlementLine(taskId, cents) },
            ),
            effectKeyOverride = "log:${AppEventType.JOB_RECEIPT_SETTLED}:${d.jobId}:r${d.revision}",
        )
    }
