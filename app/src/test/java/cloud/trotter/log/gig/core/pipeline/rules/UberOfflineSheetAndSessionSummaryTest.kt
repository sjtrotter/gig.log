package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.test.util.TestResourceLoader
import cloud.trotter.log.gig.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

/**
 * Pins the #1699 Uber 4.606 re-anchors and the state each one claims, which the parse golden does
 * not record.
 *
 * - `uber.screen.idle_map` branch 1 recognizes the offline status sheet by structure
 *   (`status_assistant` + `agenda_feed_content_view`) when its label is a promo line ("It's dinner
 *   time") or the required-action heading ("Before you go online") instead of "You're offline".
 *   The claim is offline, so the online neighbours must not reach it: the awaiting_offer frame
 *   0.9 s before the promo sheet's shift ("You're online" + GO OFFLINE) and an on-trip card both
 *   carry `agenda_content_view`, never the feed view.
 * - `uber.screen.session_summary` branch 1 recognizes the "Your session (…)" sheet, which never
 *   draws "Session summary", off "Trips completed", "Trips offered" and "Open earnings activity".
 *
 * The partial frames that carry `status_assistant` without the feed view stay UNKNOWN in
 * `snapshots/UNKNOWN/negative/` ([cloud.trotter.log.gig.core.pipeline.recognition.matchers.NegativeCorpusStaysUnknownTest]).
 */
class UberOfflineSheetAndSessionSummaryTest {

    private fun snapshot(folder: String, filename: String): UiNode {
        val file = File("src/test/resources/snapshots/$folder/$filename")
        if (!file.isFile) error("fixture $folder/$filename is missing; this test's subject moved or was pruned")
        return TestResourceLoader.loadNode(file)
    }

    private fun classify(node: UiNode) = TestRulesetFactory.screenRuleset.matchFirst(node, platformWire = "uber")

    @Test
    fun `the offline status sheet without an offline label is idle_map and offline`() {
        listOf(PROMO_SHEET, REQUIRED_ACTION_FIRST, REQUIRED_ACTION_SETTLED, OFFLINE_LABEL_SHEET).forEach { file ->
            val result = classify(snapshot(IDLE_MAP, file))
                ?: throw AssertionError("$file classified UNKNOWN; the #1699 status-sheet branch regressed")
            assertEquals(file, IDLE_MAP_ID, result.ruleId)
            assertEquals(file, Flow.Idle, result.flow)
            assertEquals(file, Mode.Offline, result.modeHint)
        }
    }

    @Test
    fun `the online frame just before the offline sheet stays awaiting_offer and online`() {
        listOf(ONLINE_BEFORE_SHEET, FINDING_TRIPS).forEach { file ->
            val result = classify(snapshot(AWAITING, file))
                ?: throw AssertionError("$file classified UNKNOWN")
            assertEquals(file, AWAITING_ID, result.ruleId)
            assertEquals(file, Mode.Online, result.modeHint)
        }
    }

    @Test
    fun `an on-trip card is never claimed offline`() {
        val result = classify(snapshot(ACTIVE_TRIP, ON_TRIP))
        assertNotEquals(IDLE_MAP_ID, result?.ruleId)
        assertEquals(ACTIVE_TRIP_ID, result?.ruleId)
    }

    @Test
    fun `the Your session sheet is session_summary, as is the older Session summary sheet`() {
        listOf(SESSION_4606, SESSION_JULY).forEach { file ->
            val result = classify(snapshot(SESSION, file))
                ?: throw AssertionError("$file classified UNKNOWN; the session summary anchor regressed")
            assertEquals(file, SESSION_ID, result.ruleId)
            assertEquals(file, Flow.SessionEnded, result.flow)
            assertEquals(file, Mode.Offline, result.modeHint)
        }
    }

    @Test
    fun `no neighbouring frame is claimed as a session summary`() {
        val neighbours = listOf(
            IDLE_MAP to PROMO_SHEET,
            IDLE_MAP to OFFLINE_LABEL_SHEET,
            AWAITING to ONLINE_BEFORE_SHEET,
            ACTIVE_TRIP to ON_TRIP,
        )
        neighbours.forEach { (folder, file) ->
            assertNotEquals(file, SESSION_ID, classify(snapshot(folder, file))?.ruleId)
        }
    }

    private companion object {
        const val IDLE_MAP = "idle_map"
        const val AWAITING = "awaiting_offer"
        const val ACTIVE_TRIP = "active_trip"
        const val SESSION = "session_summary"

        const val IDLE_MAP_ID = "uber.screen.idle_map"
        const val AWAITING_ID = "uber.screen.awaiting_offer"
        const val ACTIVE_TRIP_ID = "uber.screen.active_trip"
        const val SESSION_ID = "uber.screen.session_summary"

        const val PROMO_SHEET = "2026-11-20_20-28-52-159__uber__accessibility.window__UNKNOWN__1246d3.json"
        const val REQUIRED_ACTION_FIRST = "2026-11-21_15-00-34-018__uber__accessibility.window__UNKNOWN__afc9e7.json"
        const val REQUIRED_ACTION_SETTLED = "2026-11-21_15-00-58-700__uber__accessibility.window__UNKNOWN__1c5d0b.json"
        const val OFFLINE_LABEL_SHEET = "2026-11-20_20-28-01-750__uber__accessibility.window__idle_map__03b17a.json"

        const val ONLINE_BEFORE_SHEET = "2026-11-20_20-28-00-878__uber__accessibility.window__awaiting_offer__22c3ba.json"
        const val FINDING_TRIPS = "2026-11-20_19-33-09-140__uber__accessibility.window__awaiting_offer__0a1a9b.json"
        const val ON_TRIP = "2026-11-20_19-34-18-367__uber__accessibility.window__active_trip__ca50e4.json"

        const val SESSION_4606 = "2026-11-20_20-28-04-971__uber__accessibility.window__UNKNOWN__0919ce.json"
        const val SESSION_JULY = "2026-07-21_19-15-20-384__uber__accessibility.window__session_summary__fbdfa9.json"
    }
}
