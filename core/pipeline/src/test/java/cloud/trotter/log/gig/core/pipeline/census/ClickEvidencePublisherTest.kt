package cloud.trotter.log.gig.core.pipeline.census

import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.census.contract.NodePath
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.UiSkeletonNodeDto
import cloud.trotter.log.gig.core.pipeline.ClickWindow
import cloud.trotter.log.gig.core.pipeline.PipelineEvent
import cloud.trotter.log.gig.core.pipeline.PipelineStats
import cloud.trotter.log.gig.core.pipeline.census.SkeletonBuilder.Outcome
import cloud.trotter.log.gig.core.pipeline.census.SkeletonBuilder.Refusal
import cloud.trotter.log.gig.core.pipeline.rules.RuleCompiler
import cloud.trotter.log.gig.core.pipeline.rules.RuleContext
import cloud.trotter.log.gig.core.pipeline.rules.RuleGeneration
import cloud.trotter.log.gig.core.pipeline.rules.Ruleset
import cloud.trotter.log.gig.domain.capture.CensusEnvelopeSink
import cloud.trotter.log.gig.domain.capture.CensusRecord
import cloud.trotter.log.gig.domain.capture.CensusSink
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.pipeline.UNKNOWN_TARGET
import cloud.trotter.log.gig.domain.pipeline.UiOwnerShape
import cloud.trotter.log.gig.domain.pipeline.actionOwner
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * #1634 (D3, D5, D6, §6): the click evidence branch of [SkeletonPublisher] and [SkeletonBuilder.clickedOutcome]. An
 * UNKNOWN tap yields one `uinode.clicked.v1` record of its own window, classified with the click's generation; nothing
 * else does, and no failure here can cost the click. Synthetic trees only.
 */
class ClickEvidencePublisherTest : SkeletonBuilderTestBase() {

    private val pkg = "com.doordash.driverapp"
    private val timestamp = day.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
    private val generationId = "0123456789abcdef".repeat(4)

    private class FakeSink : CensusSink {
        override var isEnabled = true
        override var acceptedSchemaIds: Set<String> = CensusSkeletonSchema.SUPPORTED_SCHEMA_IDS.toSet()
        override var clearBoundary: Long = 4L
        var failOffer = false
        val records = mutableListOf<Pair<CensusRecord, Long>>()
        override fun offer(record: CensusRecord): Boolean = throw AssertionError("click evidence is offered with its boundary")
        override fun offer(record: CensusRecord, boundary: Long): Boolean {
            if (failOffer) throw IllegalStateException("sink broke")
            records += record to boundary
            return true
        }
    }

    private val noPairing = object : CensusEnvelopeSink {
        override val isEnabled = true
        override suspend fun invalidate() = Unit
        override fun hold(captureId: String, platform: Platform, envelopeJson: String) = Unit
        override fun pair(captureId: String, fingerprint: String): Boolean = throw AssertionError("click evidence is never paired")
    }

    private fun screens(vararg rules: String): Ruleset<UiNode> =
        Ruleset(RuleCompiler.compileRules(Json.parseToJsonElement("[${rules.joinToString(",")}]").jsonArray, RuleContext.SCREEN))

    private val sensitiveRule = """{ "id": "doordash.screen.sensitive.known", "priority": 0, "overrideable": false,
        "parse": { "as": "sensitive" }, "require": { "exists": { "hasText": "Quiet Panel" } } }"""
    private val noiseRule = """{ "id": "doordash.screen.noise.sheet", "priority": 5,
        "parse": { "as": "noise" }, "require": { "exists": { "hasText": "Quiet Panel" } } }"""
    private val offerRule = """{ "id": "doordash.screen.offer_card", "priority": 10,
        "require": { "exists": { "hasText": "New order" } },
        "redact": [ { "find": { "hasIdSuffix": "store_line" } } ] }"""

    private fun generation(vararg rules: String, id: String? = generationId) = RuleGeneration(screens = screens(*rules), id = id)

    /** A window: a header, an optional extra line, and the Decline button (clickable) whose label was tapped. */
    private fun window(header: String = "New order", extra: UiNode? = null): UiNode = UiNode(
        className = "android.widget.FrameLayout",
        children = listOfNotNull(
            UiNode(className = "android.widget.TextView", text = header),
            UiNode(
                className = "android.widget.Button", viewIdResourceName = "$pkg:id/decline", isClickable = true, isEnabled = true,
                children = listOf(
                    UiNode(className = "android.widget.TextView", text = "Decline offer", contentDescription = "Decline this offer now",
                        hintText = "Hold to decline quickly"),
                ),
            ),
            extra,
        ),
    ).restoreParents()

