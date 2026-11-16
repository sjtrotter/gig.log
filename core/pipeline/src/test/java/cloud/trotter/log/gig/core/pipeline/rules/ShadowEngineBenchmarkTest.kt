package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.core.pipeline.rules.LiveMatchBenchmarkTest.Companion.enabled
import cloud.trotter.log.gig.core.pipeline.rules.LiveMatchBenchmarkTest.Companion.largestCorpusTrees
import cloud.trotter.log.gig.core.pipeline.rules.LiveMatchBenchmarkTest.Companion.micros
import cloud.trotter.log.gig.core.pipeline.rules.LiveMatchBenchmarkTest.Companion.nodeCount
import cloud.trotter.log.gig.core.pipeline.rules.LiveMatchBenchmarkTest.Companion.percentile
import cloud.trotter.log.gig.core.pipeline.rules.LiveMatchBenchmarkTest.Companion.report
import cloud.trotter.log.gig.core.pipeline.rules.LiveMatchBenchmarkTest.Companion.screenRuleObjects
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * #1630 benchmark, shadow half: the candidate engine over the largest committed corpus trees with every shipped live
 * screen rule treated as a candidate, and over worst-case legal candidates built to the DSL caps. Reports fuel and
 * wall time per candidate and per frame, and nanoseconds per fuel unit by kind of work, which is what the caps in
 * [ShadowFrameRunner] and the weights in [ShadowFuel] and [ShadowMaskScan] are read from. Off the PR path
 * (`-Dgiglog.shadowBench=true`); writes `core/pipeline/build/shadow-bench/shadow.md`. JVM numbers are a proxy.
 */
class ShadowEngineBenchmarkTest {

    private val clock = TransformRegistry.TransformClock(1_700_000_000_000L, ZoneOffset.UTC)

    private class Sample(val fuel: Long, val ns: Long, val outcome: CandidateOutcome, val id: String = "")

    private fun run(rule: CompiledRule<UiNode>, tree: UiNode, cap: Long): Sample {
        val meter = FuelMeter(cap)
        val start = System.nanoTime()
        val outcome = TransformRegistry.withClock(clock) { ShadowCandidateEngine.evaluateRule(rule, tree, meter) }
        return Sample(meter.used, System.nanoTime() - start, outcome, rule.id)
    }

    /** Median ns per fuel unit of [work] (which charges the meter it is given), after a warm-up. */
    private fun nsPerUnit(work: (FuelMeter) -> Unit): Pair<Long, Double> {
        repeat(20) { work(FuelMeter(Long.MAX_VALUE)) }
        val samples = List(31) {
            val meter = FuelMeter(Long.MAX_VALUE)
            val start = System.nanoTime()
            work(meter)
            meter.used to (System.nanoTime() - start)
        }
        val fuel = samples.first().first
        return fuel to percentile(samples.map { it.second }, .5).toDouble() / fuel.coerceAtLeast(1)
    }

    /** The match phase alone (no mask scan) of a metered [rule] on [tree]. */
    private fun matchPhase(rule: CompiledRule<UiNode>, tree: UiNode): (FuelMeter) -> Unit = { meter ->
        ShadowFuel.metering(meter) {
            TransformRegistry.withClock(clock) { RuleEvaluation.firstSurvivingBranch(rule, tree, null, { e -> throw e }, {}) }
        }
    }

