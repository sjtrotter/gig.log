package cloud.trotter.log.gig.core.pipeline.shadow

import cloud.trotter.log.gig.core.pipeline.CaptureWriter
import cloud.trotter.log.gig.core.pipeline.Classified
import cloud.trotter.log.gig.core.pipeline.ObservationClassifier
import cloud.trotter.log.gig.core.pipeline.PipelineEvent
import cloud.trotter.log.gig.core.pipeline.PipelineStats
import cloud.trotter.log.gig.core.pipeline.RecognitionHealthMonitor
import cloud.trotter.log.gig.core.pipeline.accessibility.AccessibilityPipeline
import cloud.trotter.log.gig.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.log.gig.core.pipeline.accessibility.event.type.window.content_changed.ContentChangedPipeline
import cloud.trotter.log.gig.core.pipeline.accessibility.event.type.window.state_changed.StateChangedPipeline
import cloud.trotter.log.gig.core.pipeline.accessibility.event.type.window.windows_changed.WindowsChangedPipeline
import cloud.trotter.log.gig.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.log.gig.core.pipeline.census.SkeletonPublisher
import cloud.trotter.log.gig.core.pipeline.rules.RuleGeneration
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.same
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * #1630 seam: the pipeline hands the shadow worker only frames that passed the rules-loaded gate, the sensitive and
 * noise gate, the platform gate and `FrameGate`, with the generation that classified each and its live result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccessibilityPipelineShadowSeamTest {
    private val doordash = requireNotNull(Platform.DoorDash.packageName)
    private val uber = requireNotNull(Platform.Uber.packageName)
    private val generation = RuleGeneration()

    private fun tree(label: String) = UiNode(text = label).restoreParents()

    private fun observation(label: String, parsed: ParsedFields = ParsedFields.None, target: String = label) = Observation.Screen(
        timestamp = 1_000L, captureId = null, ruleId = "doordash.screen.$label".takeUnless { target == UNKNOWN_TARGET },
        metadata = ReplayMetadata.EMPTY, flow = null, modeHint = null, parsed = parsed, target = target,
    )

    @Test fun `only admitted frames of an enabled platform reach the worker, with their live result`() = runTest {
        val frames = listOf(
            "before_load" to doordash,
            "sensitive" to doordash,
            "noise" to doordash,
            "live" to doordash,
            "live" to doordash, // the same screen again: FrameGate drops it
            "unknown" to doordash,
            "other_platform" to uber,
        )
        val content = mock<ContentChangedPipeline> {
            on { output() } doAnswer { flowOf(*frames.map { (label, pkg) -> TreeSnapshot(tree(label), pkg) }.toTypedArray()) }
        }
        val state = mock<StateChangedPipeline> { on { output() } doAnswer { emptyFlow() } }
        val windows = mock<WindowsChangedPipeline> { on { output() } doAnswer { emptyFlow() } }
        val source = mock<AccessibilitySource> { on { events } doAnswer { MutableSharedFlow() } }
        val classifier = mock<ObservationClassifier>()
        whenever(classifier.isReady).thenReturn(false, true)
        whenever(classifier.classifyFrame(any<PipelineEvent>())).doAnswer { invocation ->
            val event = invocation.arguments[0] as PipelineEvent.Screen
            val obs = when (val label = event.tree.text!!) {
                "sensitive" -> observation(label, ParsedFields.SensitiveFields())
                "noise" -> observation(label, ParsedFields.NoiseFields())
                "unknown" -> observation(label, target = UNKNOWN_TARGET)
                else -> observation(label)
            }
            Classified(obs, generation)
        }
        val capture = mock<CaptureWriter> { on { captureScreen(any(), any(), any()) } doAnswer { it.arguments[0] as Observation.Screen } }
        val preferences = object : PlatformPreferences {
            override val enabledPlatforms = MutableStateFlow(setOf(Platform.DoorDash))
            override val enabledPackages = MutableStateFlow(setOf(doordash))
            override val graceConfig = MutableStateFlow(emptyMap<Platform, GraceConfig>())
        }
        val worker = mock<ShadowWorker>()
        val pipeline = AccessibilityPipeline(
            content, state, windows, source, classifier, capture, preferences, PipelineStats(),
            mock<RecognitionHealthMonitor>(), mock<SkeletonPublisher>(), worker,
        )

        backgroundScope.launch { pipeline.output().collect {} }
        runCurrent()

        val trees = argumentCaptor<UiNode>()
        val liveWon = argumentCaptor<Boolean>()
        verify(worker, times(2)).offer(
            same(generation), eq(Platform.DoorDash), trees.capture(), anyOrNull(), eq(1_000L), anyOrNull(), liveWon.capture(),
        )
        assertEquals(listOf("live", "unknown"), trees.allValues.map { it.text })
        assertEquals(listOf(true, false), liveWon.allValues)
    }
}
