package cloud.trotter.log.gig.core.pipeline

import cloud.trotter.log.gig.domain.pipeline.ParseShortfall
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1573 A1: [ParseRotTracker.rotRuleIds] is the tracker's own definition of a frame's rot rules, the one a matched
 * skeleton is built for: a recognized frame whose winning branch rotted, and an UNKNOWN frame carrying a skipped
 * branch's rot, name the rule; a healthy winner and a shape with no required fields name none.
 */
class ParseRotRuleIdsTest {

    private val r = "doordash.screen.delivery_summary_expanded"
    private val offer = "doordash.screen.offer_card"

    private fun receiptRot(skipped: Boolean = false) =
        ParseShortfall(ruleId = r, shape = "post_task", nullRequiredFields = listOf("totalPay"), branchSkipped = skipped)

    @Test fun `a winner whose own branch rotted is its rot rule`() {
        assertEquals(setOf(r), ParseRotTracker.rotRuleIds(r, listOf(receiptRot())))
    }

    @Test fun `a healthy winner's skipped-branch rot names nothing`() {
        assertEquals(emptySet<String>(), ParseRotTracker.rotRuleIds(r, listOf(receiptRot(skipped = true))))
    }

    @Test fun `an UNKNOWN frame's skipped-branch rot names its rule`() {
        val offerRot = ParseShortfall(ruleId = offer, shape = "offer", nullRequiredFields = listOf("payAmount"), branchSkipped = true)
        assertEquals(setOf(offer), ParseRotTracker.rotRuleIds(null, listOf(offerRot)))
        assertEquals(setOf(r), ParseRotTracker.rotRuleIds(null, listOf(receiptRot(skipped = true))))
    }

    @Test fun `a shape without required fields never names its rule`() {
        val carousel = ParseShortfall(ruleId = "doordash.screen.waiting_for_offer", shape = "idle", allNullFieldCount = 2)
        assertEquals(emptySet<String>(), ParseRotTracker.rotRuleIds(null, listOf(carousel)))
        assertEquals(emptySet<String>(), ParseRotTracker.rotRuleIds("doordash.screen.waiting_for_offer", listOf(carousel)))
    }

    @Test fun `a shortfall with no trigger is not rot`() {
        val bindOnly = ParseShortfall(ruleId = r, shape = "post_task", unresolvedOptionalBindings = listOf("expand"))
        assertEquals(emptySet<String>(), ParseRotTracker.rotRuleIds(null, listOf(bindOnly)))
    }
}
