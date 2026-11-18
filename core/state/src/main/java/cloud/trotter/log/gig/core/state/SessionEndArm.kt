package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.state.DestructiveKind
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.PendingDestructive
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.SessionIntent
import cloud.trotter.log.gig.domain.state.Task
import cloud.trotter.log.gig.domain.state.TaskPhase
import timber.log.Timber

/*
 * The non-authoritative `SESSION_END` arm, the retire it may absorb (#1078), and the end-shift confirm
 * tap that arms it without a mode change (#1668). Split out of `PlatformRegionStepper` (size ceiling);
 * the stepper calls these, and nothing here reads a wall clock or a platform literal.
 */

/**
 * #1078 — the `TASK_RETIRE` provenances ([PendingDestructive.armedFromFlow], #596) a dash end
 * may NOT absorb as evidence of a finished delivery. See [absorbableRetire]:
 * `OfferPresented` retires an UNDELIVERED drop (the dasher stepped off to deliberate on an
 * add-on), and a `PostTask` retire's completion is already minted on the receipt's exit
 * frame. A null provenance is refused separately — unprovenanced evidence is not evidence.
 * Flow VALUES, never platform literals (principle 8).
 */
private val UNABSORBABLE_RETIRE_FLOWS = setOf(Flow.OfferPresented, Flow.PostTask)

/**
 * #1078 — **the retire a `SESSION_END` may absorb the evidence of**, or null. Round 5 returns
 * the whole pending rather than its `since`, because BOTH halves are evidence: the instant it
 * armed ([PendingDestructive.absorbedRetireSince]) and the window it was standing in
 * ([PendingDestructive.absorbedRetireDeadline]).
 *
 * The ONE spelling, read by BOTH arm sites (the authoritative summary arm and [armSessionEndGrace],
 * the mode-edge and end-shift-tap arm) so the two can never disagree about what a teardown is allowed
 * to honor.
 *
 * A `TASK_RETIRE` is absorbable only when its PROVENANCE says the task it retires was actually
 * finished. That is exactly `armedFromFlow` (#596), and two values are excluded:
 *
 * - **[Flow.OfferPresented]** — the dasher stepped off the task to deliberate on a mid-route
 *   add-on offer. That drop is NOT delivered, which is why `retireActiveTask` already refuses to
 *   close the job on such a retire; letting a dash end honor it would mint a completion (and an
 *   offer-pay share) for undelivered work — the exact fabrication #1078 exists to prevent, in the
 *   opposite direction.
 * - **[Flow.PostTask]** — a receipt-armed retire's completion is ALREADY minted, by the
 *   PostTask-exit block on the very frame that left the receipt. Honoring it again at the
 *   teardown emits a SECOND raw `DELIVERY_COMPLETED` for the same task: live, the engine's
 *   `effects_fired` per-task key hides it; in a replay (and in any consumer counting raw
 *   effects) it is a double-count.
 * - **`armedFromFlow == null`** — a pre-#596 snapshot's retire, or one whose provenance was never
 *   recorded. Unprovenanced evidence is not evidence: fail-null (#745).
 *
 * **Delivery evidence is an ADDITIONAL condition** (#615/#1101/#1315). T1 close, absorption
 * and endSession share one standard — arrival OR a completion-steps tap the dasher did not
 * navigate away from (#1315), on an un-unassigned DROPOFF. A gift sheet can supply the tap
 * without arrival; SESSION_END inside its retire window must honor it once the window matures,
 * just like T1. [completedInline] lets the shared [isDeliveredEvidenced] predicate see the retire
 * as committed without mutating the task. Provenance still requires a watched retire; evidence
 * alone never authorizes a completion (the rejected rule 2 below).
 *
 * **Rule 2 was considered and REJECTED** (round 2): absorbing at the AUTHORITATIVE summary
 * whenever an ARRIVED dropoff was still active would have covered the retire-less 09-05 shape,
 * but it fabricates a completion for an arrived drop that was cancelled or unassigned through a
 * path the sensor never captured — and for a misrecognized summary. Fail-null beats fail-wrong:
 * the machine records only what it watched finish.
 */
internal fun PlatformRegion.absorbableRetire(): PendingDestructive? {
    val drop = activeTask ?: return null
    if (drop.phase != TaskPhase.DROPOFF) return null
    return pendingDestructive
        ?.takeIf { it.kind == DestructiveKind.TASK_RETIRE }
        ?.takeIf { it.armedFromFlow != null && it.armedFromFlow !in UNABSORBABLE_RETIRE_FLOWS }
        ?.takeIf { drop.completedInline(drop.honoredRetireInstant(it.since)).isDeliveredEvidenced() }
}

/** #1315: a witness-only completion cannot precede the tap that supplies its evidence. */
internal fun Task.honoredRetireInstant(since: Long): Long =
    if (arrivedAt == null) maxOf(since, completionStepsAt ?: since) else since

