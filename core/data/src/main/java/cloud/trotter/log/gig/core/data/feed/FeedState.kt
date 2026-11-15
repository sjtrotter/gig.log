package cloud.trotter.log.gig.core.data.feed

import cloud.trotter.feed.contract.FeedRefusal
import cloud.trotter.feed.contract.VerifiedFeedFile
import cloud.trotter.log.gig.core.network.feed.FeedFetchFailure
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * What a feed consumer reads: anchors only (Reactive UI rule 2). [value] is the live generation's content, or null
 * when none is live (the consumer then uses its bundled baseline, or no feed price for fuel). [issuedAt] is that
 * generation's `issued_at` and [period] the payload's own period; the UI derives "current" or "old" from them with
 * `rememberNow()` and `FeedFreshness`. [lastError] is why the last refresh or start-up kept last-good.
 */
data class FeedState<out T>(
    val value: T? = null,
    val period: Long? = null,
    val issuedAt: Long? = null,
    val lastError: FeedError? = null,
)

/** Why last-good was kept. Never carries feed text, a URL or a header. */
sealed interface FeedError {
    /** The feed contract refused the envelope, a payload or the candidate (`ClockBehind` among them). */
    data class Refused(val reason: FeedRefusal) : FeedError

    data class Fetch(val failure: FeedFetchFailure) : FeedError

    /** The consumer's check refused the staged files; [reason] is the stager's closed code. */
    data class Stage(val reason: String) : FeedError

    /** The phone could not write or read the cache. */
    data object Storage : FeedError
}

/** The outcome of one [FeedRepository.refresh]. */
sealed interface FeedRefresh {
    data class Accepted(val sequence: Long) : FeedRefresh

    /** The site holds what the phone holds (an equal manifest or a 304). Freshness is not renewed. */
    data object Unchanged : FeedRefresh

    /** The feature is off and no live one-shot grant for this feature and source was given: nothing was sent. */
    data object Denied : FeedRefresh

    /** The feature was turned off, the source changed, or the grant died while the work ran. Nothing new is live. */
    data object Cancelled : FeedRefresh

    data class Failed(val error: FeedError) : FeedRefresh
}

/**
 * A consumer's semantic check of a generation (fuel and constants bounds; for rules the batch loader's prepare step).
 * It never activates anything: the repository publishes [FeedStage.Ready.value] only after the commit.
 *
 * The carry is the consumer's state that must travel with a generation (fuel's anchors, constants' retained years).
 * It lives only in that generation's `meta.json`, so it rolls back with its generation and is never kept twice.
 */
interface FeedStager<T> {
    /** The manifest `contract` numbers this build reads. */
    val contracts: Set<Long>

    /** Whether a rules file name is one this build reads (the app passes its platform lookup); ignored for fuel and constants. */
    fun readsFile(name: String): Boolean = true

    /** Checks a new generation against the live one's [carry] and returns its value and the carry to commit. */
    fun stage(files: List<VerifiedFeedFile>, carry: JsonObject?): FeedStage<T>

    /** Rebuilds a committed generation's value from its files and its own committed [carry], at start-up. */
    fun restore(files: List<VerifiedFeedFile>, carry: JsonObject?): FeedStage<T>
}

sealed interface FeedStage<out T> {
    data class Ready<out T>(val value: T, val period: Long?, val carry: JsonObject?) : FeedStage<T>
    data class Rejected(val reason: String) : FeedStage<Nothing>
}

/**
 * Where a test kills the process: after staging (before the commit), after the generation's rename (before the pointer
 * moves), and after the commit (before activation).
 */
internal enum class CrashPoint { STAGED, RENAMED, COMMITTED }

internal fun interface CrashPoints {
    fun reached(point: CrashPoint)
}

/**
 * Makes a directory's entries durable. [published] is true for the sync after the pointer moved: a failure there is
 * logged and the commit stands; any earlier failure aborts the commit.
 */
internal fun interface DirectorySync {
    @Throws(IOException::class)
    fun sync(dir: File, published: Boolean)

    companion object {
        val FSYNC = DirectorySync { dir, _ -> FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    }
}
