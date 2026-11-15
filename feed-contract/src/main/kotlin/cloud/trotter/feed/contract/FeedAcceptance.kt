package cloud.trotter.feed.contract

/** The fields of a verified manifest that acceptance decides on. [digest] is the manifest's sha256 (lowercase hex). */
data class FeedCandidate(
    val source: String,
    val feed: Feed,
    val sequence: Long,
    val issuedAt: Long,
    val contract: Long,
    val digest: String,
)

/** A sequence and the manifest digest that carried it: the bundled `FEED_SEQUENCE` / `FEED_DIGEST`, or a high-water. */
data class FeedMark(val sequence: Long, val digest: String)

/**
 * What the phone holds for one source and feed.
 *
 * @property committed the high-water stored in the committed generation's metadata, with the digest of the manifest
 *   that set it; null on a fresh install (high-water 0).
 * @property bundled the generation inside the APK (`FEED_SEQUENCE`, `FEED_DIGEST`); rules only, null otherwise.
 */
data class FeedCacheState(val committed: FeedMark? = null, val bundled: FeedMark? = null) {
    /** For rules, `max(FEED_SEQUENCE, committed)`; for the other feeds the committed high-water or 0. */
    val highWater: Long get() = maxOf(committed?.sequence ?: 0L, bundled?.sequence ?: 0L)

    /**
     * Start-up recovery to an older, re-verified generation ([fallback]): the client serves its content, but the
     * high-water is the larger of the committed one and the fallback's own sequence, so recovery never lowers it.
     */
    fun recoveredTo(fallback: FeedMark): FeedCacheState =
        if (committed != null && committed.sequence >= fallback.sequence) this else copy(committed = fallback)

    /**
     * Whether a committed generation may run at start. A rules generation at or below the bundled `FEED_SEQUENCE` is
     * dropped, so an APK that bundles newer rules never runs older cached ones.
     */
    fun keepsCommittedGeneration(generationSequence: Long): Boolean =
        bundled == null || generationSequence > bundled.sequence
}

/** What the request asked for and the two clocks admission reads. Times are Unix milliseconds. */
data class FeedRequest(
    val source: String,
    val feed: Feed,
    val wallClockMs: Long,
    val buildTimeMs: Long,
    val supportedContracts: Set<Long>,
)

sealed interface FeedDecision {
    /** A new generation: stage and commit it, store [highWater], and renew freshness from [issuedAt]. */
    data class Accept(val highWater: Long, val issuedAt: Long) : FeedDecision

    /**
     * The candidate is what the phone already holds. Nothing changes and freshness is not renewed. [bundled] is true
     * when it equals the APK's rules, the silent case.
     */
    data class NoOp(val bundled: Boolean) : FeedDecision

    /** Keep last-good; the high-water does not move. */
    data class Refuse(val reason: FeedRefusal) : FeedDecision
}

/**
 * The acceptance rules as one pure function. Nothing a feed sends moves the admission bound: it reads only the wall
 * clock and the build time.
 */
object FeedAcceptance {

    fun decide(state: FeedCacheState, candidate: FeedCandidate, request: FeedRequest): FeedDecision {
        if (candidate.source != request.source) return refuse(FeedRefusal.WrongSource)
        if (candidate.feed != request.feed) return refuse(FeedRefusal.WrongFeed)
        if (candidate.issuedAt < FeedLimits.MIN_EPOCH_MS || candidate.sequence < FeedLimits.MIN_EPOCH_MS) {
            return refuse(FeedRefusal.NotMilliseconds)
        }
        if (candidate.sequence != candidate.issuedAt) return refuse(FeedRefusal.SequenceNotIssuedAt)
        val admissionNow = maxOf(request.wallClockMs, request.buildTimeMs)
        if (candidate.issuedAt - admissionNow > FeedLimits.ADMISSION_WINDOW_MS) return refuse(FeedRefusal.ClockBehind)

        val highWater = state.highWater
        when {
            candidate.sequence < highWater -> return refuse(FeedRefusal.Rollback)
            candidate.sequence == highWater -> {
                val bundled = state.bundled
                if (bundled != null && bundled.sequence == highWater && bundled.digest == candidate.digest) {
                    return FeedDecision.NoOp(bundled = true)
                }
                val committed = state.committed
                if (committed != null && committed.sequence == highWater && committed.digest == candidate.digest) {
                    return FeedDecision.NoOp(bundled = false)
                }
                return refuse(FeedRefusal.ReusedSequence)
            }
        }
        if (candidate.contract !in request.supportedContracts) return refuse(FeedRefusal.UnsupportedContract)
        return FeedDecision.Accept(highWater = candidate.sequence, issuedAt = candidate.issuedAt)
    }

    private fun refuse(reason: FeedRefusal) = FeedDecision.Refuse(reason)
}
