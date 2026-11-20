package cloud.trotter.log.gig.core.pipeline

import android.content.Context
import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.WireTokenGrammars
import cloud.trotter.log.gig.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.log.gig.core.pipeline.census.ClickEvidenceRefusal
import cloud.trotter.log.gig.core.pipeline.census.SkeletonPublisher
import cloud.trotter.log.gig.core.pipeline.rules.JsonRuleInterpreter
import cloud.trotter.log.gig.core.pipeline.rules.RuleIdentifiers
import cloud.trotter.log.gig.core.pipeline.rules.TestBundledFloor
import cloud.trotter.log.gig.core.pipeline.rules.TestRulesFeed
import cloud.trotter.log.gig.domain.capability.RuleCapabilityGrants
import cloud.trotter.log.gig.domain.capture.CensusEnvelopeSink
import cloud.trotter.log.gig.domain.capture.CensusRecord
import cloud.trotter.log.gig.domain.capture.CensusSink
import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.capture.ReplayMetadataProvider
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.pipeline.UNKNOWN_TARGET
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * #1634 (D5, §6): click evidence is classified with the generation whose click rules classified the tap, read once, and
 * names that generation by its 16-hex wire id. A swap between the last screen and the tap cannot mix two generations.
 */
class ClickEvidenceGenerationTest {

    private val pkg = "com.doordash.driverapp"
    private val interpreter = JsonRuleInterpreter(mock<Context>(), mock<RuleCapabilityGrants>())
    private val classifier = ObservationClassifier(
        interpreter,
        object : ReplayMetadataProvider { override fun current() = ReplayMetadata(engineVersion = 1) },
        PlatformAppVersions.NONE,
    )

    private class Sink : CensusSink {
        override val isEnabled = true
        override val acceptedSchemaIds: Set<String> = CensusSkeletonSchema.SUPPORTED_SCHEMA_IDS.toSet()
        val records = mutableListOf<CensusRecord>()
        override fun offer(record: CensusRecord): Boolean = throw AssertionError("offered with its boundary")
        override fun offer(record: CensusRecord, boundary: Long): Boolean = records.add(record)
    }

    private val noPairing = object : CensusEnvelopeSink {
        override val isEnabled = false
        override suspend fun invalidate() = Unit
        override fun hold(captureId: String, platform: Platform, envelopeJson: String) = Unit
        override fun pair(captureId: String, fingerprint: String): Boolean = false
    }

    /** Generation A blocks the "Quiet Panel" screen; generation B does not know it. Neither has a click rule for it. */
    private fun generation(blocksQuietPanel: Boolean): String {
        val blocked = if (blocksQuietPanel) """,
            { "id": "doordash.screen.quiet_panel", "priority": 1,
              "parse": { "as": "sensitive" }, "require": { "exists": { "hasText": "Quiet Panel" } } }""" else ""
        return """
        {
          "format_version": 2,
          "platform_id": "doordash.driver",
          "screens": [
            { "id": "doordash.screen.sensitive.known", "priority": 0, "overrideable": false,
              "parse": { "as": "sensitive" }, "require": { "exists": { "hasText": "Available Balance" } } }$blocked,
            { "id": "doordash.screen.order_card", "priority": 10, "require": { "exists": { "hasText": "Order card" } } }
          ],
          "clicks": [
            { "id": "doordash.click.order_note", "priority": 10, "intent": "order_note_tap",
              "require": { "hasIdSuffix": "order_note" } }
          ]
        }
        """.trimIndent()
    }

    private suspend fun goLive(blocksQuietPanel: Boolean) {
        if (!interpreter.isLoaded) interpreter.loadBundled(listOf(TestBundledFloor.uber))
        interpreter.load(TestRulesFeed.verified(generation(blocksQuietPanel)))
    }

    private val label = UiNode(className = "android.widget.TextView", text = "Keep going")
    private val tree = UiNode(
        className = "android.widget.FrameLayout",
        children = listOf(
            UiNode(className = "android.widget.TextView", text = "Quiet Panel"),
            UiNode(className = "android.widget.Button", viewIdResourceName = "$pkg:id/go", isClickable = true, children = listOf(label)),
        ),
    ).restoreParents()

    private fun clickEvent() = PipelineEvent.Click(
        timestamp = 2_000L, node = label, packageName = pkg, window = ClickWindow(tree, listOf(1, 0), null, boundary = 0L),
    )

    private fun publish(sink: Sink, stats: PipelineStats): CensusRecord? {
        val classified = classifier.classifyFrame(clickEvent())
        assertEquals(UNKNOWN_TARGET, classified.observation.target)
        SkeletonPublisher(sink, stats, noPairing).publishClick(classified.observation, clickEvent(), classified.clickGeneration)
        return sink.records.singleOrNull()
    }

    @Test fun `the click carries the generation read for its own classification`() = runTest {
        goLive(blocksQuietPanel = false)
        val classified = classifier.classifyFrame(clickEvent())
        assertSame(interpreter.generation, classified.clickGeneration)
    }

    @Test fun `a swap after the last screen classifies the tap's window with the click's generation`() = runTest {
        // The last screen is classified under A, which blocks the Quiet Panel; B goes live before the tap.
        goLive(blocksQuietPanel = true)
        classifier.classifyFrame(PipelineEvent.Screen(1_000L, tree, TreeSnapshot(tree = tree, packageName = pkg), pkg))
        val a = requireNotNull(interpreter.generation?.id)
        goLive(blocksQuietPanel = false)
        val b = requireNotNull(interpreter.generation?.id)
        assertNotEquals(a, b)

        val record = requireNotNull(publish(Sink(), PipelineStats()))
        val item = CensusSkeletonSchema.deserialize(record.skeletonJson) as UiSkeletonDto
        assertEquals("the click's generation, not the last screen's", RuleIdentifiers.wire(b), item.tap!!.generation)
    }

    @Test fun `under a generation that blocks the window, the tap sends nothing`() = runTest {
        goLive(blocksQuietPanel = false)
        goLive(blocksQuietPanel = true)
        val stats = PipelineStats()
        val sink = Sink()
        publish(sink, stats)
        assertTrue(sink.records.isEmpty())
        assertEquals(1L, stats.clickedRefusedCount(ClickEvidenceRefusal.BLOCKED))
    }

    @Test fun `a real assembled generation's wire id passes the contract grammar`() = runTest {
        goLive(blocksQuietPanel = false)
        val id = requireNotNull(interpreter.generation?.id)
        assertTrue(WireTokenGrammars.shortDigest.matches(RuleIdentifiers.wire(id)))
    }
}
