package cloud.trotter.log.gig.core.pipeline.census

import cloud.trotter.log.gig.core.pipeline.ParseRotTracker
import cloud.trotter.log.gig.core.pipeline.PipelineEvent
import cloud.trotter.log.gig.core.pipeline.PipelineStats
import cloud.trotter.log.gig.core.pipeline.passesContentGates
import cloud.trotter.log.gig.core.pipeline.rules.ParsedFieldsFactory
import cloud.trotter.log.gig.core.pipeline.rules.RuleGeneration
import cloud.trotter.log.gig.core.pipeline.rules.RuleIdentifiers
import cloud.trotter.log.gig.core.pipeline.rules.ScreenRedactionSource
import cloud.trotter.log.gig.core.pipeline.rules.TransformRegistry
import cloud.trotter.log.gig.core.pipeline.census.SkeletonBuilder.Outcome
import cloud.trotter.log.gig.domain.capture.CensusEnvelopeSink
import cloud.trotter.log.gig.domain.capture.CensusRecord
import cloud.trotter.log.gig.domain.capture.CensusSink
import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.census.contract.NotificationSkeletonDto
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.SkeletonVersion
import cloud.trotter.log.gig.domain.model.notification.RawNotificationData
import cloud.trotter.census.contract.KindClassifier
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.UiSkeletonNodeDto
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.pipeline.RecognitionIncident
import cloud.trotter.log.gig.domain.pipeline.RecognitionIncidents
import cloud.trotter.log.gig.domain.pipeline.UNKNOWN_TARGET
import cloud.trotter.log.gig.domain.pipeline.UiOwnerShape
import cloud.trotter.log.gig.domain.pipeline.actionOwner
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import timber.log.Timber

/**
 * ADR-0011 publisher (#1146): UNKNOWN branch, after `FrameGate.admit` and `captureScreen` so the
 * stamped `captureId` is the pairing key, before the terminal UNKNOWN filter.
 * Screens and notifications share one sink and server daily budget (ADR §10).
 * Clicks (#1634) go out only as click evidence ([publishClick]): an UNKNOWN tap's own window, read at click time.
 * Every build binds the HTTP spool sink behind the census consent (#1182, #1620).
 *
 * Each kind goes out in the NEWEST wire version the server's last policy lists in `acceptedSchemaIds`
 * ([SkeletonVersion.newestScreen] / [SkeletonVersion.newestNotification]; ADR-0011 §13): a server that does not
 * accept `uinode.skeleton.v2` yet keeps receiving v1, and the v2 fields leave the phone only once it does. The
 * skeleton `day` is the observation's UTC day (ADR-0011 §21), never the phone's zone.
 *
 * Matched skeletons (#1573): a screen a rule still recognizes while that rule is in an open parse-rot incident goes out
 * as `uinode.matched.v1`, tagged with the rule's id, only once the server lists that schema and the census consent was
 * given under the disclosure that names it (the sink folds both into [CensusSink.acceptedSchemaIds]). A matched frame
 * never leaves without its `ruleId` and never under a v1 or v2 schema id.
 */
