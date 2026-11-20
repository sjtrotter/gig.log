package cloud.trotter.log.gig.core.pipeline.accessibility

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.log.gig.core.pipeline.CaptureWriter
import cloud.trotter.log.gig.core.pipeline.Classified
import cloud.trotter.log.gig.core.pipeline.ClickWindow
import cloud.trotter.log.gig.core.pipeline.ObservationClassifier
import cloud.trotter.log.gig.core.pipeline.PipelineEvent
import cloud.trotter.log.gig.core.pipeline.PipelineStats
import cloud.trotter.log.gig.core.pipeline.RecognitionHealthMonitor
import cloud.trotter.log.gig.core.pipeline.accessibility.event.type.window.content_changed.ContentChangedPipeline
import cloud.trotter.log.gig.core.pipeline.accessibility.event.type.window.state_changed.StateChangedPipeline
import cloud.trotter.log.gig.core.pipeline.accessibility.event.type.window.windows_changed.WindowsChangedPipeline
import cloud.trotter.log.gig.core.pipeline.accessibility.input.AccEvent
import cloud.trotter.log.gig.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.log.gig.core.pipeline.accessibility.input.SourceNodeRef
import cloud.trotter.log.gig.core.pipeline.census.ClickEvidenceArming
import cloud.trotter.log.gig.core.pipeline.census.ClickEvidenceRefusal
import cloud.trotter.log.gig.core.pipeline.census.SkeletonPublisher
import cloud.trotter.log.gig.core.pipeline.rules.RuleGeneration
import cloud.trotter.log.gig.core.pipeline.shadow.ShadowWorker
import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.log.gig.domain.capture.CensusEnvelopeSink
import cloud.trotter.log.gig.domain.capture.CensusRecord
import cloud.trotter.log.gig.domain.capture.CensusSink
import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.pipeline.UNKNOWN_TARGET
import cloud.trotter.log.gig.domain.settings.GraceConfig
import cloud.trotter.log.gig.domain.settings.PlatformPreferences
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.same
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1634 D4: the click path reads a tap's window only while click evidence is armed, reads the clear boundary first,
 * and no failure in that read costs the click. The window and the click's generation reach the publisher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ClickEvidencePipelineTest {

    private val pkg = requireNotNull(Platform.DoorDash.packageName)
    private val clickGeneration = RuleGeneration(id = "ab".repeat(32))
    private val tapped = mock<AccessibilityNodeInfo>()
    private val taps = MutableSharedFlow<AccEvent>(extraBufferCapacity = 4)

    private val source = mock<AccessibilitySource> {
        on { events } doReturn taps
        on { mapNodeOrNull(any()) } doReturn UiNode(text = "Decline")
    }
    private val publisher = mock<SkeletonPublisher> { on { clearBoundary() } doReturn 5L }
    private val stats = PipelineStats()

    private fun click() = Observation.Click(
        timestamp = 1_000L, captureId = null, ruleId = "doordash.click.decline", metadata = ReplayMetadata.EMPTY, flow = null,
        modeHint = null, parsed = ParsedFields.ClickFields(intent = "decline"), target = "decline",
    )

    /** An UNKNOWN tap; every one has the same detached node and cached screen target, so FrameGate sees one tap. */
    private fun unknownClick() = Observation.Click(
        timestamp = 1_000L, captureId = null, ruleId = null, metadata = ReplayMetadata(engineVersion = 1), flow = null,
        modeHint = null, parsed = ParsedFields.ClickFields(intent = "unknown"), target = UNKNOWN_TARGET, screenTarget = "offer_card",
    )

    private fun pipeline(
        arming: ClickEvidenceArming,
        publisher: SkeletonPublisher = this.publisher,
        observation: () -> Observation.Click = ::click,
    ): AccessibilityPipeline {
        val classifier = mock<ObservationClassifier>()
        whenever(classifier.isReady).thenReturn(true)
        whenever(classifier.classifyFrame(any<PipelineEvent>())).doAnswer { Classified(observation(), RuleGeneration.EMPTY, clickGeneration) }
        val capture = mock<CaptureWriter> {
            on { captureClick(any(), any(), anyOrNull(), anyOrNull(), any()) } doAnswer { it.arguments[0] as Observation.Click }
        }
        val preferences = object : PlatformPreferences {
            override val enabledPlatforms = MutableStateFlow(setOf(Platform.DoorDash))
            override val enabledPackages = MutableStateFlow(setOf(pkg))
            override val graceConfig = MutableStateFlow(emptyMap<Platform, GraceConfig>())
        }
        return AccessibilityPipeline(
            mock<ContentChangedPipeline> { on { output() } doAnswer { emptyFlow() } },
            mock<StateChangedPipeline> { on { output() } doAnswer { emptyFlow() } },
            mock<WindowsChangedPipeline> { on { output() } doAnswer { emptyFlow() } },
            source, classifier, capture, preferences, stats, mock<RecognitionHealthMonitor>(), publisher, mock<ShadowWorker>(),
            clickArming = arming,
        )
    }

    private fun tap(): AccEvent {
        val copy = mock<AccessibilityEvent> { on { this.source } doReturn tapped }
        return AccEvent(AccessibilityEvent.TYPE_VIEW_CLICKED, 7, pkg, "android.widget.Button", 0, 0L, SourceNodeRef(copy))
    }

    private fun armed() = ClickEvidenceArming(MutableStateFlow(true))

    @Test fun `unarmed, the window is never read and the click goes on`() = runTest {
        val forwarded = mutableListOf<Observation>()
        backgroundScope.launch { pipeline(ClickEvidenceArming.OFF).output().collect { forwarded += it } }
        runCurrent()
        taps.emit(tap()); runCurrent()
        verify(source, never()).clickWindow(any(), anyOrNull(), any())
        verify(publisher, never()).clearBoundary()
        assertEquals(1L, stats.clickedRefusedCount(ClickEvidenceRefusal.UNARMED))
        assertEquals(1, forwarded.size)
        val event = argumentCaptor<PipelineEvent.Click>()
        verify(publisher).publishClick(any(), event.capture(), same(clickGeneration))
        assertNull(event.firstValue.window)
    }

    @Test fun `armed, the boundary is read first and the window reaches the publisher`() = runTest {
        val window = ClickWindow(UiNode(text = "Panel").restoreParents(), emptyList(), null, 5L)
        whenever(source.clickWindow(same(tapped), eq(pkg), eq(5L))).thenReturn(AccessibilitySource.ClickAcquisition.Acquired(window))
        backgroundScope.launch { pipeline(armed()).output().collect {} }
        runCurrent()
        taps.emit(tap()); runCurrent()
        val event = argumentCaptor<PipelineEvent.Click>()
        verify(publisher).publishClick(any(), event.capture(), same(clickGeneration))
        assertSame(window, event.firstValue.window)
    }

    @Test fun `a refused or failed window read costs the evidence, never the click`() = runTest {
        whenever(source.clickWindow(any(), anyOrNull(), any()))
            .thenReturn(AccessibilitySource.ClickAcquisition.Refused(ClickEvidenceRefusal.SOURCE_GONE))
            .thenThrow(IllegalStateException("binder died"))
            .thenThrow(NoSuchMethodError("missing"))
        val forwarded = mutableListOf<Observation>()
        backgroundScope.launch { pipeline(armed()).output().collect { forwarded += it } }
        runCurrent()
        repeat(3) { taps.emit(tap()); runCurrent() }
        assertEquals("every click still reaches the state machine", 3, forwarded.size)
        assertEquals(1L, stats.clickedRefusedCount(ClickEvidenceRefusal.SOURCE_GONE))
        assertEquals(2L, stats.clickedRefusedCount(ClickEvidenceRefusal.ACQUIRE_FAILED))
    }

    private class RecordingSink : CensusSink {
        override val isEnabled = true
        override val acceptedSchemaIds: Set<String> = CensusSkeletonSchema.SUPPORTED_SCHEMA_IDS.toSet()
        val records = mutableListOf<String>()
        override fun offer(record: CensusRecord): Boolean = throw AssertionError("offered with its boundary")
        override fun offer(record: CensusRecord, boundary: Long): Boolean = records.add(record.skeletonJson)
    }

    private val noPairing = object : CensusEnvelopeSink {
        override val isEnabled = false
        override suspend fun invalidate() = Unit
        override fun hold(captureId: String, platform: Platform, envelopeJson: String) = Unit
        override fun pair(captureId: String, fingerprint: String): Boolean = false
    }

    /** A window with two buttons, the tap on button [owner]'s label. */
    private fun window(owner: Int): ClickWindow {
        fun button(id: String) = UiNode(className = "android.widget.Button", viewIdResourceName = "$pkg:id/$id", isClickable = true,
            children = listOf(UiNode(className = "android.widget.TextView", text = "Go")))
        val tree = UiNode(className = "android.widget.FrameLayout", children = listOf(button("first"), button("second"))).restoreParents()
        return ClickWindow(tree, listOf(owner, 0), null, boundary = 0L)
    }

    /** Two UNKNOWN taps FrameGate would collapse, one on each owner, in [order]; the evidence each one offered. */
    private suspend fun kotlinx.coroutines.test.TestScope.evidence(order: List<Int>): List<String> {
        val sink = RecordingSink()
        val real = SkeletonPublisher(sink, PipelineStats(), noPairing)
        val windows = order.map { window(it) }
        whenever(source.clickWindow(any(), anyOrNull(), any())).thenReturn(
            AccessibilitySource.ClickAcquisition.Acquired(windows[0]),
            AccessibilitySource.ClickAcquisition.Acquired(windows[1]),
        )
        val job = backgroundScope.launch { pipeline(armed(), real, ::unknownClick).output().collect {} }
        runCurrent()
        repeat(2) { taps.emit(tap()); runCurrent() }
        job.cancel()
        return sink.records
    }

    @Test fun `every UNKNOWN tap reaches the evidence store, whatever FrameGate keeps, in either order`() = runTest {
        val forward = evidence(listOf(0, 1))
        val backward = evidence(listOf(1, 0))
        assertEquals("both taps, though the gate admits one", 2, forward.size)
        assertEquals(2, forward.toSet().size)
        assertEquals("the same evidence whichever tap came first", forward.toSet(), backward.toSet())
    }
}