    private fun calibration(tree: UiNode): List<Pair<String, (FuelMeter) -> Unit>> {
        val absent = "absent phrase"
        val p = maxProgramPattern
        return listOf(
            "node text scan (hasTextContaining)" to matchPhase(metered(rule("c_text", """"require": { "exists": { "hasTextContaining": "$absent" } }""")), tree),
            "subtree text (hasAnyText)" to matchPhase(metered(rule("c_any", """"require": { "exists": { "hasAnyText": "$absent" } }""")), tree),
            "joined text (20 × allTextContains)" to matchPhase(
                metered(rule("c_joined", """"require": { "any": [ ${(1..20).joinToString(",") { """{ "allTextContains": "$absent $it" }""" }} ] }""")), tree,
            ),
            "small regex (hasTextMatchesRegex)" to matchPhase(metered(rule("c_small", """"require": { "exists": { "hasTextMatchesRegex": "^zz[0-9]{3}$" } }""")), tree),
            "max-program regex (hasTextMatchesRegex)" to matchPhase(metered(rule("c_max", """"require": { "exists": { "hasTextMatchesRegex": "$p" } }""")), tree),
            "transform: trim + sha256 of the whole text" to matchPhase(
                metered(
                    rule(
                        "c_hash",
                        """"require": { "exists": { "isLeaf": false } }, "redact": [ { "find": { "hasText": "x" } } ], "parse": { "as": "ratings", "fields": { ${
                            (1..10).joinToString(",") { """"h$it": { "find": { "isLeaf": false }, "read": "allText", "transform": ["trim", "sha256"] }""" }
                        } } }""",
                    ),
                ),
                tree,
            ),
            "mask scan (no redact)" to { meter -> ShadowFuel.metering(meter) { ShadowMaskScan.scan(tree, CompiledRedact.EMPTY) } },
        )
    }

    private fun metered(rulesJson: String, platformId: String = "doordash.driver"): CompiledRule<UiNode> =
        ShadowFuel.compileMetered {
            RuleCompiler.compileRules<UiNode>(Json.parseToJsonElement(rulesJson).jsonArray, RuleContext.SCREEN, platformId)
        }.single()

    private fun rule(id: String, body: String) = """[{ "id": "doordash.screen.$id", "priority": 10, $body }]"""

    /** The legal regex (≤ [RuleCompiler.MAX_REGEX_LENGTH] chars, ≤ [RegexSafety.MAX_PROGRAM_SIZE]) with the largest program found. */
    private val maxProgramPattern: String by lazy {
        (1..60).flatMap { a -> (1..12).map { b -> "(?:[a-z0-9]{1,$a}[ .,-]){1,$b}qz" } }
            .mapNotNull { p -> runCatching { p to RegexSafety.compileRegex(p).programSize() }.getOrNull() }
            .maxBy { it.second }.first
    }

    /** A 4 000-node, depth-60 tree (the ingestion caps): 66 chains of 60, every node carrying text. */
    private val maxTree: UiNode by lazy {
        val chains = List(66) { c ->
            var node = UiNode(text = "Item $c-60 of the order")
            for (d in 59 downTo 1) node = UiNode(text = "Item $c-$d of the order", children = listOf(node))
            node
        }
        UiNode(text = "root", children = chains).restoreParents()
    }

    private fun worstCases(): List<Pair<String, CompiledRule<UiNode>>> {
        val p = maxProgramPattern.replace("\\", "\\\\")
        val exists = """{ "exists": { "hasAnyTextMatchesRegex": "$p" } }"""
        val longTexts = (1..40).joinToString(",") { "\"" + "absent phrase number $it ".repeat(4) + "\"" }
        // Each fold adds three predicate levels; six of them under `exists` reach RuleCompiler.MAX_DEPTH.
        val nested = (1..6).fold("""{ "hasAnyTextMatchesRegex": "$p" }""") { acc, _ -> """{ "any": [ { "not": { "not": $acc } } ] }""" }
        return listOf(
            "regex on every subtree" to metered(rule("w_regex", """"require": $exists""")),
            "any of 200 subtree regexes" to metered(rule("w_wide", """"require": { "any": [ ${List(200) { exists }.joinToString(",")} ] }""")),
            "200 joined-text scans × 40" to metered(
                rule("w_joined", """"require": { "any": [ ${List(200) { """{ "allTextContainsAll": [ $longTexts ] }""" }.joinToString(",")} ] }"""),
            ),
            "max-depth nesting" to metered(rule("w_deep", """"require": { "exists": $nested }""")),
            "64 branches × subtree regex" to metered(
                rule("w_branches", """"branches": [ ${List(RuleCompiler.MAX_BRANCHES_PER_RULE) { """{ "require": $exists }""" }.joinToString(",")} ]"""),
            ),
            "matched, 40 parse fields scanning every node" to metered(
                rule(
                    "w_parse",
                    """"require": { "exists": { "hasChildren": true } }, "parse": { "as": "ratings", "fields": { ${
                        (1..40).joinToString(",") { """"f$it": { "findAll": { "hasAnyTextMatchesRegex": "$p" }, "read": "allText" }""" }
                    } } }""",
                ),
            ),
        )
    }

