package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.SessionEndSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionStartPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionStopPayload
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.pipeline.ObservationPayload
import cloud.trotter.log.gig.domain.pipeline.TimeoutType
import cloud.trotter.log.gig.domain.settings.GraceConfig
import cloud.trotter.log.gig.domain.state.AppState
import cloud.trotter.log.gig.domain.state.DestructiveKind
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.FlowRegion
import cloud.trotter.log.gig.domain.state.Job
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.PendingDestructive
import cloud.trotter.log.gig.domain.state.PendingModeResume
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.Regions
import cloud.trotter.log.gig.domain.state.Session
import cloud.trotter.log.gig.domain.state.Task
import cloud.trotter.log.gig.domain.state.TaskPhase
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1698: a shift left open through a long silence ends at the next offline screen, and going online again starts a
 * new shift. The fielded 11-20 sequence: an Uber shift's last online screen at noon, 7.5 h of DoorDash only, then
 * Uber's offline home, the GO tap 3 s later and "Finding trips" 7 s after the home. Before this the resume cancelled
 * the offline end and the evening shift merged into the noon one.
 */
class StaleShiftEndTest {
    private val uber = Platform.Uber
    private val doorDash = Platform.DoorDash
    private val policy = TransitionPolicy()
    private val machine = StateMachine(
        FlowRegionStepper(), PlatformRegionStepper(), CrossPlatformRegionStepper(), policy, EffectMap(),
    )
    private val threshold = requireNotNull(policy.staleShiftSilenceMs(uber))
    private val lastOnline = 1_000_000L
    private val hour = 3_600_000L
    private val home = lastOnline + 7 * hour + hour / 2

    // ---- fixtures ----

    /** Uber online with its shift open, DoorDash online beside it. */
    private fun twoPlatforms(uberShape: (PlatformRegion) -> PlatformRegion = { it }) = AppState(
        regions = Regions(
            flow = FlowRegion(flow = Flow.Idle, activePlatform = uber),
            platforms = mapOf(
                uber to uberShape(PlatformRegion(uber, mode = Mode.Online, session = Session("uber-shift", startedAt = 0L))),
                doorDash to PlatformRegion(doorDash, mode = Mode.Online, session = Session("dd-shift", startedAt = 0L)),
            ),
        ),
    )

    private fun AppState.region(p: Platform = uber) = regions.platforms.getValue(p)

    private fun screen(p: Platform, t: Long, flow: Flow?, hint: Mode?, parsed: ParsedFields = ParsedFields.None) =
        Observation.Screen(
            timestamp = t, captureId = null, ruleId = "${p.wire}.screen.synthetic", metadata = ReplayMetadata.EMPTY,
            flow = flow, modeHint = hint, parsed = parsed,
        )

    /** `uber.screen.awaiting_offer` ("Finding trips"): idle, hint online. */
    private fun findingTrips(t: Long) = screen(uber, t, Flow.Idle, Mode.Online)

    /** `uber.screen.home_dashboard` offline: idle, hint offline. */
    private fun offlineHome(t: Long, p: Platform = uber) = screen(p, t, Flow.Idle, Mode.Offline)

    private fun ddIdle(t: Long) = screen(doorDash, t, Flow.Idle, Mode.Online)

    private fun goOnline(t: Long, flow: Flow? = null) = Observation.Click(
        timestamp = t, captureId = null, ruleId = "uber.click.go_online", metadata = ReplayMetadata.EMPTY,
        flow = flow, modeHint = Mode.Online, parsed = ParsedFields.ClickFields(intent = "go_online"),
    )

    private fun promo(t: Long) = Observation.Notification(
        timestamp = t, captureId = null, ruleId = "uber.notification.synthetic", metadata = ReplayMetadata.EMPTY,
        flow = Flow.Idle, modeHint = Mode.Online, parsed = ParsedFields.None,
    )

    private fun endWake(pending: PendingDestructive, p: Platform = uber) = Observation.Timeout(
        timestamp = pending.deadline, type = TimeoutType.GRACE_COMMIT, targetPlatform = p,
        payload = ObservationPayload.GraceWake(pending.wakeId.value),
    )

