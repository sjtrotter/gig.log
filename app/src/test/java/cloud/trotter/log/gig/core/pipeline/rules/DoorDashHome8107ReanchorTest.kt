package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.IdleSurface
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.test.util.TestResourceLoader
import cloud.trotter.log.gig.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Pins the DoorDash 8.107.10 home re-anchors, the state each one claims, which the parse golden
 * does not record.
 *
 * - #1669: `waiting_for_offer` branch 1 recognizes the screen drawn without its "Finding offers"
 *   card. It claims idle + online and reads the "This dash" pill, but never `idleSurface: no_orders`,
 *   which only the card shows. Branch 0 keeps that surface, and its third waitTimeEstimate arm reads
 *   the value past 8.107.10's info View.
 * - #1668: `idle_map` recognizes the offline home drawn without its Dash button, off the
 *   "Avg. offer wait" stat inside `offline_screen`. The start-of-shift frame that draws both hosts
 *   stays UNKNOWN (`snapshots/UNKNOWN/negative/`, [NegativeCorpusStaysUnknownTest]).
 *
 * Corpus note: `waiting_for_offer/` and `idle_map/` already hold more than
 * `SnapshotLibrarian.RETENTION_LIMIT` (15) distinct variants, so the Inbox path drops a newcomer.
 * These fixtures were placed by hand as a documented cap override (the PR #1038 precedent).
 */
class DoorDashHome8107ReanchorTest {

    private fun snapshot(folder: String, filename: String): UiNode {
        val file = File("src/test/resources/snapshots/$folder/$filename")
        if (!file.isFile) error("fixture $folder/$filename is missing; this test's subject moved or was pruned")
        return TestResourceLoader.loadNode(file)
    }

    private fun classify(node: UiNode) = TestRulesetFactory.screenRuleset.matchFirst(node, platformWire = "doordash")

    @Test
    fun `the card-less waiting screen is idle and online, reads the pill, and claims no no_orders surface`() {
        val expectedPay = mapOf(CARDLESS_NO_PILL to null, CARDLESS_PAY_5 to 5.0, CARDLESS_PAY_22 to 22.55)
        expectedPay.forEach { (file, pay) ->
            val result = classify(snapshot(WAITING, file))
                ?: throw AssertionError("$file classified UNKNOWN; the #1669 card-less branch regressed")
            assertEquals(file, WAITING_ID, result.ruleId)
            assertEquals(file, Flow.Idle, result.flow)
            assertEquals(file, Mode.Online, result.modeHint)
            assertNull("$file has no Finding-offers card, so it shows nothing about orders", result.idleSurface)
            assertEquals(file, pay, result.fields["sessionPay"])
            assertNull(file, result.fields["waitTimeEstimate"])
        }
    }

    @Test
    fun `the card frame keeps no_orders and reads the wait past the info view`() {
        val result = classify(snapshot(WAITING, CARD_FRAME))
            ?: throw AssertionError("$CARD_FRAME classified UNKNOWN")
        assertEquals(WAITING_ID, result.ruleId)
        assertEquals(IdleSurface.NO_ORDERS, result.idleSurface)
        assertEquals(Mode.Online, result.modeHint)
        assertEquals("1-2 min", result.fields["waitTimeEstimate"])
        // The pill reads a weekly-goal percent beside "This dash", never money.
        assertNull(result.fields["sessionPay"])
    }

    @Test
    fun `the offline home without its Dash button is idle_map and offline`() {
        listOf(OFFLINE_A, OFFLINE_B, OFFLINE_PEAK).forEach { file ->
            val result = classify(snapshot(IDLE_MAP, file))
                ?: throw AssertionError("$file classified UNKNOWN; the #1668 offline anchor regressed")
            assertEquals(file, IDLE_MAP_ID, result.ruleId)
            assertEquals(file, Mode.Offline, result.modeHint)
            assertNull("$file: 8.107.10 shows no earnings-mode signal to read", result.fields["sessionType"])
        }
    }

    private companion object {
        const val WAITING = "waiting_for_offer"
        const val IDLE_MAP = "idle_map"
        const val WAITING_ID = "doordash.screen.waiting_for_offer"
        const val IDLE_MAP_ID = "doordash.screen.idle_map"

        const val CARDLESS_NO_PILL = "2026-11-15_16-42-06-520__doordash__accessibility.window__UNKNOWN__9bee5e.json"
        const val CARDLESS_PAY_5 = "2026-11-15_18-49-03-557__doordash__accessibility.window__UNKNOWN__d44c7e.json"
        const val CARDLESS_PAY_22 = "2026-11-15_19-18-10-387__doordash__accessibility.window__UNKNOWN__da9562.json"
        const val CARD_FRAME = "2026-11-15_19-19-22-970__doordash__accessibility.window__waiting_for_offer__9c717b.json"

        const val OFFLINE_A = "2026-11-15_14-14-18-414__doordash__accessibility.window__UNKNOWN__cbdaca.json"
        const val OFFLINE_PEAK = "2026-11-15_17-47-32-858__doordash__accessibility.window__UNKNOWN__a0778d.json"
        const val OFFLINE_B = "2026-11-16_13-36-04-377__doordash__accessibility.window__UNKNOWN__96043c.json"
    }
}
