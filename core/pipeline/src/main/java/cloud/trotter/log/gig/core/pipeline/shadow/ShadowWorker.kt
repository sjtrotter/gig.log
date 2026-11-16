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
import java.util.concurrent.ConcurrentHashMap
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
 * One dedicated background thread at minimum priority, started with the worker and kept for the process, with a
 * queue of [QUEUE_CAPACITY] frames. A frame that finds the queue full is dropped and counted ([ShadowStats.onDropped]);
 * a dropped frame changes no state, like an unevaluated one. Each frame carries [HealthSink.resetToken] as read at
 * handoff, so an outcome evaluated across a census reset is refused by the sink. On the worker, a frame whose tree or
 * window title carries a `SensitiveTextMarkers` marker is discarded before any candidate sees it (the UNKNOWN capture
 * backstops, applied whatever the live result). The rest run through [ShadowFrameRunner] on the frame's own clock, and
 * the outcomes go to [HealthSink.onShadowFrame]: ids, outcomes and the live result, never the tree.
 */
@Singleton
class ShadowWorker internal constructor(
    private val sink: HealthSink,
    private val stats: ShadowStats,
    queueCapacity: Int,
) {
    @Inject constructor(sink: HealthSink, stats: PipelineStats) : this(sink, stats.shadow, QUEUE_CAPACITY)

    private class Frame(
        val platform: Platform,
        val tree: UiNode,
        val windowTitle: String?,
        val clock: TransformRegistry.TransformClock,
        val platformAppVersion: String?,
        val liveWon: Boolean,
        val resetToken: Long,
    )

    /** Touched only by the worker thread: the runner's rotation is per-instance state. */
    private val runner = ShadowFrameRunner()

    // AbortPolicy: offer() learns that a frame was dropped, so a boundary that did not fit is sent again.
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(queueCapacity),
        { task -> Thread(task, THREAD_NAME).apply { isDaemon = true; priority = Thread.MIN_PRIORITY } },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { prestartCoreThread() }

    /**
     * Pipeline-thread state: the platforms whose last queued frame had candidates. A frame of a generation with none for
     * the platform then queues one boundary record, so the collector closes the old visits.
     */
    private val withCandidates: MutableSet<Platform> = ConcurrentHashMap.newKeySet()

    private val errorWarned = AtomicBoolean(false)

    /**
     * Queue one admitted screen frame. [generation] is the generation that classified it, [timestampMillis] its
     * classification instant, and [liveWon] whether a live rule won it (false for live UNKNOWN). Returns at once and
     * never throws. Nothing is queued when the frame has no [platformAppVersion] (its health row has no key), nor when
     * the generation has no candidate for [platform], except one boundary record after a generation that had some.
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
            val set = generation?.shadow?.takeIf { it.candidatesFor(platform).isNotEmpty() }
            if (set == null && platform !in withCandidates) return
            val clock = TransformRegistry.TransformClock(timestampMillis, ZoneId.systemDefault())
            val frame = Frame(platform, tree, windowTitle, clock, platformAppVersion, liveWon, sink.resetToken)
            executor.execute { if (set == null) boundary(frame, generation?.id) else evaluate(frame, set) }
            if (set == null) withCandidates.remove(platform) else withCandidates.add(platform)
        } catch (_: Throwable) {
            stats.onDropped()
        }
    }

    /** The worker thread is running, before any frame. */
    internal fun threadStarted(): Boolean = executor.poolSize > 0

    private fun boundary(frame: Frame, generationId: String?) {
        try {
            sink.onShadowFrame(record(frame, generationId?.let(RuleIdentifiers::wire).orEmpty(), emptyList()))
        } catch (t: Throwable) {
            failed(t)
        }
    }

    private fun evaluate(frame: Frame, set: ShadowSet) {
        try {
            if (SensitiveTextMarkers.findMarker(frame.tree) != null ||
                frame.windowTitle?.let(SensitiveTextMarkers::findMarker) != null
            ) {
                stats.onSkipped()
                return
            }
            val started = System.nanoTime()
            val evaluations = runner.evaluate(set, frame.platform, frame.tree, frame.clock)
            stats.onFrame(evaluations.map { it.outcome }, System.nanoTime() - started)
            val results = evaluations.map {
                ShadowResult(it.candidate.id, RuleIdentifiers.wire(it.candidate.digest), record(it.outcome))
            }
            sink.onShadowFrame(record(frame, RuleIdentifiers.wire(set.generationId), results))
        } catch (t: Throwable) {
            failed(t)
        }
    }

    private fun record(frame: Frame, generationId: String, results: List<ShadowResult>) = ShadowFrameRecord(
        timestampMillis = frame.clock.nowMillis,
        platformWire = frame.platform.wire,
        platformAppVersion = frame.platformAppVersion,
        generationId = generationId,
        results = results,
        liveWon = frame.liveWon,
        resetToken = frame.resetToken,
    )

    /** A diagnostic thread: a failure costs this frame's record, never recognition. */
    private fun failed(t: Throwable) {
        stats.onError()
        if (errorWarned.compareAndSet(false, true)) {
            Timber.tag(TAG).w("Shadow frame failed (%s); recognition unaffected (#1630)", t.javaClass.simpleName)
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
        private const val TAG = "Shadow"
    }
}
