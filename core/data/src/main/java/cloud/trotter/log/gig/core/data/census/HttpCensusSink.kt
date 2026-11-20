package cloud.trotter.log.gig.core.data.census

import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.log.gig.domain.capture.CensusRecord
import cloud.trotter.log.gig.domain.capture.CensusSink
import cloud.trotter.log.gig.domain.census.CensusSampleKey
import cloud.trotter.log.gig.domain.census.CensusUploadPreferences
import cloud.trotter.log.gig.domain.census.CensusUploadStats
import cloud.trotter.log.gig.domain.census.ClickEvidence
import cloud.trotter.log.gig.domain.census.MatchedSkeletons
import cloud.trotter.log.gig.domain.census.contributor.ContributorReviewIntake
import cloud.trotter.log.gig.domain.di.ApplicationScope
import cloud.trotter.log.gig.domain.di.IoDispatcher
import cloud.trotter.log.gig.domain.di.ReadsUnboundPlatforms
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The census sink of every build (#1620), behind the census consent. The pipeline collector only reads a snapshot and
 * calls trySend; one IO consumer queues a record only for a platform the dasher's choices cover, and only the day's
 * first skeleton of its variant ([CensusDailySample]). Queueing never asks for an upload: the daily window does.
 *
 * #1573: a matched skeleton also passes the daily matched limits ([CensusMatchedCap]), after the sample so a limit slot
 * is never spent on an item the sample drops. The matched schema is offered to the publisher only while the census
 * consent was given under a disclosure that names it ([MatchedSkeletons.MIN_DISCLOSURE_REVISION]).
 *
 * #1634: a clicked skeleton is offered only under the disclosure that names taps ([ClickEvidence.MIN_DISCLOSURE_REVISION])
 * and never enters the daily sample, the matched limits or the spool: it goes to the click evidence store
 * ([CensusClickCandidates]), whose selection does not depend on arrival order. A first-come sample would make the kept
 * tap depend on which came first.
 */
@Singleton
class HttpCensusSink @Inject constructor(
    private val preferences: CensusUploadPreferences,
    private val spool: CensusSpool,
    private val sample: CensusDailySample,
    private val stats: CensusUploadStats,
    @ApplicationScope scope: CoroutineScope,
    @IoDispatcher io: CoroutineDispatcher,
    @ReadsUnboundPlatforms readsUnboundPlatforms: Boolean,
    private val matchedCap: CensusMatchedCap,
    /** #1634: the click evidence store; clicked skeletons go here instead of the spool. */
    private val clicks: CensusClickCandidates,
    /** #1626: takes a spooled screen as a review candidate; it checks the contributor gate itself. */
    private val reviews: ContributorReviewIntake = ContributorReviewIntake.NONE,
) : CensusSink {
    private val readable = Platform.readable(readsUnboundPlatforms)
    private val enabled = preferences.enabled.stateIn(scope, SharingStarted.Eagerly, false)
    private val schemas = preferences.acceptedSchemaIds.stateIn(scope, SharingStarted.Eagerly, null)
    private val revision = preferences.consentRevision.stateIn(scope, SharingStarted.Eagerly, null)
    // Wait for the persisted policy snapshot before enabling publication after process restart.
    override val isEnabled: Boolean get() = enabled.value && schemas.value != null
    override val acceptedSchemaIds: Set<String> get() = consented(schemas.value.orEmpty(), revision.value)
    override val clickEvidenceArmed: StateFlow<Boolean> =
        combine(preferences.enabled, preferences.acceptedSchemaIds, preferences.consentRevision) { on, ids, revision ->
            on && SkeletonSchema.SCHEMA_ID_CLICKED in consented(ids, revision)
        }.stateIn(scope, SharingStarted.Eagerly, false)
    override val clearBoundary: Long get() = spool.generation
    override val collectsReviewWords: Boolean get() = isEnabled && reviews.wantsCandidates
    /** A record with the spool's clear boundary at the moment it was offered (#1622). */
    private class Offered(val record: CensusRecord, val generation: Long)

    private val channel = Channel<Offered>(
        capacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { stats.spoolDropped.incrementAndGet() },
    )

    init {
        scope.launch(io) {
            try {
                for (offered in channel) {
                    val record = offered.record
                    if (!isEnabled) {
                        stats.spoolDropped.incrementAndGet()
                        continue
                    }
                    try {
                        if (record.platform !in preferences.choices.first().platformsAmong(readable)) {
                            stats.platformNotChosen.incrementAndGet()
                            continue
                        }
                        // #1573: a record taken before a clear is dropped before it can spend the day's sample or
                        // matched quota; the spool checks the boundary again under its lock.
                        if (offered.generation != spool.generation) {
                            stats.spoolDropped.incrementAndGet()
                            continue
                        }
                        val item = CensusSkeletonSchema.deserialize(record.skeletonJson)
                        // #1634: a clicked skeleton goes to its own store, which checks the boundary again under the
                        // spool's lock; it never spends the sample or the matched limits.
                        if ((item as? UiSkeletonDto)?.isClicked == true) {
                            clicks.admit(item, record.skeletonJson, record.platform.wire, offered.generation)
                            continue
                        }
                        val key = requireNotNull(CensusSampleKey.of(item, record.presentFields))
                        if (!sample.admit(item.day, key)) {
                            stats.sampledRepeat.incrementAndGet()
                            continue
                        }
                        val ruleId = (item as? UiSkeletonDto)?.ruleId
                        if (ruleId != null && !matchedCap.admit(item.day, ruleId)) {
                            stats.matchedCapped.incrementAndGet()
                            continue
                        }
                        spool.append(record, offered.generation)
                        // #1626: only a spooled skeleton (the one the server will hold) can be reviewed; its words stay in memory.
                        if (record.reviewWords != null) reviews.offer(record)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // No throwable message: a failed write can contain the serialized item.
                        stats.spoolDropped.incrementAndGet()
                        stats.uploadFailures.incrementAndGet()
                        Timber.tag("Census").i("census spool failures=%d", stats.uploadFailures.get())
                    }
                }
            } finally {
                channel.cancel()
            }
        }
    }

    override fun offer(record: CensusRecord): Boolean = channel.trySend(Offered(record, spool.generation)).isSuccess

    internal companion object {
        /**
         * The schema ids [schemas] lists that the census consent given under disclosure [revision] covers: the matched
         * schema from [MatchedSkeletons.MIN_DISCLOSURE_REVISION], the clicked schema from
         * [ClickEvidence.MIN_DISCLOSURE_REVISION]. The ONE fold [acceptedSchemaIds] and [clickEvidenceArmed] share.
         */
        fun consented(schemas: Set<String>, revision: Int?): Set<String> {
            val granted = revision ?: 0
            var out = schemas
            if (granted < MatchedSkeletons.MIN_DISCLOSURE_REVISION) out = out - SkeletonSchema.SCHEMA_ID_MATCHED
            if (granted < ClickEvidence.MIN_DISCLOSURE_REVISION) out = out - SkeletonSchema.SCHEMA_ID_CLICKED
            return out
        }
    }

    /** #1573: [boundary] is the [clearBoundary] the caller read when it took the frame; the spool drops a stale one. */
    override fun offer(record: CensusRecord, boundary: Long): Boolean = channel.trySend(Offered(record, boundary)).isSuccess
}
