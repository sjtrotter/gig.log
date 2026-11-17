package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.action.ActionTrigger
import cloud.trotter.log.gig.domain.action.RuleAction
import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.model.accessibility.BoundingBox
import cloud.trotter.log.gig.domain.pipeline.NodeRef
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.pipeline.ObservationPayload
import cloud.trotter.log.gig.domain.pipeline.TimeoutType
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1672 — the app's automatic receipt expand fires once per receipt episode. Once a frame of the episode has shown
 * the receipt expanded, no further expand is scheduled and a pending one performs nothing, so a dasher's dismissal
 * (which DoorDash draws as a brief collapsed frame) is never fought.
 */
class ReceiptExpandOnceTest {

    private val chevron = NodeRef(
        viewIdSuffix = "expand", text = null, classNameHint = "android.widget.ImageView",
        boundsInScreen = BoundingBox(0, 0, 40, 40), pathFingerprint = "fp/expand",
    )

    private fun collapsed(at: Long) = RS.postTask(RS.collapsed(16.70), at)
        .copy(ruleId = "doordash.screen.delivery_summary_collapsed", targets = mapOf("expandButton" to chevron))

    private fun expanded(at: Long) = RS.postTask(RS.expanded(RS.pay(base = 9.70, tip = 7.00)), at)
        .copy(ruleId = "doordash.screen.delivery_summary_expanded")

    /** A rule with no state block (DoorDash's rate-the-delivery prompt): no flow, no parse. */
    private fun flowLess(at: Long) = Observation.Screen(
        timestamp = at, captureId = "cap-$at", ruleId = "doordash.screen.task_feedback",
        metadata = ReplayMetadata.EMPTY, flow = null, modeHint = null, parsed = ParsedFields.None,
    )

    private fun waitingForOffer(at: Long) = RS.screen(Flow.Idle, at)

    private fun expandDeferred() = ObservationPayload.DeferredAction(
        action = RuleAction.EXPAND_EARNINGS.wire, platform = Platform.DoorDash.wire,
        ruleId = "doordash.screen.delivery_summary_collapsed", target = chevron,
    )

    private fun fire(at: Long, payload: ObservationPayload.DeferredAction = expandDeferred()) = Observation.Timeout(
        timestamp = at, type = TimeoutType.SETTLE_UI, targetPlatform = Platform.DoorDash, payload = payload,
    )

    private fun ReceiptFold.expandSchedules(): List<Long> = effects
        .filter { (_, e) ->
            e is AppEffect.ScheduleTimeout && e.type == TimeoutType.SETTLE_UI &&
                (e.payload as? ObservationPayload.DeferredAction)?.action == RuleAction.EXPAND_EARNINGS.wire
        }
        .map { it.first }

    private fun ReceiptFold.expandTaps(): List<Long> = effects
        .filter { (_, e) -> e is AppEffect.PerformRuleAction && e.action == RuleAction.EXPAND_EARNINGS }
        .map { it.first }

    @Test
    fun `the fielded dismissal sequence taps expand exactly once`() {
        val f = ReceiptFold(RS.live())
            .step(collapsed(0L))
            .step(fire(500L))
            .step(expanded(1_200L))
            // The dasher dismisses: a brief collapsed redraw, then the flow-less feedback prompt.
            .step(collapsed(9_200L))
            .step(flowLess(9_300L))
            .step(fire(9_700L))
            .step(collapsed(15_100L))
            .step(fire(15_600L))
            .step(waitingForOffer(18_200L))
        assertEquals("one automatic expand for the receipt", listOf(500L), f.expandTaps())
        assertTrue(
            "no expand is scheduled once the receipt has shown expanded",
            f.expandSchedules().none { it >= 1_200L },
        )
        assertNull("the episode ended on the idle frame", f.region.receiptExpandSeenAt)
    }

    @Test
    fun `a timer armed before the expanded frame performs nothing after it`() {
        val f = ReceiptFold(RS.live())
            .step(collapsed(0L))
            .step(expanded(300L))
            .step(fire(500L))
        assertEquals(emptyList<Long>(), f.expandTaps())
        assertEquals(300L, f.region.receiptExpandSeenAt)
    }

    @Test
    fun `a failed first expand still retries while no expanded frame has been seen`() {
        val f = ReceiptFold(RS.live())
            .step(collapsed(0L))
            .step(fire(500L))
            .step(collapsed(1_000L))
            .step(fire(1_500L))
        assertEquals(listOf(500L, 1_500L), f.expandTaps())
        assertEquals(listOf(0L, 1_000L), f.expandSchedules())
    }

