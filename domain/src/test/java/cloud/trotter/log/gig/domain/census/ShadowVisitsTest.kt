package cloud.trotter.log.gig.domain.census

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** #1630 design F1: the shadow visit protocol and where a closed visit is credited. */
class ShadowVisitsTest {
    private val generation = "0123456789abcdef"
    private val digest = "fedcba9876543210"
    private val rule = "doordash.screen.waiting_for_offer"
    private val entry = ShadowTally.entryKey(generation, digest)
    private val day1Start = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli()

    private val clean = ShadowOutcome.Matched(requiredOk = true, maskScan = MaskScanResult.CLEAN)
    private val noMatch = ShadowOutcome.NoMatch
    private val unevaluated = ShadowOutcome.Unevaluated

    private class Run(var ledger: HealthLedger = HealthLedger(), var visits: ShadowVisits = ShadowVisits())

    private fun key(at: Long, version: String = "7.1.0") =
        HealthKey(Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).toLocalDate().toString(), "doordash", version)

    private fun Run.frame(
        at: Long,
        outcome: ShadowOutcome,
        liveWon: Boolean = true,
        version: String = "7.1.0",
        generation: String = this@ShadowVisitsTest.generation,
    ) {
        val record = ShadowFrameRecord(
            at, "doordash", version, generation, listOf(ShadowResult(rule, digest, outcome)), liveWon, resetToken = 0L,
        )
        ledger = visits.step(ledger, key(at, version), record)
    }

    private fun Run.boundary(at: Long, generation: String) {
        ledger = visits.step(ledger, key(at), ShadowFrameRecord(at, "doordash", "7.1.0", generation, emptyList(), true, 0L))
    }

    private fun Run.counters(at: Long = day1Start, version: String = "7.1.0", entry: String = this@ShadowVisitsTest.entry) =
        ledger.rows.getValue(key(at, version).toString()).shadow.getValue(entry)

    private fun s(seconds: Double) = day1Start + (seconds * 1000).toLong()

    @Test fun `one continuous stay longer than 60 s is one visit`() {
        val run = Run()
        for (second in 0..90) run.frame(s(second.toDouble()), clean)
        run.frame(s(91.0), noMatch)
        assertEquals(1, run.counters().visits)
        assertEquals(92, run.counters().evaluated)
    }

    @Test fun `matched, unevaluated, matched is one visit, and unevaluated counts no frame`() {
        val run = Run()
        run.frame(s(0.0), clean)
        run.frame(s(1.0), unevaluated)
        run.frame(s(2.0), clean)
        run.frame(s(10.0), noMatch)
        assertEquals(1, run.counters().visits)
        assertEquals(3, run.counters().evaluated)
    }

    @Test fun `a restart inside the spacing window goes spent, and spent plus matched stays spent`() {
        val run = Run()
        run.frame(s(0.0), clean)
        run.frame(s(0.5), noMatch)
        run.frame(s(2.0), clean) // under 3 s since the last start: Spent
        run.frame(s(4.0), clean) // Spent + Matched stays Spent
        run.frame(s(5.0), noMatch)
        run.frame(s(6.0), clean) // Idle again, last start 6 s ago: a second visit
        run.frame(s(7.0), noMatch)
        assertEquals(2, run.counters().visits)
    }

    @Test fun `a restart between two visit starts under 3 s apart counts two visits`() {
        val run = Run()
        run.frame(s(0.0), clean)
        run.frame(s(0.5), noMatch)
        run.visits = ShadowVisits() // process death: the last start time is lost
        run.frame(s(1.0), clean)
        run.frame(s(1.5), noMatch)
        assertEquals(2, run.counters().visits)
    }

    @Test fun `process death loses an open visit`() {
        val run = Run()
        run.frame(s(0.0), clean)
        run.visits = ShadowVisits()
        run.frame(s(1.0), noMatch)
        assertEquals(0, run.counters().visits)
    }

    @Test fun `a customer value that appears at 4 s is caught`() {
        val run = Run()
        run.frame(s(0.0), clean)
        run.frame(s(4.0), ShadowOutcome.Matched(requiredOk = true, maskScan = MaskScanResult.HIT))
        run.frame(s(5.0), noMatch)
        val counters = run.counters()
        assertEquals(1, counters.maskFailN)
        assertEquals(1, counters.maskFailK)
    }

    @Test fun `a version change during an open visit credits the start row`() {
        val run = Run()
        run.frame(s(0.0), clean, version = "7.1.0")
        run.frame(s(1.0), clean, version = "7.2.0")
        run.frame(s(2.0), noMatch, version = "7.2.0")
        assertEquals(1, run.counters(version = "7.1.0").visits)
        assertEquals(1, run.counters(version = "7.1.0").evaluated)
        // The version row saw the candidate, so it lists it, with its own frames and no visit.
        assertEquals(0, run.counters(version = "7.2.0").visits)
        assertEquals(2, run.counters(version = "7.2.0").evaluated)
    }

    @Test fun `midnight during an open visit credits the start day and the stay is not a new visit`() {
        val run = Run()
        val beforeMidnight = day1Start + 86_400_000L - 20_000L
        val afterMidnight = day1Start + 86_400_000L + 5_000L
        run.frame(beforeMidnight, clean)
        run.frame(afterMidnight, clean)
        run.frame(afterMidnight + 1_000L, clean)
        run.frame(afterMidnight + 2_000L, noMatch)
        assertEquals(1, run.counters(at = beforeMidnight).visits)
        assertEquals(0, run.counters(at = afterMidnight).visits)
    }

    @Test fun `a late closure re-posts the start row`() {
        val run = Run()
        val beforeMidnight = day1Start + 86_400_000L - 5_000L
        run.frame(beforeMidnight, clean)
        val startKey = key(beforeMidnight)
        val posted = run.ledger.rows.getValue(startKey.toString()).revision
        run.ledger = run.ledger.markPosted(startKey, posted)
        assertTrue(run.ledger.closedRowsToPost("2026-10-02", "2026-09-25").isEmpty())

        run.frame(day1Start + 86_400_000L + 1_000L, noMatch)

        val row = run.ledger.rows.getValue(startKey.toString())
        assertTrue(row.revision > posted)
        assertEquals(1, row.shadow.getValue(entry).visits)
        assertEquals(listOf(startKey), run.ledger.closedRowsToPost("2026-10-02", "2026-09-25").map { it.first })
    }

    @Test fun `a generation change closes the open visit under the old ids and starts afresh`() {
        val run = Run()
        val next = "1111111111111111"
        run.frame(s(0.0), clean)
        run.frame(s(1.0), clean, generation = next)
        run.frame(s(2.0), noMatch, generation = next)
        assertEquals(1, run.counters().visits)
        // The new generation's candidate starts Idle with no last start, so its own match at 1 s opened a visit.
        assertEquals(1, run.counters(entry = ShadowTally.entryKey(next, digest)).visits)
    }

    @Test fun `a boundary record with no candidates closes the open visit, whatever its generation id`() {
        val run = Run()
        run.frame(s(0.0), clean)
        run.boundary(s(1.0), generation) // a reload with the same live files and no candidate for the platform
        assertEquals(1, run.counters().visits)

        val other = Run()
        other.frame(s(0.0), clean)
        other.boundary(s(1.0), "")
        assertEquals(1, other.counters().visits)
    }

    @Test fun `the fold carries tied, onUnknown, the parse and the mask families`() {
        val run = Run()
        run.frame(s(0.0), ShadowOutcome.Matched(requiredOk = false, maskScan = MaskScanResult.INCOMPLETE), liveWon = false)
        run.frame(s(1.0), ShadowOutcome.Matched(requiredOk = null, maskScan = MaskScanResult.INCOMPLETE), liveWon = true)
        run.frame(s(2.0), noMatch)
        val c = run.counters()
        assertEquals(listOf(1, 1, 1), listOf(c.visits, c.tied, c.onUnknown))
        assertEquals(1 to 1, c.emptyParseN to c.emptyParseK)
        assertEquals("no scan finished: not assessable", 0 to 0, c.maskFailN to c.maskFailK)

        run.frame(s(10.0), ShadowOutcome.Matched(requiredOk = false, maskScan = MaskScanResult.CLEAN))
        run.frame(s(11.0), ShadowOutcome.Matched(requiredOk = true, maskScan = MaskScanResult.CLEAN))
        run.frame(s(12.0), noMatch)
        val after = run.counters()
        assertEquals("one healthy read clears the visit's parse", 2 to 1, after.emptyParseN to after.emptyParseK)
        assertEquals(1 to 0, after.maskFailN to after.maskFailK)
        assertEquals(2 to 1, after.tied to after.onUnknown)
    }

    @Test fun `a census reset during an open visit, then a late closure, writes nothing`() {
        val run = Run()
        run.frame(s(0.0), clean)
        run.ledger = run.ledger.clear()
        run.frame(s(1.0), noMatch)
        assertTrue(run.ledger.rows.values.all { row -> row.shadow.values.all { it.visits == 0 } })

        // The same through the owner's explicit clear, with a ledger whose generation did not move.
        val held = Run()
        held.frame(s(0.0), clean)
        held.visits.clear()
        held.frame(s(1.0), noMatch)
        assertEquals(0, held.counters().visits)
    }
}
