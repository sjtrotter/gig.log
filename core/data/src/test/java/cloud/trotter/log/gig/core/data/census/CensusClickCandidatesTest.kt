package cloud.trotter.log.gig.core.data.census

import cloud.trotter.census.contract.ClickedIdentity
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.sha256OrNull
import cloud.trotter.log.gig.core.data.census.CensusClickCandidates.Admission
import cloud.trotter.log.gig.core.network.census.CensusApi
import cloud.trotter.log.gig.domain.capture.CensusRecord
import cloud.trotter.log.gig.domain.census.CensusUploadStats
import cloud.trotter.log.gig.domain.census.ClickEvidence
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * #1634 D7: what the phone keeps and sends of click evidence does not depend on the order of the taps, a frozen day is
 * sent unchanged until answered and never replenished, and a census deletion or identity reset clears it all.
 */
class CensusClickCandidatesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val day = "2026-10-02"

    /** A clock the test moves; the store reads it on every call. */
    private class MovableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private fun at(date: String) = LocalDate.parse(date).atTime(12, 0).toInstant(ZoneOffset.UTC)

    private class Store(val spool: CensusSpool, val clicks: CensusClickCandidates, val clock: MovableClock, val stats: CensusUploadStats, val root: File)

    private fun TestScope.store(root: File = tmp.newFolder(), clock: MovableClock = MovableClock(at(day))): Store {
        val stats = CensusUploadStats()
        val spool = CensusSpool(File(root, "spool"), stats, StandardTestDispatcher(testScheduler))
        return Store(spool, CensusClickCandidates(spool, stats, clock), clock, stats, root)
    }

    private suspend fun Store.admit(pair: Pair<UiSkeletonDto, CensusRecord>, boundary: Long = spool.generation): Admission =
        clicks.admit(pair.first, pair.second.skeletonJson, pair.second.platform.wire, boundary)

    private suspend fun Store.closeNextDay(): List<CensusClickCandidates.Batch> {
        clock.now = at(LocalDate.parse(day).plusDays(1).toString())
        clicks.closeEndedDays()
        return clicks.frozen()
    }

    private fun frozenBytes(root: File): Map<String, String> =
        File(root, "clicks-frozen").listFiles().orEmpty().associate { it.name to it.readText() }

    private fun key(pair: Pair<UiSkeletonDto, CensusRecord>) = requireNotNull(ClickedIdentity.selectionKey(pair.first))

    @Test fun `opposite tap orders freeze identical bytes`() = runTest {
        val taps = (1..20).flatMap { listOf(clickedCensusRecord(it, owner = 0, day = day), clickedCensusRecord(it, owner = 1, day = day)) }
        val forward = store()
        val backward = store()
        taps.forEach { forward.admit(it) }
        taps.reversed().forEach { backward.admit(it) }
        val a = forward.closeNextDay()
        val b = backward.closeNextDay()
        assertEquals(1, a.size)
        assertEquals(ClickEvidence.SENT_PER_DAY, a.single().items.size)
        assertEquals(a.single().items, b.single().items)
        assertEquals(frozenBytes(forward.root), frozenBytes(backward.root))
        val expected = taps.sortedBy { key(it) }.take(ClickEvidence.SENT_PER_DAY)
        assertEquals("the smallest keys, in key order", expected.map { it.second.skeletonJson }, a.single().items)
        assertEquals(CensusApi.clickedBatchId(expected.map { ClickedIdentity.of(it.first)!! }), a.single().batchId)
        assertTrue(a.single().batchId.startsWith("c-"))
    }

    @Test fun `opposite orders of one candidate's bodies keep the same representative`() = runTest {
        val one = clickedCensusRecord(3, day = day, appVersion = "1.0")
        val two = clickedCensusRecord(3, day = day, appVersion = "2.0")
        assertEquals(key(one), key(two))
        val smaller = listOf(one, two).minBy { sha256OrNull(it.second.skeletonJson)!! }
        val forward = store().apply { admit(one); admit(two) }
        val backward = store().apply { admit(two); admit(one) }
        assertEquals(listOf(smaller.second.skeletonJson), forward.closeNextDay().single().items)
        assertEquals(listOf(smaller.second.skeletonJson), backward.closeNextDay().single().items)
    }

    @Test fun `past 64 candidates a new one stays only if its key is smaller than the largest kept`() = runTest {
        val s = store()
        val taps = (1..ClickEvidence.CANDIDATES_PER_DAY + 1).map { clickedCensusRecord(it, day = day) }.sortedBy { key(it) }
        val (largest, kept) = taps.last() to taps.dropLast(1)
        kept.forEach { assertEquals(Admission.KEPT, s.admit(it)) }
        assertEquals("the 65th, larger than every kept key, is not kept", Admission.NOT_KEPT, s.admit(largest))

        val t = store()
        taps.drop(1).forEach { assertEquals(Admission.KEPT, t.admit(it)) }
        assertEquals("the 65th, smaller than the largest kept key, replaces it", Admission.KEPT, t.admit(taps.first()))
        assertEquals(taps.take(ClickEvidence.SENT_PER_DAY).map { it.second.skeletonJson }, t.closeNextDay().single().items)
    }

    @Test fun `a late candidate for a closed day is dropped and nothing replenishes the day`() = runTest {
        val s = store()
        (1..12).forEach { s.admit(clickedCensusRecord(it, day = day)) }
        val frozen = s.closeNextDay().single()
        assertEquals(Admission.LATE, s.admit(clickedCensusRecord(99, day = day)))
        s.clicks.resolve(frozen)
        assertEquals(Admission.LATE, s.admit(clickedCensusRecord(98, day = day)))
        s.clicks.closeEndedDays()
        assertTrue("an answered day never refills", s.clicks.frozen().isEmpty())
        assertEquals(2L, s.stats.clickedLate.get())
    }

    @Test fun `today's candidates wait for the day to end`() = runTest {
        val s = store()
        s.admit(clickedCensusRecord(1, day = day))
        s.clicks.closeEndedDays()
        assertTrue(s.clicks.frozen().isEmpty())
        assertEquals(1, s.closeNextDay().size)
    }

    @Test fun `a restart between freeze and send finds the same frozen batch`() = runTest {
        val s = store()
        (1..4).forEach { s.admit(clickedCensusRecord(it, day = day)) }
        val before = s.closeNextDay().single()
        val restarted = CensusClickCandidates(CensusSpool(File(s.root, "spool"), CensusUploadStats(), StandardTestDispatcher(testScheduler)),
            CensusUploadStats(), s.clock)
        val after = restarted.frozen().single()
        assertEquals(before.batchId, after.batchId)
        assertEquals(before.items, after.items)
    }

    @Test fun `a split keeps key order, each half is its own batch, and a sent half is not resent`() = runTest {
        val s = store()
        (1..10).forEach { s.admit(clickedCensusRecord(it, day = day)) }
        val whole = s.closeNextDay().single()
        val halves = requireNotNull(s.clicks.split(whole))
        assertEquals(listOf(5, 5), halves.map { it.items.size })
        assertEquals(whole.items, halves.flatMap { it.items })
        assertTrue(halves.all { it.batchId.startsWith("c-") && it.batchId != whole.batchId })
        assertEquals(halves.map { it.batchId }, s.clicks.frozen().map { it.batchId })
        s.clicks.resolve(halves.first())
        assertEquals(listOf(halves.last().batchId), s.clicks.frozen().map { it.batchId })
        assertEquals(halves.last().items, s.clicks.frozen().single().items)
    }

    @Test fun `a frozen batch past seven days is deleted`() = runTest {
        val s = store()
        (1..3).forEach { s.admit(clickedCensusRecord(it, day = day)) }
        s.closeNextDay()
        s.clock.now = at(LocalDate.parse(day).plusDays(ClickEvidence.KEPT_DAYS + 1).toString())
        assertTrue(s.clicks.frozen().isEmpty())
        assertEquals(3L, s.stats.stale.get())
        assertTrue(frozenBytes(s.root).isEmpty())
    }

    @Test fun `evidence read before a deletion is dropped even when it arrives after`() = runTest {
        val s = store()
        val boundary = s.spool.generation
        s.spool.clear()
        assertEquals(Admission.STALE, s.admit(clickedCensusRecord(1, day = day), boundary))
        assertEquals(1L, s.stats.clickedStale.get())
        assertTrue(s.closeNextDay().isEmpty())
    }

    @Test fun `a deletion or identity reset clears candidates, closed days and frozen batches`() = runTest {
        val s = store()
        (1..3).forEach { s.admit(clickedCensusRecord(it, day = day)) }
        s.closeNextDay()
        s.admit(clickedCensusRecord(5, day = LocalDate.parse(day).plusDays(1).toString()))
        assertTrue(File(s.root, "clicks.json").exists())
        // Both CensusDataDeletion and CensusIdentityResetter clear through the spool's clear.
        s.spool.clear()
        assertFalse(File(s.root, "clicks.json").exists())
        assertTrue(frozenBytes(s.root).isEmpty())
        assertTrue(s.clicks.frozen().isEmpty())
        assertEquals("the closed marker went too: the day is open again", Admission.KEPT, s.admit(clickedCensusRecord(7, day = day)))
    }

    @Test fun `a write that read its boundary before a racing clear never survives it`() = runTest {
        val s = store()
        val boundary = s.spool.generation
        // The admission and the clear contend for the one lock; whichever runs first, nothing stays.
        val write = async { s.admit(clickedCensusRecord(1, day = day), boundary) }
        val clear = async { s.spool.clear() }
        write.await(); clear.await()
        assertFalse(File(s.root, "clicks.json").exists())
        assertTrue(s.closeNextDay().isEmpty())
    }

    @Test fun `restricting to covered delivery apps rewrites the batch without the others`() = runTest {
        val s = store()
        (1..3).forEach { s.admit(clickedCensusRecord(it, day = day)) }
        val batch = s.closeNextDay().single()
        assertEquals(batch.batchId, s.clicks.restrict(batch, setOf("doordash"))?.batchId)
        assertEquals(null, s.clicks.restrict(batch, setOf("uber")))
        assertTrue(s.clicks.frozen().isEmpty())
    }

    @Test fun `holding back an app the policy does not accept keeps its items as their own frozen batch`() = runTest {
        val s = store()
        (1..2).forEach { s.admit(clickedCensusRecord(it, day = day)) }
        (3..4).forEach { val pair = clickedCensusRecord(it, day = day); s.clicks.admit(pair.first, pair.second.skeletonJson, "instacart", s.spool.generation) }
        val whole = s.closeNextDay().single()
        assertEquals(whole, s.clicks.holdBack(whole) { true })
        assertEquals("none accepted: the batch is left as it is", null, s.clicks.holdBack(whole) { false })
        assertEquals(listOf(whole.batchId), s.clicks.frozen().map { it.batchId })
        val sent = requireNotNull(s.clicks.holdBack(whole) { it == "doordash" })
        assertEquals(listOf("doordash", "doordash"), sent.platforms)
        assertTrue(sent.batchId != whole.batchId)
        val frozen = s.clicks.frozen()
        assertEquals(2, frozen.size)
        assertEquals(listOf("instacart", "instacart"), frozen.single { it.batchId != sent.batchId }.platforms)
        assertEquals(whole.items.toSet(), frozen.flatMap { it.items }.toSet())
    }
}
