package cloud.trotter.log.gig.core.pipeline.census

import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.log.gig.core.pipeline.PipelineEvent
import cloud.trotter.log.gig.core.pipeline.PipelineStats
import cloud.trotter.log.gig.core.pipeline.RecognitionHealthMonitor
import cloud.trotter.log.gig.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.log.gig.core.pipeline.rules.CompiledRedact
import cloud.trotter.log.gig.core.pipeline.rules.ScreenRedactionSource
import cloud.trotter.log.gig.domain.capture.CensusRecord
import cloud.trotter.log.gig.domain.capture.CensusSink
import cloud.trotter.log.gig.domain.capture.NoOpCensusEnvelopeSink
import cloud.trotter.log.gig.domain.census.HealthSink
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.pipeline.ParseShortfall
import cloud.trotter.log.gig.domain.pipeline.RecognitionHealthReporter
import cloud.trotter.log.gig.domain.pipeline.RecognitionNoticeStore
import cloud.trotter.log.gig.domain.state.ParsedFields
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1573 A5: the monitor, the hold and the publisher in pipeline order (the monitor steps a frame before it is
 * published). The visits that trip a rule are never held; the first visit after the trip is held and released when the
 * next frame confirms it; a pre-render visit (a healthy parse inside the window) releases nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MatchedSkeletonVisitTest : SkeletonBuilderTestBase() {

    private val rule = "doordash.screen.delivery_summary_expanded"
    private val pkg = "com.doordash.driverapp"

    private class Sink : CensusSink {
        override val isEnabled = true
        override val acceptedSchemaIds = CensusSkeletonSchema.SUPPORTED_SCHEMA_IDS.toSet()
        val records = mutableListOf<CensusRecord>()
        override fun offer(record: CensusRecord): Boolean = records.add(record)
    }

    private val redaction = object : ScreenRedactionSource {
        override fun redactFor(ruleId: String): CompiledRedact? = null
    }

    private val receipt = UiNode(
        className = "android.widget.LinearLayout",
        children = listOf(UiNode(className = "android.widget.TextView", text = "This offer")),
    ).restoreParents()
    private val home = UiNode(className = "android.widget.FrameLayout", viewIdResourceName = "com.x:id/home").restoreParents()

    private val rot = ParseShortfall(ruleId = rule, shape = "post_task", nullRequiredFields = listOf("totalPay"))

    private inner class Pipeline(scope: TestScope) {
        val sink = Sink()
        val stats = PipelineStats()
        val hold = MatchedSkeletonHold(sink, stats)
        val monitor = RecognitionHealthMonitor(
            RecognitionHealthReporter.NoOp, HealthSink.NoOp, RecognitionNoticeStore.NoOp, scope.backgroundScope,
            UnconfinedTestDispatcher(scope.testScheduler), hold,
        )
        val publisher = SkeletonPublisher(sink, stats, NoOpCensusEnvelopeSink, monitor, hold)

        fun frame(t: Long, tree: UiNode, target: String, ruleId: String?, shortfalls: List<ParseShortfall> = emptyList(), shape: String? = null) {
            val obs = Observation.Screen(
                timestamp = t, captureId = null, ruleId = ruleId, metadata = meta, flow = null, modeHint = null,
                parsed = ParsedFields.None, target = target, parseShortfalls = shortfalls, matchedShape = shape,
            )
            val boundary = publisher.clearBoundary()
            monitor.onScreenAdmitted(pkg, obs)
            publisher.publish(obs, PipelineEvent.Screen(t, tree, TreeSnapshot(tree, pkg), pkg), redaction, boundary)
        }

        fun rotVisit(t: Long) = frame(t, receipt, "delivery_summary_expanded", rule, listOf(rot), "post_task")
        fun healthy(t: Long) = frame(t, receipt, "delivery_summary_expanded", rule, shape = "post_task")
        fun benign(t: Long) = frame(t, home, "home", "doordash.screen.home")

        /** Visits 0-4, 10 s apart, each followed by a benign frame 4 s later: the fifth confirmation trips the rule. */
        fun trip() {
            for (visit in 0..4) {
                rotVisit(visit * 10_000L)
                benign(visit * 10_000L + 4_000L)
            }
            assertTrue(monitor.open.value.single().parseRot.any { it.ruleId == rule })
        }
    }

    @Test fun `the tripping visits are not held, the next visit is released once on the next frame`() = runTest {
        val p = Pipeline(this)
        p.trip()
        assertEquals("no tripping visit is held", 0L, p.stats.matchedHeldCount())
        assertTrue(p.sink.records.isEmpty())
        p.rotVisit(50_000L)
        assertEquals(1L, p.stats.matchedHeldCount())
        assertTrue("held until confirmed", p.sink.records.isEmpty())
        p.benign(54_000L)
        val record = p.sink.records.single()
        assertEquals(SkeletonSchema.SCHEMA_ID_MATCHED, CensusSkeletonSchema.deserialize(record.skeletonJson).schemaId)
        p.benign(58_000L)
        assertEquals("released once", 1, p.sink.records.size)
    }

    @Test fun `a pre-render visit releases nothing`() = runTest {
        val p = Pipeline(this)
        p.trip()
        p.rotVisit(50_000L)
        p.healthy(51_000L)
        p.benign(55_000L)
        p.benign(60_000L)
        assertEquals(1L, p.stats.matchedHeldCount())
        assertTrue(p.sink.records.isEmpty())
    }
}
