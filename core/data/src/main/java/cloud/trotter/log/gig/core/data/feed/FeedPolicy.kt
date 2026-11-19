package cloud.trotter.log.gig.core.data.feed

import cloud.trotter.feed.contract.Feed
import cloud.trotter.log.gig.core.network.feed.FeedSource
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-feature network consent and the chosen source, as state: [FeedRepository] reads the current values before every
 * step and inside every network exchange, and watches them while work runs: turning [enabled] off cancels that feed's
 * work, and a new [source] cancels the old source's.
 */
interface FeedPolicy {
    fun enabled(feed: Feed): StateFlow<Boolean>
    fun source(feed: Feed): StateFlow<FeedSource>
}

/**
 * The app's switches, one per feed, each gating its own feed only. Fuel uses "Auto gas price" ([FuelFeedSwitch]) and
 * rules use "Keep recognition rules up to date" ([RulesFeedSwitch], #640); each turns off at the instant the dasher
 * turns it off, before the store write lands. Constants stay off until their own switch exists (#695, default off).
 * Every feed reads gig.log's site until the custom source setting (#1567).
 */
@Singleton
class AppFeedPolicy @Inject constructor(
    fuel: FuelFeedSwitch,
    rules: RulesFeedSwitch,
) : FeedPolicy {
    private val fuelOn: StateFlow<Boolean> = fuel.enabled
    private val rulesOn: StateFlow<Boolean> = rules.enabled
    private val off: StateFlow<Boolean> = MutableStateFlow(false)
    private val gigLog: StateFlow<FeedSource> = MutableStateFlow(FeedSource.GigLog)

    override fun enabled(feed: Feed): StateFlow<Boolean> = when (feed) {
        Feed.FUEL -> fuelOn
        Feed.RULES -> rulesOn
        Feed.CONSTANTS -> off
    }

    override fun source(feed: Feed): StateFlow<FeedSource> = gigLog
}

/**
 * Permission for exactly one fetch of [feed] from the source [sourceId], given by an explicit user act (for example
 * "Resume auto") while the feature's switch is off. It is a parameter of one [FeedRepository.refresh] call and is never
 * stored, so a background refresh can never start from it.
 *
 * It dies with cancellation: it belongs to the coroutine that minted it and is dead from the moment that coroutine
 * starts cancelling (or is done), and it is spent by the one call that claims it, however that call ends.
 */
class OneShotGrant private constructor(val feed: Feed, val sourceId: String, private val owner: Job) {
    private val state = AtomicInteger(UNUSED)

    /** Takes the grant for one call. False for another feed or source, a dead owner, or a grant already used. */
    internal fun claim(feed: Feed, sourceId: String): Boolean =
        feed == this.feed && sourceId == this.sourceId && owner.isActive && state.compareAndSet(UNUSED, IN_USE)

    internal fun isLive(): Boolean = state.get() == IN_USE && owner.isActive

    internal fun spend() = state.set(SPENT)

    /**
     * A child job of the owner. The owner's cancellation cancels it at once, before the owner itself finishes, so a
     * refresh running in another scope can stop at that onset. The caller completes it when the call ends.
     */
    internal fun ownerChild(): CompletableJob = Job(owner)

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
