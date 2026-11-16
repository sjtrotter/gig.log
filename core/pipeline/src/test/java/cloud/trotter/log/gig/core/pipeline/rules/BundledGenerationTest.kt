package cloud.trotter.log.gig.core.pipeline.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1568: the shipped rules through the batch loader. A bundled file that fails to compile, or a
 * bundled rule the compiler isolates, rejects the whole bundled generation, and with nothing older
 * to fall back to the app would recognize nothing, so CI holds the shipped set to zero of either.
 * The corpus suites in `:app` read this same path through `TestRulesetFactory`.
 */
class BundledGenerationTest {

    private val rulesDir: File by lazy {
        val relative = "core/pipeline/build/generated/assets/importMatchersRules/rules"
        var dir = File(".").absoluteFile.normalize()
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("could not locate the repo root")
        }
        File(dir, relative).also { check(it.isDirectory) { "generated rules dir missing at ${it.path}" } }
    }

    private val generated: List<Pair<String, String>> by lazy {
        rulesDir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
            .map { "rules/${it.name}" to it.readText() }
    }

    /** The live files; the `_shadow` files (#1630) are checked on their own below. */
    private val shipped by lazy { generated.filterNot { (path, _) -> path.removeSuffix(".json").endsWith(ShadowSetCompiler.SHADOW_SUFFIX) } }
    private val shippedShadow by lazy { generated - shipped.toSet() }

    @Test
    fun `every shipped file compiles whole, with no isolated rule`() {
        assertTrue(shipped.isNotEmpty())
        for ((path, json) in shipped) {
            val compiled = compileRuleFile(json, path)
            assertNotNull("$path failed to compile", compiled)
            assertEquals("$path isolated rules", emptyList<String>(), compiled!!.isolatedRuleIds)
        }
    }

    @Test
    fun `no shipped live file carries a shadow rule`() {
        for ((path, json) in shipped) {
            for (section in listOf("screens", "clicks", "notifications")) {
                val marked = Json.parseToJsonElement(json).jsonObject[section]?.jsonArray.orEmpty()
                    .filter { "shadow" in it.jsonObject }
                assertEquals("$path $section", emptyList<Any>(), marked)
            }
        }
    }

    @Test
    fun `every shipped shadow candidate loads, none dropped`() {
        val prepared = RuleGenerationAssembler.assemble(
            generated.map { (path, json) -> RuleFile.Bundled(path, json) }, emptyList(),
        ) as GenerationResult.Prepared
        val candidates = shippedShadow.sumOf { (_, json) -> Json.parseToJsonElement(json).jsonObject["screens"]?.jsonArray?.size ?: 0 }
        assertEquals(candidates, prepared.generation.shadow?.candidates?.size ?: 0)
        assertEquals(0, prepared.generation.shadow?.droppedCandidates ?: 0)
    }

    @Test
    fun `the bundled generation carries every shipped rule`() {
        val result = RuleGenerationAssembler.assemble(shipped.map { (path, json) -> RuleFile.Bundled(path, json) }, emptyList())
        val generation = (result as GenerationResult.Prepared).generation

        fun count(section: String) = shipped.sumOf { (_, json) ->
            Json.parseToJsonElement(json).jsonObject[section]?.jsonArray?.size ?: 0
        }
        assertEquals(count("screens"), generation.screens.ruleCount)
        assertEquals(count("clicks"), generation.clicks.ruleCount)
        assertEquals(count("notifications"), generation.notifications.ruleCount)
        assertEquals(shipped.size, result.bundledFiles)
    }
}