/**
 * Arm the NON-authoritative `SESSION_END` grace at [at]: the session stays alive until the grace
 * commits (lazy expiry or its `GRACE_COMMIT` wake), and the commit ends it at [at] (#431).
 *
 * Two callers: the Online→Offline mode edge, and the end-shift confirm tap ([EndShiftTap], #1668).
 * Arms only over a live session with no `SESSION_END` already standing, so a second signal never
 * moves `since` or the deadline.
 *
 * #1078: this arm used to OVERWRITE a standing `TASK_RETIRE` with `SESSION_END` (the loss documented
 * as a #1076 gap) — the retire's evidence simply vanished and the eventual teardown force-stamped an
 * unqualified completion. Absorb the retire's `since` so `endSession` can honor it, through the SAME
 * predicate the summary arm reads ([absorbableRetire]). Round 5: absorbing a retire never PULLS its
 * commit forward. The end may be delayed to the retire's own deadline, so a contradicting
 * `task:unassigned` frame the retire would still have seen can still disown it.
 */
internal fun PlatformRegion.armSessionEndGrace(at: Long, policy: TransitionPolicy): PlatformRegion {
    if (session == null || pendingDestructive?.kind == DestructiveKind.SESSION_END) return this
    val absorbed = absorbableRetire()
    val candidate = at + policy.gracePeriodMs(platform)
    return withWakeIdIfDeadlineMoved(null, WindowStart.Live(at)) { wakeId ->
        PendingDestructive(
            kind = DestructiveKind.SESSION_END,
            since = at,
            deadline = maxOf(candidate, absorbed?.deadline ?: candidate),
            absorbedRetireSince = absorbed?.since,
            absorbedRetireDeadline = absorbed?.deadline,
            wakeId = wakeId,
        )
    }
}

/**
 * #1668 — the dasher's end-shift confirm tap ends the shift, graced.
 *
 * A shift that earned nothing gets no platform summary, so the end used to wait for the offline home,
 * which can render late or in a shape no rule claims. The confirm tap (a click whose rule declares
 * [SessionIntent.END_SHIFT_CONFIRM]; the tap that only OPENS the confirm sheet must not, because the
 * dasher can still cancel there) arms the same non-authoritative grace the offline edge arms. The click
 * does not move mode: a click records intent.
 *
 * While that grace stands, an offer or task-flow screen cancels it ([yieldToWork]): the platform sent
 * work as the dasher tapped, and the dasher is still working. Any other screen leaves it alone, in
 * particular the timeline's online redraw that follows every confirm tap. The summary tightens it as
 * it tightens any standing `SESSION_END` (authoritative, `since` kept), and the offline home neither
 * re-arms it nor moves `since`.
 */
internal object EndShiftTap {

    /** True when [obs] is [region]'s own end-shift confirm click. */
    fun isConfirm(obs: Observation, region: PlatformRegion): Boolean =
        obs is Observation.Click && obs.platform == region.platform &&
            (obs.parsed as? ParsedFields.ClickFields)?.intent == SessionIntent.END_SHIFT_CONFIRM

    /** Arm the grace at the tap; a no-op without a live session or with a `SESSION_END` already standing. */
    fun arm(region: PlatformRegion, obs: Observation, policy: TransitionPolicy): PlatformRegion {
        val armed = region.armSessionEndGrace(obs.timestamp, policy)
        if (armed !== region) {
            Timber.tag("StateMachine").d(
                "End-shift tap armed SESSION_END grace: platform=${region.platform.wire} " +
                    "session=${region.session?.sessionId} graceMs=${policy.gracePeriodMs(region.platform)}",
            )
        }
        return armed
    }

    /**
     * A non-authoritative `SESSION_END` yields to work on screen: an offer or a task-flow screen
     * cancels it. Offline that already happens on the mode edge (the frame implies Online); this
     * covers the tap-armed grace, which stands while the region is still Online or Paused. An
     * authoritative (summary-armed) pending keeps its own cancel rule (a task flow, in the task
     * lifecycle). Runs after lazy expiry, so a frame past the deadline finds the shift already ended.
     */
    fun yieldToWork(region: PlatformRegion, obs: Observation): PlatformRegion {
        val pend = region.pendingDestructive
        if (pend?.kind != DestructiveKind.SESSION_END || pend.authoritative) return region
        val flow = (obs as? Observation.Screen)?.flow ?: return region
        if (flow != Flow.OfferPresented && !flow.isTaskFlow()) return region
        Timber.tag("StateMachine").d(
            "Non-authoritative SESSION_END cancelled by ${flow.wire} screen: platform=${region.platform.wire} " +
                "session=${region.session?.sessionId}",
        )
        return region.copy(pendingDestructive = null)
    }
}
