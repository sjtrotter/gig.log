package cloud.trotter.log.gig.core.pipeline.accessibility

import cloud.trotter.log.gig.domain.pipeline.PipelineRegistry
import android.view.accessibility.AccessibilityEvent
import cloud.trotter.log.gig.domain.settings.PlatformPreferences
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.pipeline.identity
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.core.pipeline.CaptureWriter
import cloud.trotter.log.gig.core.pipeline.FrameGate
import cloud.trotter.log.gig.core.pipeline.passesContentGates
import cloud.trotter.log.gig.core.pipeline.ObservationClassifier
import cloud.trotter.log.gig.core.pipeline.PipelineEvent
import cloud.trotter.log.gig.core.pipeline.PipelineStats
import cloud.trotter.log.gig.core.pipeline.ClickWindow
import cloud.trotter.log.gig.core.pipeline.RecognitionHealthMonitor
import cloud.trotter.log.gig.core.pipeline.census.ClickEvidenceArming
import cloud.trotter.log.gig.core.pipeline.census.ClickEvidenceRefusal
import cloud.trotter.log.gig.core.pipeline.census.SkeletonPublisher
import cloud.trotter.log.gig.core.pipeline.rules.RuleGeneration
import cloud.trotter.log.gig.core.pipeline.shadow.ShadowWorker
import cloud.trotter.log.gig.core.pipeline.accessibility.event.type.window.content_changed.ContentChangedPipeline
import cloud.trotter.log.gig.core.pipeline.accessibility.event.type.window.state_changed.StateChangedPipeline
import cloud.trotter.log.gig.core.pipeline.accessibility.event.type.window.windows_changed.WindowsChangedPipeline
import cloud.trotter.log.gig.core.pipeline.accessibility.input.AccessibilitySource
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import cloud.trotter.log.gig.domain.pipeline.UNKNOWN_TARGET

/**
 * Unified accessibility pipeline: merges screen and click event sources,
 * classifies through the rule engine, captures unique observations for replay,
 * and forwards only known, non-sensitive observations to the state machine.
 *
 * Flow: raw events → PipelineEvent → classify → gate sensitive → capture
 * (identity-based dedup) → gate unknown → state machine.
 */
