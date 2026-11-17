package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.test.util.TestResourceLoader
import cloud.trotter.log.gig.test.util.TestRulesetFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1670: `doordash.screen.earnings` (priority 93) runs before `doordash.screen.sensitive.catchall`
 * (999, overrideable), and DoorDash draws banking-shaped text on the same weekly earnings page. The
 * earnings rule therefore rejects every frame carrying one of the catchall's terms, so the catchall
 * still claims (and blocks) it. A rule file cannot reference another rule's list, so the list is
 * copied, and this test is the control against drift under CLAUDE.md principle 5, following the
 * [DrawerOfferSignaturePinTest] precedent. Reads the GENERATED assets.
 */
class EarningsCatchallParityTest {

    private val screens by lazy {
        val file = File(File(TestRulesetFactory.rulesDir), "doordash.json")
        Json.parseToJsonElement(file.readText()).jsonObject.getValue("screens").jsonArray.map { it.jsonObject }
    }

    private fun rule(id: String): JsonObject = screens.single { it.getValue("id").jsonPrimitive.content == id }

    private fun lists(element: JsonElement, into: MutableList<List<String>>) {
        when (element) {
            is JsonObject -> {
                (element["allTextContainsAny"] as? JsonArray)?.let { arr -> into += arr.map { it.jsonPrimitive.content } }
                element.values.forEach { lists(it, into) }
            }
            is JsonArray -> element.forEach { lists(it, into) }
            else -> Unit
        }
    }

    private fun catchallTerms(): List<String> {
        val found = mutableListOf<List<String>>()
        lists(rule(CATCHALL).getValue("require"), found)
        return found.single()
    }

    @Test
    fun `the earnings reject list is the catchall list, byte for byte and in order`() {
        val catchall = catchallTerms()
        val inRejects = mutableListOf<List<String>>()
        lists(rule(EARNINGS).getValue("reject"), inRejects)

        assertTrue("the pin is worthless if the catchall lists no terms", catchall.isNotEmpty())
        assertEquals(
            "doordash.screen.earnings must reject exactly sensitive.catchall's terms, or a banking frame " +
                "that sensitive.known misses is recognized as earnings instead of blocked. Change both lists together.",
            listOf(catchall),
            inRejects,
        )
    }

    @Test
    fun `a weekly earnings page carrying a catchall term is blocked, not recognized`() {
        val page = TestResourceLoader.loadNode(File(WEEKLY_PAGE))
        assertEquals(
            "the subject fixture must be recognized as earnings on its own, or the block below proves nothing",
            EARNINGS,
            classify(page)?.ruleId,
        )

        assertEquals(CATCHALL, classify(page.withText("Direct deposit"))?.ruleId)

        // Every term: some sensitive rule claims the frame (sensitive.known takes a few terms first).
        catchallTerms().forEach { term ->
            val ruleId = classify(page.withText(term))?.ruleId
            assertTrue(
                "'$term' scrolled into the weekly earnings page classified $ruleId; it must be blocked",
                ruleId?.startsWith("doordash.screen.sensitive.") == true,
            )
        }
    }

    private fun classify(node: UiNode) = TestRulesetFactory.screenRuleset.matchFirst(node, platformWire = "doordash")

    private fun UiNode.withText(text: String) = copy(children = children + UiNode(text = text))

    private companion object {
        const val EARNINGS = "doordash.screen.earnings"
        const val CATCHALL = "doordash.screen.sensitive.catchall"
        const val WEEKLY_PAGE =
            "src/test/resources/snapshots/earnings/" +
                "2026-11-15_17-46-10-667__doordash__accessibility.window__UNKNOWN__cc9ac1.json"
    }
}
