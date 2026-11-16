package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.core.pipeline.rules.BoundedRegex.Companion.evaluationFailed
import cloud.trotter.log.gig.core.pipeline.rules.CandidateOutcome.Matched
import cloud.trotter.log.gig.core.pipeline.rules.CandidateOutcome.NoMatch
import cloud.trotter.log.gig.core.pipeline.rules.CandidateOutcome.Reason
import cloud.trotter.log.gig.core.pipeline.rules.CandidateOutcome.Unevaluated
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.pipeline.ParseShortfall
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber
import java.time.ZoneOffset

/**
 * #1630: one shadow candidate against one frame. The outcome is explicit (`NoMatch`, `Matched`, `Unevaluated`), the
 * diagnostic branch is the first predicate-matched branch that does not Skip, and running out of fuel is never a
 * non-match.
 */
class ShadowCandidateEngineTest {

    private companion object {
        const val PLENTY = 10_000_000L
        val CLOCK = TransformRegistry.TransformClock(0L, ZoneOffset.UTC)
    }

    private fun compile(json: String, metered: Boolean = true): CompiledRule<UiNode> {
        val array = Json.parseToJsonElement("[$json]").jsonArray
        val compile = { RuleCompiler.compileRules<UiNode>(array, RuleContext.SCREEN, "doordash.driver").single() }
        return if (metered) ShadowFuel.compileMetered(compile) else compile()
    }

    private fun candidate(json: String) = ShadowCandidate(compile(json), Platform.DoorDash, "%064x".format(json.hashCode()))

    private fun outcome(json: String, tree: UiNode, fuel: Long = PLENTY) =
        ShadowCandidateEngine.evaluate(candidate(json), tree, FuelMeter(fuel))

    private fun screen(vararg nodes: UiNode) = UiNode(children = nodes.toList()).restoreParents()

    private fun text(value: String, id: String? = null) = UiNode(text = value, viewIdResourceName = id)

    private val postTask = """
        "parse": { "as": "post_task", "fields": { "totalPay": { "find": { "hasIdSuffix": "total_pay" } } } }
    """.trimIndent()

    private fun rule(body: String) = """{ "id": "doordash.screen.candidate", "priority": 10, $body }"""

