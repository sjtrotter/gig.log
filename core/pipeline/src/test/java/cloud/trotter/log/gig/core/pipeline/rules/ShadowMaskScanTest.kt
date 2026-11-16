package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1630: the shadow candidate's mask scan. Only complete mask tokens count as masked, a lead-in whose whole tail is
 * masked is clean, text around a mask is still read, a hit outweighs an unfinished check, and running out of fuel is
 * incomplete. Names here are invented.
 */
class ShadowMaskScanTest {

    private fun value(text: String, idMarked: Boolean = false) = ShadowMaskScan.scanValue(text, idMarked)

    private val overBound = "Tap the button below to continue " + "and review the details ".repeat(12)

    @Test
    fun `the design vectors`() {
        assertEquals(MaskScan.CLEAN, value("Deliver to [redacted:ab12]"))
        assertEquals(MaskScan.HIT, value("Deliver to [redacted:ab12] Riley S."))
        assertEquals(MaskScan.INCOMPLETE, value(overBound))
        // A partial marker is not a mask: the value is read as text, and its lead-in tail is raw.
        assertEquals(MaskScan.HIT, value("Deliver to [redacted Riley S."))
    }

    @Test
    fun `a lead-in whose whole tail is masked is clean`() {
        assertEquals(MaskScan.CLEAN, value("Pickup for [redacted]"))
        assertEquals(MaskScan.CLEAN, value("Deliver to [redacted:ab12]  "))
    }

    @Test
    fun `text after a mask is still checked`() {
        assertEquals(MaskScan.HIT, value("[redacted:ab12] 555-201-4477"))
        assertEquals(MaskScan.HIT, value("Order [redacted:ab12] Deliver to Jordan"))
        assertEquals(MaskScan.CLEAN, value("[redacted:ab12] 2 items"))
    }

    @Test
    fun `a hit outweighs an incomplete check`() {
        assertEquals(MaskScan.HIT, value("$overBound[redacted:ab12] Riley S."))
    }

    @Test
    fun `an over-bound value is never sliced`() {
        // A shape at the very start of a long value is not found by reading a prefix of it.
        assertEquals(MaskScan.INCOMPLETE, value("555-201-4477 $overBound"))
        assertEquals(MaskScan.INCOMPLETE, value("a".repeat(ShadowMaskScan.PII_SHAPE_MAX_CHARS + 1)))
        assertEquals(MaskScan.CLEAN, value("a".repeat(ShadowMaskScan.PII_SHAPE_MAX_CHARS)))
    }

    @Test
    fun `a customer-PII id is raw wherever text outside its masks is not blank`() {
        assertEquals(MaskScan.HIT, value("Riley", idMarked = true))
        assertEquals(MaskScan.CLEAN, value("[redacted:ab12]", idMarked = true))
        assertEquals(MaskScan.CLEAN, value(" [redacted:ab12] [redacted] ", idMarked = true))
        // A trailing mask does not cover the raw text before it.
        assertEquals(MaskScan.HIT, value("Riley [redacted:ab12]", idMarked = true))
        assertEquals(MaskScan.HIT, value("Apt/Suite: [redacted:ab12]", idMarked = true))
        assertEquals(MaskScan.HIT, value("[redacted:ab12] 4B", idMarked = true))
        assertEquals(MaskScan.HIT, value("Riley [redacted", idMarked = true))
    }

    @Test
    fun `segments split at complete masks only`() {
        assertEquals(listOf("Deliver to ", ""), ShadowMaskScan.segmentsOf("Deliver to [redacted:ab12]"))
        assertEquals(listOf("a ", " b ", " c"), ShadowMaskScan.segmentsOf("a [redacted] b [redacted:00ff] c"))
        assertEquals(listOf("x [redacted:XYZ1] y"), ShadowMaskScan.segmentsOf("x [redacted:XYZ1] y"))
        assertEquals(listOf("[redacted"), ShadowMaskScan.segmentsOf("[redacted"))
    }

    // ── Whole frames, through the candidate's own redact ─────────────────────────────────────────

    private fun candidate(redact: String): ShadowCandidate {
        val json = """
            [{ "id": "doordash.screen.dropoff", "priority": 10,
               "require": { "exists": { "hasTextStartsWith": "Deliver to " } } $redact }]
        """.trimIndent()
        val rule = ShadowFuel.compileMetered {
            RuleCompiler.compileRules<UiNode>(Json.parseToJsonElement(json).jsonArray, RuleContext.SCREEN, "doordash.driver").single()
        }
        return ShadowCandidate(rule, Platform.DoorDash, "0".repeat(64))
    }

    private val masking = """, "redact": [ { "find": { "hasTextStartsWith": "Deliver to " }, "keepPrefix": [ "Deliver to " ] } ]"""

    private val frame = UiNode(children = listOf(UiNode(text = "Deliver to Riley S."), UiNode(text = "2 items"))).restoreParents()

    private fun scan(candidate: ShadowCandidate, fuel: Long = 1_000_000L) =
        ShadowCandidateEngine.evaluate(candidate, frame, FuelMeter(fuel))

    @Test
    fun `a redact that masks the customer line is clean, and none at all is a hit`() {
        assertEquals(CandidateOutcome.Matched(null, null, MaskScan.CLEAN), scan(candidate(masking)))
        assertEquals(CandidateOutcome.Matched(null, null, MaskScan.HIT), scan(candidate("")))
    }

    @Test
    fun `fuel that runs out mid-scan leaves the scan incomplete`() {
        val candidate = candidate(masking)
        val full = FuelMeter(1_000_000L).also { ShadowCandidateEngine.evaluate(candidate, frame, it) }.used
        val matchOnly = FuelMeter(1_000_000L).also {
            ShadowFuel.metering(it) { RuleEvaluation.firstSurvivingBranch(candidate.rule, frame, null, { e -> throw e }, {}) }
        }.used
        // Enough for the match, not for the scan.
        assertEquals(CandidateOutcome.Matched(null, null, MaskScan.INCOMPLETE), scan(candidate, fuel = (matchOnly + full) / 2))
        assertEquals(MaskScan.INCOMPLETE, ShadowFuel.metering(FuelMeter(3)) { ShadowMaskScan.scan(frame, candidate.rule.redact) })
    }
}