    private fun resumeWake(pending: PendingModeResume) = Observation.Timeout(
        timestamp = pending.deadline, type = TimeoutType.MODE_RESUME_COMMIT, targetPlatform = uber,
        payload = ObservationPayload.GraceWake(pending.wakeId.value),
    )

    private fun stops(effects: List<AppEffect>) = effects.filterIsInstance<AppEffect.LogEvent>()
        .filter { it.event.type == AppEventType.DASH_STOP }.map { it.event.payload as SessionStopPayload }

    private fun starts(effects: List<AppEffect>) = effects.filterIsInstance<AppEffect.LogEvent>()
        .filter { it.event.type == AppEventType.DASH_START }.map { it.event.payload as SessionStartPayload }

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

    /** Uber's last online screen at [lastOnline], so the anchor is stamped by a real step. */
    private fun stamped(from: AppState = twoPlatforms()): AppState {
        val state = machine.step(from, findingTrips(lastOnline)).newState
        assertEquals(lastOnline, state.region().lastOnlineEvidenceAt)
        return state
    }

    /** The silence: DoorDash frames only, hourly, after Uber's last online screen. */
    private fun silence(): List<Observation> = (1..7).map { ddIdle(lastOnline + it * hour) }

    // ---- the fielded sequence ----

    @Test
    fun `the 19-30 sequence ends the stale shift at the offline home and starts a new shift`() {
        val (quiet, quietEffects) = steps(stamped(), *silence().toTypedArray())
        assertEquals("DoorDash frames never touch Uber's anchor", lastOnline, quiet.region().lastOnlineEvidenceAt)
        assertTrue(stops(quietEffects).isEmpty())

        val (atHome, homeEffects) = steps(quiet, offlineHome(home))
        val end = requireNotNull(atHome.region().pendingDestructive)
        assertEquals(DestructiveKind.SESSION_END, end.kind)
        assertTrue("the stale silence arms the end authoritative", end.authoritative)
        assertFalse(end.armedByTap)
        assertEquals("the end is the offline frame, never backdated", home, end.since)
        assertEquals(home + policy.gracePeriodMs(uber), end.deadline)
        assertEquals(Mode.Offline, atHome.region().mode)
        assertEquals("uber-shift", atHome.region().session?.sessionId)

        val (online, onlineEffects) = steps(atHome, goOnline(home + 3_000L), findingTrips(home + 7_000L))
        assertEquals("the resume does not cancel the stale end", end, online.region().pendingDestructive)
        val start = requireNotNull(online.region().pendingModeResume)
        assertEquals("the #1311 graced start runs from the first online screen", home + 7_000L, start.since)
        assertEquals("uber-shift", online.region().session?.sessionId)

        val (ended, endEffects) = steps(online, endWake(end))
        assertNull(ended.region().session)
        assertEquals(home, ended.region().lastSessionEndedAt)
        assertEquals("the graced start survives the old shift's end", start, ended.region().pendingModeResume)
        assertNull(ended.region().lastOnlineEvidenceAt)

        val (fresh, freshEffects) = steps(ended, resumeWake(start))
        val newShift = requireNotNull(fresh.region().session)
        assertNotEquals("uber-shift", newShift.sessionId)
        assertEquals(home + 7_000L, newShift.startedAt)
        assertEquals(Mode.Online, fresh.region().mode)

        val effects = quietEffects + homeEffects + onlineEffects + endEffects + freshEffects
        val stop = stops(effects).single()
        assertEquals("uber-shift", stop.sessionId)
        assertEquals(home, stop.endedAt)
        assertEquals(SessionEndSource.EARLY_OFFLINE, stop.source)
        val newStart = starts(effects).single()
        assertEquals(newShift.sessionId, newStart.sessionId)
        assertTrue("nothing starts before the graced start commits", starts(homeEffects + onlineEffects + endEffects).isEmpty())
        assertEquals("DoorDash's shift is untouched", "dd-shift", fresh.region(doorDash).session?.sessionId)
    }

