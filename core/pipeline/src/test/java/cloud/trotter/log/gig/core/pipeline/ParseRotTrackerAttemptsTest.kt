package cloud.trotter.log.gig.core.pipeline

import cloud.trotter.log.gig.domain.pipeline.ParseShortfall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1571: the eligible attempts behind the health report's per-rule parse sample. Visits, not frames, on both sides:
 * a healthy visit is one healthy attempt, a confirmed rot occurrence is one shortfall attempt, a pre-render visit is
 * one healthy attempt, and every attempt carries its start frame's time and platform app version.
 *
 * Fixture vocabulary follows [ParseRotTrackerTest]: a *rot frame* of A is an UNKNOWN frame carrying a skipped offer
 * branch's shortfall of A; a *healthy frame* of A is one A won, with the offer shape, and no shortfall; a *separator*
 * is a frame of a rule with no required fields.
 */
class ParseRotTrackerAttemptsTest {

    private val dd = "doordash"
    private val ruleA = "doordash.screen.offer_card"
    private val home = "doordash.screen.home"

    private val offerRot =
        ParseShortfall(ruleId = ruleA, shape = "offer", nullRequiredFields = listOf("payAmount", "distance"), branchSkipped = true)

    private fun ParseRotTracker.rot(t: Long, version: String = "7.1"): List<ParseAttempt> =
        step(dd, t, version, null, null, listOf(offerRot)).attempts

    private fun ParseRotTracker.healthy(t: Long, version: String = "7.1", shape: String? = "offer"): List<ParseAttempt> =
        step(dd, t, version, ruleA, shape, emptyList()).attempts

    private fun ParseRotTracker.separator(t: Long): List<ParseAttempt> =
        step(dd, t, "7.1", home, "idle", emptyList()).attempts

    private fun healthyAttempt(t: Long, version: String = "7.1") = ParseAttempt(dd, ruleA, t, version, shortfall = false)
    private fun shortfallAttempt(t: Long, version: String = "7.1") = ParseAttempt(dd, ruleA, t, version, shortfall = true)

    @Test
    fun `a healthy visit is one attempt, not one per frame`() {
        val tracker = ParseRotTracker()
        val attempts = (0 until 20).flatMap { tracker.healthy(it * 500L) }
        assertEquals(listOf(healthyAttempt(0)), attempts)
    }

    @Test
    fun `healthy visits count once the window has passed since the last start`() {
        val tracker = ParseRotTracker()
        val attempts = tracker.healthy(0) + tracker.separator(1_000) + tracker.healthy(2_000) +
            tracker.separator(2_500) + tracker.healthy(3_000) + tracker.separator(4_000) + tracker.healthy(9_000)
        assertEquals("a return inside the window is the same visit", listOf(healthyAttempt(0), healthyAttempt(3_000), healthyAttempt(9_000)), attempts)
    }

    @Test
    fun `a healthy frame of a shape with no required fields is no attempt`() {
        val tracker = ParseRotTracker()
        val attempts = tracker.healthy(0, shape = "idle") + tracker.separator(1_000) + tracker.healthy(5_000, shape = null)
        assertTrue(attempts.isEmpty())
    }

    @Test
    fun `a pre-render visit is one healthy attempt`() {
        val tracker = ParseRotTracker()
        val attempts = tracker.rot(0) + tracker.rot(100) + tracker.healthy(900) + tracker.healthy(1_200) +
            tracker.separator(2_000) + tracker.separator(20_000)
        assertEquals(listOf(healthyAttempt(900)), attempts)
    }

    @Test
    fun `a confirmed rot visit is one shortfall attempt at its start`() {
        val tracker = ParseRotTracker()
        val attempts = (0 until 10).flatMap { tracker.rot(it * 200L) } + tracker.separator(2_500) + tracker.separator(3_000)
        assertEquals(listOf(shortfallAttempt(0)), attempts)
        assertTrue(tracker.sweepStep(dd, 60_000).attempts.isEmpty())
    }

    @Test
    fun `a sweep confirms a pending rot visit as an attempt`() {
        val tracker = ParseRotTracker()
        tracker.rot(0)
        assertTrue(tracker.sweepStep(dd, 2_999).attempts.isEmpty())
        assertEquals(listOf(shortfallAttempt(0)), tracker.sweepStep(dd, 3_000).attempts)
    }

    @Test
    fun `a tripped rule's later rot visits still count`() {
        val tracker = ParseRotTracker()
        val attempts = mutableListOf<ParseAttempt>()
        for (i in 0 until 7) {
            val t = i * 4_000L
            attempts += tracker.rot(t)
            attempts += tracker.separator(t + 1_000)
        }
        attempts += tracker.sweepStep(dd, 60_000).attempts
        assertTrue(tracker.isRotted(dd, ruleA))
        assertEquals((0 until 7).map { shortfallAttempt(it * 4_000L) }, attempts)
    }

    @Test
    fun `attempts carry the start frame's version`() {
        val tracker = ParseRotTracker()
        val attempts = tracker.rot(0, version = "7.1") + tracker.rot(500, version = "7.2") +
            tracker.separator(3_000) + tracker.healthy(10_000, version = "7.2") + tracker.healthy(10_500, version = "7.3")
        assertEquals(listOf(shortfallAttempt(0, "7.1"), healthyAttempt(10_000, "7.2")), attempts)
    }

    @Test
    fun `attempts never change trips or clears`() {
        val withShape = ParseRotTracker()
        val bare = ParseRotTracker()
        val frames = (0 until 6).flatMap { i ->
            val t = i * 4_000L
            listOf(Triple(t, null as String?, listOf(offerRot)), Triple(t + 1_000, home, emptyList<ParseShortfall>()))
        } + (0 until 3).map { Triple(30_000L + it * 1_000L, ruleA, emptyList<ParseShortfall>()) }
        for ((t, winner, shortfalls) in frames) {
            val shape = if (winner == ruleA) "offer" else "idle"
            assertEquals(
                bare.record(dd, t, winner, shortfalls),
                withShape.step(dd, t, "7.1", winner, shape.takeIf { winner != null }, shortfalls).edges,
            )
        }
    }
}