    @Test
    fun `the next receipt after the episode ends expands again`() {
        val f = ReceiptFold(RS.live())
            .step(collapsed(0L))
            .step(fire(500L))
            .step(expanded(1_200L))
            .step(RS.screen(Flow.TaskDropoffNavigation, 5_000L))
        assertNull(f.region.receiptExpandSeenAt)
        f.step(collapsed(60_000L)).step(fire(60_500L))
        assertEquals(listOf(500L, 60_500L), f.expandTaps())
        assertEquals(listOf(0L, 60_000L), f.expandSchedules())
    }

    @Test
    fun `an offer overlay over the expanded receipt keeps the episode, so the return does not re-tap`() {
        val f = ReceiptFold(RS.live())
            .step(collapsed(0L))
            .step(fire(500L))
            .step(expanded(1_200L))
            .step(RS.offerScreen(3_000L, "hash-B"))
            .step(collapsed(6_000L))
            .step(fire(6_500L))
        assertEquals(listOf(500L), f.expandTaps())
        assertEquals(listOf(0L), f.expandSchedules())
        assertEquals(1_200L, f.region.receiptExpandSeenAt)
    }

    @Test
    fun `a confirm-decline tap fires whatever the receipt expand state`() {
        val seen = RS.live().copy(receiptExpandSeenAt = 1_200L)
        val state = RS.appState(seen, Flow.OfferPresented)
        val fire = fire(
            2_000L,
            ObservationPayload.DeferredAction(
                action = RuleAction.CONFIRM_DECLINE.wire, platform = Platform.DoorDash.wire,
                ruleId = "doordash.screen.offer_popup_confirm_decline", target = chevron,
            ),
        )
        val taps = RS.effectMap.diff(state, state, fire).filterIsInstance<AppEffect.PerformRuleAction>()
        assertEquals(RuleAction.CONFIRM_DECLINE, taps.single().action)
        assertEquals(ActionTrigger.AUTOMATION, taps.single().trigger)
    }

    // ---- the reducer ----------------------------------------------------------------------------------------------

    @Test
    fun `the reducer keeps the first expanded timestamp of the episode`() {
        val once = receiptExpandEpisode(RS.live(), Flow.TaskDropoffArrived, expanded(1_000L))
        assertEquals(1_000L, once.receiptExpandSeenAt)
        assertEquals(1_000L, receiptExpandEpisode(once, Flow.PostTask, expanded(2_000L)).receiptExpandSeenAt)
    }

    @Test
    fun `the reducer leaves a collapsed receipt frame and a flow-less observation alone`() {
        val fresh = RS.live()
        assertSame(fresh, receiptExpandEpisode(fresh, Flow.TaskDropoffArrived, collapsed(1_000L)))
        val seen = fresh.copy(receiptExpandSeenAt = 1_000L)
        assertSame(seen, receiptExpandEpisode(seen, Flow.PostTask, collapsed(2_000L)))
        assertSame(seen, receiptExpandEpisode(seen, Flow.PostTask, flowLess(2_100L)))
        assertSame(seen, receiptExpandEpisode(seen, Flow.PostTask, fire(2_200L)))
    }

    @Test
    fun `the reducer clears on a flow-bearing frame that does not continue the episode`() {
        val seen = RS.live().copy(receiptExpandSeenAt = 1_000L)
        assertNull(receiptExpandEpisode(seen, Flow.PostTask, waitingForOffer(2_000L)).receiptExpandSeenAt)
        // The overlay continues only when reached from the receipt or the overlay.
        assertNotNull(receiptExpandEpisode(seen, Flow.PostTask, RS.offerScreen(2_000L, "h")).receiptExpandSeenAt)
        assertNull(receiptExpandEpisode(seen, Flow.Idle, RS.offerScreen(2_000L, "h")).receiptExpandSeenAt)
    }

    @Test
    fun `an expanded frame of another flow is not a receipt expand`() {
        val other = Observation.Screen(
            timestamp = 1_000L, captureId = "c", ruleId = "doordash.screen.any", metadata = ReplayMetadata.EMPTY,
            flow = Flow.TaskDropoffArrived, modeHint = Mode.Online, parsed = RS.expanded(RS.pay(1.0, 1.0)),
        )
        assertNull(receiptExpandEpisode(RS.live(), Flow.TaskDropoffArrived, other).receiptExpandSeenAt)
    }
}
