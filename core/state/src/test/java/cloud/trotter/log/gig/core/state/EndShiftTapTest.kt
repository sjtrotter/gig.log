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
import cloud.trotter.log.gig.domain.state.Job
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.PendingDestructive
import cloud.trotter.log.gig.domain.state.PendingWake
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.Regions
import cloud.trotter.log.gig.domain.state.Session
import cloud.trotter.log.gig.domain.state.SessionIntent
import cloud.trotter.log.gig.domain.state.Task
import cloud.trotter.log.gig.domain.state.TaskPhase
import cloud.trotter.log.gig.domain.state.TaskSubFlow
import cloud.trotter.log.gig.domain.state.mintWakeId
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
 *
 * #1697: Uber's GO OFFLINE tap (no confirm sheet) arms the same end; see the Uber section at the bottom.
 */
class EndShiftTapTest {
    private val platform = Platform.DoorDash
    private val policy = TransitionPolicy()
    private val machine = StateMachine(
        FlowRegionStepper(), PlatformRegionStepper(), CrossPlatformRegionStepper(), policy, EffectMap(),
    )
    private val tap = 1_000_000L
    private val grace = policy.gracePeriodMs(platform)

    private fun state(
        session: Session? = Session("shift", startedAt = 0L),
        mode: Mode = Mode.Online,
        flow: Flow = Flow.Idle,
        shape: (PlatformRegion) -> PlatformRegion = { it },
    ) = AppState(
        regions = Regions(
            flow = FlowRegion(flow = flow, activePlatform = platform),
            platforms = mapOf(platform to shape(PlatformRegion(platform = platform, mode = mode, session = session))),
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

    /** The fielded timeline redraw: no flow, hint online. */
    private fun timeline(t: Long) = screen(t, null, Mode.Online)
    private fun offlineHome(t: Long) = screen(t, Flow.Idle, Mode.Offline)

    private fun wake(pending: PendingDestructive, at: Long = pending.deadline) = Observation.Timeout(
        timestamp = at, type = TimeoutType.GRACE_COMMIT, targetPlatform = platform,
        payload = ObservationPayload.GraceWake(pending.wakeId.value),
    )

    private fun stop(effects: List<AppEffect>): SessionStopPayload = effects.filterIsInstance<AppEffect.LogEvent>()
        .single { it.event.type == AppEventType.DASH_STOP }.event.payload as SessionStopPayload

    private fun tapped(from: AppState = state()): AppState {
        val armed = machine.step(from, click(tap))
        val pending = requireNotNull(armed.newState.region().pendingDestructive)
        assertEquals(DestructiveKind.SESSION_END, pending.kind)
        assertFalse(pending.authoritative)
        assertTrue(pending.armedByTap)
        assertEquals(tap, pending.since)
        assertEquals(tap + grace, pending.deadline)
        assertEquals(from.region().mode, armed.newState.region().mode)
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
        // A late online redraw after the home neither resumes the shift nor cancels the end.
        val late = machine.step(home, timeline(tap + 6_000L)).newState
        assertEquals(Mode.Offline, late.region().mode)
        assertEquals(before, late.region().pendingDestructive)

        val commit = machine.step(late, wake(before))
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
    fun `paused, a tap and its redraw end the shift Offline with pause safety cleared`() {
        val paused = state(mode = Mode.Paused) { region ->
            region.copy(wakeSeq = 5L, pauseSafety = PendingWake(tap + 3_600_000L, PlatformRegion(platform, wakeSeq = 4L).mintWakeId().second))
        }
        val armed = tapped(paused)
        val pending = requireNotNull(armed.region().pendingDestructive)
        // The redraw arms no resume, and past the 8 s resume window it still has not cancelled the end.
        val redraw = machine.step(armed, timeline(tap + 350L)).newState
        assertEquals(Mode.Paused, redraw.region().mode)
        assertNull(redraw.region().pendingModeResume)
        val later = machine.step(redraw, timeline(tap + policy.pauseResumeGraceMs(platform) + 400L)).newState
        assertEquals(Mode.Paused, later.region().mode)
        assertEquals(pending, later.region().pendingDestructive)

        val commit = machine.step(later, wake(pending))
        val region = commit.newState.region()
        assertNull(region.session)
        assertEquals(Mode.Offline, region.mode)
        assertNull(region.pauseSafety)
        assertEquals(tap, stop(commit.effects).endedAt)
    }

    @Test
    fun `the tap and the flow-less redraw keep the end over an outstanding task or a receipt`() {
        val drop = Task("drop", "job", TaskPhase.DROPOFF, startedAt = 100L, arrivedAt = 200L)
        val job = Job("job", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 100L, tasks = listOf(drop))
        for (acted in listOf(Flow.TaskDropoffArrived, Flow.PostTask)) {
            val working = state(flow = acted) { it.copy(activeJob = job, activeTask = drop, lastActedFlow = acted) }
            val armed = tapped(working)
            val pending = requireNotNull(armed.region().pendingDestructive)
            val redraw = machine.step(armed, timeline(tap + 350L)).newState
            assertEquals(acted.wire, pending, redraw.region().pendingDestructive)

            val commit = machine.step(redraw, wake(pending))
            assertNull(acted.wire, commit.newState.region().session)
            assertEquals(acted.wire, Mode.Offline, commit.newState.region().mode)
            assertEquals(acted.wire, tap, stop(commit.effects).endedAt)
        }
    }

    // =========================================================================
    // #1697: Uber's GO OFFLINE tap (SessionIntent.GO_OFFLINE) arms the same graced end. Timings mirror the
    // fielded 11-20 sequence: two "You're online" redraws at +0.6 s and +5.2 s, then the dasher switched to
    // DoorDash before Uber drew its offline screen, so no further Uber frame arrived.
    // =========================================================================

    private val uber = Platform.Uber
    private val uberGrace = policy.gracePeriodMs(uber)

    /** Uber online and waiting for offers, DoorDash online beside it. */
    private fun twoPlatforms() = AppState(
        regions = Regions(
            flow = FlowRegion(flow = Flow.Idle, activePlatform = uber),
            platforms = mapOf(
                uber to PlatformRegion(platform = uber, mode = Mode.Online, session = Session("uber-shift", startedAt = 0L)),
                platform to PlatformRegion(platform = platform, mode = Mode.Online, session = Session("dd-shift", startedAt = 0L)),
            ),
        ),
    )

    private fun AppState.uberRegion() = regions.platforms.getValue(uber)

    private fun goOffline(t: Long) = Observation.Click(
        timestamp = t, captureId = null, ruleId = "uber.click.go_offline",
        metadata = ReplayMetadata.EMPTY, flow = null, modeHint = Mode.Offline,
        parsed = ParsedFields.ClickFields(intent = SessionIntent.GO_OFFLINE),
    )

    private fun uberScreen(t: Long, flow: Flow?, hint: Mode?) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "uber.screen.synthetic", metadata = ReplayMetadata.EMPTY,
        flow = flow, modeHint = hint, parsed = ParsedFields.None,
    )

    /** The "You're online" / "Finding trips" sheet (`uber.screen.awaiting_offer`). */
    private fun youreOnline(t: Long) = uberScreen(t, Flow.Idle, Mode.Online)
    private fun youreOffline(t: Long) = uberScreen(t, Flow.Idle, Mode.Offline)

    private fun uberWake(pending: PendingDestructive) = Observation.Timeout(
        timestamp = pending.deadline, type = TimeoutType.GRACE_COMMIT, targetPlatform = uber,
        payload = ObservationPayload.GraceWake(pending.wakeId.value),
    )

    private fun stops(effects: List<AppEffect>): List<SessionStopPayload> = effects.filterIsInstance<AppEffect.LogEvent>()
        .filter { it.event.type == AppEventType.DASH_STOP }.map { it.event.payload as SessionStopPayload }

    /** Steps [obs] in order from [from], returning the final state and every effect emitted. */
    private fun steps(from: AppState, vararg obs: Observation): Pair<AppState, List<AppEffect>> {
        var state = from
        val effects = mutableListOf<AppEffect>()
        for (o in obs) {
            val t = machine.step(state, o)
            state = t.newState
            effects += t.effects
        }
        return state to effects
    }

    /** The tap on [from]: the graced end armed at the tap, Uber still Online, its wake scheduled. */
    private fun uberTapped(from: AppState = twoPlatforms()): AppState {
        val armed = machine.step(from, goOffline(tap))
        val pending = requireNotNull(armed.newState.uberRegion().pendingDestructive)
        assertEquals(DestructiveKind.SESSION_END, pending.kind)
        assertFalse(pending.authoritative)
        assertTrue(pending.armedByTap)
        assertEquals(tap, pending.since)
        assertEquals(tap + uberGrace, pending.deadline)
        assertEquals(Mode.Online, armed.newState.uberRegion().mode)
        assertEquals("uber-shift", armed.newState.uberRegion().session?.sessionId)
        assertTrue(stops(armed.effects).isEmpty())
        val timer = armed.effects.filterIsInstance<AppEffect.ScheduleTimeout>().single { it.type == TimeoutType.GRACE_COMMIT }
        assertEquals(uber, timer.platform)
        assertEquals(pending.deadline, timer.deadlineMs)
        return armed.newState
    }

    /** A DoorDash frame (the class's [platform]). */
    private fun ddScreen(t: Long, flow: Flow?, hint: Mode?, parsed: ParsedFields = ParsedFields.None) =
        screen(t, flow, hint, parsed)

    @Test
    fun `Uber go-offline tap, two online redraws, then only DoorDash ends the Uber shift at the tap`() {
        val armed = uberTapped()
        val pending = requireNotNull(armed.uberRegion().pendingDestructive)
        val (end, effects) = steps(
            armed,
            youreOnline(tap + 600L),
            youreOnline(tap + 5_200L),
            ddScreen(tap + 7_000L, Flow.Idle, Mode.Online),
            ddScreen(tap + 9_000L, Flow.Idle, Mode.Online),
            uberWake(pending),
            ddScreen(tap + 60_000L, Flow.Idle, Mode.Online),
        )
        val region = end.uberRegion()
        assertNull(region.session)
        assertNull(region.pendingDestructive)
        assertEquals(Mode.Offline, region.mode)
        assertEquals(tap, region.lastSessionEndedAt)
        val stop = stops(effects).single()
        assertEquals("uber-shift", stop.sessionId)
        assertEquals(uber.name, stop.platform)
        assertEquals(tap, stop.endedAt)
        assertEquals(SessionEndSource.EARLY_OFFLINE, stop.source)
        assertEquals("dd-shift", end.region().session?.sessionId)
        assertEquals(Mode.Online, end.region().mode)
    }

    @Test
    fun `an Uber offer inside the grace cancels the go-offline end and its wake stays inert`() {
        val armed = uberTapped()
        val pending = requireNotNull(armed.uberRegion().pendingDestructive)
        val (offer, effects) = steps(armed, youreOnline(tap + 600L), uberScreen(tap + 3_000L, Flow.OfferPresented, null))
        assertNull(offer.uberRegion().pendingDestructive)
        assertEquals("uber-shift", offer.uberRegion().session?.sessionId)
        assertEquals(Mode.Online, offer.uberRegion().mode)

        val (stale, wakeEffects) = steps(offer, uberWake(pending))
        assertEquals("uber-shift", stale.uberRegion().session?.sessionId)
        assertTrue(stops(effects + wakeEffects).isEmpty())
    }

    @Test
    fun `Uber's offline screen inside the grace keeps the end at the tap`() {
        val armed = uberTapped()
        val pending = requireNotNull(armed.uberRegion().pendingDestructive)
        val (home, homeEffects) = steps(armed, youreOnline(tap + 600L), youreOffline(tap + 4_000L))
        assertEquals(Mode.Offline, home.uberRegion().mode)
        assertEquals(pending, home.uberRegion().pendingDestructive)
        assertEquals("uber-shift", home.uberRegion().session?.sessionId)

        val (end, wakeEffects) = steps(home, uberWake(pending))
        assertNull(end.uberRegion().session)
        assertEquals(Mode.Offline, end.uberRegion().mode)
        val stop = stops(homeEffects + wakeEffects).single()
        assertEquals(tap, stop.endedAt)
    }

    @Test
    fun `a second go-offline tap inside the grace moves neither since nor the deadline`() {
        val armed = uberTapped()
        val first = requireNotNull(armed.uberRegion().pendingDestructive)
        val (again, _) = steps(armed, youreOnline(tap + 600L), goOffline(tap + 3_000L))
        assertEquals(first, again.uberRegion().pendingDestructive)
        val (replayed, _) = steps(armed, goOffline(tap))
        assertEquals(first, replayed.uberRegion().pendingDestructive)
    }

    @Test
    fun `DoorDash frames inside the grace neither cancel nor commit the Uber go-offline end`() {
        val armed = uberTapped()
        val pending = requireNotNull(armed.uberRegion().pendingDestructive)
        val task = ParsedFields.TaskFields(phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION, storeName = "Store")
        val (during, effects) = steps(
            armed,
            ddScreen(tap + 2_000L, Flow.OfferPresented, null),
            ddScreen(tap + 4_000L, Flow.TaskPickupNavigation, Mode.Online, task),
            ddScreen(tap + 9_000L, Flow.Idle, Mode.Online),
        )
        assertEquals(pending, during.uberRegion().pendingDestructive)
        assertEquals("uber-shift", during.uberRegion().session?.sessionId)
        assertEquals(Mode.Online, during.uberRegion().mode)
        assertTrue(stops(effects).isEmpty())

        val (end, wakeEffects) = steps(during, uberWake(pending))
        assertNull(end.uberRegion().session)
        assertEquals(tap, stops(wakeEffects).single().endedAt)
    }
}
