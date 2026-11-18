package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.SessionEndSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionStopPayload
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.pipeline.ObservationPayload
import cloud.trotter.log.gig.domain.pipeline.TimeoutType
import cloud.trotter.log.gig.domain.state.AppState
import cloud.trotter.log.gig.domain.state.DestructiveKind
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.FlowRegion
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.PendingDestructive
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.Regions
import cloud.trotter.log.gig.domain.state.Session
import cloud.trotter.log.gig.domain.state.SessionIntent
import cloud.trotter.log.gig.domain.state.TaskPhase
import cloud.trotter.log.gig.domain.state.TaskSubFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1668: the end-shift confirm tap arms the graced, non-authoritative dash end. A shift that earned
 * nothing gets no summary, so without the tap the end waited for the offline home (11 s late on 11-16).
 * Timings mirror the fielded sequence: the timeline redraws (hint online) ~0.35 s after the tap, then
 * the summary at ~+2 s or, on a shift that earned nothing, the offline home at ~+11 s.
 */
class EndShiftTapTest {
    private val platform = Platform.DoorDash
    private val policy = TransitionPolicy()
    private val machine = StateMachine(
        FlowRegionStepper(), PlatformRegionStepper(), CrossPlatformRegionStepper(), policy, EffectMap(),
    )
    private val tap = 1_000_000L
    private val grace = policy.gracePeriodMs(platform)

    private fun state(session: Session? = Session("shift", startedAt = 0L), mode: Mode = Mode.Online) = AppState(
        regions = Regions(
            flow = FlowRegion(flow = Flow.Idle, activePlatform = platform),
            platforms = mapOf(platform to PlatformRegion(platform = platform, mode = mode, session = session)),
        ),
    )

    private fun AppState.region() = regions.platforms.getValue(platform)

    private fun click(t: Long, intent: String = SessionIntent.END_SHIFT_CONFIRM) = Observation.Click(
        timestamp = t, captureId = null, ruleId = "doordash.click.synthetic",
        metadata = ReplayMetadata.EMPTY, flow = null, modeHint = null,
        parsed = ParsedFields.ClickFields(intent = intent),
    )

