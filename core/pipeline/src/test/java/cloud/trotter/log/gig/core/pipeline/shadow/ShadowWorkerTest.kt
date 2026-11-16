package cloud.trotter.log.gig.core.pipeline.shadow

import cloud.trotter.log.gig.core.pipeline.rules.RuleCompiler
import cloud.trotter.log.gig.core.pipeline.rules.RuleContext
import cloud.trotter.log.gig.core.pipeline.rules.RuleGeneration
import cloud.trotter.log.gig.core.pipeline.rules.ShadowCandidate
import cloud.trotter.log.gig.core.pipeline.rules.ShadowFuel
import cloud.trotter.log.gig.core.pipeline.rules.ShadowSet
import cloud.trotter.log.gig.domain.census.HealthSink
import cloud.trotter.log.gig.domain.census.MaskScanResult
import cloud.trotter.log.gig.domain.census.ShadowFrameRecord
import cloud.trotter.log.gig.domain.census.ShadowOutcome
import cloud.trotter.log.gig.domain.census.ShadowResult
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** #1630: the shadow worker evaluates off the caller's thread, drops on a full queue and skips sensitive frames. */
class ShadowWorkerTest {
    private val generationId = "ab".repeat(32)
    private val digest = "cd".repeat(32)
    private val rule = "doordash.screen.waiting_for_offer"

    private val candidate = ShadowCandidate(
        ShadowFuel.compileMetered {
            val json = """[{ "id": "$rule", "priority": 120, "require": { "exists": { "hasText": "Looking for offers" } } }]"""
            RuleCompiler.compileRules<UiNode>(Json.parseToJsonElement(json).jsonArray, RuleContext.SCREEN, "doordash.driver").single()
        },
        Platform.DoorDash,
        digest,
    )
    private val generation = RuleGeneration(shadow = ShadowSet(generationId, listOf(candidate), droppedCandidates = 0))

    private class Recording(private val gate: CountDownLatch? = null, private val started: CountDownLatch? = null) :
        HealthSink by HealthSink.NoOp {
        val records = LinkedBlockingQueue<Pair<String, ShadowFrameRecord>>()
        override fun onShadowFrame(frame: ShadowFrameRecord) {
            started?.countDown()
            gate?.await(10, TimeUnit.SECONDS)
            records.put(Thread.currentThread().name to frame)
        }
    }

    private fun screen(vararg texts: String) = UiNode(children = texts.map { UiNode(text = it) }).restoreParents()

    private fun ShadowWorker.offer(tree: UiNode, title: String? = null, platform: Platform = Platform.DoorDash, at: Long = 1_000L) =
        offer(generation, platform, tree, title, at, "7.1.0", liveWon = true)

    private fun Recording.next(): Pair<String, ShadowFrameRecord> = requireNotNull(records.poll(10, TimeUnit.SECONDS))

    @Test fun `a candidate is evaluated on the worker thread and only ids and outcomes reach the sink`() {
        val sink = Recording()
        val stats = ShadowStats()
        val worker = ShadowWorker(sink, stats, ShadowWorker.QUEUE_CAPACITY)

        worker.offer(screen("Looking for offers"), at = 5_000L)
        worker.offer(generation, Platform.DoorDash, screen("Dash summary"), null, 6_000L, "7.1.0", liveWon = false)

        val (thread, matched) = sink.next()
        assertEquals(ShadowWorker.THREAD_NAME, thread)
        assertNotEquals(Thread.currentThread().name, thread)
        assertEquals(
            ShadowFrameRecord(
                5_000L, "doordash", "7.1.0", generationId.take(16),
                listOf(ShadowResult(rule, digest.take(16), ShadowOutcome.Matched(null, MaskScanResult.CLEAN))), liveWon = true,
            ),
            matched,
        )
        val (_, unmatched) = sink.next()
        assertEquals(ShadowOutcome.NoMatch, unmatched.results.single().outcome)
        assertFalse(unmatched.liveWon)

        val summary = stats.suffix()
        assertTrue(summary, summary.startsWith(" shadow{frames=2,dropped=0,skipped=0,matched=1,noMatch=1,budget=0,failed=0,medianMs="))
        assertFalse("counts only, no rule text", summary.contains("doordash"))
    }

    @Test fun `a full queue drops frames without blocking the caller`() {
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        val sink = Recording(gate, started)
        val stats = ShadowStats()
        val worker = ShadowWorker(sink, stats, ShadowWorker.QUEUE_CAPACITY)

        worker.offer(screen("Looking for offers"))
        assertTrue(started.await(10, TimeUnit.SECONDS))
        val before = System.nanoTime()
        repeat(ShadowWorker.QUEUE_CAPACITY + 2) { worker.offer(screen("Looking for offers")) }
        val tookMs = (System.nanoTime() - before) / 1_000_000
        assertTrue("offers never wait for the worker, took $tookMs ms", tookMs < 1_000)
        assertEquals(2L, stats.droppedCount())

        gate.countDown()
        repeat(1 + ShadowWorker.QUEUE_CAPACITY) { sink.next() }
        assertNull(sink.records.poll(200, TimeUnit.MILLISECONDS))
        assertEquals(5L, stats.frameCount())
    }

    @Test fun `a frame with a sensitive marker in its tree or window title never reaches a candidate`() {
        val sink = Recording()
        val stats = ShadowStats()
        val worker = ShadowWorker(sink, stats, ShadowWorker.QUEUE_CAPACITY)

        worker.offer(screen("Looking for offers", "Bank Account"))
        worker.offer(screen("Looking for offers"), title = "Routing Number")
        worker.offer(screen("Looking for offers"), at = 9_000L)

        assertEquals(9_000L, sink.next().second.timestampMillis)
        assertEquals(2L, stats.skippedCount())
        assertEquals(1L, stats.frameCount())
    }

    @Test fun `nothing is queued without a candidate for the frame's platform`() {
        val sink = Recording()
        val stats = ShadowStats()
        val worker = ShadowWorker(sink, stats, ShadowWorker.QUEUE_CAPACITY)

        worker.offer(screen("Looking for offers"), platform = Platform.Uber)
        worker.offer(screen("Looking for offers"), platform = Platform.Unknown)
        worker.offer(null, Platform.DoorDash, screen("Looking for offers"), null, 1L, "7.1.0", liveWon = true)
        worker.offer(RuleGeneration(), Platform.DoorDash, screen("Looking for offers"), null, 2L, "7.1.0", liveWon = true)
        worker.offer(screen("Looking for offers"), at = 3L)

        assertEquals(3L, sink.next().second.timestampMillis)
        assertNull(sink.records.poll(200, TimeUnit.MILLISECONDS))
        assertEquals(1L, stats.frameCount())
        assertEquals("", ShadowStats().suffix())
    }
}