@Singleton
class SkeletonPublisher @Inject constructor(
    private val sink: CensusSink,
    private val stats: PipelineStats,
    private val envelopeSink: CensusEnvelopeSink,
    /** #1573: the open recognition incidents; a matched skeleton is built only for a rule in one. */
    private val incidents: RecognitionIncidents = NO_INCIDENTS,
    /** #1573: holds a matched skeleton until its visit is confirmed. */
    private val hold: MatchedSkeletonHold = MatchedSkeletonHold(sink, stats),
) {

    /** One WARN per publisher; the publisher is a Hilt @Singleton, so per process in the app (#1171 review: an instance field, not a JVM static, keeps tests isolated). */
    private val warned = AtomicBoolean()

    /** #1573: the census clear boundary, read once per admitted frame before its capture and any build. */
    fun clearBoundary(): Long = try {
        sink.clearBoundary
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        // Fail open for the frame, closed for the census: a boundary no spool ever has drops whatever is offered with it.
        Long.MIN_VALUE
    }

    /**
     * Post-admission; fail-OPEN — nothing here may ever cost a frame (#1146). An UNKNOWN screen goes out as its skeleton
     * ([publishUnknown], unchanged); any admitted screen that carries rot of a rule in an open parse-rot incident is also
     * built as that rule's matched skeleton and held ([publishMatched], #1573). [redaction] is the generation that
     * classified the frame; [boundary] is [clearBoundary] read at the frame's admission.
     */
    fun publish(
        obs: Observation.Screen,
        event: PipelineEvent.Screen,
        redaction: ScreenRedactionSource = RuleGeneration.EMPTY,
        boundary: Long = clearBoundary(),
    ) {
        publishUnknown(obs, event)
        publishMatched(obs, event, redaction, boundary)
    }

    private fun publishUnknown(obs: Observation.Screen, event: PipelineEvent.Screen) {
        if (obs.target != UNKNOWN_TARGET) return

        try {
            // #1171 review (Astra): a sink's enablement getter is itself a seam a future binding can break;
            // it is read INSIDE the fail-open try, after the free UNKNOWN check.
            if (!sink.isEnabled) return
            val platform = Platform.fromPackage(event.packageName)
            // #1171 review: the disabled-platform gate deliberately ADMITS Platform.Unknown, but ADR-0011 scopes
            // the census to PLATFORM screens — an unattributable package is refused before any build (fail closed).
            if (platform == Platform.Unknown) {
                stats.onCensusUnattributedPlatform()
                return
            }
            val version = SkeletonVersion.newestScreen(sink.acceptedSchemaIds)
            if (version == null) {
                stats.onCensusRefused(SkeletonBuilder.Refusal.POLICY_UNSUPPORTED_SCHEMA)
                return
            }
            when (val outcome = SkeletonBuilder.outcome(
                event.tree,
                event.snapshot.windowContext?.windowTitle,
                obs.metadata,
                platform,
                utcDay(obs.timestamp),
                version,
                // #1626: words for a contributor review only while one could take this frame; held in memory only.
                collectWords = sink.collectsReviewWords,
            )) {
                is Outcome.Built -> {
                    val (hashed, withheld) = tokenCounts(outcome.skeleton)
                    stats.onCensusSkeleton(hashed, withheld)
                    val accepted = sink.offer(CensusRecord(
                        platform = platform,
                        fingerprint = outcome.skeleton.fingerprint,
                        skeletonJson = outcome.json,
                        itemBytes = outcome.itemBytes,
                        captureId = obs.captureId,
                        reviewWords = outcome.reviewWords,
                    ))
                    if (!accepted) stats.onCensusSinkRefused()
                    val captureId = obs.captureId
                    if (accepted && captureId != null && envelopeSink.isEnabled) {
                        stats.onCensusEnvelopePaired(envelopeSink.pair(captureId, outcome.skeleton.fingerprint))
                    }
                }
                is Outcome.Refused -> stats.onCensusRefused(outcome.reason)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            stats.onCensusPublishFailure()
            if (warned.compareAndSet(false, true)) {
                Timber.tag("Census").w(
                    "census publisher failed (%s); frame unaffected",
                    t.javaClass.simpleName,
                )
            }
        }
    }

    /**
     * #1573: the matched skeletons of [obs], one per rot rule of the frame ([ParseRotTracker.rotRuleIds]) that is in an
     * open parse-rot incident of the frame's platform. Cheap checks first: the platform's incident, the rule set, the
     * sink and the server policy. A built skeleton is held, never offered here; it carries no capture id, so no
     * envelope is ever paired with it, and no review words.
     */
    private fun publishMatched(
        obs: Observation.Screen,
        event: PipelineEvent.Screen,
        redaction: ScreenRedactionSource,
        boundary: Long,
    ) {
        try {
            val platform = Platform.fromPackage(event.packageName)
            if (platform == Platform.Unknown) return
            val open = openRotRules(platform.wire)
            if (open.isEmpty()) return
            val winner = obs.ruleId.takeUnless { obs.target == UNKNOWN_TARGET }
            val rules = ParseRotTracker.rotRuleIds(winner, obs.parseShortfalls).filter { it in open }
            if (rules.isEmpty()) return
            if (!sink.isEnabled) return
            if (SkeletonSchema.SCHEMA_ID_MATCHED !in sink.acceptedSchemaIds) {
                stats.onMatchedRefused(SkeletonBuilder.Refusal.POLICY_UNSUPPORTED_SCHEMA)
                return
            }
            for (ruleId in rules) {
                if (Platform.fromRuleId(ruleId) != platform) {
                    stats.onMatchedRefused(SkeletonBuilder.Refusal.MATCHED_PLATFORM_MISMATCH)
                    continue
                }
                if (redaction is RuleGeneration && redaction.screens.ruleById(ruleId) == null) {
                    stats.onMatchedRefused(SkeletonBuilder.Refusal.MATCHED_RULE_NOT_IN_GENERATION)
                    continue
                }
                when (val outcome = SkeletonBuilder.matchedOutcome(
                    event.tree,
                    event.snapshot.windowContext?.windowTitle,
                    obs.metadata,
                    platform,
                    utcDay(obs.timestamp),
                    ruleId,
                    redaction.redactFor(ruleId),
                )) {
                    is Outcome.Built -> hold.hold(
                        platform.wire,
                        ruleId,
                        obs.timestamp,
                        CensusRecord(
                            platform = platform,
                            fingerprint = outcome.skeleton.fingerprint,
                            skeletonJson = outcome.json,
                            itemBytes = outcome.itemBytes,
                            captureId = null,
                        ),
                        boundary,
                        open,
                    )
                    is Outcome.Refused -> stats.onMatchedRefused(outcome.reason)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            stats.onCensusPublishFailure()
            if (warned.compareAndSet(false, true)) {
                Timber.tag("Census").w("census publisher failed (%s); frame unaffected", t.javaClass.simpleName)
            }
        }
    }

    /** The platform's rules in an open parse-rot incident; empty when there is none. */
    private fun openRotRules(platformWire: String): Set<String> =
        incidents.open.value.firstOrNull { it.platformWire == platformWire }?.parseRot?.mapTo(HashSet()) { it.ruleId }.orEmpty()

    /**
     * #1634: the click evidence of an UNKNOWN tap (a recognized tap sends nothing): the `uinode.clicked.v1` skeleton of
     * the tap's own window ([PipelineEvent.Click.window], read at click time) with the tapped button as a position in it.
     * Called for every tap that passed the content and platform gates, BEFORE `FrameGate`: its UNKNOWN suppressor keys a
     * tap on the detached node and the cached screen target, which would keep only the first of two taps in different
     * windows or on different owners. The store's selection is order-free instead. Fail-OPEN, so nothing here can cost
     * the click.
     *
     * The window is classified with [clickGeneration] (the generation whose click rules classified the tap) through a
     * pure match: no cached screen context, frame gate, health or shadow state is read or written. A sensitive or noise
     * window sends nothing ([ClickEvidenceRefusal.BLOCKED]); a window a screen rule recognized is masked with that rule's
     * own `redact` from the same generation; an UNKNOWN window builds exactly as an UNKNOWN frame. The tapped node's
     * owner is bound by the same walk the tap executor binds with ([actionOwner], [UiOwnerShape]). The record is offered
     * with the clear boundary read before the window was, so a deletion in between drops it.
     */
    fun publishClick(obs: Observation.Click, event: PipelineEvent.Click, clickGeneration: RuleGeneration?) {
        if (obs.target != UNKNOWN_TARGET) return
        val window = event.window ?: return // The acquisition counted why.
        try {
            if (!sink.isEnabled || SkeletonSchema.SCHEMA_ID_CLICKED !in sink.acceptedSchemaIds) {
                return refuseClick(ClickEvidenceRefusal.UNARMED)
            }
            val platform = Platform.fromPackage(event.packageName)
            if (platform == Platform.Unknown) {
                stats.onCensusUnattributedPlatform()
                return
            }
            val generation = clickGeneration ?: return refuseClick(ClickEvidenceRefusal.NO_GENERATION)
            val generationId = generation.id ?: return refuseClick(ClickEvidenceRefusal.NO_GENERATION)
            if (window.boundary != clearBoundary()) return refuseClick(ClickEvidenceRefusal.STALE_BOUNDARY)
            val match = TransformRegistry.withClock(obs.timestamp) { generation.screens.matchFirst(window.tree, platform.wire) }
            if (match != null && !passesContentGates(ParsedFieldsFactory.create(match.shape, match.fields))) {
                return refuseClick(ClickEvidenceRefusal.BLOCKED)
            }
            val tapped = window.source() ?: return refuseClick(ClickEvidenceRefusal.SOURCE_GONE)
            val owner = actionOwner(tapped, UiOwnerShape) ?: return refuseClick(ClickEvidenceRefusal.NO_OWNER)
            when (val outcome = SkeletonBuilder.clickedOutcome(
                window.tree,
                window.windowTitle,
                obs.metadata,
                platform,
                utcDay(obs.timestamp),
                match?.let { generation.redactFor(it.ruleId) },
                owner,
                tapped,
                RuleIdentifiers.wire(generationId),
            )) {
                is Outcome.Built -> {
                    val offered = sink.offer(
                        CensusRecord(
                            platform = platform,
                            fingerprint = outcome.skeleton.fingerprint,
                            skeletonJson = outcome.json,
                            itemBytes = outcome.itemBytes,
                            captureId = null,
                        ),
                        window.boundary,
                    )
                    if (offered) stats.onClickedOffered() else stats.onCensusSinkRefused()
                    Timber.tag("Census").d("click evidence offered=%s", offered)
                }
                is Outcome.Refused -> when (outcome.reason) {
                    SkeletonBuilder.Refusal.SENSITIVE_FRAME, SkeletonBuilder.Refusal.SENSITIVE_TITLE ->
                        refuseClick(ClickEvidenceRefusal.SENSITIVE)
                    SkeletonBuilder.Refusal.CLICK_OWNER_WITHHELD -> refuseClick(ClickEvidenceRefusal.OWNER_WITHHELD)
                    else -> stats.onClickedBuildRefused(outcome.reason)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            stats.onCensusPublishFailure()
            if (warned.compareAndSet(false, true)) {
                Timber.tag("Census").w("census publisher failed (%s); click unaffected", t.javaClass.simpleName)
            }
        }
    }

    /** Counts [reason] and logs the decision at DEBUG (a reason, never text). */
    private fun refuseClick(reason: ClickEvidenceRefusal) {
        stats.onClickedRefused(reason)
        Timber.tag("Census").d("click evidence refused reason=%s", reason)
    }

    /** Post-admission/capture, before UNKNOWN rejection. Trusted envelope routing stays screen-only. */
    fun publish(obs: Observation.Notification, raw: RawNotificationData) {
        if (obs.target != UNKNOWN_TARGET) return
        try {
            if (!sink.isEnabled) return
            val platform = Platform.fromPackage(raw.packageName)
            if (platform == Platform.Unknown) {
                stats.onCensusUnattributedPlatform()
                return
            }
            val version = SkeletonVersion.newestNotification(sink.acceptedSchemaIds)
            if (version == null) {
                stats.onCensusNotificationRefused(NotificationSkeletonBuilder.Refusal.POLICY_UNSUPPORTED_SCHEMA)
                return
            }
            when (val outcome = NotificationSkeletonBuilder.outcome(raw, obs.metadata, platform, utcDay(obs.timestamp), version)) {
                is NotificationSkeletonBuilder.Outcome.Built -> {
                    val (hashed, withheld) = tokenCounts(outcome.skeleton)
                    stats.onCensusNotificationSkeleton(hashed, withheld)
                    if (!sink.offer(CensusRecord(
                        platform = platform,
                        fingerprint = outcome.skeleton.fingerprint,
                        skeletonJson = outcome.json,
                        itemBytes = outcome.itemBytes,
                        captureId = obs.captureId,
                        presentFields = outcome.presentFields,
                    ))) stats.onCensusSinkRefused()
                }
                is NotificationSkeletonBuilder.Outcome.Refused -> stats.onCensusNotificationRefused(outcome.reason)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            stats.onCensusPublishFailure()
            if (warned.compareAndSet(false, true)) {
                Timber.tag("Census").w("census publisher failed (%s); frame unaffected", t.javaClass.simpleName)
            }
        }
    }

    private fun utcDay(timestamp: Long): LocalDate = Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDate()

    private companion object {
        /** No incidents: a publisher built without the monitor builds no matched skeleton. */
        val NO_INCIDENTS = object : RecognitionIncidents {
            override val open: StateFlow<List<RecognitionIncident>> = MutableStateFlow(emptyList())
        }
    }
}

/** Counts every node text slot and the window title, without retaining token content (#1146). */
internal fun tokenCounts(skeleton: UiSkeletonDto): Pair<Int, Int> {
    var hashed = 0
    var withheld = 0
    fun count(slot: TextSlot) {
        if (slot.h != null) hashed++
        if (slot.kind == KindClassifier.WITHHELD) withheld++
    }
    fun walk(node: UiSkeletonNodeDto) {
        node.text.values.forEach { count(it) }
        node.children.forEach { walk(it) }
    }
    walk(skeleton.root)
    skeleton.windowTitle?.let { count(it) }
    return hashed to withheld
}

/** The five text slots and, from v2, the action slots ([CensusSkeletonSchema.slots]). */
internal fun tokenCounts(skeleton: NotificationSkeletonDto): Pair<Int, Int> {
    val slots = CensusSkeletonSchema.slots(skeleton)
    return slots.count { it.h != null } to slots.count { it.kind == KindClassifier.WITHHELD }
}
