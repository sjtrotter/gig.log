package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.domain.capture.dto.UiNodeDto
import cloud.trotter.log.gig.domain.capture.toDomain
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * #1630 benchmark, live half: `Ruleset.matchFirst` per frame over the largest committed corpus trees, so the shared
 * per-rule evaluation can be shown not to slow the live path. Off the PR path: runs only with
 * `-Dgiglog.shadowBench=true` (forwarded to the test worker by the root build). Uses only APIs master also has, so the
 * same file measures master's code path. Writes its table to `core/pipeline/build/shadow-bench/live.md`.
 *
 * A JVM number is a proxy for the device; the on-device check is a field checklist item.
 */
class LiveMatchBenchmarkTest {

    companion object {
        val enabled: Boolean get() = System.getProperty("giglog.shadowBench") == "true"

        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        val repoRoot: File by lazy {
            var dir = File(".").absoluteFile.normalize()
            while (!File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile ?: error("no repo root")
            dir
        }

        /** Every live screen rule of the generated assets, with its file's platform id. */
        val screenRuleObjects: List<Pair<String, JsonObject>> by lazy {
            File(repoRoot, "core/pipeline/build/generated/assets/importMatchersRules/rules")
                .listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
                .flatMap { file ->
                    val root = Json.parseToJsonElement(file.readText()).jsonObject
                    val platformId = (root["platform_id"] as JsonPrimitive).content
                    (root["screens"] as? JsonArray).orEmpty().map { platformId to it.jsonObject }
                }
        }

        val liveScreenRuleset: Ruleset<UiNode> by lazy {
            Ruleset(
                screenRuleObjects.groupBy({ it.first }, { it.second }).flatMap { (platformId, rules) ->
                    RuleCompiler.compileRules<UiNode>(JsonArray(rules), RuleContext.SCREEN, platformId)
                },
            )
        }

        /** The [count] largest committed corpus trees by node count, largest first. */
        fun largestCorpusTrees(count: Int): List<Pair<String, UiNode>> =
            File(repoRoot, "app/src/test/resources/snapshots").walkTopDown()
                .filter { it.isFile && it.extension == "json" && "INBOX" !in it.path && it.name != "approved-parse-output.json" }
                .mapNotNull { file -> runCatching { file.parentFile?.name + "/" + file.name to decode(file.readText()) }.getOrNull() }
                .sortedByDescending { nodeCount(it.second) }
                .take(count)
                .toList()

        private fun decode(text: String): UiNode {
            val root = Json.parseToJsonElement(text).jsonObject
            val dto = root["payload"] ?: root["root"] ?: root
            return json.decodeFromJsonElement(UiNodeDto.serializer(), dto).toDomain()
        }

        fun nodeCount(node: UiNode): Int = 1 + node.children.sumOf { nodeCount(it) }

        fun percentile(samples: List<Long>, p: Double): Long {
            val sorted = samples.sorted()
            return sorted[((sorted.size - 1) * p).toInt()]
        }

        fun micros(ns: Long): String = "%.1f".format(ns / 1_000.0)

        fun report(name: String, lines: List<String>) {
            val dir = File(repoRoot, "core/pipeline/build/shadow-bench").apply { mkdirs() }
            File(dir, "$name.md").writeText(lines.joinToString("\n", postfix = "\n"))
            lines.forEach(::println)
        }
    }

    @Test
    fun `live matchFirst per frame over the largest corpus trees`() {
        assumeTrue(enabled)
        val trees = largestCorpusTrees(20)
        val ruleset = liveScreenRuleset
        repeat(200) { trees.forEach { (_, tree) -> ruleset.matchFirst(tree) } }

        val perTree = trees.associate { it.first to mutableListOf<Long>() }
        repeat(500) {
            for ((name, tree) in trees) {
                val start = System.nanoTime()
                ruleset.matchFirst(tree)
                perTree.getValue(name) += System.nanoTime() - start
            }
        }
        val all = perTree.values.flatten()
        val lines = mutableListOf(
            "## Live matchFirst, ${ruleset.ruleCount} screen rules, ${trees.size} largest corpus trees, 500 rounds",
            "",
            "| tree | nodes | median µs | p95 µs |",
            "|---|---:|---:|---:|",
        )
        for ((name, tree) in trees.take(5)) {
            val samples = perTree.getValue(name)
            lines += "| $name | ${nodeCount(tree)} | ${micros(percentile(samples, 0.5))} | ${micros(percentile(samples, 0.95))} |"
        }
        lines += "| **all ${trees.size} trees** | | **${micros(percentile(all, 0.5))}** | **${micros(percentile(all, 0.95))}** |"
        report("live", lines)
    }
}
