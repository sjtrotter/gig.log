package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.state.DestructiveKind
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.PendingDestructive
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.SessionIntent
import cloud.trotter.log.gig.domain.state.Task
import cloud.trotter.log.gig.domain.state.TaskPhase
import timber.log.Timber

/*
 * The non-authoritative `SESSION_END` arm, the retire it may absorb (#1078), and the end-shift tap
 * (a confirm sheet's last tap, #1668; a one-tap go-offline toggle, #1697) that arms it without a mode
 * change. Split out of `PlatformRegionStepper` (size ceiling); the stepper calls these, and nothing here
 * reads a wall clock or a platform literal.
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
 * Two callers: the Online→Offline mode edge, and the end-shift tap ([EndShiftTap], #1668/#1697).
 * Arms only over a live session with no `SESSION_END` already standing, so a second signal never
 * moves `since` or the deadline.
 *
 * [authoritative] (#1698): the offline edge after a stale silence ([StaleShiftEnd]) arms the same
 * grace marked authoritative, so a resume cannot cancel it; the timing is unchanged.
 *
 * #1078: this arm used to OVERWRITE a standing `TASK_RETIRE` with `SESSION_END` (the loss documented
 * as a #1076 gap) — the retire's evidence simply vanished and the eventual teardown force-stamped an
 * unqualified completion. Absorb the retire's `since` so `endSession` can honor it, through the SAME
 * predicate the summary arm reads ([absorbableRetire]). Round 5: absorbing a retire never PULLS its
 * commit forward. The end may be delayed to the retire's own deadline, so a contradicting
 * `task:unassigned` frame the retire would still have seen can still disown it.
 */
internal fun PlatformRegion.armSessionEndGrace(
    at: Long,
    policy: TransitionPolicy,
    byTap: Boolean = false,
    authoritative: Boolean = false,
): PlatformRegion {
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
            armedByTap = byTap,
            authoritative = authoritative,
        )
    }
}

/**
 * #1668/#1697 — the dasher's end-shift tap ends the shift, graced.
 *
 * A shift that earned nothing gets no platform summary, so the end used to wait for the offline home,
 * which can render late, in a shape no rule claims, or never (the dasher switched apps first). The end
 * request tap is the earlier signal: a click whose rule declares [SessionIntent.END_SHIFT_CONFIRM] (the
 * last tap of a confirm sheet; the tap that only OPENS that sheet must not, because the dasher can still
 * cancel there) or [SessionIntent.GO_OFFLINE] (a one-tap offline toggle with no confirm sheet). Either
 * arms the same non-authoritative grace the offline edge arms, marked [PendingDestructive.armedByTap].
 * The click does not move mode: a click records intent.
 *
 * The mark exists because this end's cancel rule differs from the Offline edge's, whose pending a
 * resume to Online cancels. While a tap-armed end stands:
 *  - an offer or task-flow screen cancels it ([yieldToWork]): the platform sent work as the dasher
 *    tapped, and the dasher is still working;
 *  - no other frame moves the region toward Online ([holdsMode]), so the timeline's online redraw
 *    that follows every confirm tap neither cancels it nor arms a Paused resume that would;
 *  - a flow-less frame's lifecycle, driven only by the inherited acted flow, never clears or replaces
 *    it ([keepThroughInheritedFlow]);
 *  - its commit leaves the region Offline with pause safety cleared, from Paused too ([afterCommit]).
 * The summary tightens it as it tightens any standing `SESSION_END` (authoritative, `since` kept), after
 * which the summary's own rules apply; the offline home neither re-arms it nor moves `since`.
 */
internal object EndShiftTap {

    /** The standing tap-armed, still non-authoritative end, or null. */
    private fun PlatformRegion.tapArmedEnd(): PendingDestructive? = pendingDestructive?.takeIf {
        it.kind == DestructiveKind.SESSION_END && it.armedByTap && !it.authoritative
    }

    /** The click intents that request the end of the shift. */
    private val END_REQUEST_INTENTS = setOf(SessionIntent.END_SHIFT_CONFIRM, SessionIntent.GO_OFFLINE)

    /** True when [obs] is [region]'s own end-request click (an end-shift confirm or a go-offline tap). */
    fun isEndRequest(obs: Observation, region: PlatformRegion): Boolean =
        obs is Observation.Click && obs.platform == region.platform &&
            (obs.parsed as? ParsedFields.ClickFields)?.intent in END_REQUEST_INTENTS

    /**
     * Arm the grace at the tap; a no-op without a live session or with a `SESSION_END` already standing.
     * A graced Paused resume armed before the tap is dropped: the dasher is ending the shift, not resuming it.
     */
    fun arm(region: PlatformRegion, obs: Observation, policy: TransitionPolicy): PlatformRegion {
        val armed = region.armSessionEndGrace(obs.timestamp, policy, byTap = true)
        if (armed === region) return region
        Timber.tag("StateMachine").d(
            "End-shift tap armed SESSION_END grace: platform=${region.platform.wire} " +
                "session=${region.session?.sessionId} graceMs=${policy.gracePeriodMs(region.platform)}",
        )
        return armed.copy(pendingModeResume = null)
    }

    /**
     * A tap-armed end yields to work on screen: an offer or a task-flow screen cancels it. Runs after
     * lazy expiry, so a frame past the deadline finds the shift already ended.
     */
    fun yieldToWork(region: PlatformRegion, obs: Observation): PlatformRegion {
        region.tapArmedEnd() ?: return region
        val flow = (obs as? Observation.Screen)?.flow ?: return region
        if (flow != Flow.OfferPresented && !flow.isTaskFlow()) return region
        Timber.tag("StateMachine").d(
            "Tap-armed SESSION_END cancelled by ${flow.wire} screen: platform=${region.platform.wire} " +
                "session=${region.session?.sessionId}",
        )
        return region.copy(pendingDestructive = null)
    }

    /** True when a tap-armed end stands and [impliedMode] would move a Paused or Offline region Online. */
    fun holdsMode(region: PlatformRegion, impliedMode: Mode?): Boolean =
        impliedMode == Mode.Online && region.mode != Mode.Online && region.tapArmedEnd() != null

    /**
     * A flow-less frame (the timeline's redraw, the tap itself) steps the lifecycle on the INHERITED acted
     * flow: a phased task's update clears the destructive slot and a receipt replaces it with a retire.
     * Neither is evidence about the end, so the tap-armed end that stood [before] the lifecycle is kept.
     */
    fun keepThroughInheritedFlow(
        before: PlatformRegion,
        after: PlatformRegion,
        obs: Observation.FlowObservation,
    ): PlatformRegion {
        if (obs.flow != null || after.session == null) return after
        val end = before.tapArmedEnd() ?: return after
        return if (after.pendingDestructive == end) after else after.copy(pendingDestructive = end)
    }

    /** The committed end of a tap-armed pending leaves the region Offline, pause safety cleared. */
    fun afterCommit(pending: PendingDestructive?, ended: PlatformRegion): PlatformRegion =
        if (pending?.armedByTap == true) ended.copy(mode = Mode.Offline, pauseSafety = null) else ended
}
