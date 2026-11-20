package cloud.trotter.log.gig.core.pipeline.census

import cloud.trotter.log.gig.core.pipeline.PipelineStats
import cloud.trotter.log.gig.domain.capture.CensusRecord
import cloud.trotter.log.gig.domain.capture.CensusSink
import cloud.trotter.log.gig.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1573 A4: [MatchedSkeletonHold] releases a held record only on a confirmation of its own visit while its rule is in
 * an open incident, never on time, and offers it with the clear boundary read at the frame's admission.
 */
class MatchedSkeletonHoldTest {

    private val dd = "doordash"
    private val rule = "doordash.screen.delivery_summary_expanded"

    /** A sink with the spool's clear-boundary semantics: an offer with a stale boundary is taken, then not written. */
    private class SpoolSink : CensusSink {
        override val isEnabled = true
        var generation = 0L
        override val clearBoundary: Long get() = generation
        val written = mutableListOf<CensusRecord>()
        val boundaries = mutableListOf<Long>()
        override fun offer(record: CensusRecord): Boolean = offer(record, generation)
        override fun offer(record: CensusRecord, boundary: Long): Boolean {
            boundaries += boundary
            if (boundary == generation) written += record
            return true
        }
    }

    private fun record(n: Int) = CensusRecord(Platform.DoorDash, n.toString(16).padStart(64, '0'), "{}", 2, null)

    private val sink = SpoolSink()
    private val stats = PipelineStats()
    private val hold = MatchedSkeletonHold(sink, stats)

    @Test fun `a confirmation of the held frame's visit releases it once`() {
        hold.hold(dd, rule, 10_000L, record(1), boundary = 0L, openRuleIds = setOf(rule))
        hold.onConfirmed(dd, rule, 10_000L, setOf(rule))
        hold.onConfirmed(dd, rule, 10_000L, setOf(rule))
        assertEquals(listOf(record(1)), sink.written)
        assertEquals(1L, stats.matchedReleasedCount())
        assertEquals(0, hold.size())
    }

    @Test fun `the latest frame of the visit is the one released`() {
        hold.hold(dd, rule, 10_000L, record(1), 0L, setOf(rule))
        hold.hold(dd, rule, 12_000L, record(2), 0L, setOf(rule))
        assertEquals(1, hold.size())
        hold.onConfirmed(dd, rule, 10_000L, setOf(rule))
        assertEquals(listOf(record(2)), sink.written)
    }

    @Test fun `there is no time-based expiry`() {
        // Held at 0; the confirmation comes on a frame 10 s later, for the visit that started at 0.
        hold.hold(dd, rule, 0L, record(1), 0L, setOf(rule))
        hold.onConfirmed(dd, rule, 0L, setOf(rule))
        assertEquals(listOf(record(1)), sink.written)
    }

    @Test fun `a frame older than the confirmed visit is dropped, never offered`() {
        hold.hold(dd, rule, 10_000L, record(1), 0L, setOf(rule))
        hold.onConfirmed(dd, rule, 12_000L, setOf(rule))
        assertTrue(sink.boundaries.isEmpty())
        assertEquals(1L, stats.matchedDroppedCount())
        assertEquals(0, hold.size())
    }

    @Test fun `a frame of a later visit waits for its own confirmation`() {
        hold.hold(dd, rule, 20_000L, record(1), 0L, setOf(rule))
        hold.onConfirmed(dd, rule, 10_000L, setOf(rule))
        assertTrue(sink.boundaries.isEmpty())
        assertEquals(1, hold.size())
        hold.onConfirmed(dd, rule, 20_000L, setOf(rule))
        assertEquals(listOf(record(1)), sink.written)
    }

    @Test fun `a rule outside the open incident is neither held nor released`() {
        hold.hold(dd, rule, 10_000L, record(1), 0L, emptySet())
        assertEquals(0, hold.size())
        hold.hold(dd, rule, 10_000L, record(1), 0L, setOf(rule))
        hold.onConfirmed(dd, rule, 10_000L, emptySet())
        assertTrue(sink.boundaries.isEmpty())
        assertEquals(0, hold.size())
    }

    @Test fun `a rule that left the incident is dropped on the platform's next hold`() {
        val other = "doordash.screen.dash_summary"
        hold.hold(dd, rule, 10_000L, record(1), 0L, setOf(rule, other))
        hold.hold(dd, other, 11_000L, record(2), 0L, setOf(other))
        assertEquals(1, hold.size())
        hold.onConfirmed(dd, rule, 10_000L, setOf(rule, other))
        assertTrue(sink.boundaries.isEmpty())
    }

    @Test fun `at most sixteen records are held, the oldest evicted`() {
        val rules = (0..16).map { "doordash.screen.rule_$it" }.toSet()
        rules.forEachIndexed { i, r -> hold.hold(dd, r, 1_000L * i, record(i), 0L, rules) }
        assertEquals(MatchedSkeletonHold.MAX_ENTRIES, hold.size())
        hold.onConfirmed(dd, "doordash.screen.rule_0", 0L, rules)
        assertTrue("the oldest was evicted", sink.boundaries.isEmpty())
        hold.onConfirmed(dd, "doordash.screen.rule_16", 16_000L, rules)
        assertEquals(listOf(record(16)), sink.written)
    }

    @Test fun `the offer carries the boundary read at admission, so a deletion while held drops it`() {
        val boundary = sink.clearBoundary
        sink.generation++ // a census deletion between the frame's admission and its release
        hold.hold(dd, rule, 10_000L, record(1), boundary, setOf(rule))
        hold.onConfirmed(dd, rule, 10_000L, setOf(rule))
        assertEquals(listOf(boundary), sink.boundaries)
        assertTrue("refused by the spool", sink.written.isEmpty())
    }
}
