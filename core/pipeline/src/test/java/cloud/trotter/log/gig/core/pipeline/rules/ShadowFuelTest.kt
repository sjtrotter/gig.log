package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1630: metering is chosen when a rule compiles. A live rule's closures are the ones it had before metering existed,
 * so the live path performs no metering even on a thread with a meter installed; the same rule compiled as a
 * candidate charges for every predicate, expression, transform, validator, navigation and redact step.
 */
class ShadowFuelTest {

    /** Exercises every metered site: binds, tree and node predicates, rejects, regex, every parse form, validate, redact. */
    private val everything = """
        [{ "id": "doordash.screen.everything", "priority": 10,
           "bind": { "anchor": { "find": { "hasText": "Total" } } },
           "require": { "all": [ { "allTextContains": "total" }, { "exists": { "hasAnyText": "Total" } } ] },
           "reject": [ { "exists": { "hasTextMatchesRegex": "^zz$" } } ],
           "parse": { "as": "post_task", "fields": {
             "totalPay": { "find": { "hasText": "Total" }, "navigate": "nextSiblingMatchingRegex(^\\$[0-9.]+$)",
                           "transform": "parseCurrency" },
             "label": { "textAfterLabel": "Total" },
             "seen": { "presence": { "allTextContains": "total" } },
             "lines": { "findAll": { "hasTextStartsWith": "Deliver" } } } },
           "validate": [ { "assert": "fieldNotNull", "field": "totalPay" } ],
           "redact": [ { "find": { "hasTextStartsWith": "Deliver to " }, "keepPrefix": [ "Deliver to " ] } ] }]
    """.trimIndent()

    private val tree = UiNode(
        children = listOf(UiNode(text = "Total"), UiNode(text = "$9.50"), UiNode(text = "Deliver to Riley S.")),
    ).restoreParents()

    private fun compile(metered: Boolean): CompiledRule<UiNode> {
        val compile = {
            RuleCompiler.compileRules<UiNode>(Json.parseToJsonElement(everything).jsonArray, RuleContext.SCREEN, "doordash.driver").single()
        }
        return if (metered) ShadowFuel.compileMetered(compile) else compile()
    }

    private fun charged(rule: CompiledRule<UiNode>): FuelMeter {
        val meter = FuelMeter(Long.MAX_VALUE)
        ShadowFuel.metering(meter) {
            val result = checkNotNull(Ruleset(listOf(rule)).matchFirst(tree))
            assertEquals(9.5, result.fields["totalPay"])
            rule.redact.apply(tree)
        }
        return meter
    }

    @Test
    fun `a live-compiled rule charges nothing with a meter installed`() {
        val meter = charged(compile(metered = false))
        assertEquals(0, meter.charges)
        assertEquals(0L, meter.used)
    }

    @Test
    fun `the same rule compiled as a candidate charges for its work`() {
        val meter = charged(compile(metered = true))
        assertTrue("charges ${meter.charges}", meter.charges > 10)
        assertTrue("used ${meter.used}", meter.used > 50)
    }

    @Test
    fun `outside a metered compile every wrapper returns the closure it was given`() {
        val pred: (UiNode) -> Boolean = { true }
        val expr: (UiNode, Bindings) -> Any? = { _, _ -> null }
        val validate: (Map<String, Any?>) -> ValidateOutcome = { ValidateOutcome.Pass }
        val nav: (UiNode) -> UiNode? = { it }
        assertSame(pred, ShadowFuel.predicate(pred) { 1L })
        assertSame(pred, ShadowFuel.nodePredicate("hasText", JsonPrimitive("x"), pred))
        assertSame(pred, ShadowFuel.treePredicate("allTextContains", JsonPrimitive("x"), pred))
        assertSame(expr, ShadowFuel.expression(expr) { _, _ -> 1L })
        assertSame(validate, ShadowFuel.validator(validate))
        assertSame(nav, ShadowFuel.navigation(nav) { 1L })
    }

    @Test
    fun `a refused charge leaves the meter at or under its limit and exhausted for good`() {
        val meter = FuelMeter(10)
        meter.charge(7)
        val refused = runCatching { meter.charge(4) }.exceptionOrNull()
        assertTrue(refused is FuelExhausted)
        assertEquals(7L, meter.used)
        assertTrue(runCatching { meter.charge(1) }.exceptionOrNull() is FuelExhausted)
        assertTrue(meter.exhausted)
    }
}
