package cloud.trotter.log.gig.core.data.feed

import cloud.trotter.feed.contract.Feed
import cloud.trotter.feed.contract.FeedAcceptance
import cloud.trotter.feed.contract.FeedCacheState
import cloud.trotter.feed.contract.FeedDecision
import cloud.trotter.feed.contract.FeedFiles
import cloud.trotter.feed.contract.FeedMark
import cloud.trotter.feed.contract.FeedRefusal
import cloud.trotter.feed.contract.FeedRequest
import cloud.trotter.feed.contract.FeedResult
import cloud.trotter.feed.contract.FeedTrust
import cloud.trotter.feed.contract.ManifestFile
import cloud.trotter.feed.contract.VerifiedFeedFile
import cloud.trotter.feed.contract.VerifiedManifest
import cloud.trotter.log.gig.core.network.feed.FeedClient
import cloud.trotter.log.gig.core.network.feed.FeedFetchFailure
import cloud.trotter.log.gig.core.network.feed.FeedGate
import cloud.trotter.log.gig.core.network.feed.FeedReply
import cloud.trotter.log.gig.core.network.feed.FeedSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * The on-phone cache and swap of one signed feed (#1565, design-r3 § App client, ADR-0013). One instance per feed;
 * the consumer supplies the [stager] that checks its payloads. It adds no network of its own: [refresh] runs only when
 * a consumer calls it, and only with the feature's consent or a one-shot grant.
 *
 * - **One mutex per (source, feed).** Start-up and every refresh of that pair run under it.
 * - **Consent and source before every step:** before each request (retries included), before staging, committing
 *   and activating. While the work runs, turning the feature off or changing the source cancels it.
 * - **Stage, commit, activate** ([FeedStore]): the files are written and checked by [stager] without activating; the
 *   commit writes `meta.json` and renames `CURRENT`; activation publishes [state].
 * - **Start-up** ([start]) re-verifies `CURRENT` through the same verifier, then `PREVIOUS` with the high-water kept,
 *   then no feed value (the consumer's bundled baseline, or no feed price for fuel).
 * - **The high-water and the carry live only in `meta.json`.**
 *
 * @param root `noBackupFilesDir/feeds`.
 * @param buildTimeMs the build's time (`BuildConfig.BUILD_TIME_MS`): clock admission reads it, and nothing a feed sends
 *   moves it.
 * @param bundled the APK's own generation of this feed (rules: `FEED_SEQUENCE` and `FEED_DIGEST`), else null.
 */
class FeedRepository<T> internal constructor(
    private val feed: Feed,
    private val stager: FeedStager<T>,
    private val client: FeedClient,
    private val policy: FeedPolicy,
    private val root: File,
    private val clock: Clock,
    private val buildTimeMs: Long,
    private val bundled: FeedMark?,
    private val crashPoints: CrashPoints,
) {
    constructor(
        feed: Feed,
        stager: FeedStager<T>,
        client: FeedClient,
        policy: FeedPolicy,
        root: File,
        clock: Clock,
        buildTimeMs: Long,
        bundled: FeedMark? = null,
    ) : this(feed, stager, client, policy, root, clock, buildTimeMs, bundled, CrashPoints { })

    private val _state = MutableStateFlow(FeedState<T>())
    val state: StateFlow<FeedState<T>> = _state.asStateFlow()

    private val locks = ConcurrentHashMap<String, Mutex>()
    private val publishing = Mutex()

    /** What is live for the source last loaded: its cache state, generation, carry and (gig.log only) ETag. */
    private class Live(
        val sourceId: String,
        val cache: FeedCacheState,
        val generation: String?,
        val carry: JsonObject?,
        val etag: String?,
    )

    @Volatile private var live: Live? = null

    /** Thrown when a step check finds the feature off, the source changed or the grant dead. */
    private class Revoked : CancellationException("feed work revoked")

    /** Loads the current source's committed generation. No network. */
    suspend fun start() {
        val source = policy.source(feed).first()
        lockFor(source).withLock { load(source) }
    }

    /**
     * One refresh: the envelope, then the files it names, then stage, commit and activate. Without [grant] it runs
     * only while the feature is on; a [grant] lets one call run while it is off, for its own feed and source only.
     */
    suspend fun refresh(grant: OneShotGrant? = null): FeedRefresh {
        val source = policy.source(feed).first()
        if (grant != null) {
            if (!grant.claim(feed, source.id)) return denied(source, "grant")
        } else if (!policy.enabled(feed).first()) {
            return denied(source, "consent")
        }
        try {
            return watched(source, grant) { lockFor(source).withLock { fetch(source, grant) } }
        } finally {
            grant?.spend()
        }
    }

    private fun denied(source: FeedSource, why: String): FeedRefresh {
        Timber.tag(TAG).d("feed refresh denied feed=%s source=%s by=%s", feed.wire, source.id, why)
        return FeedRefresh.Denied
    }

    /** Runs [work] and cancels it the moment the feature turns off, the source changes, or the grant's owner ends. */
    private suspend fun watched(source: FeedSource, grant: OneShotGrant?, work: suspend () -> FeedRefresh): FeedRefresh =
        coroutineScope {
            val job = async { work() }
            val watcher = launch {
                // firstOrNull: a policy whose flows complete (a fixed switch) can never revoke, so the watch just ends.
                combine(policy.enabled(feed), policy.source(feed)) { on, current -> current == source && (grant != null || on) }
                    .firstOrNull { allowed -> !allowed } ?: return@launch
                job.cancel(Revoked())
            }
            val owner = grant?.onOwnerDone { job.cancel(Revoked()) }
            try {
                job.await()
            } catch (e: CancellationException) {
                if (!isActive || !job.isCancelled) throw e
                Timber.tag(TAG).d("feed refresh cancelled feed=%s source=%s", feed.wire, source.id)
                FeedRefresh.Cancelled
            } finally {
                watcher.cancel()
                owner?.dispose()
            }
        }

    private suspend fun allowed(source: FeedSource, grant: OneShotGrant?): Boolean {
        currentCoroutineContext().ensureActive()
        if (policy.source(feed).first() != source) return false
        return grant?.isLive() ?: policy.enabled(feed).first()
    }

    private suspend fun step(source: FeedSource, grant: OneShotGrant?) {
        if (!allowed(source, grant)) throw Revoked()
    }

    private suspend fun fetch(source: FeedSource, grant: OneShotGrant?): FeedRefresh {
        val current = live?.takeIf { it.sourceId == source.id } ?: load(source)
        val trust = when (val built = FeedTrust.shipped(listOfNotNull(source.userPin))) {
            is FeedResult.Ok -> built.value
            is FeedResult.Refused -> return fail(source, FeedError.Refused(built.reason))
        }
        // Today's pin file holds no gig.log key, so nothing is requested from a source this build cannot verify.
        if (!trust.trusts(source.id, feed)) return fail(source, FeedError.Refused(FeedRefusal.NoTrustedEpoch))
        val gate = FeedGate { allowed(source, grant) }

        val envelope = when (val reply = client.envelope(source, feed, current.etag, gate)) {
            is FeedReply.Body -> reply
            FeedReply.NotModified -> return unchanged(source, current, current.etag)
            FeedReply.Denied -> throw Revoked()
            is FeedReply.Failed -> return fail(source, FeedError.Fetch(reply.failure))
        }
        val manifest = when (val verified = VerifiedManifest.verify(trust, source.id, feed, ByteArrayInputStream(envelope.bytes))) {
            is FeedResult.Ok -> verified.value
            is FeedResult.Refused -> return fail(source, FeedError.Refused(verified.reason))
        }
        val request = FeedRequest(source.id, feed, clock.millis(), buildTimeMs, stager.contracts)
        val decision = when (val decided = FeedAcceptance.decide(current.cache, manifest.candidate, request)) {
            is FeedDecision.Accept -> decided
            is FeedDecision.NoOp -> return unchanged(source, current, envelope.etag)
            is FeedDecision.Refuse -> return fail(source, FeedError.Refused(decided.reason))
        }

        val files = ArrayList<VerifiedFeedFile>()
        for (file in FeedFiles.select(manifest, stager::readsFile)) {
            val kept = held(source, current, manifest, file)
            if (kept != null) {
                files += kept
                continue
            }
            val bytes = when (val reply = client.payload(source, feed, file.sha256, file.size, gate)) {
                is FeedReply.Body -> reply.bytes
                FeedReply.Denied -> throw Revoked()
                FeedReply.NotModified -> return fail(source, FeedError.Fetch(FeedFetchFailure.HTTP_ERROR))
                is FeedReply.Failed -> return fail(source, FeedError.Fetch(reply.failure))
            }
            files += when (val verified = VerifiedFeedFile.verify(manifest, file.name, ByteArrayInputStream(bytes))) {
                is FeedResult.Ok -> verified.value
                is FeedResult.Refused -> return fail(source, FeedError.Refused(verified.reason))
            }
        }

        step(source, grant)
        val store = FeedStore(root, source.id, feed)
        val generation = try {
            withContext(Dispatchers.IO) { store.stage(manifest.sequence, files.associate { it.sha256 to it.bytes() }) }
        } catch (_: IOException) {
            return fail(source, FeedError.Storage)
        }
        val staged = try {
            stager.stage(files, current.carry)
        } catch (_: Exception) {
            FeedStage.Rejected("stager_failed")
        }
        if (staged !is FeedStage.Ready) {
            withContext(Dispatchers.IO) { store.discard(generation) }
            return fail(source, FeedError.Stage((staged as FeedStage.Rejected).reason))
        }
        crashPoints.reached(CrashPoint.STAGED)

        step(source, grant)
        val highWater = FeedMark(decision.highWater, manifest.digest)
        val etag = envelope.etag.takeIf { source is FeedSource.GigLog }
        val meta = FeedMeta(envelope.bytes, manifest.digest, manifest.sequence, highWater, staged.carry, etag)
        try {
            withContext(Dispatchers.IO) { store.commit(generation, meta, previous = current.generation) }
        } catch (_: IOException) {
            withContext(Dispatchers.IO) { store.discard(generation) }
            return fail(source, FeedError.Storage)
        }
        crashPoints.reached(CrashPoint.COMMITTED)

        // Committed: a crash or a revocation from here on re-activates from CURRENT at the next start.
        step(source, grant)
        val next = Live(source.id, current.cache.copy(committed = highWater), generation, staged.carry, etag)
        publish(next, FeedState(staged.value, staged.period, decision.issuedAt, lastError = null))
        Timber.tag(TAG).i("feed accepted feed=%s sequence=%d source=%s", feed.wire, manifest.sequence, source.id)
        return FeedRefresh.Accepted(manifest.sequence)
    }

    /** A file a held generation already has with the same sha256, re-verified against the new manifest. */
    private suspend fun held(source: FeedSource, current: Live, manifest: VerifiedManifest, file: ManifestFile): VerifiedFeedFile? {
        val store = FeedStore(root, source.id, feed)
        val generations = listOfNotNull(current.generation, (store.pointer(FeedStore.PREVIOUS) as? FeedPointer.Names)?.generation)
        for (generation in generations.distinct()) {
            val bytes = withContext(Dispatchers.IO) { store.readFile(generation, file.sha256, file.size) } ?: continue
            val verified = VerifiedFeedFile.verify(manifest, file.name, ByteArrayInputStream(bytes))
            if (verified is FeedResult.Ok) return verified.value
        }
        return null
    }

    private suspend fun unchanged(source: FeedSource, current: Live, etag: String?): FeedRefresh {
        val kept = Live(current.sourceId, current.cache, current.generation, current.carry, etag.takeIf { source is FeedSource.GigLog })
        publish(kept, _state.value.copy(lastError = null))
        return FeedRefresh.Unchanged
    }

    private suspend fun fail(source: FeedSource, error: FeedError): FeedRefresh {
        when (error) {
            is FeedError.Fetch -> Timber.tag(TAG).d("feed fetch failed feed=%s source=%s failure=%s", feed.wire, source.id, error.failure)
            else -> Timber.tag(TAG).w("feed kept last-good feed=%s source=%s error=%s", feed.wire, source.id, error)
        }
        publishing.withLock {
            if (live?.sourceId == source.id && policy.source(feed).first().id == source.id) {
                _state.value = _state.value.copy(lastError = error)
            }
        }
        return FeedRefresh.Failed(error)
    }

    /** Publishes [next] only while its source is still the chosen one. */
    private suspend fun publish(next: Live, state: FeedState<T>) {
        publishing.withLock {
            if (policy.source(feed).first().id != next.sourceId) return
            live = next
            _state.value = state
        }
    }

    private fun lockFor(source: FeedSource): Mutex = locks.computeIfAbsent(source.id) { Mutex() }

    // --- start-up recovery ---

    private suspend fun load(source: FeedSource): Live {
        val restored = withContext(Dispatchers.IO) { recover(source) }
        publish(restored.first, restored.second)
        return restored.first
    }

    private fun recover(source: FeedSource): Pair<Live, FeedState<T>> {
        val store = FeedStore(root, source.id, feed)
        val trust = (FeedTrust.shipped(listOfNotNull(source.userPin)) as? FeedResult.Ok)?.value
        val current = store.pointer(FeedStore.CURRENT)
        val previous = store.pointer(FeedStore.PREVIOUS)

        // The high-water is the largest one any committed generation still records. A garbled CURRENT may have named
        // any generation on disk, so then every readable one counts: recovery never lowers it.
        val marks = ArrayList<FeedMark>()
        if (current is FeedPointer.Names) marks += store.readMeta(current.generation)?.highWater ?: FeedMark(current.sequence, "")
        if (previous is FeedPointer.Names) store.readMeta(previous.generation)?.highWater?.let { marks += it }
        if (current is FeedPointer.Garbled) store.generations().forEach { gen -> store.readMeta(gen)?.highWater?.let { marks += it } }
        var cache = FeedCacheState(committed = marks.maxByOrNull { it.sequence }, bundled = bundled)
        if (current is FeedPointer.Names) store.prune(setOfNotNull(current.generation, (previous as? FeedPointer.Names)?.generation))

        for (pointer in listOf(current, previous).filterIsInstance<FeedPointer.Names>().distinct()) {
            val generation = trust?.let { restore(store, it, source, pointer.generation, cache) } ?: continue
            cache = cache.recoveredTo(FeedMark(generation.manifest.sequence, generation.manifest.digest))
            if (pointer != current) {
                Timber.tag(TAG).w("feed fell back feed=%s source=%s to=previous", feed.wire, source.id)
            }
            val live = Live(source.id, cache, pointer.generation, generation.meta.carry, generation.meta.etag)
            return live to FeedState(generation.ready.value, generation.ready.period, generation.manifest.issuedAt)
        }
        val held = current != FeedPointer.Absent
        if (held) Timber.tag(TAG).w("feed fell back feed=%s source=%s to=baseline", feed.wire, source.id)
        return Live(source.id, cache, null, null, null) to FeedState(lastError = if (held) FeedError.Storage else null)
    }

    private class Restored<T>(val manifest: VerifiedManifest, val meta: FeedMeta, val ready: FeedStage.Ready<T>)

    /** One committed generation through the same verifier a download uses, or null when any check fails. */
    private fun restore(store: FeedStore, trust: FeedTrust, source: FeedSource, generation: String, cache: FeedCacheState): Restored<T>? {
        val meta = store.readMeta(generation) ?: return null
        val manifest = (VerifiedManifest.verify(trust, source.id, feed, ByteArrayInputStream(meta.envelope)) as? FeedResult.Ok)?.value
            ?: return null
        if (manifest.digest != meta.digest || manifest.sequence != meta.sequence) return null
        if (!cache.keepsCommittedGeneration(manifest.sequence)) return null
        val files = FeedFiles.select(manifest, stager::readsFile).map { file ->
            val bytes = store.readFile(generation, file.sha256, file.size) ?: return null
            (VerifiedFeedFile.verify(manifest, file.name, ByteArrayInputStream(bytes)) as? FeedResult.Ok)?.value ?: return null
        }
        val ready = try {
            stager.restore(files, meta.carry) as? FeedStage.Ready
        } catch (_: Exception) {
            null
        } ?: return null
        return Restored(manifest, meta, ready)
    }

    private companion object {
        const val TAG = "Feeds"
    }
}