@Singleton
class AccessibilityPipeline @Inject constructor(
    private val contentChangedPipeline: ContentChangedPipeline,
    private val stateChangedPipeline: StateChangedPipeline,
    private val windowsChangedPipeline: WindowsChangedPipeline,
    private val source: AccessibilitySource,
    private val classifier: ObservationClassifier,
    private val captureWriter: CaptureWriter,
    private val platformPreferences: PlatformPreferences,
    private val stats: PipelineStats,
    private val recognitionHealth: RecognitionHealthMonitor,
    private val skeletonPublisher: SkeletonPublisher,
    private val shadowWorker: ShadowWorker,
    /** The event clock (#1573): production binds the system clock; a replay test drives frame times through it. */
    private val clock: Clock = Clock.systemUTC(),
    /** #1634: whether a tap's window is read for click evidence; [ClickEvidenceArming.OFF] reads none. */
    private val clickArming: ClickEvidenceArming = ClickEvidenceArming.OFF,
) {
    companion object {
        const val SCREEN_PIPELINE_ID = PipelineRegistry.SCREEN_PIPELINE_ID
        const val CLICK_PIPELINE_ID = "accessibility.click"
    }

    /** Identity dedup + content-bearing UNKNOWN suppression (#360). */
    private val frameGate = FrameGate()

    // ── Source flows ────────────────────────────────────────────────────

    private fun screenEvents(): Flow<PipelineEvent.Screen> = merge(
        contentChangedPipeline.output(),
        stateChangedPipeline.output(),
        windowsChangedPipeline.output(),
    ).map { snapshot ->
        PipelineEvent.Screen(
            timestamp = clock.millis(),
            tree = snapshot.tree,
            snapshot = snapshot,
            packageName = snapshot.packageName,
        )
    }

    private fun clickEvents(): Flow<PipelineEvent.Click> = source.events
        .filter { it.type == AccessibilityEvent.TYPE_VIEW_CLICKED }
        .mapNotNull { event ->
            // #1148 review F3: the envelope carries an owned event copy; the binder fetch of the
            // clicked node happens HERE on the collector, never on the a11y callback thread.
            val ref = event.source ?: return@mapNotNull null
            try {
                val sourceNode = ref.resolve() ?: return@mapNotNull null
                // #1164: the one mapping seam — an API-mismatch LinkageError costs this click, not the flow.
                val node = source.mapNodeOrNull(sourceNode) ?: return@mapNotNull null
                PipelineEvent.Click(
                    timestamp = clock.millis(),
                    node = node,
                    packageName = event.packageName,
                    // #1634: read while the source node is still open, before the release below.
                    window = clickWindow(sourceNode, event.packageName),
                )
            } catch (e: Exception) {
                // A failed ref.resolve() must cost one click, not the whole sensing upstream (#430).
                // This catch now covers only ref.resolve(); mapping failures are counted inside
                // mapNodeOrNull.
                stats.onMappingFailure()
                Timber.w(e, "Click mapping failed — dropping click event")
                null
            } finally {
                ref.release()
            }
        }

    /**
     * #1634: the tap's own window for click evidence, or null. Unarmed, this is one StateFlow read and a counter.
     * Armed, the census clear boundary is read first, then the window ([AccessibilitySource.clickWindow]). Its own
     * catch: a failure here costs the evidence, never the click.
     */
    private fun clickWindow(sourceNode: AccessibilityNodeInfo, eventPackage: String?): ClickWindow? {
        if (!clickArming.isArmed()) {
            stats.onClickedRefused(ClickEvidenceRefusal.UNARMED)
            return null
        }
        return try {
            val boundary = skeletonPublisher.clearBoundary()
            when (val acquired = source.clickWindow(sourceNode, eventPackage, boundary)) {
                is AccessibilitySource.ClickAcquisition.Acquired -> acquired.window
                is AccessibilitySource.ClickAcquisition.Refused -> {
                    stats.onClickedRefused(acquired.reason)
                    Timber.tag("Census").d("click window refused reason=%s", acquired.reason)
                    null
                }
            }
        } catch (e: Exception) {
            onClickWindowFailed(e)
        } catch (e: LinkageError) {
            // The API-mismatch class the mapper refuses (#1164): deterministic, so it must never restart the flow.
            onClickWindowFailed(e)
        }
    }

    private fun onClickWindowFailed(t: Throwable): ClickWindow? {
        stats.onClickedRefused(ClickEvidenceRefusal.ACQUIRE_FAILED)
        Timber.tag("Census").d("click window failed (%s); click unaffected", t.javaClass.simpleName)
        return null
    }

    // ── Main pipeline ──────────────────────────────────────────────────

    fun output(): Flow<Observation> = merge(screenEvents(), clickEvents())
        // Gate: drop everything until rulesets load (#432). The sensitive gate
        // below is RULE-driven — a frame classified before rules exist would
        // bypass it straight into the UNKNOWN capture path.
        .filter { event ->
            val ready = classifier.isReady
            if (!ready) {
                stats.onDroppedAwaitingRules()
                Timber.w("Dropping %s — rulesets not loaded yet", event::class.simpleName)
            }
            ready
        }
        // Classify through the unified rule engine (typed: FlowObservation, #361). The
        // classifying generation rides along to the capture stage (#1568).
        .map { event -> classifier.classifyFrame(event) to event }

        // Gate: drop sensitive/noise observations (pledge: never store or
        // forward) — the shared content gate (#399).
        .filter { (classified, _) ->
            val obs = classified.observation
            val passes = passesContentGates(obs)
            if (!passes) stats.onContentGateDrop(obs.parsed)
            passes
        }

        // Gate: drop observations from disabled platforms (defense-in-depth)
        .filter { (_, event) ->
            val pkg = when (event) {
                is PipelineEvent.Screen -> event.packageName
                is PipelineEvent.Click -> event.packageName
                else -> null
            }
            val platform = Platform.fromPackage(pkg)
            val allowed = platform == Platform.Unknown ||
                platform in platformPreferences.enabledPlatforms.value
            if (!allowed) stats.onDisabledPlatformDrop()
            allowed
        }

        // Dedup + Capture: write unique observations to disk, skip duplicates.
        // Known screens dedup by identity; UNKNOWN frames dedup by tree/node
        // content hash in a rolling seen-set (#360) — lastIdentity semantics
        // (an UNKNOWN interlude doesn't reset known-screen dedup) are preserved
        // inside FrameGate.
        .mapNotNull { (classified, event) ->
            val obs = classified.observation
            val contentHash = when (event) {
                is PipelineEvent.Screen -> event.tree.stableHash
                is PipelineEvent.Click ->
                    // #438 item 2: the click's own per-platform screen context (set at
                    // classify time), not a global — Observation.Click carries it.
                    clickDedupHash(event.node, (obs as? Observation.Click)?.screenTarget)
                else -> null
            }
            if (!frameGate.admit(obs, contentHash)) {
                stats.onDuplicateSuppressed()
                Timber.v("Dedup: suppressed %s", obs.target)
                return@mapNotNull null
            }
            // #1573: the census clear boundary, read once per admitted screen before its capture and any build, so a
            // matched skeleton held past this frame is dropped if a census deletion runs in between.
            val censusBoundary = if (event is PipelineEvent.Screen) skeletonPublisher.clearBoundary() else 0L
            // #937 recognition-health sample, taken HERE — post-admission, pre-capture — so the
            // ratio is measured on the same deduped stream the field logs report (a dasher
            // parked on one unruled screen contributes a couple of samples, not a thousand).
            // Screens only: a click/notification carries no recognition-coverage signal about
            // the screen surface, and mixing them would dilute the denominator. Fail-open: the
            // monitor swallows everything, so a broken alarm can never cost a frame.
            if (event is PipelineEvent.Screen) {
                recognitionHealth.onScreenAdmitted(event.packageName, obs)
                // #1630 shadow seam, after the health sample so the frame's row exists first: the frame, the
                // generation that classified it (a screen's redaction source is that generation) and the live result
                // go to the shadow worker's queue. No candidate runs on this thread; the worker applies the UNKNOWN
                // sensitive backstops before any candidate sees the frame.
                shadowWorker.offer(
                    classified.redaction as? RuleGeneration,
                    Platform.fromPackage(event.packageName),
                    event.tree,
                    event.snapshot.windowContext?.windowTitle,
                    obs.timestamp,
                    obs.metadata.platformAppVersion,
                    liveWon = obs.target != UNKNOWN_TARGET,
                )
            }
            // #1036 parse-shortfall census, taken HERE for the same reason as the #937 sample
            // above: post-admission, so a dasher parked on a rotted screen contributes the frames
            // the dedup actually let through — not one per redraw — and a disabled platform
            // contributes none at all. The observation only carried the evidence here; this is
            // where it is counted and (once per rule per process) WARNed.
            (obs as? Observation.Screen)?.parseShortfalls?.forEach { stats.onParseShortfall(it) }
            // Capture via the shared writer; smart-casts replace the old
            // unchecked downcasts (#361).
            val captured = when {
                obs is Observation.Screen && event is PipelineEvent.Screen ->
                    captureWriter.captureScreen(obs, event, classified.redaction)
                obs is Observation.Click && event is PipelineEvent.Click ->
                    // #438 item 2: capture with the click's own per-platform screen context —
                    // its classification (envelope context) AND the rule that produced it
                    // (#910: the click envelope inherits that rule's `redact`, read from the
                    // generation that classified that screen, #1568).
                    captureWriter.captureClick(obs, event, obs.screenTarget, obs.screenRuleId, classified.redaction)
                else -> obs
            }
            // #1146 census publisher (ADR-0011): post-admission, AFTER the capture so the stamped captureId is
            // the trusted-install pairing key; fail-open, screens only. UNKNOWN screens go out as skeletons; a
            // screen carrying rot of a rule in an open incident is also held as that rule's matched skeleton
            // (#1573), masked with the classifying generation's redact.
            if (captured is Observation.Screen && event is PipelineEvent.Screen) {
                skeletonPublisher.publish(captured, event, classified.redaction, censusBoundary)
            }
            // #1634 click evidence: an UNKNOWN tap's own window, classified with the click's generation; fail-open.
            if (captured is Observation.Click && event is PipelineEvent.Click) {
                skeletonPublisher.publishClick(captured, event, classified.clickGeneration)
            }
            captured
        }

        // Gate: don't forward UNKNOWN observations to state machine
        .filter { obs ->
            val isUnknown = obs.target == UNKNOWN_TARGET
            if (isUnknown) {
                stats.onUnknownDropped()
                Timber.v("Unknown gate: captured but not forwarding %s", obs.target)
            }
            !isUnknown
        }
        .onEach { stats.onForwarded() }

}

/**
 * Dedup hash for a click capture that includes the current screen target.
 *
 * Same-shape buttons (e.g. DoorDash's `primary_action_button`) appear on many
 * screens with different labels and meanings. [UiNode.structuralHash] ignores
 * text, so without the screen mix all such clicks collide in the per-bucket
 * dedup set inside the disk capture bus (`DiskCaptureBus` in `:core:data`)
 * and only the first one in a session survives — silently dropping later
 * clicks the developer needs to triage screen-specific behavior.
 */
internal fun clickDedupHash(node: UiNode, screenTarget: String?): Int =
    31 * node.structuralHash + (screenTarget?.hashCode() ?: 0)