    @Test
    fun `shadow candidate engine fuel and time`() {
        assumeTrue(enabled)
        val lines = mutableListOf<String>()
        val trees = largestCorpusTrees(10)
        val candidates = screenRuleObjects.mapNotNull { (platformId, obj) ->
            runCatching { obj.toString().let { metered("[$it]", platformId) } }.getOrNull()
        }

        // Warm up every path once over every tree.
        repeat(5) { trees.forEach { (_, tree) -> candidates.forEach { run(it, tree, Long.MAX_VALUE) } } }

        // ── Every shipped live screen rule as a candidate ────────────────────────────────────────
        val rounds = 30
        val perCandidate = mutableListOf<Sample>()
        val perFrameAll = mutableListOf<Pair<Long, Long>>()
        val perFrameTop16 = mutableListOf<Pair<Long, Long>>()
        repeat(rounds) {
            for ((_, tree) in trees) {
                val samples = candidates.map { run(it, tree, Long.MAX_VALUE) }
                perCandidate += samples
                perFrameAll += samples.sumOf { it.fuel } to samples.sumOf { it.ns }
                val top = samples.sortedByDescending { it.fuel }.take(ShadowSet.MAX_CANDIDATES_PER_PLATFORM)
                perFrameTop16 += top.sumOf { it.fuel } to top.sumOf { it.ns }
            }
        }
        val fuel = perCandidate.map { it.fuel }
        val ns = perCandidate.map { it.ns }
        lines += "## Shipped live screen rules as candidates: ${candidates.size} of ${screenRuleObjects.size} compiled, " +
            "${trees.size} largest corpus trees (${trees.joinToString { nodeCount(it.second).toString() }} nodes), $rounds rounds"
        lines += ""
        lines += "| measure | median | p95 | p99 | max |"
        lines += "|---|---:|---:|---:|---:|"
        lines += "| fuel per candidate | ${percentile(fuel, .5)} | ${percentile(fuel, .95)} | ${percentile(fuel, .99)} | ${fuel.max()} |"
        lines += "| µs per candidate | ${micros(percentile(ns, .5))} | ${micros(percentile(ns, .95))} | ${micros(percentile(ns, .99))} | ${micros(ns.max())} |"
        lines += "| fuel per frame, all candidates | ${percentile(perFrameAll.map { it.first }, .5)} | ${percentile(perFrameAll.map { it.first }, .95)} | | ${perFrameAll.maxOf { it.first }} |"
        lines += "| µs per frame, all candidates | ${micros(percentile(perFrameAll.map { it.second }, .5))} | ${micros(percentile(perFrameAll.map { it.second }, .95))} | | ${micros(perFrameAll.maxOf { it.second })} |"
        lines += "| fuel per frame, 16 costliest | ${percentile(perFrameTop16.map { it.first }, .5)} | ${percentile(perFrameTop16.map { it.first }, .95)} | | ${perFrameTop16.maxOf { it.first }} |"
        lines += "| µs per frame, 16 costliest | ${micros(percentile(perFrameTop16.map { it.second }, .5))} | ${micros(percentile(perFrameTop16.map { it.second }, .95))} | | ${micros(perFrameTop16.maxOf { it.second })} |"
        val heavy = perCandidate.filter { it.fuel > 1_000 }
        val byId = perCandidate.groupBy { it.id }.mapValues { (_, v) -> percentile(v.map { it.fuel }, .5) to percentile(v.map { it.ns }, .5) }
        lines += "| ns per unit (candidates over 1 000 units) | ${"%.2f".format(heavy.sumOf { it.ns }.toDouble() / heavy.sumOf { it.fuel })} | | | |"
        lines += "| outcomes | ${perCandidate.groupingBy { it.outcome.javaClass.simpleName + ((it.outcome as? CandidateOutcome.Matched)?.maskScan?.let { s -> ":$s" } ?: "") }.eachCount()} | | | |"
        lines += ""
        lines += "Costliest candidates (median over trees and rounds):"
        lines += ""
        lines += "| rule | fuel | µs | ns/unit |"
        lines += "|---|---:|---:|---:|"
        byId.entries.sortedByDescending { it.value.first }.take(6).forEach { (id, v) ->
            lines += "| $id | ${v.first} | ${micros(v.second)} | ${"%.2f".format(v.second.toDouble() / v.first.coerceAtLeast(1))} |"
        }
        lines += ""

        // ── Calibration: ns per unit by kind of work ─────────────────────────────────────────────
        lines += "## Calibration: median ns per fuel unit by kind of work"
        lines += ""
        lines += "| work | corpus max: fuel | ns/unit | synthetic: fuel | ns/unit |"
        lines += "|---|---:|---:|---:|---:|"
        val onCorpus = calibration(trees.first().second)
        val onSynthetic = calibration(maxTree)
        for (i in onCorpus.indices) {
            val (a, b) = nsPerUnit(onCorpus[i].second) to nsPerUnit(onSynthetic[i].second)
            lines += "| ${onCorpus[i].first} | ${a.first} | ${"%.2f".format(a.second)} | ${b.first} | ${"%.2f".format(b.second)} |"
        }
        lines += ""

        // ── Worst-case legal candidates ──────────────────────────────────────────────────────────
        val corpusMax = trees.first()
        lines += "## Worst-case legal candidates (regex `$maxProgramPattern`, program ${RegexSafety.compileRegex(maxProgramPattern).programSize()})"
        lines += ""
        lines += "| candidate | tree | unbounded fuel | unbounded µs | ns/unit | µs to exhaust ${ShadowFrameRunner.PER_CANDIDATE_FUEL} | outcome at cap |"
        lines += "|---|---|---:|---:|---:|---:|---|"
        val ceiling = 2_000_000_000L
        for ((name, rule) in worstCases()) {
            for ((treeName, tree) in listOf("corpus max (${nodeCount(corpusMax.second)})" to corpusMax.second, "synthetic 4 000 / depth 60" to maxTree)) {
                repeat(3) { run(rule, tree, ShadowFrameRunner.PER_CANDIDATE_FUEL) }
                val unbounded = run(rule, tree, ceiling)
                val capped = List(9) { run(rule, tree, ShadowFrameRunner.PER_CANDIDATE_FUEL) }
                val cappedNs = percentile(capped.map { it.ns }, .5)
                val unboundedFuel = if (unbounded.outcome == CandidateOutcome.Unevaluated(CandidateOutcome.Reason.BUDGET)) "> $ceiling" else "${unbounded.fuel}"
                lines += "| $name | $treeName | $unboundedFuel | ${micros(unbounded.ns)} | ${"%.2f".format(unbounded.ns.toDouble() / unbounded.fuel.coerceAtLeast(1))} | " +
                    "${micros(cappedNs)} | ${capped.first().outcome} |"
            }
        }
        lines += ""

        // ── The chosen caps on the worst case: a whole frame of 16 worst candidates ──────────────
        val worst = worstCases().first().second
        val set = ShadowSet("bench", List(ShadowSet.MAX_CANDIDATES_PER_PLATFORM) {
            ShadowCandidate(worst, Platform.DoorDash, "%064x".format(it))
        }, 0)
        lines += "## A frame of ${ShadowSet.MAX_CANDIDATES_PER_PLATFORM} worst-case candidates, " +
            "caps ${ShadowFrameRunner.PER_CANDIDATE_FUEL} / ${ShadowFrameRunner.PER_FRAME_FUEL}"
        lines += ""
        lines += "| tree | frame fuel | median µs | max µs |"
        lines += "|---|---:|---:|---:|"
        for ((treeName, tree) in listOf("corpus max" to corpusMax.second, "synthetic 4 000 / depth 60" to maxTree)) {
            val runner = ShadowFrameRunner()
            repeat(5) { runner.evaluate(set, Platform.DoorDash, tree, clock) }
            val frames = List(9) {
                val start = System.nanoTime()
                val fuelUsed = runner.evaluate(set, Platform.DoorDash, tree, clock).sumOf { it.fuelUsed }
                fuelUsed to System.nanoTime() - start
            }
            lines += "| $treeName | ${frames.maxOf { it.first }} | ${micros(percentile(frames.map { it.second }, .5))} | ${micros(frames.maxOf { it.second })} |"
        }
        report("shadow", lines)
    }
}
