package cloud.trotter.feed.contract

import cloud.trotter.feed.contract.TestFeeds.DAY
import cloud.trotter.feed.contract.TestFeeds.HOUR
import cloud.trotter.feed.contract.TestFeeds.NOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedAcceptanceTest {

    private val fuel = FeedRequest(FeedSources.GIG_LOG, Feed.FUEL, wallClockMs = NOW, buildTimeMs = NOW - 30 * DAY, supportedContracts = setOf(1L))
    private val rules = fuel.copy(feed = Feed.RULES)

    private fun candidate(sequence: Long, digest: String = "d$sequence", feed: Feed = Feed.FUEL, contract: Long = 1) =
        FeedCandidate(FeedSources.GIG_LOG, feed, sequence, sequence, contract, digest)

    private fun refused(reason: FeedRefusal) = FeedDecision.Refuse(reason)

    // --- clock admission, no ratchet ---

    @Test
    fun `an accepted manifest 23 h ahead does not move admission`() {
        val ahead = candidate(NOW + 23 * HOUR)
        val first = FeedAcceptance.decide(FeedCacheState(), ahead, fuel)
        assertEquals(FeedDecision.Accept(highWater = ahead.sequence, issuedAt = ahead.issuedAt), first)

        // The phone now holds a generation 23 h ahead. A manifest 25 h past the same wall clock is still refused:
        // admission reads only the wall clock and the build time, never what a feed sent.
        val state = FeedCacheState(committed = FeedMark(ahead.sequence, ahead.digest))
        assertEquals(refused(FeedRefusal.ClockBehind), FeedAcceptance.decide(state, candidate(NOW + 25 * HOUR), fuel))
        // ...and one inside the window is a normal candidate.
        val next = candidate(NOW + 24 * HOUR)
        assertEquals(FeedDecision.Accept(next.sequence, next.issuedAt), FeedAcceptance.decide(state, next, fuel))
    }

    @Test
    fun `admission is the larger of the wall clock and the build time`() {
        val setBack = fuel.copy(wallClockMs = NOW - 10 * DAY, buildTimeMs = NOW)
        assertTrue(FeedAcceptance.decide(FeedCacheState(), candidate(NOW + 23 * HOUR), setBack) is FeedDecision.Accept)
        assertEquals(
            refused(FeedRefusal.ClockBehind),
            FeedAcceptance.decide(FeedCacheState(), candidate(NOW + 25 * HOUR), setBack),
        )
    }

    @Test
    fun `a phone clock more than a day behind returns ClockBehind`() {
        // Real time is NOW; the phone says two days earlier and the build is a month old.
        val behind = fuel.copy(wallClockMs = NOW - 2 * DAY, buildTimeMs = NOW - 30 * DAY)
        assertEquals(refused(FeedRefusal.ClockBehind), FeedAcceptance.decide(FeedCacheState(), candidate(NOW), behind))
        // Exactly a day behind is still admitted.
        val oneDay = fuel.copy(wallClockMs = NOW - DAY)
        assertTrue(FeedAcceptance.decide(FeedCacheState(), candidate(NOW), oneDay) is FeedDecision.Accept)
    }

    // --- units ---

    @Test
    fun `every time is milliseconds and a value below 10^12 is refused`() {
        val seconds = candidate(NOW / 1000)
        val secondsState = FeedCacheState()
        assertEquals(refused(FeedRefusal.NotMilliseconds), FeedAcceptance.decide(secondsState, seconds, fuel))
        val belowFloor = candidate(FeedLimits.MIN_EPOCH_MS - 1)
        assertEquals(refused(FeedRefusal.NotMilliseconds), FeedAcceptance.decide(secondsState, belowFloor, fuel))
        val atFloor = candidate(FeedLimits.MIN_EPOCH_MS)
        assertTrue(FeedAcceptance.decide(secondsState, atFloor, fuel) is FeedDecision.Accept)
    }

    @Test
    fun `sequence must equal issued_at`() {
        val skewed = candidate(NOW).copy(issuedAt = NOW - 1)
        assertEquals(refused(FeedRefusal.SequenceNotIssuedAt), FeedAcceptance.decide(FeedCacheState(), skewed, fuel))
    }

    // --- high-water ---

    @Test
    fun `above the high-water is a candidate and below it is a rollback`() {
        val state = FeedCacheState(committed = FeedMark(NOW - DAY, "old"))
        assertEquals(FeedDecision.Accept(NOW, NOW), FeedAcceptance.decide(state, candidate(NOW), fuel))
        assertEquals(refused(FeedRefusal.Rollback), FeedAcceptance.decide(state, candidate(NOW - 2 * DAY), fuel))
    }

    @Test
    fun `recovery never lowers the high-water`() {
        val committed = FeedCacheState(committed = FeedMark(NOW, "current"))
        val recovered = committed.recoveredTo(FeedMark(NOW - 7 * DAY, "previous"))
        assertEquals(NOW, recovered.highWater)
        // The generation recovery fell back to cannot come back in as new, nor can anything between it and the mark.
        assertEquals(refused(FeedRefusal.Rollback), FeedAcceptance.decide(recovered, candidate(NOW - 7 * DAY, "previous"), fuel))
        assertEquals(refused(FeedRefusal.Rollback), FeedAcceptance.decide(recovered, candidate(NOW - DAY), fuel))
        // The re-verified committed manifest restores as a no-op.
        assertEquals(FeedDecision.NoOp(bundled = false), FeedAcceptance.decide(recovered, candidate(NOW, "current"), fuel))
        // With no committed mark left, the fallback's own sequence becomes the high-water.
        assertEquals(NOW - 7 * DAY, FeedCacheState().recoveredTo(FeedMark(NOW - 7 * DAY, "previous")).highWater)
    }

    @Test
    fun `the bundled FEED_SEQUENCE beats a staler cached generation`() {
        val bundled = FeedMark(NOW - DAY, "bundled")
        val state = FeedCacheState(committed = FeedMark(NOW - 10 * DAY, "cached"), bundled = bundled)
        assertEquals(NOW - DAY, state.highWater)
        assertFalse("a cached generation older than the APK's rules is dropped", state.keepsCommittedGeneration(NOW - 10 * DAY))
        assertFalse("equal to FEED_SEQUENCE is dropped too", state.keepsCommittedGeneration(NOW - DAY))
        assertTrue(state.keepsCommittedGeneration(NOW))
        // A signed generation between the cached one and the bundle is a rollback against the bundle.
        assertEquals(refused(FeedRefusal.Rollback), FeedAcceptance.decide(state, candidate(NOW - 5 * DAY, feed = Feed.RULES), rules))
        assertTrue(FeedAcceptance.decide(state, candidate(NOW, feed = Feed.RULES), rules) is FeedDecision.Accept)
    }

    @Test
    fun `equal sequence with the same digest is a no-op and with a different digest is refused`() {
        val state = FeedCacheState(committed = FeedMark(NOW, "abc"))
        assertEquals(FeedDecision.NoOp(bundled = false), FeedAcceptance.decide(state, candidate(NOW, "abc"), fuel))
        assertEquals(refused(FeedRefusal.ReusedSequence), FeedAcceptance.decide(state, candidate(NOW, "xyz"), fuel))
    }

    @Test
    fun `equal FEED_SEQUENCE plus FEED_DIGEST is the silent no-op`() {
        val state = FeedCacheState(bundled = FeedMark(NOW, "bundled"))
        assertEquals(
            FeedDecision.NoOp(bundled = true),
            FeedAcceptance.decide(state, candidate(NOW, "bundled", feed = Feed.RULES), rules),
        )
        assertEquals(
            refused(FeedRefusal.ReusedSequence),
            FeedAcceptance.decide(state, candidate(NOW, "other", feed = Feed.RULES), rules),
        )
    }

    // --- contract ---

    @Test
    fun `an unknown contract keeps last-good and does not move the high-water`() {
        val state = FeedCacheState(committed = FeedMark(NOW - DAY, "good"))
        val decision = FeedAcceptance.decide(state, candidate(NOW, contract = 2), fuel)
        assertEquals(refused(FeedRefusal.UnsupportedContract), decision)
        // Refuse carries no high-water: the state the caller keeps is the one it had, so the same candidate is still
        // a candidate once an app update supports contract 2.
        val updated = fuel.copy(supportedContracts = setOf(1L, 2L))
        assertEquals(FeedDecision.Accept(NOW, NOW), FeedAcceptance.decide(state, candidate(NOW, contract = 2), updated))
    }

    // --- source and feed ---

    @Test
    fun `source and feed must equal the request`() {
        assertEquals(
            refused(FeedRefusal.WrongFeed),
            FeedAcceptance.decide(FeedCacheState(), candidate(NOW, feed = Feed.FUEL), rules),
        )
        val custom = candidate(NOW).copy(source = FeedSources.custom("0123456789abcdef"))
        assertEquals(refused(FeedRefusal.WrongSource), FeedAcceptance.decide(FeedCacheState(), custom, fuel))
    }

    // --- freshness ---

    @Test
    fun `freshness is a label by feed age`() {
        assertEquals(FeedFreshness.CURRENT, FeedFreshness.of(Feed.FUEL, NOW - 21 * DAY, NOW))
        assertEquals(FeedFreshness.OLD, FeedFreshness.of(Feed.FUEL, NOW - 21 * DAY - 1, NOW))
        assertEquals(FeedFreshness.CURRENT, FeedFreshness.of(Feed.CONSTANTS, NOW - 400 * DAY, NOW))
        assertEquals(FeedFreshness.OLD, FeedFreshness.of(Feed.CONSTANTS, NOW - 401 * DAY, NOW))
        assertEquals(FeedFreshness.CURRENT, FeedFreshness.of(Feed.RULES, NOW - 90 * DAY, NOW))
        assertEquals(FeedFreshness.OLD, FeedFreshness.of(Feed.RULES, NOW - 91 * DAY, NOW))
        assertEquals("a wall clock behind issued_at is current", FeedFreshness.CURRENT, FeedFreshness.of(Feed.FUEL, NOW + DAY, NOW))
    }

    @Test
    fun `an equal sequence never renews freshness`() {
        val issued = NOW - 30 * DAY
        val state = FeedCacheState(committed = FeedMark(issued, "abc"))
        val decision = FeedAcceptance.decide(state, candidate(issued, "abc"), fuel)
        // NoOp carries no issued_at, so the newest accepted issued_at, and with it the label, stays where it was.
        assertEquals(FeedDecision.NoOp(bundled = false), decision)
        assertEquals(FeedFreshness.OLD, FeedFreshness.of(Feed.FUEL, issued, NOW))
    }
}
