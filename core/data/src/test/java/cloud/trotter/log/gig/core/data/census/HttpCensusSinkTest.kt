package cloud.trotter.log.gig.core.data.census

import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.NotificationSkeletonSchema
import cloud.trotter.log.gig.domain.capture.CensusRecord
import cloud.trotter.log.gig.domain.census.CensusChoices
import cloud.trotter.log.gig.domain.census.CensusUploadPreferences
import cloud.trotter.log.gig.domain.census.CensusUploadStats
import cloud.trotter.log.gig.domain.census.MatchedSkeletons
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class HttpCensusSinkTest {
    @get:Rule val tmp = TemporaryFolder()
    private class Preferences : CensusUploadPreferences {
        override val enabled = MutableStateFlow(true)
        override val baseUrl = flowOf("https://example.test")
        override val acceptedSchemaIds = MutableStateFlow(setOf(SkeletonSchema.SCHEMA_ID))
        override val choices = MutableStateFlow(CensusChoices(null, wifiOnly = true))
        override val consentRevision = MutableStateFlow<Int?>(MatchedSkeletons.MIN_DISCLOSURE_REVISION)
    }

    private fun sample() = CensusDailySample(java.io.File(tmp.newFolder(), "sampled.json"), kotlinx.coroutines.Dispatchers.Unconfined)

    private fun kotlinx.coroutines.test.TestScope.sink(
        prefs: Preferences,
        spool: CensusSpool,
        stats: CensusUploadStats,
        io: kotlinx.coroutines.CoroutineDispatcher,
        matchedCap: CensusMatchedCap = newMatchedCap(),
    ) = HttpCensusSink(prefs, spool, sample(), stats, backgroundScope, io, readsUnboundPlatforms = false, matchedCap = matchedCap)

    private fun newMatchedCap(file: java.io.File = java.io.File(tmp.newFolder(), "matched.json")) =
        CensusMatchedCap(file, kotlinx.coroutines.Dispatchers.Unconfined)

    @Test fun `publisher schema snapshot follows policy including an empty accepted set`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val stats = CensusUploadStats()
        val prefs = Preferences()
        val sink = sink(prefs, CensusSpool(tmp.newFolder(), stats, io), stats, io)
        assertFalse(sink.isEnabled)
        runCurrent()
        assertTrue(sink.isEnabled)
        assertEquals(setOf(SkeletonSchema.SCHEMA_ID), sink.acceptedSchemaIds)
        prefs.acceptedSchemaIds.value = setOf(NotificationSkeletonSchema.SCHEMA_ID)
        runCurrent()
        assertEquals(setOf(NotificationSkeletonSchema.SCHEMA_ID), sink.acceptedSchemaIds)
        prefs.acceptedSchemaIds.value = emptySet()
        runCurrent()
        assertTrue(sink.isEnabled) // Publisher counts the refusal even when no schema is advertised.
        assertEquals(emptySet<String>(), sink.acceptedSchemaIds)
    }

    @Test fun `without consent nothing is queued`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val stats = CensusUploadStats()
        val spool = CensusSpool(tmp.newFolder(), stats, io)
        val prefs = Preferences().apply { enabled.value = false }
        val sink = sink(prefs, spool, stats, io)
        runCurrent()
        assertFalse(sink.isEnabled)
        repeat(5) { sink.offer(censusRecord(it)) }; runCurrent()
        assertEquals(0, spool.count())
        assertEquals(5L, stats.spoolDropped.get())
    }

    @Test fun `only the delivery apps the choices cover are queued`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val stats = CensusUploadStats()
        val spool = CensusSpool(tmp.newFolder(), stats, io)
        val prefs = Preferences().apply { choices.value = CensusChoices(setOf(Platform.Uber), wifiOnly = true) }
        val sink = sink(prefs, spool, stats, io)
        runCurrent()
        sink.offer(censusRecord(1)); runCurrent()
        assertEquals("a DoorDash skeleton is left out", 0, spool.count())
        assertEquals(1L, stats.platformNotChosen.get())
        prefs.choices.value = CensusChoices(setOf(Platform.DoorDash), wifiOnly = true)
        sink.offer(censusRecord(1)); runCurrent()
        assertEquals(1, spool.count())
    }

    @Test fun `one skeleton per variant per UTC day`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val stats = CensusUploadStats()
        val spool = CensusSpool(tmp.newFolder(), stats, io)
        val sink = sink(Preferences(), spool, stats, io)
        runCurrent()
        repeat(3) { sink.offer(censusRecord(7)) }
        sink.offer(censusRecord(8))
        sink.offer(censusRecord(7, day = "2026-10-03"))
        runCurrent()
        assertEquals("one of each variant a day, the next day again", 3, spool.count())
        assertEquals(2L, stats.sampledRepeat.get())
    }

    @Test(timeout = 5000) fun `offer never waits for a stalled append and overflow drops oldest with counts`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val received = mutableListOf<String>()
        val stats = CensusUploadStats()
        val io = StandardTestDispatcher(testScheduler)
        val spool = object : CensusSpool(tmp.newFolder(), stats, io) {
            override suspend fun append(record: CensusRecord, generation: Long) {
                gate.await()
                received += record.fingerprint
            }
        }
        val prefs = Preferences()
        val sink = sink(prefs, spool, stats, io)
        assertFalse(sink.isEnabled)
        runCurrent()
        assertTrue(sink.isEnabled)
        assertTrue(sink.offer(censusRecord(0)))
        runCurrent() // Consumer now waits inside append, not on the producer.
        repeat(300) { assertTrue(sink.offer(censusRecord(it + 1))) }
        assertEquals(44L, stats.spoolDropped.get())
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(0) + (45..300).toList(), received.map { it.toInt(16) })
        prefs.enabled.value = false
        runCurrent()
        assertFalse(sink.isEnabled)
    }

    @Test fun `both kinds enter one spool, sampled alike`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val stats = CensusUploadStats()
        val spool = CensusSpool(tmp.newFolder(), stats, io)
        val sink = sink(Preferences(), spool, stats, io)
        runCurrent()
        repeat(50) { index ->
            assertTrue(sink.offer(if (index % 2 == 0) notificationCensusRecord() else censusRecord(index)))
        }
        runCurrent()
        val items = spool.take(100, 1_000_000)
        assertEquals(26, items.size)
        assertEquals(1, items.count { it.itemJson == notificationCensusRecord().skeletonJson })
        assertEquals(26L, stats.spooled.get())
        assertEquals(24L, stats.sampledRepeat.get())
    }

    @Test fun `an outline offered before the queue is cleared is never written behind the clear`() = runTest {
        // #1622: a deletion clears the spool while a record waits in the sink's channel or its consumer.
        val io = StandardTestDispatcher(testScheduler)
        val stats = CensusUploadStats()
        val gate = CompletableDeferred<Unit>()
        val spool = object : CensusSpool(tmp.newFolder(), stats, io) {
            override suspend fun append(record: CensusRecord, generation: Long) {
                gate.await()
                super.append(record, generation)
            }
        }
        val sink = sink(Preferences(), spool, stats, io)
        runCurrent()
        assertTrue(sink.offer(censusRecord(1)))
        runCurrent() // The consumer holds the record, waiting to write it.
        spool.clear()
        gate.complete(Unit)
        runCurrent()
        assertEquals("offered before the clear: dropped", 0, spool.count())
        assertTrue(sink.offer(censusRecord(2)))
        runCurrent()
        assertEquals("offered after it: kept", 1, spool.count())
    }

    // #1573 A6: the matched limits, after the daily sample; the UNKNOWN stream is untouched by them.
    @Test fun `matched skeletons keep one a rule and ten a day, across a restart, and not over an UNKNOWN layout`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val stats = CensusUploadStats()
        val spool = CensusSpool(tmp.newFolder(), stats, io)
        val capFile = java.io.File(tmp.newFolder(), "matched.json")
        val rule = "doordash.screen.delivery_summary_expanded"
        val sink = sink(Preferences(), spool, stats, io, newMatchedCap(capFile))
        runCurrent()
        sink.offer(matchedCensusRecord(1, rule)); runCurrent()
        assertEquals("a matched record is spooled", 1, spool.count())
        sink.offer(matchedCensusRecord(2, rule)); runCurrent()
        assertEquals("a second layout of the same rule that day is refused", 1, spool.count())
        assertEquals(1L, stats.matchedCapped.get())
        (1..9).forEach { sink.offer(matchedCensusRecord(10 + it, "doordash.screen.rule_$it")) }; runCurrent()
        assertEquals("ten rules in all", 10, spool.count())
        sink.offer(matchedCensusRecord(30, "doordash.screen.rule_eleven")); runCurrent()
        assertEquals("the eleventh rule that day is refused", 10, spool.count())
        assertEquals(2L, stats.matchedCapped.get())
        sink.offer(censusRecord(1)); runCurrent()
        assertEquals("an UNKNOWN record of a matched layout is its own variant", 11, spool.count())

        // A restart: a new sink and cap over the same file.
        val restarted = sink(Preferences(), spool, stats, io, newMatchedCap(capFile))
        runCurrent()
        restarted.offer(matchedCensusRecord(3, rule))
        restarted.offer(matchedCensusRecord(31, "doordash.screen.rule_twelve")); runCurrent()
        assertEquals("the limits survive a restart", 11, spool.count())
        assertEquals(4L, stats.matchedCapped.get())
        restarted.offer(matchedCensusRecord(4, rule, day = "2026-10-03")); runCurrent()
        assertEquals("the next UTC day admits the rule again", 12, spool.count())
    }

    @Test fun `a matched record offered with a boundary from before a clear is not written`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val stats = CensusUploadStats()
        val spool = CensusSpool(tmp.newFolder(), stats, io)
        val sink = sink(Preferences(), spool, stats, io)
        runCurrent()
        val boundary = sink.clearBoundary
        spool.clear()
        assertTrue(sink.offer(matchedCensusRecord(1), boundary)); runCurrent()
        assertEquals("read before the clear: dropped", 0, spool.count())
        assertTrue(sink.offer(matchedCensusRecord(1), sink.clearBoundary)); runCurrent()
        assertEquals("read after it: kept", 1, spool.count())
    }

    @Test fun `the matched schema is offered only under a consent that names it`() = runTest {
        // #1573 A10, the sink half: the policy lists the matched schema, the consent revision decides.
        val io = StandardTestDispatcher(testScheduler)
        val stats = CensusUploadStats()
        val prefs = Preferences().apply {
            acceptedSchemaIds.value = setOf(SkeletonSchema.SCHEMA_ID_V2, SkeletonSchema.SCHEMA_ID_MATCHED)
            consentRevision.value = MatchedSkeletons.MIN_DISCLOSURE_REVISION - 1
        }
        val sink = sink(prefs, CensusSpool(tmp.newFolder(), stats, io), stats, io)
        runCurrent()
        assertEquals(setOf(SkeletonSchema.SCHEMA_ID_V2), sink.acceptedSchemaIds)
        prefs.consentRevision.value = MatchedSkeletons.MIN_DISCLOSURE_REVISION
        runCurrent()
        assertEquals(setOf(SkeletonSchema.SCHEMA_ID_V2, SkeletonSchema.SCHEMA_ID_MATCHED), sink.acceptedSchemaIds)
        prefs.consentRevision.value = null
        runCurrent()
        assertEquals("no granted receipt sends none", setOf(SkeletonSchema.SCHEMA_ID_V2), sink.acceptedSchemaIds)
    }
}
