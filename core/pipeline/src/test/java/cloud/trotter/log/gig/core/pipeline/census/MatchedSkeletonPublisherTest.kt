package cloud.trotter.log.gig.core.pipeline.census

import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.log.gig.core.pipeline.PipelineEvent
import cloud.trotter.log.gig.core.pipeline.PipelineStats
import cloud.trotter.log.gig.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.log.gig.core.pipeline.census.SkeletonBuilder.Refusal
import cloud.trotter.log.gig.core.pipeline.rules.CompiledRedact
import cloud.trotter.log.gig.core.pipeline.rules.CompiledRedactEntry
import cloud.trotter.log.gig.core.pipeline.rules.RuleGeneration
import cloud.trotter.log.gig.core.pipeline.rules.ScreenRedactionSource
import cloud.trotter.log.gig.domain.capture.CensusEnvelopeSink
import cloud.trotter.log.gig.domain.capture.CensusRecord
import cloud.trotter.log.gig.domain.capture.CensusSink
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.pipeline.ParseRotCause
import cloud.trotter.log.gig.domain.pipeline.ParseShortfall
import cloud.trotter.log.gig.domain.pipeline.RecognitionIncident
import cloud.trotter.log.gig.domain.pipeline.RecognitionIncidents
import cloud.trotter.log.gig.domain.pipeline.UNKNOWN_TARGET
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * #1573 A3 (and A10's publisher half): the matched branch of [SkeletonPublisher]. A frame carrying rot of a rule in an
 * open parse-rot incident is built as that rule's matched skeleton and held; nothing else is, and the UNKNOWN branch is
 * byte-for-byte what it was.
 */
class MatchedSkeletonPublisherTest : SkeletonBuilderTestBase() {

    private val rule = "doordash.screen.delivery_summary_expanded"
    private val timestamp = day.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()

    private class FakeSink(
        override val isEnabled: Boolean = true,
        override var acceptedSchemaIds: Set<String> = CensusSkeletonSchema.SUPPORTED_SCHEMA_IDS.toSet(),
    ) : CensusSink {
        val records = mutableListOf<CensusRecord>()
        override fun offer(record: CensusRecord): Boolean {
            records += record
            return true
        }
    }

    private class Incidents(vararg rules: String) : RecognitionIncidents {
        override val open = MutableStateFlow<List<RecognitionIncident>>(
            if (rules.isEmpty()) emptyList() else listOf(
                RecognitionIncident("doordash", openedAt = 0L, parseRot = rules.map { ParseRotCause(it, "post_task", listOf("totalPay"), 0L) }),
            ),
        )
    }

    private val noPairing = object : CensusEnvelopeSink {
        override val isEnabled = true
        override suspend fun invalidate() = Unit
        override fun hold(captureId: String, platform: Platform, envelopeJson: String) = Unit
        override fun pair(captureId: String, fingerprint: String): Boolean = throw AssertionError("a matched skeleton is never paired")
    }

    /** The rule's redact: the id-less name row. */
    private val redaction = object : ScreenRedactionSource {
        override fun redactFor(ruleId: String) =
            CompiledRedact(listOf(CompiledRedactEntry(find = { it.className == "android.widget.CheckedTextView" })))
    }

    private val tree = UiNode(
        className = "android.widget.LinearLayout",
        children = listOf(
            UiNode(className = "android.widget.TextView", text = "This offer"),
            UiNode(className = "android.widget.CheckedTextView", text = "Tobias Wren"),
        ),
    ).restoreParents()

    private fun rot(ruleId: String = rule, skipped: Boolean = false) =
        ParseShortfall(ruleId = ruleId, shape = "post_task", nullRequiredFields = listOf("totalPay"), branchSkipped = skipped)

    private fun screen(ruleId: String? = rule, shortfalls: List<ParseShortfall> = listOf(rot()), target: String = "delivery_summary_expanded") =
        Observation.Screen(
            timestamp = timestamp, captureId = null, ruleId = ruleId, metadata = meta, flow = Flow.Idle, modeHint = Mode.Online,
            parsed = ParsedFields.None, target = target, parseShortfalls = shortfalls,
        )

    private fun event() = PipelineEvent.Screen(
        timestamp = timestamp, tree = tree, snapshot = TreeSnapshot(tree = tree, packageName = "com.doordash.driverapp"),
        packageName = "com.doordash.driverapp",
    )

    private class Setup(val sink: FakeSink, val stats: PipelineStats, val hold: MatchedSkeletonHold, val publisher: SkeletonPublisher)

    private fun setup(sink: FakeSink = FakeSink(), incidents: RecognitionIncidents = Incidents(rule)): Setup {
        val stats = PipelineStats()
        val hold = MatchedSkeletonHold(sink, stats)
        return Setup(sink, stats, hold, SkeletonPublisher(sink, stats, noPairing, incidents, hold))
    }

    private fun Setup.publish(obs: Observation.Screen = screen(), source: ScreenRedactionSource = redaction) =
        publisher.publish(obs, event(), source, boundary = 0L)

    private fun Setup.release() = hold.onConfirmed("doordash", rule, timestamp, setOf(rule))

