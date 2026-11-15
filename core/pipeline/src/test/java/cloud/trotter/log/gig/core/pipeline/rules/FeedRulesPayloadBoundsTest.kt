package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.feed.contract.Feed
import cloud.trotter.feed.contract.FeedJson
import cloud.trotter.feed.contract.FeedLimits
import cloud.trotter.feed.contract.FeedRefusal
import cloud.trotter.log.gig.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1563: the shipped rules, published as a rules feed generation, fit the feed format's rules-payload bounds: the
 * per-file and per-generation byte caps, the file count, a name the platform registry resolves, and the rule compiler's
 * own depth bound. They do not fit the depth-4 bound of the envelope and the other payloads, which is why rules
 * payloads keep the compiler's bounds.
 */
class FeedRulesPayloadBoundsTest {

    private val files: List<File> by lazy {
        val relative = "core/pipeline/build/generated/assets/importMatchersRules/rules"
        var dir = File(".").absoluteFile.normalize()
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("could not locate the repo root")
        }
        val rules = File(dir, relative).also { check(it.isDirectory) { "generated rules dir missing at ${it.path}" } }
        rules.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
    }

    @Test
    fun `the production rules bundle fits the rules feed caps`() {
        assertTrue(files.isNotEmpty())
        assertTrue(files.size <= FeedLimits.MAX_FILES_PER_MANIFEST)
        for (file in files) {
            assertTrue("${file.name}: ${file.length()} bytes", file.length() <= Feed.RULES.maxFileBytes)
            assertNotNull("${file.name} is not a platform wire", Platform.fromWire(file.nameWithoutExtension))
        }
        assertTrue(files.sumOf { it.length() } <= Feed.RULES.maxGenerationBytes)
        assertEquals(FeedLimits.MAX_RULES_FILE_BYTES, MAX_RULE_FILE_BYTES)
    }

    @Test
    fun `the production rules bundle passes the compiler's bounds and only those`() {
        for (file in files) {
            val text = file.readText()
            RuleCompiler.parseBoundedJson(text)
            assertNull("${file.name} is strict JSON with no duplicate key", FeedJson.check(text, RuleCompiler.MAX_JSON_DEPTH))
            assertEquals(file.name, FeedRefusal.TooDeep, FeedJson.check(text, FeedLimits.MAX_STRUCTURE_DEPTH))
        }
    }
}