    private fun click(target: String = UNKNOWN_TARGET) = Observation.Click(
        timestamp = timestamp, captureId = null, ruleId = null, metadata = meta, flow = null, modeHint = null,
        parsed = ParsedFields.ClickFields(intent = "unknown"), target = target, screenTarget = "offer_card",
    )

    private fun event(tree: UiNode, path: List<Int> = listOf(1, 0), boundary: Long = 4L) = PipelineEvent.Click(
        timestamp = timestamp, node = UiNode(text = "Decline offer"), packageName = pkg,
        window = ClickWindow(tree, path, windowTitle = null, boundary = boundary),
    )

    private class Setup(val sink: FakeSink, val stats: PipelineStats, val publisher: SkeletonPublisher)

    private fun setup(sink: FakeSink = FakeSink()): Setup {
        val stats = PipelineStats()
        return Setup(sink, stats, SkeletonPublisher(sink, stats, noPairing))
    }

    private fun node(root: UiSkeletonNodeDto, path: String): UiSkeletonNodeDto =
        requireNotNull(NodePath.parse(path)).fold(root) { node, step -> node.children[step] }

    @Test fun `an UNKNOWN tap yields exactly one clicked record whose owner path names the bound owner`() {
        val s = setup()
        val tree = window()
        val event = event(tree)
        s.publisher.publishClick(click(), event, generation(sensitiveRule))

        val (record, boundary) = s.sink.records.single()
        assertEquals("offered with the boundary read before the window", 4L, boundary)
        assertNull("no capture is paired with click evidence", record.captureId)
        val item = CensusSkeletonSchema.deserialize(record.skeletonJson) as UiSkeletonDto
        assertEquals(SkeletonSchema.SCHEMA_ID_CLICKED, item.schemaId)
        assertNull(item.ruleId)
        val tap = requireNotNull(item.tap)
        assertEquals("the 16-hex wire form of the click's generation", generationId.take(16), tap.generation)
        val owner = requireNotNull(actionOwner(event.window!!.source(), UiOwnerShape))
        assertSame(tree.children[1], owner)
        assertEquals("root/1", tap.ownerPath)
        assertEquals("root/1/0", tap.tapPath)
        assertEquals("$pkg:id/decline", node(item.root, tap.ownerPath).id)
        assertTrue(node(item.root, tap.ownerPath).isClickable)
        assertEquals(1L, s.stats.clickedOfferedCount())
    }

    @Test fun `the record carries none of the tapped node's or its owner's words`() {
        val s = setup()
        s.publisher.publishClick(click(), event(window()), generation())
        val json = s.sink.records.single().first.skeletonJson
        for (plain in listOf("Decline offer", "Decline this offer now", "Hold to decline quickly", "Decline", "decline quickly", "New order")) {
            assertFalse("'$plain' left the phone", json.contains(plain))
        }
    }

    @Test fun `a tap on the owner itself carries no tap path`() {
        val s = setup()
        s.publisher.publishClick(click(), event(window(), path = listOf(1)), generation())
        val tap = (CensusSkeletonSchema.deserialize(s.sink.records.single().first.skeletonJson) as UiSkeletonDto).tap!!
        assertEquals("root/1", tap.ownerPath)
        assertNull(tap.tapPath)
    }

    @Test fun `a recognized tap yields nothing`() {
        val s = setup()
        s.publisher.publishClick(click(target = "decline_offer"), event(window()), generation())
        assertTrue(s.sink.records.isEmpty())
        assertEquals(0L, s.stats.clickedOfferedCount())
    }

    @Test fun `no window, no evidence`() {
        val s = setup()
        s.publisher.publishClick(click(), event(window()).copy(window = null), generation())
        assertTrue(s.sink.records.isEmpty())
    }

    @Test fun `a sensitive window sends nothing, whatever the previous screen was`() {
        val s = setup()
        // The click's cached screen context says "offer_card" (benign); its own window is the blocked screen.
        s.publisher.publishClick(click(), event(window(header = "Quiet Panel")), generation(sensitiveRule))
        assertTrue(s.sink.records.isEmpty())
        assertEquals(1L, s.stats.clickedRefusedCount(ClickEvidenceRefusal.BLOCKED))
    }

