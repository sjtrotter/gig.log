package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.state.DestructiveKind
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.PlatformRegion
import timber.log.Timber

/**
 * #1698 — a shift left open through a long silence ends at the next offline screen.
 *
 * The Online→Offline edge normally arms a non-authoritative `SESSION_END` that the next Online frame cancels, because
 * a quick offline flash inside a live shift is common. After hours with no online screen from the platform, the
 * offline screen is not a flash: the shift is over, and the dasher going online again starts a new one. Without this
 * rule that resume cancelled the end and the new shift merged into the stale one.
 *
 * The evidence is [PlatformRegion.lastOnlineEvidenceAt], stamped by [stampOnlineEvidence]. The threshold is
 * per-platform policy data ([TransitionPolicy.staleShiftSilenceMs], null = disabled). When the offline screen lands at
 * or past the threshold, the end is armed authoritative at that frame ([armAtOfflineEdge]): a resume cannot cancel it,
 * a task-flow screen still can (as for the summary's end, #431), and a hint-only online screen arms the #1311 graced
 * start of the next shift. The end is stamped at the offline frame, never backdated to the last evidence: the time in
 * between is unobserved, not proven idle.
 *
 * A Paused shift keeps its countdown and pause-safety path; the rule reads only an Online region. Nothing here reads a
 * wall clock or a platform literal.
 */
internal object StaleShiftEnd {

    /**
     * Stamp [obs] as online evidence for [region]'s live shift: an own screen with a flow that implies Online. Clicks,
     * notifications, timeouts and flow-less (inherited-flow) frames never stamp, and the stamp never moves backwards.
     * A foreign platform's observation never reaches this region (`StateMachine.stepPlatforms`). Runs after the step,
     * so the offline edge reads the anchor as it stood before the frame.
     */
    fun stampOnlineEvidence(region: PlatformRegion, obs: Observation, policy: TransitionPolicy): PlatformRegion {
        if (region.session == null) return region
        val last = region.lastOnlineEvidenceAt
        if (last != null && last >= obs.timestamp) return region
        val at = onlineEvidenceAt(obs, policy) ?: return region
        return region.copy(lastOnlineEvidenceAt = at)
    }

    /** [obs]'s timestamp when it is online evidence (an own screen whose non-null flow implies Online), else null. */
    fun onlineEvidenceAt(obs: Observation, policy: TransitionPolicy): Long? {
        val screen = obs as? Observation.Screen ?: return null
        val flow = screen.flow ?: return null
        return obs.timestamp.takeIf { policy.resolveMode(flow, screen.modeHint) == Mode.Online }
    }

    /**
     * True while an Offline-origin pending start (#1311) must wait for the authoritative end standing over the live
     * shift: its 8 s grace can lapse before the end's 10 s one, and committing it then would resume the OLD shift.
     * Kept, it commits on the frame or wake that commits the end, as the first step of the new shift (`since` kept).
     */
    fun startWaitsForEnd(region: PlatformRegion): Boolean =
        region.mode == Mode.Offline && region.session != null && region.pendingDestructive?.let {
            it.kind == DestructiveKind.SESSION_END && it.authoritative
        } == true

    /**
     * Arm the shift end at the Online/Paused→Offline mode edge. [prev] is the region before the edge, [region] the one
     * with the new mode. Authoritative when [prev] was Online on a live shift and its last online evidence is at
     * least the platform's threshold old at this offline screen; otherwise the ordinary end a resume cancels.
     */
    fun armAtOfflineEdge(
        prev: PlatformRegion,
        region: PlatformRegion,
        obs: Observation,
        policy: TransitionPolicy,
    ): PlatformRegion {
        val silenceMs = staleSilenceMs(prev, obs, policy)
        val armed = region.armSessionEndGrace(obs.timestamp, policy, authoritative = silenceMs != null)
        if (silenceMs != null && armed !== region) {
            Timber.tag("StateMachine").d(
                "Stale shift ends at offline screen: platform=${prev.platform.wire} " +
                    "session=${prev.session?.sessionId} silenceS=${silenceMs / 1_000} " +
                    "thresholdMs=${policy.staleShiftSilenceMs(prev.platform)}",
            )
        }
        return armed
    }

    /** The silence that makes this offline screen a stale shift's end, or null when the rule does not apply. */
    private fun staleSilenceMs(prev: PlatformRegion, obs: Observation, policy: TransitionPolicy): Long? {
        if (obs !is Observation.Screen || prev.session == null || prev.mode != Mode.Online) return null
        val threshold = policy.staleShiftSilenceMs(prev.platform) ?: return null
        val anchor = prev.lastOnlineEvidenceAt ?: return null
        return (obs.timestamp - anchor).takeIf { it >= threshold }
    }
}