    @Test
    fun `a short offline flash inside a live Uber shift is still cancelled by the resume`() {
        val (flash, _) = steps(stamped(), offlineHome(lastOnline + 2_000L))
        val end = requireNotNull(flash.region().pendingDestructive)
        assertFalse(end.authoritative)

        val (resumed, effects) = steps(flash, findingTrips(lastOnline + 5_000L), endWake(end))
        assertNull(resumed.region().pendingDestructive)
        assertEquals("uber-shift", resumed.region().session?.sessionId)
        assertEquals(Mode.Online, resumed.region().mode)
        assertTrue(stops(effects).isEmpty())
    }

    @Test
    fun `the threshold is inclusive - one ms short arms the ordinary end, the threshold arms the stale one`() {
        val base = stamped()
        val short = machine.step(base, offlineHome(lastOnline + threshold - 1)).newState
        assertFalse(requireNotNull(short.region().pendingDestructive).authoritative)
        val atThreshold = machine.step(base, offlineHome(lastOnline + threshold)).newState
        assertTrue(requireNotNull(atThreshold.region().pendingDestructive).authoritative)
    }

    @Test
    fun `a push, a click and a flow-less screen inside the silence do not refresh the anchor`() {
        val (quiet, _) = steps(
            stamped(),
            promo(lastOnline + 10 * 60_000L),
            goOnline(lastOnline + 15 * 60_000L, flow = Flow.Idle),
            screen(uber, lastOnline + 20 * 60_000L, flow = null, hint = Mode.Online),
        )
        assertEquals(lastOnline, quiet.region().lastOnlineEvidenceAt)
        val atHome = machine.step(quiet, offlineHome(lastOnline + threshold)).newState
        assertTrue(requireNotNull(atHome.region().pendingDestructive).authoritative)
    }

    @Test
    fun `an online screen refreshes the anchor and never moves it backwards`() {
        val later = machine.step(stamped(), findingTrips(lastOnline + 60_000L)).newState
        assertEquals(lastOnline + 60_000L, later.region().lastOnlineEvidenceAt)
        val older = machine.step(later, findingTrips(lastOnline + 30_000L)).newState
        assertEquals(lastOnline + 60_000L, older.region().lastOnlineEvidenceAt)
    }

    @Test
    fun `a Paused shift after a long silence takes the ordinary end`() {
        val paused = twoPlatforms { it.copy(mode = Mode.Paused, lastOnlineEvidenceAt = lastOnline) }
        val atHome = machine.step(paused, offlineHome(home)).newState
        val end = requireNotNull(atHome.region().pendingDestructive)
        assertEquals(DestructiveKind.SESSION_END, end.kind)
        assertFalse("Paused keeps its own path", end.authoritative)
    }

    @Test
    fun `a platform with the rule disabled behaves as before - the resume cancels the end`() {
        assertNull(policy.staleShiftSilenceMs(doorDash))
        assertNull(GraceConfig.DEFAULT.staleShiftSilenceMs)
        val online = machine.step(twoPlatforms(), ddIdle(lastOnline)).newState
        assertEquals(lastOnline, online.region(doorDash).lastOnlineEvidenceAt)

        val atHome = machine.step(online, offlineHome(home, doorDash)).newState
        val end = requireNotNull(atHome.region(doorDash).pendingDestructive)
        assertFalse(end.authoritative)

        val (resumed, effects) = steps(atHome, ddIdle(home + 7_000L), endWake(end, doorDash))
        assertNull(resumed.region(doorDash).pendingDestructive)
        assertEquals("dd-shift", resumed.region(doorDash).session?.sessionId)
        assertTrue(stops(effects).isEmpty())
    }