    @Test fun `a noise window sends nothing`() {
        val s = setup()
        s.publisher.publishClick(click(), event(window(header = "Quiet Panel")), generation(noiseRule))
        assertTrue(s.sink.records.isEmpty())
        assertEquals(1L, s.stats.clickedRefusedCount(ClickEvidenceRefusal.BLOCKED))
    }

    @Test fun `the builder's own sensitive scan still refuses a window no rule blocks`() {
        val s = setup()
        val banking = window(header = "Transfer out", extra = UiNode(className = "android.widget.TextView", text = "\$45.66 available"))
        s.publisher.publishClick(click(), event(banking), generation())
        assertTrue(s.sink.records.isEmpty())
        assertEquals(1L, s.stats.clickedRefusedCount(ClickEvidenceRefusal.SENSITIVE))
    }

    @Test fun `a window a screen rule recognized is masked with that rule's redact`() {
        val s = setup()
        val store = UiNode(className = "android.widget.TextView", viewIdResourceName = "$pkg:id/store_line", text = "Corner Bakery")
        s.publisher.publishClick(click(), event(window(extra = store)), generation(offerRule))
        val item = CensusSkeletonSchema.deserialize(s.sink.records.single().first.skeletonJson) as UiSkeletonDto
        assertEquals("withheld", item.root.children[2].text.getValue("text").kind)
    }

    @Test fun `no generation, or one without an id, sends nothing`() {
        val s = setup()
        s.publisher.publishClick(click(), event(window()), null)
        s.publisher.publishClick(click(), event(window()), generation(id = null))
        assertTrue(s.sink.records.isEmpty())
        assertEquals(2L, s.stats.clickedRefusedCount(ClickEvidenceRefusal.NO_GENERATION))
    }

    @Test fun `a window read before a census deletion sends nothing`() {
        val s = setup()
        s.publisher.publishClick(click(), event(window(), boundary = 3L), generation())
        assertTrue(s.sink.records.isEmpty())
        assertEquals(1L, s.stats.clickedRefusedCount(ClickEvidenceRefusal.STALE_BOUNDARY))
    }

    @Test fun `a sink that no longer lists the clicked schema sends nothing`() {
        val sink = FakeSink().apply { acceptedSchemaIds = acceptedSchemaIds - SkeletonSchema.SCHEMA_ID_CLICKED }
        val s = setup(sink)
        s.publisher.publishClick(click(), event(window()), generation())
        assertTrue(sink.records.isEmpty())
        assertEquals(1L, s.stats.clickedRefusedCount(ClickEvidenceRefusal.UNARMED))
    }

    @Test fun `a source path off the window, or a tap with no owner, sends nothing`() {
        val s = setup()
        s.publisher.publishClick(click(), event(window(), path = listOf(5)), generation())
        s.publisher.publishClick(click(), event(window(), path = listOf(0)), generation())
        assertTrue(s.sink.records.isEmpty())
        assertEquals(1L, s.stats.clickedRefusedCount(ClickEvidenceRefusal.SOURCE_GONE))
        assertEquals(1L, s.stats.clickedRefusedCount(ClickEvidenceRefusal.NO_OWNER))
    }

    @Test fun `a census failure never escapes into the click`() {
        val sink = FakeSink().apply { failOffer = true }
        val s = setup(sink)
        s.publisher.publishClick(click(), event(window()), generation())
        assertEquals(1L, s.stats.censusPublishFailureCount())
    }

    @Test fun `the builder refuses an owner that is not a node of the tree`() {
        val tree = window()
        val stranger = UiNode(className = "android.widget.Button", isClickable = true)
        val out = SkeletonBuilder.clickedOutcome(tree, null, meta, platform, day, null, stranger, stranger, generationId.take(16))
        assertEquals(Outcome.Refused(Refusal.CLICK_OWNER_WITHHELD), out)
    }

    @Test fun `a clicked build never projects to v1 and collects no review words`() {
        val tree = window()
        val out = SkeletonBuilder.clickedOutcome(
            tree, null, meta, platform, day, null, tree.children[1], tree.children[1].children[0], generationId.take(16),
        ) as Outcome.Built
        assertEquals(SkeletonSchema.SCHEMA_ID_CLICKED, out.skeleton.schemaId)
        assertNull(out.reviewWords)
        assertEquals(SkeletonSchema.serialize(out.skeleton), out.json)
    }
}
