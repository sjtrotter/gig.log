package cloud.trotter.log.gig.core.pipeline.rules

import android.content.Context
import android.content.res.AssetManager
import cloud.trotter.log.gig.domain.capability.RuleCapabilityGrants
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.pipeline.RuleEngineConstants
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * #1630: the shadow set. Bundled `<platform>_shadow` files compile into a [ShadowSet] attached to the live generation
 * they were validated against, and no shadow input changes the live generation: its rules, its order, its coverage,
 * its capabilities and its id stay what they are without the shadow file.
 */
class ShadowSetTest {

    private fun file(vararg screens: String, platform: String = "doordash.driver", version: Int = 2) = """
        { "format_version": $version, "platform_id": "$platform", "screens": [ ${screens.joinToString(",")} ] }
    """.trimIndent()

    private fun rule(id: String, priority: Int, text: String, shape: String? = null, extra: String = "") = """
        { "id": "$id", "priority": $priority,
          ${shape?.let { "\"parse\": { \"as\": \"$it\" }," } ?: ""}
          "require": { "exists": { "hasText": "$text" } }$extra }
    """.trimIndent()

    private fun candidate(id: String, priority: Int, text: String, extra: String = "") =
        rule(id, priority, text, extra = """, "shadow": true$extra""")

    private val known = rule("doordash.screen.sensitive.known", 0, "Available Balance", "sensitive")
        .replace("\"priority\": 0,", "\"priority\": 0, \"overrideable\": false,")
    private val idle = rule("doordash.screen.idle_map", 10, "Dash Now")
    private val live = RuleFile.Bundled("rules/doordash.json", file(known, idle))

    /** A candidate that shares the live idle rule's id, matches a screen no live rule matches, and binds a target. */
    private val offerCandidate = candidate(
        "doordash.screen.idle_map", 5, "New Offer",
        extra = """, "enables": ["accept_offer"], "bind": { "acceptButton": { "find": { "hasText": "Accept" } } }""",
    )
    private val otherCandidate = candidate("doordash.screen.pickup", 20, "Pickup")

    private fun shadowFile(vararg candidates: String, version: Int = RuleEngineConstants.SHADOW_FORMAT_VERSION, name: String = "doordash_shadow") =
        RuleFile.Bundled("rules/$name.json", file(*candidates, version = version))

    private fun prepared(vararg files: RuleFile.Bundled, remote: List<RuleFile.Remote> = emptyList()): GenerationResult.Prepared {
        val result = RuleGenerationAssembler.assemble(files.toList(), remote)
        if (result !is GenerationResult.Prepared) fail("expected a prepared generation, got $result")
        return result as GenerationResult.Prepared
    }

    private fun screen(vararg text: String) = UiNode(children = text.map { UiNode(text = it) })

    private fun assertSameLive(expected: GenerationResult.Prepared, actual: GenerationResult.Prepared) {
        val a = expected.generation
        val b = actual.generation
        assertEquals(a.id, b.id)
        assertEquals(a.screens.ruleCount, b.screens.ruleCount)
        assertEquals(a.clicks.ruleCount, b.clicks.ruleCount)
        assertEquals(a.notifications.ruleCount, b.notifications.ruleCount)
        assertEquals(expected.capabilities, actual.capabilities)
        assertEquals(expected.bundledFiles, actual.bundledFiles)
        for (text in listOf("Available Balance", "Dash Now", "New Offer", "Pickup")) {
            assertEquals(text, a.screens.matchFirst(screen(text), "doordash")?.ruleId, b.screens.matchFirst(screen(text), "doordash")?.ruleId)
        }
    }

    @Test
    fun `no shadow file, no shadow set`() {
        val generation = prepared(live).generation
        assertNull(generation.shadow)
        assertNotNull(generation.id)
    }