    @Test
    fun `a task screen inside the stale end's grace keeps the shift, as it does under the summary's end`() {
        val pickup = Task("pickup", "job", TaskPhase.PICKUP, startedAt = 100L)
        val job = Job("job", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 100L, tasks = listOf(pickup))
        val working = twoPlatforms {
            it.copy(activeJob = job, activeTask = pickup, lastActedFlow = Flow.TaskPickupNavigation)
        }.let { it.copy(regions = it.regions.copy(flow = FlowRegion(flow = Flow.TaskPickupNavigation, activePlatform = uber))) }
        val pickupFrame = screen(uber, home + 1_000L, Flow.TaskPickupNavigation, null)

        // The stale end: last online evidence 7.5 h before the offline home.
        val stale = machine.step(working.withUberAnchor(lastOnline), offlineHome(home)).newState
        val staleEnd = requireNotNull(stale.region().pendingDestructive)
        assertTrue(staleEnd.authoritative)
        // The summary's end on the same shift, evidence fresh.
        val summary = machine.step(
            working.withUberAnchor(home - 1_000L),
            screen(uber, home, Flow.SessionEnded, null, ParsedFields.SessionEndedFields()),
        ).newState
        val summaryEnd = requireNotNull(summary.region().pendingDestructive)
        assertTrue(summaryEnd.authoritative)

        val (staleAfter, staleEffects) = steps(stale, pickupFrame, endWake(staleEnd))
        val (summaryAfter, summaryEffects) = steps(summary, pickupFrame, endWake(summaryEnd))
        assertNull("the task frame cancels the stale end", staleAfter.region().pendingDestructive)
        assertEquals("uber-shift", staleAfter.region().session?.sessionId)
        assertEquals(Mode.Online, staleAfter.region().mode)
        assertTrue(stops(staleEffects).isEmpty())
        assertEquals(summaryAfter.region().pendingDestructive, staleAfter.region().pendingDestructive)
        assertEquals(summaryAfter.region().session?.sessionId, staleAfter.region().session?.sessionId)
        assertEquals(summaryAfter.region().mode, staleAfter.region().mode)
        assertEquals(stops(summaryEffects).size, stops(staleEffects).size)
    }

    private fun AppState.withUberAnchor(at: Long) = copy(
        regions = regions.copy(platforms = regions.platforms + (uber to region().copy(lastOnlineEvidenceAt = at))),
    )

    // ---- recovery ----

    /**
     * Runs the fielded sequence, crashing after prefix step [crashAfter] (null = no crash): the state is encoded,
     * decoded and scrubbed by `recoveryHygiene`, then the run continues, as a restore replays the journal tail.
     */
    private fun run(crashAfter: Int?): Pair<PlatformRegion, List<String>> {
        var state = twoPlatforms()
        val keys = mutableListOf<String>()
        fun step(obs: Observation) {
            val t = machine.step(state, obs)
            t.effects.filterIsInstance<AppEffect.LogEvent>()
                .filter { it.event.type == AppEventType.DASH_STOP || it.event.type == AppEventType.DASH_START }
                .forEach { keys += it.effectKey.toString() }
            state = t.newState
        }
        val prefix = listOf(findingTrips(lastOnline)) + silence()
        prefix.forEachIndexed { i, obs ->
            step(obs)
            if (crashAfter == i) {
                val decoded = StateJson.decodeFromString<AppState>(StateJson.encodeToString(state))
                assertEquals("the anchor survives the snapshot", lastOnline, decoded.region().lastOnlineEvidenceAt)
                state = decoded.recoveryHygiene(state.timestamp)
            }
        }
        step(offlineHome(home))
        step(goOnline(home + 3_000L))
        step(findingTrips(home + 7_000L))
        step(endWake(requireNotNull(state.region().pendingDestructive)))
        step(resumeWake(requireNotNull(state.region().pendingModeResume)))
        return state.region() to keys
    }

    @Test
    fun `a crash anywhere in the silence replays to the live result`() {
        val (live, liveKeys) = run(crashAfter = null)
        assertEquals("one stop and one start", 2, liveKeys.size)
        assertNotNull(live.session)
        for (k in 0..silence().size) {
            assertEquals("crash after step $k", live to liveKeys, run(crashAfter = k))
        }
    }
}