    private fun screen(t: Long, flow: Flow?, hint: Mode?, parsed: ParsedFields = ParsedFields.None) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "doordash.screen.synthetic", metadata = ReplayMetadata.EMPTY,
        flow = flow, modeHint = hint, parsed = parsed,
    )

    private fun timeline(t: Long) = screen(t, Flow.Idle, Mode.Online)
    private fun offlineHome(t: Long) = screen(t, Flow.Idle, Mode.Offline)

    private fun wake(pending: PendingDestructive, at: Long = pending.deadline) = Observation.Timeout(
        timestamp = at, type = TimeoutType.GRACE_COMMIT, targetPlatform = platform,
        payload = ObservationPayload.GraceWake(pending.wakeId.value),
    )

    private fun stop(effects: List<AppEffect>): SessionStopPayload = effects.filterIsInstance<AppEffect.LogEvent>()
        .single { it.event.type == AppEventType.DASH_STOP }.event.payload as SessionStopPayload

    private fun tapped(): AppState {
        val armed = machine.step(state(), click(tap))
        val pending = requireNotNull(armed.newState.region().pendingDestructive)
        assertEquals(DestructiveKind.SESSION_END, pending.kind)
        assertFalse(pending.authoritative)
        assertEquals(tap, pending.since)
        assertEquals(tap + grace, pending.deadline)
        assertEquals(Mode.Online, armed.newState.region().mode)
        assertNotNull(armed.newState.region().session)
        assertTrue(armed.effects.none { it is AppEffect.LogEvent && it.event.type == AppEventType.DASH_STOP })
        return armed.newState
    }

    @Test
    fun `tap then the timeline redraw and nothing else ends the shift at the tap after the grace`() {
        val redraw = machine.step(tapped(), timeline(tap + 350L)).newState
        val pending = requireNotNull(redraw.region().pendingDestructive)
        assertEquals(tap, pending.since)
        assertEquals(Mode.Online, redraw.region().mode)

        val commit = machine.step(redraw, wake(pending))
        assertNull(commit.newState.region().session)
        assertEquals(Mode.Offline, commit.newState.region().mode)
        assertEquals(tap, commit.newState.region().lastSessionEndedAt)
        val payload = stop(commit.effects)
        assertEquals(tap, payload.endedAt)
        assertEquals(SessionEndSource.EARLY_OFFLINE, payload.source)
    }

    @Test
    fun `tap then the offline home at 11 s ends the shift at the tap`() {
        val redraw = machine.step(tapped(), timeline(tap + 350L)).newState
        val home = machine.step(redraw, offlineHome(tap + 11_200L))
        assertNull(home.newState.region().session)
        assertEquals(Mode.Offline, home.newState.region().mode)
        assertEquals(tap, home.newState.region().lastSessionEndedAt)
        assertEquals(tap, stop(home.effects).endedAt)
    }

    @Test
    fun `an offline home inside the window neither re-arms nor moves since`() {
        val armed = tapped()
        val before = requireNotNull(armed.region().pendingDestructive)
        val home = machine.step(armed, offlineHome(tap + 5_000L)).newState
        assertEquals(Mode.Offline, home.region().mode)
        assertEquals(before, home.region().pendingDestructive)

        val commit = machine.step(home, wake(before))
        assertNull(commit.newState.region().session)
        assertEquals(tap, stop(commit.effects).endedAt)
    }

    @Test
    fun `the summary inside the window tightens the standing end as it does today`() {
        val redraw = machine.step(tapped(), timeline(tap + 350L)).newState
        val summaryAt = tap + 2_000L
        val fields = ParsedFields.SessionEndedFields(totalEarnings = 0.0)
        val summary = machine.step(redraw, screen(summaryAt, Flow.SessionEnded, null, fields)).newState
        val pending = requireNotNull(summary.region().pendingDestructive)
        assertTrue(pending.authoritative)
        assertEquals(fields, pending.endFields)
        assertEquals(summaryAt + policy.authoritativeGraceMs(platform), pending.deadline)
        // The tighten keeps the earliest destructive signal, the tap, as the end (#431).
        assertEquals(tap, pending.since)

        val commit = machine.step(summary, wake(pending))
        assertNull(commit.newState.region().session)
        val payload = stop(commit.effects)
        assertEquals(SessionEndSource.SUMMARY_SCREEN, payload.source)
        assertEquals(tap, payload.endedAt)
    }

    @Test
    fun `an offer as the dasher taps cancels the end and a stale wake stays inert`() {
        val armed = tapped()
        val pending = requireNotNull(armed.region().pendingDestructive)
        val offer = machine.step(armed, screen(tap + 1_000L, Flow.OfferPresented, null)).newState
        assertNull(offer.region().pendingDestructive)
        assertEquals("shift", offer.region().session?.sessionId)
        assertEquals(Mode.Online, offer.region().mode)

        val stale = machine.step(offer, wake(pending))
        assertEquals("shift", stale.newState.region().session?.sessionId)
        assertTrue(stale.effects.none { it is AppEffect.LogEvent && it.event.type == AppEventType.DASH_STOP })
    }

    @Test
    fun `a task screen cancels the end`() {
        val task = screen(
            tap + 1_000L, Flow.TaskPickupNavigation, Mode.Online,
            ParsedFields.TaskFields(phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION, storeName = "Store"),
        )
        val after = machine.step(tapped(), task).newState
        assertNull(after.region().pendingDestructive)
        assertEquals("shift", after.region().session?.sessionId)
    }

    @Test
    fun `the timeline redraw does not cancel the end`() {
        val armed = tapped()
        val redraw = machine.step(armed, timeline(tap + 350L)).newState
        assertEquals(armed.region().pendingDestructive, redraw.region().pendingDestructive)
    }

    @Test
    fun `a tap with no session arms nothing`() {
        val after = machine.step(state(session = null, mode = Mode.Offline), click(tap)).newState
        assertNull(after.region().pendingDestructive)
        assertNull(after.region().session)
    }

    @Test
    fun `the timeline End dash tap arms nothing`() {
        val after = machine.step(state(), click(tap, intent = "open_end_dash")).newState
        assertNull(after.region().pendingDestructive)
        assertEquals(Mode.Online, after.region().mode)
    }

    @Test
    fun `a second confirm tap, or the same tap replayed, keeps since and the wake`() {
        val armed = tapped()
        val first = requireNotNull(armed.region().pendingDestructive)
        for (again in listOf(click(tap), click(tap + 3_000L))) {
            val after = machine.step(armed, again).newState
            assertEquals(first, after.region().pendingDestructive)
        }
    }

    @Test
    fun `a tap while paused arms the end without moving mode`() {
        val after = machine.step(state(mode = Mode.Paused), click(tap)).newState
        assertEquals(tap, after.region().pendingDestructive?.since)
        assertEquals(Mode.Paused, after.region().mode)
    }
}