    @Test fun `a recognized frame whose rule rotted is held, then released with no capture id`() {
        val s = setup()
        s.publish()
        assertEquals(1, s.hold.size())
        assertEquals(1L, s.stats.matchedHeldCount())
        assertTrue("held, not offered", s.sink.records.isEmpty())
        s.release()
        val record = s.sink.records.single()
        assertNull(record.captureId)
        val item = CensusSkeletonSchema.deserialize(record.skeletonJson) as UiSkeletonDto
        assertEquals(SkeletonSchema.SCHEMA_ID_MATCHED, item.schemaId)
        assertEquals(rule, item.ruleId)
        assertEquals("the rule's redact is withheld", TextSlot.WITHHELD, item.root.children[1].text.getValue("text"))
        assertEquals(1L, s.stats.matchedReleasedCount())
        assertTrue(s.stats.summary().contains(" matched{held=1,released=1,dropped=0}"))
    }

    @Test fun `a healthy frame of the rule builds nothing`() {
        val s = setup()
        s.publish(screen(shortfalls = emptyList()))
        s.publish(screen(shortfalls = listOf(rot(skipped = true))))
        assertEquals(0L, s.stats.matchedHeldCount())
        Refusal.entries.forEach { assertEquals(0L, s.stats.matchedRefusedCount(it)) }
        assertTrue(s.sink.records.isEmpty())
    }

    @Test fun `rot of a rule outside the incident builds nothing`() {
        val s = setup()
        val other = "doordash.screen.dash_summary"
        s.publish(screen(ruleId = other, shortfalls = listOf(rot(other))))
        assertEquals(0L, s.stats.matchedHeldCount())
        assertEquals(0, s.hold.size())
        val none = setup(incidents = Incidents())
        none.publish()
        assertEquals(0L, none.stats.matchedHeldCount())
    }

    @Test fun `a policy or a consent without the matched schema refuses the build and leaves UNKNOWN alone`() {
        // A10: the sink leaves the matched schema out while the census consent predates its disclosure.
        val sink = FakeSink(acceptedSchemaIds = setOf(SkeletonSchema.SCHEMA_ID, SkeletonSchema.SCHEMA_ID_V2))
        val s = setup(sink)
        s.publish()
        assertEquals(1L, s.stats.matchedRefusedCount(Refusal.POLICY_UNSUPPORTED_SCHEMA))
        assertEquals(0, s.hold.size())
        s.publish(screen(ruleId = null, shortfalls = listOf(rot(skipped = true)), target = UNKNOWN_TARGET))
        val unknown = CensusSkeletonSchema.deserialize(sink.records.single().skeletonJson)
        assertEquals("UNKNOWN is unaffected", SkeletonSchema.SCHEMA_ID_V2, unknown.schemaId)
        assertEquals(0, s.hold.size())
    }

    @Test fun `a rule of another platform is refused`() {
        val foreign = "uber.screen.offer_card"
        val s = setup(incidents = Incidents(rule, foreign))
        s.publish(screen(shortfalls = listOf(rot(foreign, skipped = true))))
        assertEquals(1L, s.stats.matchedRefusedCount(Refusal.MATCHED_PLATFORM_MISMATCH))
        assertEquals(0, s.hold.size())
    }

    @Test fun `a generation without the rule refuses the build`() {
        val s = setup()
        s.publish(source = RuleGeneration.EMPTY)
        assertEquals(1L, s.stats.matchedRefusedCount(Refusal.MATCHED_RULE_NOT_IN_GENERATION))
        assertEquals(0, s.hold.size())
    }

    @Test fun `an UNKNOWN frame carrying the rule's skipped-branch rot keeps its UNKNOWN record and holds a matched one`() {
        val obs = screen(ruleId = null, shortfalls = listOf(rot(skipped = true)), target = UNKNOWN_TARGET)
        val head = FakeSink()
        SkeletonPublisher(head, PipelineStats(), noPairing).publish(obs, event())
        val s = setup()
        s.publish(obs)
        assertEquals("the UNKNOWN record is byte-identical", head.records.map { it.skeletonJson }, s.sink.records.map { it.skeletonJson })
        assertEquals(1, s.hold.size())
        s.release()
        assertEquals(SkeletonSchema.SCHEMA_ID_MATCHED, CensusSkeletonSchema.deserialize(s.sink.records.last().skeletonJson).schemaId)
    }

    @Test fun `a disabled sink builds nothing`() {
        val s = setup(FakeSink(isEnabled = false))
        s.publish()
        assertEquals(0L, s.stats.matchedHeldCount())
        Refusal.entries.forEach { assertEquals(0L, s.stats.matchedRefusedCount(it)) }
        assertTrue(s.sink.records.isEmpty())
    }

    @Test fun `a throwing sink costs the census, never the frame`() {
        val throwing = object : CensusSink {
            override val isEnabled: Boolean get() = true
            override val acceptedSchemaIds: Set<String> get() = throw IllegalStateException("boom")
            override val clearBoundary: Long get() = throw IllegalStateException("boom")
            override fun offer(record: CensusRecord): Boolean = throw IllegalStateException("boom")
        }
        val stats = PipelineStats()
        val publisher = SkeletonPublisher(throwing, stats, noPairing, Incidents(rule), MatchedSkeletonHold(throwing, stats))
        assertEquals("an unreadable boundary is one no spool has", Long.MIN_VALUE, publisher.clearBoundary())
        publisher.publish(screen(), event(), redaction, publisher.clearBoundary())
        assertTrue(stats.censusPublishFailureCount() > 0)
    }
}
