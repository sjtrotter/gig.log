package cloud.trotter.log.gig.core.pipeline.shadow

import cloud.trotter.log.gig.core.pipeline.PipelineStats
import cloud.trotter.log.gig.core.pipeline.SensitiveTextMarkers
import cloud.trotter.log.gig.core.pipeline.rules.CandidateOutcome
import cloud.trotter.log.gig.core.pipeline.rules.MaskScan
import cloud.trotter.log.gig.core.pipeline.rules.RuleGeneration
import cloud.trotter.log.gig.core.pipeline.rules.RuleIdentifiers
import cloud.trotter.log.gig.core.pipeline.rules.ShadowFrameRunner
import cloud.trotter.log.gig.core.pipeline.rules.ShadowSet
import cloud.trotter.log.gig.core.pipeline.rules.TransformRegistry
import cloud.trotter.log.gig.domain.census.HealthSink
import cloud.trotter.log.gig.domain.census.MaskScanResult
import cloud.trotter.log.gig.domain.census.ShadowFrameRecord
import cloud.trotter.log.gig.domain.census.ShadowOutcome
import cloud.trotter.log.gig.domain.census.ShadowResult
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.state.Platform
import timber.log.Timber
import java.time.ZoneId
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Evaluates shadow candidates off the pipeline thread (#1630, design R6). `AccessibilityPipeline` calls [offer] for
 * each admitted screen frame; [offer] only checks that the frame's generation has candidates for its platform and
 * queues the frame, so live recognition does the work it did before.
 *
 * One dedicated background thread at minimum priority with a queue of [QUEUE_CAPACITY] frames. A frame that finds the
 * queue full is dropped and counted ([ShadowStats.onDropped]); a dropped frame changes no state, like an unevaluated
 * one. On the worker, a frame whose tree or window title carries a `SensitiveTextMarkers` marker is discarded before
 * any candidate sees it (the UNKNOWN capture backstops, applied whatever the live result). The rest run through
 * [ShadowFrameRunner] on the frame's own clock, and the outcomes go to [HealthSink.onShadowFrame]: ids, outcomes and
 * the live result, never the tree.
 */
@Singleton
class ShadowWorker internal constructor(
    private val sink: HealthSink,
    private val stats: ShadowStats,
    queueCapacity: Int,
) {
    @Inject constructor(sink: HealthSink, stats: PipelineStats) : this(sink, stats.shadow, QUEUE_CAPACITY)

    private class Frame(
        val set: ShadowSet,
        val platform: Platform,
        val tree: UiNode,
        val windowTitle: String?,
        val clock: TransformRegistry.TransformClock,
        val platformAppVersion: String?,
        val liveWon: Boolean,
    )

    /** Touched only by the worker thread: the runner's rotation is per-instance state. */
    private val runner = ShadowFrameRunner()

    private val executor = ThreadPoolExecutor(
        1, 1, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, ArrayBlockingQueue(queueCapacity),
        { task -> Thread(task, THREAD_NAME).apply { isDaemon = true; priority = Thread.MIN_PRIORITY } },
        { _, _ -> stats.onDropped() },
    ).apply { allowCoreThreadTimeOut(true) }

    private val errorWarned = AtomicBoolean(false)

    /**
     * Queue one admitted screen frame. [generation] is the generation that classified it, [timestampMillis] its
     * classification instant, and [liveWon] whether a live rule won it (false for live UNKNOWN). Returns at once and
     * never throws. Nothing is queued when the generation has no candidate for [platform], or when the frame has no
     * [platformAppVersion] (its health row has no key).
     */
    fun offer(
        generation: RuleGeneration?,
        platform: Platform,
        tree: UiNode,
        windowTitle: String?,
        timestampMillis: Long,
        platformAppVersion: String?,
        liveWon: Boolean,
    ) {
        try {
            if (platformAppVersion == null) return
            val set = generation?.shadow ?: return
            if (set.candidatesFor(platform).isEmpty()) return
            val clock = TransformRegistry.TransformClock(timestampMillis, ZoneId.systemDefault())
            val frame = Frame(set, platform, tree, windowTitle, clock, platformAppVersion, liveWon)
            executor.execute { evaluate(frame) }
        } catch (_: Throwable) {
            stats.onDropped()
        }
    }

    private fun evaluate(frame: Frame) {
        try {
            if (SensitiveTextMarkers.findMarker(frame.tree) != null ||
                frame.windowTitle?.let(SensitiveTextMarkers::findMarker) != null
            ) {
                stats.onSkipped()
                return
            }
            val started = System.nanoTime()
            val evaluations = runner.evaluate(frame.set, frame.platform, frame.tree, frame.clock)
            stats.onFrame(evaluations.map { it.outcome }, System.nanoTime() - started)
            sink.onShadowFrame(
                ShadowFrameRecord(
                    timestampMillis = frame.clock.nowMillis,
                    platformWire = frame.platform.wire,
                    platformAppVersion = frame.platformAppVersion,
                    generationId = RuleIdentifiers.wire(frame.set.generationId),
                    results = evaluations.map {
                        ShadowResult(it.candidate.id, RuleIdentifiers.wire(it.candidate.digest), record(it.outcome))
                    },
                    liveWon = frame.liveWon,
                ),
            )
        } catch (t: Throwable) {
            // A diagnostic thread: a failure costs this frame's record, never recognition.
            stats.onError()
            if (errorWarned.compareAndSet(false, true)) {
                Timber.tag(TAG).w("Shadow frame failed (%s); recognition unaffected (#1630)", t.javaClass.simpleName)
            }
        }
    }

    private fun record(outcome: CandidateOutcome): ShadowOutcome = when (outcome) {
        CandidateOutcome.NoMatch -> ShadowOutcome.NoMatch
        is CandidateOutcome.Unevaluated -> ShadowOutcome.Unevaluated
        is CandidateOutcome.Matched -> ShadowOutcome.Matched(
            outcome.requiredOk,
            when (outcome.maskScan) {
                MaskScan.CLEAN -> MaskScanResult.CLEAN
                MaskScan.HIT -> MaskScanResult.HIT
                MaskScan.INCOMPLETE -> MaskScanResult.INCOMPLETE
            },
        )
    }

    companion object {
        /** Frames waiting behind the one in evaluation; a frame past them is dropped. */
        const val QUEUE_CAPACITY = 4
        const val THREAD_NAME = "gig-shadow"
        private const val KEEP_ALIVE_SECONDS = 30L
        private const val TAG = "Shadow"
    }
}
