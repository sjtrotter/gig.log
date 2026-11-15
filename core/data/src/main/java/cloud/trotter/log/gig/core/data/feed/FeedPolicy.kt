package cloud.trotter.log.gig.core.data.feed

import cloud.trotter.feed.contract.Feed
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.network.feed.FeedSource
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-feature network consent and the chosen source. [FeedRepository] reads both before every step and request, and
 * watches them while work runs: turning [enabled] off cancels that feed's work, and a new [source] cancels the old
 * source's.
 */
interface FeedPolicy {
    fun enabled(feed: Feed): Flow<Boolean>
    fun source(feed: Feed): Flow<FeedSource>
}

/**
 * The app's switches. Fuel uses the existing auto gas price switch. Constants and rules stay off until their own
 * switches exist (#695, #640; both default off). Every feed reads gig.log's site until the custom source setting
 * (#1567).
 */
@Singleton
class AppFeedPolicy @Inject constructor(private val preferences: AppPreferencesRepository) : FeedPolicy {
    override fun enabled(feed: Feed): Flow<Boolean> = when (feed) {
        Feed.FUEL -> preferences.isGasPriceAuto
        Feed.CONSTANTS, Feed.RULES -> flowOf(false)
    }

    override fun source(feed: Feed): Flow<FeedSource> = flowOf(FeedSource.GigLog)
}

/**
 * Permission for exactly one fetch of [feed] from the source [sourceId], given by an explicit user act (for example
 * "Resume auto") while the feature's switch is off. It is a parameter of one [FeedRepository.refresh] call and is never
 * stored, so a background refresh can never start from it.
 *
 * It dies with cancellation: it belongs to the coroutine that minted it and is dead once that coroutine is cancelled
 * or done, and it is spent by the one call that claims it, however that call ends.
 */
class OneShotGrant private constructor(val feed: Feed, val sourceId: String, private val owner: Job) {
    private val state = AtomicInteger(UNUSED)

    /** Takes the grant for one call. False for another feed or source, a dead owner, or a grant already used. */
    internal fun claim(feed: Feed, sourceId: String): Boolean =
        feed == this.feed && sourceId == this.sourceId && owner.isActive && state.compareAndSet(UNUSED, IN_USE)

    internal fun isLive(): Boolean = state.get() == IN_USE && owner.isActive

    internal fun spend() = state.set(SPENT)

    internal fun onOwnerDone(action: () -> Unit): DisposableHandle = owner.invokeOnCompletion { action() }

    override fun toString(): String = "OneShotGrant(${feed.wire}, $sourceId)"

    companion object {
        private const val UNUSED = 0
        private const val IN_USE = 1
        private const val SPENT = 2

        /** Called from the coroutine that handles the dasher's tap; the grant lives no longer than that coroutine. */
        suspend fun forUserAct(feed: Feed, source: FeedSource): OneShotGrant {
            val owner = requireNotNull(currentCoroutineContext()[Job]) { "a one-shot grant needs the user act's coroutine" }
            return OneShotGrant(feed, source.id, owner)
        }
    }
}