    @Test
    fun `a candidate compiles into the shadow set and nowhere live`() {
        val without = prepared(live)
        val with = prepared(live, shadowFile(offerCandidate, otherCandidate))
        val shadow = with.generation.shadow!!

        assertEquals(listOf("doordash.screen.idle_map", "doordash.screen.pickup"), shadow.candidates.map { it.id })
        assertEquals(Platform.DoorDash, shadow.candidates.first().platform)
        assertEquals(0, shadow.droppedCandidates)
        assertEquals(with.generation.id, shadow.generationId)
        // Compiled in full: binds and enables are kept on the candidate.
        assertEquals(listOf("acceptButton"), shadow.candidates.first().rule.bindings.map { it.name })

        assertSameLive(without, with)
        // Not in evaluation order: only the candidate matches this screen, and nothing live does.
        assertNull(with.generation.screens.matchFirst(screen("New Offer"), "doordash"))
        // The shared id resolves to the live rule.
        assertEquals(10, with.generation.screens.ruleById("doordash.screen.idle_map")?.priority)
        // Not enumerated: the candidate's accept binding mints no capability.
        assertTrue(with.capabilities.none { it.ruleId == "doordash.screen.idle_map" })
    }

    @Test
    fun `the candidate digest is the JCS digest of the object as emitted, shadow key included`() {
        val set = prepared(live, shadowFile(otherCandidate)).generation.shadow!!
        val raw = Json.parseToJsonElement(file(otherCandidate)).jsonObject.getValue("screens").jsonArray.single().jsonObject
        assertEquals(RuleIdentifiers.candidateDigest(raw), set.candidates.single().digest)
        assertEquals(64, set.candidates.single().digest.length)
    }

    @Test
    fun `a sensitive-shaped candidate does not cover a platform`() {
        val uncovered = RuleFile.Bundled("rules/doordash.json", file(idle))
        val sensitive = rule("doordash.screen.sensitive.extra", 1, "Available Balance", "sensitive", extra = """, "shadow": true""")
        val generation = prepared(uncovered, shadowFile(sensitive)).generation
        // The platform stays uncovered, so its live screen rules are excluded (#432) and its candidates have no platform.
        assertEquals(0, generation.screens.ruleCount)
        assertNull(generation.shadow)
    }

    @Test
    fun `a broken shadow file leaves the live generation identical and loads no shadow set`() {
        val without = prepared(live)
        val broken = listOf(
            RuleFile.Bundled("rules/doordash_shadow.json", "{ not json"),
            RuleFile.Bundled("rules/doordash_shadow.json", "[]"),
            shadowFile(otherCandidate, version = 2),
            shadowFile(otherCandidate, version = RuleEngineConstants.SHADOW_FORMAT_VERSION + 1),
            RuleFile.Bundled("rules/doordash_shadow.json", file(otherCandidate, version = 3).replace("\"screens\"", "\"clicks\": [], \"screens\"")),
            RuleFile.Bundled("rules/doordash_shadow.json", file(otherCandidate, platform = "uber.driver", version = 3)),
            RuleFile.Bundled("rules/doordash_shadow.json", file(otherCandidate, platform = "acme.driver", version = 3)),
            RuleFile.Bundled("rules/doordash_shadow.json", "{".repeat(200) + "}".repeat(200)),
        )
        for (bad in broken) {
            val with = prepared(live, bad)
            assertSameLive(without, with)
            assertNull(bad.json.take(40), with.generation.shadow)
        }
    }

    @Test
    fun `a shadow file for a platform with no live screen rules loads nothing`() {
        val generation = prepared(TestBundledFloor.uber, shadowFile(otherCandidate)).generation
        assertNull(generation.shadow)
    }

