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
import cloud.trotter.log.gig.core.network.feed.FeedReply
import cloud.trotter.log.gig.core.network.feed.FeedSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
 * - **Stage, commit, activate** ([FeedStore]): the files are written to a stage and checked by [stager] without
 *   activating; the commit renames the stage to its generation and moves the `CURRENT` pointer pair; activation
 *   publishes [state].
 * - **The disk decides.** Every acceptance decision reads the high-water fresh from disk under the mutex, never from
 *   memory, so a generation committed but never activated (a cancellation or a crash after the commit) still counts.
 * - **Start-up** ([start]) deletes every stage, then activates the newest generation that re-verifies through the
 *   same verifier and carries a contract this build reads, else no feed value (the consumer's bundled baseline, or no
 *   feed price for fuel). The high-water never drops.
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
    private val directorySync: DirectorySync,
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
    ) : this(feed, stager, client, policy, root, clock, buildTimeMs, bundled, CrashPoints { }, DirectorySync.FSYNC)

    private val _state = MutableStateFlow(FeedState<T>())
    val state: StateFlow<FeedState<T>> = _state.asStateFlow()

    private val locks = ConcurrentHashMap<String, Mutex>()
    private val publishing = Mutex()

    /**
     * What is live for the source last loaded: the active generation (null for the baseline), its carry and
     * (gig.log only) ETag, and the newest generation on disk when it was loaded.
     */
    private class Live(
        val sourceId: String,
        val generation: String?,
        val carry: JsonObject?,
        val etag: String?,
        val newestSeen: String?,
    )

    @Volatile private var live: Live? = null

    /** Thrown when a step check finds the feature off, the source changed or the grant dead. */
    private class Revoked : CancellationException("feed work revoked")

    /** Loads the current source's committed generation. No network. */
    suspend fun start() {
        val source = policy.source(feed).value
        lockFor(source).withLock { load(source) }
    }

    /**
     * One refresh: the envelope, then the files it names, then stage, commit and activate. Without [grant] it runs
     * only while the feature is on; a [grant] lets one call run while it is off, for its own feed and source only.
     */
    suspend fun refresh(grant: OneShotGrant? = null): FeedRefresh {
        val source = policy.source(feed).value
        if (grant != null) {
            if (!grant.claim(feed, source.id)) return denied(source, "grant")
        } else if (!policy.enabled(feed).value) {
            return denied(source, "consent")
        }
        try {
            return watched(source, grant) { gate -> lockFor(source).withLock { fetch(source, gate) } }
        } finally {
            grant?.spend()
        }
    }

    private fun denied(source: FeedSource, why: String): FeedRefresh {
        Timber.tag(TAG).d("feed refresh denied feed=%s source=%s by=%s", feed.wire, source.id, why)
        return FeedRefresh.Denied
    }

    /**
     * Runs [work] and cancels it the moment the feature turns off, the source changes, or the grant's owner starts
     * cancelling (a child job of the owner is cancelled at that onset, before the owner finishes).
     */
    private suspend fun watched(
        source: FeedSource,
        grant: OneShotGrant?,
        work: suspend (RefreshGate) -> FeedRefresh,
    ): FeedRefresh =
        coroutineScope {
            val gate = RefreshGate(feed, policy, source, grant)
            val job = async { work(gate) }
            val watcher = launch {
                combine(policy.enabled(feed), policy.source(feed)) { on, current -> current == source && (grant != null || on) }
                    .first { allowed -> !allowed }
                gate.revoke()
                job.cancel(Revoked())
            }
            val ownerChild = grant?.ownerChild()
            val ownerWatch = ownerChild?.invokeOnCompletion {
                gate.revoke()
                job.cancel(Revoked())
            }
            try {
                job.await()
            } catch (e: CancellationException) {
                if (!isActive || !job.isCancelled) throw e
                Timber.tag(TAG).d("feed refresh cancelled feed=%s source=%s", feed.wire, source.id)
                FeedRefresh.Cancelled
            } finally {
                watcher.cancel()
                ownerWatch?.dispose()
                ownerChild?.complete()
            }
        }

    private suspend fun step(gate: RefreshGate) {
        if (!gate.allows()) throw Revoked()
    }

    private fun store(source: FeedSource) = FeedStore(root, source.id, feed, directorySync, crashPoints)

    private suspend fun fetch(source: FeedSource, gate: RefreshGate): FeedRefresh {
        val store = store(source)
        val newest = withContext(Dispatchers.IO) { store.generations().firstOrNull() }
        // A generation committed but never activated (or a source not loaded yet) is loaded before anything else.
        val current = live?.takeIf { it.sourceId == source.id && it.newestSeen == newest } ?: load(source)
        val trust = when (val built = FeedTrust.shipped(listOfNotNull(source.userPin))) {
            is FeedResult.Ok -> built.value
            is FeedResult.Refused -> return fail(source, FeedError.Refused(built.reason))
        }
        // Today's pin file holds no gig.log key, so nothing is requested from a source this build cannot verify.
        if (!trust.trusts(source.id, feed)) return fail(source, FeedError.Refused(FeedRefusal.NoTrustedEpoch))

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
        val cache = FeedCacheState(committed = withContext(Dispatchers.IO) { store.highWater() }, bundled = bundled)
        val request = FeedRequest(source.id, feed, clock.millis(), buildTimeMs, stager.contracts)
        val decision = when (val decided = FeedAcceptance.decide(cache, manifest.candidate, request)) {
            is FeedDecision.Accept -> decided
            is FeedDecision.NoOp -> return unchanged(source, current, envelope.etag)
            is FeedDecision.Refuse -> return fail(source, FeedError.Refused(decided.reason))
        }

        val files = ArrayList<VerifiedFeedFile>()
        for (file in FeedFiles.select(manifest, stager::readsFile)) {
            val kept = held(store, manifest, file)
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

        step(gate)
        val stage = try {
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
            withContext(Dispatchers.IO) { store.discard(stage) }
            return fail(source, FeedError.Stage((staged as FeedStage.Rejected).reason))
        }
        crashPoints.reached(CrashPoint.STAGED)

        step(gate)
        val highWater = FeedMark(decision.highWater, manifest.digest)
        val etag = envelope.etag.takeIf { source is FeedSource.GigLog }
        val meta = FeedMeta(envelope.bytes, manifest.digest, manifest.sequence, highWater, staged.carry, etag)
        val generation = FeedStore.generationName(manifest.sequence)
        val durable = try {
            withContext(Dispatchers.IO) {
                store.commit(stage, meta, previous = current.generation).also {
                    store.prune(setOfNotNull(generation, current.generation))
                }
            }
        } catch (_: IOException) {
            withContext(Dispatchers.IO) { store.discard(stage) }
            return fail(source, FeedError.Storage)
        }
        if (!durable) Timber.tag(TAG).w("feed pointer not proven after commit feed=%s source=%s", feed.wire, source.id)
        crashPoints.reached(CrashPoint.COMMITTED)

        // Committed: a crash or a revocation from here on activates it from disk at the next load.
        step(gate)
        val next = Live(source.id, generation, staged.carry, etag, newestSeen = generation)
        publish(next, FeedState(staged.value, staged.period, decision.issuedAt, lastError = null))
        Timber.tag(TAG).i("feed accepted feed=%s sequence=%d source=%s", feed.wire, manifest.sequence, source.id)
        return FeedRefresh.Accepted(manifest.sequence)
    }

    /** A file a committed generation already has with the same sha256, re-verified against the new manifest. */
    private suspend fun held(store: FeedStore, manifest: VerifiedManifest, file: ManifestFile): VerifiedFeedFile? =
        withContext(Dispatchers.IO) {
            store.generations().firstNotNullOfOrNull { generation ->
                store.readFile(generation, file.sha256, file.size)?.let { bytes ->
                    (VerifiedFeedFile.verify(manifest, file.name, ByteArrayInputStream(bytes)) as? FeedResult.Ok)?.value
                }
            }
        }

    private suspend fun unchanged(source: FeedSource, current: Live, etag: String?): FeedRefresh {
        val kept = Live(current.sourceId, current.generation, current.carry, etag.takeIf { source is FeedSource.GigLog }, current.newestSeen)
        publish(kept, _state.value.copy(lastError = null))
        return FeedRefresh.Unchanged
    }

    private suspend fun fail(source: FeedSource, error: FeedError): FeedRefresh {
        when (error) {
            is FeedError.Fetch -> Timber.tag(TAG).d("feed fetch failed feed=%s source=%s failure=%s", feed.wire, source.id, error.failure)
            else -> Timber.tag(TAG).w("feed kept last-good feed=%s source=%s error=%s", feed.wire, source.id, error)
        }
        publishing.withLock {
            if (live?.sourceId == source.id && policy.source(feed).value.id == source.id) {
                _state.value = _state.value.copy(lastError = error)
            }
        }
        return FeedRefresh.Failed(error)
    }

    /** Publishes [next] only while its source is still the chosen one. */
    private suspend fun publish(next: Live, state: FeedState<T>) {
        publishing.withLock {
            if (policy.source(feed).value.id != next.sourceId) return
            live = next
            _state.value = state
        }
    }

    private fun lockFor(source: FeedSource): Mutex = locks.computeIfAbsent(source.id) { Mutex() }

    // --- loading committed state ---

    private suspend fun load(source: FeedSource): Live {
        val (next, state) = withContext(Dispatchers.IO) { recover(source) }
        publish(next, state)
        return next
    }

    private fun recover(source: FeedSource): Pair<Live, FeedState<T>> {
        val store = store(source)
        store.deleteStages()
        val cache = FeedCacheState(committed = store.highWater(), bundled = bundled)
        val trust = (FeedTrust.shipped(listOfNotNull(source.userPin)) as? FeedResult.Ok)?.value
        val generations = store.generations()
        var skipped: FeedError? = null
        for (generation in generations) {
            when (val restored = trust?.let { restore(store, it, source, generation, cache) } ?: Restore.Skip(FeedError.Storage)) {
                is Restore.Skip -> skipped = skipped ?: restored.error
                is Restore.Ok -> {
                    if (generation != generations.first()) {
                        Timber.tag(TAG).w("feed fell back feed=%s source=%s to=older", feed.wire, source.id)
                    }
                    val live = Live(source.id, generation, restored.meta.carry, restored.meta.etag, generations.first())
                    return live to FeedState(restored.ready.value, restored.ready.period, restored.manifest.issuedAt, skipped)
                }
            }
        }
        if (generations.isNotEmpty()) Timber.tag(TAG).w("feed fell back feed=%s source=%s to=baseline", feed.wire, source.id)
        return Live(source.id, null, null, null, generations.firstOrNull()) to FeedState(lastError = skipped)
    }

    private sealed interface Restore<out T> {
        class Ok<T>(val manifest: VerifiedManifest, val meta: FeedMeta, val ready: FeedStage.Ready<T>) : Restore<T>
        class Skip(val error: FeedError) : Restore<Nothing>
    }

    /** One committed generation through the same verifier a download uses, or why it cannot run. */
    private fun restore(store: FeedStore, trust: FeedTrust, source: FeedSource, generation: String, cache: FeedCacheState): Restore<T> {
        val unreadable = Restore.Skip(FeedError.Storage)
        val meta = store.readMeta(generation) ?: return unreadable
        val manifest = when (val verified = VerifiedManifest.verify(trust, source.id, feed, ByteArrayInputStream(meta.envelope))) {
            is FeedResult.Ok -> verified.value
            is FeedResult.Refused -> return Restore.Skip(FeedError.Refused(verified.reason))
        }
        if (manifest.digest != meta.digest || manifest.sequence != meta.sequence) return unreadable
        if (FeedStore.generationName(manifest.sequence) != generation) return unreadable
        if (manifest.contract !in stager.contracts) return Restore.Skip(FeedError.Refused(FeedRefusal.UnsupportedContract))
        if (!cache.keepsCommittedGeneration(manifest.sequence)) return Restore.Skip(FeedError.Refused(FeedRefusal.Rollback))
        val files = FeedFiles.select(manifest, stager::readsFile).map { file ->
            val bytes = store.readFile(generation, file.sha256, file.size) ?: return unreadable
            when (val verified = VerifiedFeedFile.verify(manifest, file.name, ByteArrayInputStream(bytes))) {
                is FeedResult.Ok -> verified.value
                is FeedResult.Refused -> return Restore.Skip(FeedError.Refused(verified.reason))
            }
        }
        val ready = try {
            stager.restore(files, meta.carry)
        } catch (_: Exception) {
            FeedStage.Rejected("stager_failed")
        }
        return when (ready) {
            is FeedStage.Ready -> Restore.Ok(manifest, meta, ready)
            is FeedStage.Rejected -> Restore.Skip(FeedError.Stage(ready.reason))
        }
    }

    private companion object {
        const val TAG = "Feeds"
    }
}