    // ── Outcomes ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `no predicate-matched branch is NoMatch`() {
        assertEquals(NoMatch, outcome(rule(""""require": { "exists": { "hasText": "Dash Now" } }"""), screen(text("Earnings"))))
    }

    @Test
    fun `a shape with no required fields reads requiredOk null`() {
        val json = rule(""""require": { "exists": { "hasText": "Ratings" } }, "parse": { "as": "ratings", "fields": {} }""")
        assertEquals(Matched("ratings", null, MaskScan.CLEAN), outcome(json, screen(text("Ratings"))))
    }

    @Test
    fun `requiredOk is true when every required field read and false when one did not`() {
        val json = rule(""""require": { "exists": { "hasText": "Delivery complete" } }, $postTask""")
        assertEquals(
            Matched("post_task", true, MaskScan.CLEAN),
            outcome(json, screen(text("Delivery complete"), text("$9.50", id = "com.x:id/total_pay"))),
        )
        assertEquals(Matched("post_task", false, MaskScan.CLEAN), outcome(json, screen(text("Delivery complete"))))
    }

    @Test
    fun `the first predicate-matched branch that does not Skip is the diagnostic branch`() {
        val json = rule(
            """
            "branches": [
              { "require": { "exists": { "hasText": "Not here" } }, "parse": { "as": "ratings", "fields": {} } },
              { "require": { "exists": { "hasText": "Done" } }, $postTask,
                "validate": [ { "assert": "fieldNotNull", "field": "totalPay" } ] },
              { "require": { "exists": { "hasText": "Done" } }, "parse": { "as": "ratings", "fields": {} } },
              { "require": { "exists": { "hasText": "Done" } }, $postTask }
            ]
            """.trimIndent(),
        )
        // Branch 2 matches and Skips (no totalPay); branch 3 is the first that does not Skip, and evaluation stops there.
        assertEquals(Matched("ratings", null, MaskScan.CLEAN), outcome(json, screen(text("Done"))))
    }

    @Test
    fun `when every predicate-matched branch Skips the first of them is the diagnostic branch`() {
        val json = rule(
            """
            "branches": [
              { "require": { "exists": { "hasText": "Done" } }, $postTask,
                "validate": [ { "assert": "fieldNotNull", "field": "totalPay" } ] },
              { "require": { "exists": { "hasText": "Done" } }, "parse": { "as": "ratings", "fields": {} },
                "validate": [ { "assert": "fieldNotNull", "field": "missing" } ] }
            ]
            """.trimIndent(),
        )
        assertEquals(Matched("post_task", false, MaskScan.CLEAN), outcome(json, screen(text("Done"))))
    }

    @Test
    fun `a healthy diagnostic branch after a skipped one does not inherit its shortfall`() {
        val json = rule(
            """
            "branches": [
              { "require": { "exists": { "hasText": "Done" } }, $postTask,
                "validate": [ { "assert": "fieldNotNull", "field": "tip" } ] },
              { "require": { "exists": { "hasText": "Done" } },
                "parse": { "as": "post_task", "fields": { "totalPay": { "find": { "hasIdSuffix": "final_value" } } } } }
            ]
            """.trimIndent(),
        )
        // Branch 1 matched with totalPay null and Skipped; branch 2 read its own totalPay.
        assertEquals(
            Matched("post_task", true, MaskScan.CLEAN),
            outcome(json, screen(text("Done"), text("$7.00", id = "com.x:id/final_value"))),
        )
    }

    @Test
    fun `a parse exception is Unevaluated FAILED on the candidate path and an empty parse live`() {
        val compiled = compile(rule(""""require": { "exists": { "hasText": "Done" } }, $postTask"""))
        val throwing = compiled.copy(branches = compiled.branches.map { it.copy(parser = { _, _ -> error("boom") }) })
        val tree = screen(text("Done"))

        assertEquals(Unevaluated(Reason.FAILED), ShadowCandidateEngine.evaluateRule(throwing, tree, FuelMeter(PLENTY)))
        // Live keeps today's answer: the frame still matches, with nothing parsed but the intent.
        assertEquals(mapOf<String, Any?>("intent" to "candidate"), checkNotNull(Ruleset(listOf(throwing)).matchFirst(tree)).fields)
    }

    @Test
    fun `a predicate regex that cannot be evaluated is Unevaluated FAILED`() {
        val compiled = compile(rule(""""require": { "exists": { "hasText": "Done" } }"""))
        val regex = RegexSafety.compileRegex("x")
        val failing = compiled.copy(branches = compiled.branches.map { it.copy(predicate = { throw regex.evaluationFailed() }) })
        assertEquals(Unevaluated(Reason.FAILED), ShadowCandidateEngine.evaluateRule(failing, screen(text("Done")), FuelMeter(PLENTY)))
    }

    @Test
    fun `a regex failure a parse field swallows is still Unevaluated FAILED`() {
        val compiled = compile(rule(""""require": { "exists": { "hasText": "Done" } }, $postTask"""))
        val regex = RegexSafety.compileRegex("x")
        // The fail-null shape of ParseExpressionCompiler: the field reads null and the exception never escapes.
        val swallowing = compiled.copy(
            branches = compiled.branches.map {
                it.copy(parser = { _, _ -> mapOf("totalPay" to try { throw regex.evaluationFailed() } catch (_: RegexEvaluationFailed) { null }) })
            },
        )
        assertEquals(Unevaluated(Reason.FAILED), ShadowCandidateEngine.evaluateRule(swallowing, screen(text("Done")), FuelMeter(PLENTY)))
    }

    @Test
    fun `a redact selector whose regex cannot be evaluated is Unevaluated FAILED`() {
        val compiled = compile(rule(""""require": { "exists": { "hasText": "Done" } }, "redact": [ { "find": { "hasText": "Done" } } ]"""))
        val regex = RegexSafety.compileRegex("x")
        // maskNode catches the failure and writes a plain mask; the scan would read that mask as the rule's own.
        val failing = compiled.copy(redact = CompiledRedact(listOf(CompiledRedactEntry(find = { throw regex.evaluationFailed() }))))
        assertEquals(Unevaluated(Reason.FAILED), ShadowCandidateEngine.evaluateRule(failing, screen(text("Done")), FuelMeter(PLENTY)))
    }

    @Test
    fun `a candidate evaluation materializes no effect or target and calls no live sink`() {
        val json = rule(
            """
            "require": { "exists": { "hasText": "New Offer" } },
            "enables": ["accept_offer"],
            "bind": { "acceptButton": { "find": { "hasText": "Accept" } } },
            "effects": [ { "log": { "type": "OFFER_RECEIVED" } } ]
            """.trimIndent(),
        )
        val tree = screen(text("New Offer"), text("Accept"))

        // Live, the same rule resolves its effect and reports the refused (owner-less) action bind to its sink.
        val sink = mutableListOf<ParseShortfall>()
        val live = checkNotNull(Ruleset(listOf(compile(json, metered = false))).matchFirst(tree) { sink += it })
        assertEquals(1, live.effects.size)
        assertEquals(listOf("acceptButton"), sink.single().refusedBindings)

        val logged = mutableListOf<String>()
        val recorder = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logged += message
            }
        }
        Timber.plant(recorder)
        try {
            assertEquals(Matched(null, null, MaskScan.CLEAN), ShadowCandidateEngine.evaluate(candidate(json), tree, FuelMeter(PLENTY)))
        } finally {
            Timber.uproot(recorder)
        }
        assertTrue("a candidate evaluation logs nothing: $logged", logged.isEmpty())
    }

    // ── Budget ───────────────────────────────────────────────────────────────────────────────────

    /** A legal candidate that reads every node's whole subtree text through a regex and never matches. */
    private val worstCase = rule(""""require": { "exists": { "hasAnyTextMatchesRegex": "zz[0-9]{3}qq" } }""")

    private fun wideTree(nodes: Int) = screen(*Array(nodes) { text("Line $it of the receipt") })

    @Test
    fun `a candidate that runs out of fuel is Unevaluated BUDGET, never NoMatch`() {
        val tree = wideTree(2_000)
        assertEquals(NoMatch, outcome(worstCase, tree))
        assertEquals(Unevaluated(Reason.BUDGET), outcome(worstCase, tree, fuel = 1_000))
    }

    @Test
    fun `a long scan stops part way and never charges past its cap`() {
        val nodes = 2_000
        val perNode = rule(""""require": { "exists": { "hasTextMatchesRegex": "zz[0-9]{3}qq" } }""")
        val meter = FuelMeter(1_000)
        assertEquals(Unevaluated(Reason.BUDGET), ShadowCandidateEngine.evaluate(candidate(perNode), wideTree(nodes), meter))
        assertTrue("charged ${meter.used} past its cap", meter.used <= meter.limit)
        // One charge per node predicate dispatch: the traversal ran, then stopped long before the last node.
        assertTrue("visited ${meter.charges} of $nodes nodes", meter.charges in 2 until nodes / 10)
    }

    private val doubling = List(30) { """{ "replace": { "pattern": "a", "replacement": "aa" } }""" }.joinToString(",")

    @Test
    fun `a transform chain that grows its value runs out of fuel before it allocates`() {
        // Thirty doublings of "a" would be a billion characters; a step admits its output before it allocates it.
        val transforms = listOf("[ $doubling ]", """{ "regex": { "pattern": "(a)", "group": 1, "then": [ $doubling ] } }""")
        for (transform in transforms) {
            val json = rule("""
                "require": { "exists": { "hasText": "a" } },
                "parse": { "as": "post_task", "fields": { "totalPay": { "find": { "hasText": "a" }, "transform": $transform } } }
            """.trimIndent())
            val meter = FuelMeter(PLENTY)
            assertEquals(transform, Unevaluated(Reason.BUDGET), ShadowCandidateEngine.evaluate(candidate(json), screen(text("a")), meter))
            assertTrue("charged ${meter.used} past its cap", meter.used <= meter.limit)
        }
    }

    /** Whether [this] node's lazy subtree text ([UiNode.allText] or [UiNode.allTextLowerJoined]) has been built. */
    private fun UiNode.textCacheBuilt(): Boolean = listOf("allText\$delegate", "allTextLowerJoined\$delegate").any { name ->
        (UiNode::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this) as Lazy<*>).isInitialized()
    }

    @Test
    fun `a nearly spent candidate stops part way through a cold tree before building its text cache`() {
        val nodes = 5_000
        val reads = listOf(
            """"require": { "allTextContains": "absent phrase" }""",
            """"require": { "exists": { "hasAnyText": "absent phrase" } }""",
            """"require": { "exists": { "isLeaf": false } }, "parse": { "as": "ratings", "fields": { "all": { "find": { "isLeaf": false }, "read": "allText" } } }""",
        )
        for (read in reads) {
            val tree = wideTree(nodes)
            val meter = FuelMeter(1_000)
            assertEquals(read, Unevaluated(Reason.BUDGET), ShadowCandidateEngine.evaluate(candidate(rule(read)), tree, meter))
            assertTrue("charged ${meter.used} past its cap", meter.used <= meter.limit)
            assertTrue("walked ${meter.charges} of $nodes nodes", meter.charges < nodes / 10)
            assertTrue("$read built the cold tree's text cache", !tree.textCacheBuilt())
        }
    }

    @Test(timeout = 5_000)
    fun `ancestor and findDescendant walks are charged and stop within budget`() {
        // "Done" sits 101 levels down. ancestor(2147483647) pays a unit per step and stops at the root instead of
        // counting out two billion steps, in a navigation and in an each scope alike.
        var chain = text("Done")
        repeat(100) { chain = UiNode(text = "row", children = listOf(chain)) }
        val tree = screen(chain)
        fun used(field: String): Long {
            val json = rule(""""require": { "exists": { "hasText": "Done" } }, "parse": { "as": "ratings", "fields": { $field } }""")
            val meter = FuelMeter(PLENTY)
            assertTrue(field, ShadowCandidateEngine.evaluate(candidate(json), tree, meter) is Matched)
            return meter.used
        }
        fun navigate(up: String) = """"f": { "find": { "hasText": "Done" }, "navigate": "ancestor($up)" }"""
        fun scope(up: String) =
            """"f": { "each": { "hasText": "Done" }, "scope": "ancestor($up)", "extract": { "t": { "find": { "hasText": "Done" } } } }"""
        for (field in listOf(::navigate, ::scope)) {
            val walk = used(field("2147483647")) - used(field("0"))
            assertTrue("the walk to the root charged $walk", walk in 50L..1_000L)
        }
        // A descendant search over a wide tree pays per node and stops part way.
        val nodes = 5_000
        val deepSearch = rule(
            """"require": { "exists": { "isLeaf": false } }, "parse": { "as": "ratings", "fields": { "x": { "find": { "isLeaf": false }, "navigate": "findDescendant(absent_id)" } } }""",
        )
        val meter = FuelMeter(2_000)
        assertEquals(Unevaluated(Reason.BUDGET), ShadowCandidateEngine.evaluate(candidate(deepSearch), wideTree(nodes), meter))
        assertTrue("charged ${meter.used} past its cap", meter.used <= meter.limit)
        assertTrue("visited ${meter.charges} of $nodes nodes", meter.charges < nodes / 10)
    }

    @Test
    fun `the frame cap starves later candidates and the rotation gives the starved one the next frame's first slot`() {
        // A deep chain makes every node's subtree text long, so the heavy candidate costs more than the light one's
        // whole evaluation, its mask scan included.
        var chain = text("end")
        repeat(50) { chain = UiNode(text = "row", children = listOf(chain)) }
        val tree = screen(text("Dash Now"), chain)
        // A near-maximal regex program (999 instructions) prices every character it reads at 63 units.
        val heavy = candidate(rule(""""require": { "exists": { "hasAnyTextMatchesRegex": "(?:[a-z0-9]{1,41}[ .,-]){1,12}qz" } }"""))
        val light = candidate(rule(""""require": { "exists": { "hasText": "Dash Now" } }"""))
        val heavyCost = FuelMeter(PLENTY).also { assertEquals(NoMatch, ShadowCandidateEngine.evaluate(heavy, tree, it)) }.used
        val lightCost = FuelMeter(PLENTY).also { ShadowCandidateEngine.evaluate(light, tree, it) }.used
        assertTrue("precondition: heavy $heavyCost > light $lightCost", heavyCost > lightCost)

        val set = ShadowSet("gen", listOf(heavy, light), 0)
        val runner = ShadowFrameRunner(perFrameFuel = heavyCost, perCandidateFuel = heavyCost)
        val frames = List(3) { runner.evaluate(set, Platform.DoorDash, tree, CLOCK) }

        // Frame 1 opens with heavy, which spends the frame: light never starts.
        assertEquals(listOf(NoMatch, Unevaluated(Reason.BUDGET)), frames[0].map { it.outcome })
        assertEquals(0L, frames[0][1].fuelUsed)
        // Frame 2 opens with light; heavy gets what is left and runs out.
        assertEquals(listOf(Unevaluated(Reason.BUDGET), Matched(null, null, MaskScan.CLEAN)), frames[1].map { it.outcome })
        // Frame 3 opens with heavy again.
        assertEquals(listOf(NoMatch, Unevaluated(Reason.BUDGET)), frames[2].map { it.outcome })
        frames.forEach { frame -> assertTrue(frame.sumOf { it.fuelUsed } <= heavyCost) }
    }

    @Test
    fun `candidates of another platform are not evaluated`() {
        val set = ShadowSet("gen", listOf(candidate(rule(""""require": { "exists": { "hasText": "A" } }"""))), 0)
        assertTrue(ShadowFrameRunner().evaluate(set, Platform.Uber, screen(text("A")), CLOCK).isEmpty())
    }

    @Test
    fun `the frame's transform clock scope does not leak out of the evaluation`() {
        val set = ShadowSet("gen", listOf(candidate(rule(""""require": { "exists": { "hasText": "A" } }"""))), 0)
        val outer = TransformRegistry.TransformClock(123_000L, ZoneOffset.UTC)
        TransformRegistry.withClock(outer) {
            ShadowFrameRunner().evaluate(set, Platform.DoorDash, screen(text("A")), CLOCK)
            // The enclosing classification's clock is back in force after the shadow frame.
            assertEquals(
                TransformRegistry.withClock(outer) { TransformRegistry.apply("parseTime", "12:02 AM") },
                TransformRegistry.apply("parseTime", "12:02 AM"),
            )
        }
    }
}