    @Test
    fun `one invalid candidate is dropped alone`() {
        val without = prepared(live)
        val invalids = listOf(
            candidate("doordash.screen.typo", 30, "Typo", extra = """, "overridable": true"""),
            candidate("doordash.screen.bad_bind", 31, "Bad", extra = """, "bind": { "acceptButton": { "find": { "hasText": "Accept" } } }"""),
            candidate("uber.screen.wrong_prefix", 32, "Wrong"),
            rule("doordash.screen.unmarked", 33, "Unmarked"),
            rule("doordash.screen.false_mark", 34, "False", extra = """, "shadow": false"""),
            rule("doordash.screen.string_mark", 35, "String", extra = """, "shadow": "true""""),
            candidate("doordash.screen.sensitive.bad", 36, "Bank", extra = """, "overridable": false"""),
        )
        for (invalid in invalids) {
            val with = prepared(live, shadowFile(otherCandidate, invalid))
            assertSameLive(without, with)
            val set = with.generation.shadow
            assertNotNull(invalid, set)
            assertEquals(invalid, listOf("doordash.screen.pickup"), set!!.candidates.map { it.id })
            assertEquals(invalid, 1, set.droppedCandidates)
        }
    }

    @Test
    fun `two candidates with one id are both dropped`() {
        val twin = candidate("doordash.screen.pickup", 21, "Pickup again")
        val set = prepared(live, shadowFile(otherCandidate, twin, offerCandidate)).generation.shadow!!
        assertEquals(listOf("doordash.screen.idle_map"), set.candidates.map { it.id })
        assertEquals(2, set.droppedCandidates)
        assertNull(prepared(live, shadowFile(otherCandidate, twin)).generation.shadow)
    }

    @Test
    fun `two candidates sharing a 16-hex digest prefix reject the whole set`() {
        val files = listOf(shadowFile(otherCandidate, offerCandidate))
        var n = 0
        val colliding: (JsonObject) -> String? = { "0123456789abcdef" + (n++).toString().padStart(48, '0') }
        assertNull(ShadowSetCompiler.build(files, "a".repeat(64), setOf(Platform.DoorDash), colliding))
        var m = 0
        val distinct: (JsonObject) -> String? = { (m++).toString().padStart(16, '0') + "f".repeat(48) }
        assertEquals(2, ShadowSetCompiler.build(files, "a".repeat(64), setOf(Platform.DoorDash), distinct)?.candidates?.size)
    }

    @Test
    fun `the current loader rejects the shadow file's version`() {
        val json = shadowFile(otherCandidate).json
        assertNotNull(RulesetLoader.validate(Json.parseToJsonElement(json).jsonObject, "rules/doordash_shadow.json"))
        assertNull(compileRuleFile(json, "rules/doordash_shadow.json"))
    }

    @Test
    fun `a remote shadow file is never minted`() {
        val file = TestRulesFeed.verifiedFiles(listOf(TestRulesFeed.File("doordash_shadow", shadowFile(otherCandidate).json))).single()
        assertNull(VerifiedRulesetBytes.fromFeedFile(file))
    }

    @Test
    fun `the shadow version is never accepted for a live file`() {
        val liveAtShadowVersion = RuleFile.Bundled("rules/doordash.json", file(known, idle, version = RuleEngineConstants.SHADOW_FORMAT_VERSION))
        assertTrue(RuleGenerationAssembler.assemble(listOf(liveAtShadowVersion), emptyList()) is GenerationResult.Rejected)
    }

    @Test
    fun `a live file carrying a shadow rule is rejected`() {
        val marked = RuleFile.Bundled("rules/doordash.json", file(known, idle, otherCandidate))
        assertTrue(RuleGenerationAssembler.assemble(listOf(marked), emptyList()) is GenerationResult.Rejected)
    }

    @Test
    fun `a remote generation rebuilds the shadow set against itself`() = runTest {
        val interpreter = JsonRuleInterpreter(mock<Context>(), mock<RuleCapabilityGrants>())
        interpreter.loadBundled(listOf(live, shadowFile(otherCandidate)))
        val bundled = interpreter.generation!!
        assertEquals(bundled.id, bundled.shadow?.generationId)

        interpreter.load(TestRulesFeed.verified(file(known, rule("doordash.screen.idle_map", 11, "Dash Now"))))
        val remote = interpreter.generation!!
        assertNotEquals(bundled.id, remote.id)
        assertEquals(remote.id, remote.shadow?.generationId)
        assertEquals(bundled.shadow?.candidates?.map { it.digest }, remote.shadow?.candidates?.map { it.digest })
    }
    @Test
    fun `an unreadable shadow asset directory leaves the bundled load intact`() = runTest {
        val assets = mock<AssetManager> {
            on { list("rules") } doReturn arrayOf("doordash.json", "shadow")
            on { open("rules/doordash.json") } doReturn live.json.byteInputStream()
            on { list("rules/shadow") } doThrow java.io.IOException("unreadable")
        }
        val context = mock<Context>()
        whenever(context.assets).thenReturn(assets)
        val interpreter = JsonRuleInterpreter(context, mock<RuleCapabilityGrants>())
        interpreter.loadDefaults()
        assertTrue(interpreter.isLoaded)
        assertNull(interpreter.generation!!.shadow)
        assertEquals(prepared(live).generation.id, interpreter.generation!!.id)
    }
}
