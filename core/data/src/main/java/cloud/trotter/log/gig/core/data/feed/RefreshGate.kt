package cloud.trotter.log.gig.core.data.feed

import cloud.trotter.feed.contract.Feed
import cloud.trotter.log.gig.core.network.feed.FeedGate
import cloud.trotter.log.gig.core.network.feed.FeedSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The consent of one refresh, for [source]: open while the feature is on (or [grant] lives) and the chosen source is
 * still [source]. Both checks read the policy's current values at the moment they run, so the exchange on OkHttp's
 * thread and the suspending step checks never disagree, and neither waits for a watcher to see a change.
 */
internal class RefreshGate(
    private val feed: Feed,
    private val policy: FeedPolicy,
    private val source: FeedSource,
    private val grant: OneShotGrant?,
) : FeedGate {
    private val revoked = AtomicBoolean(false)

    fun revoke() = revoked.set(true)

    override suspend fun allows(): Boolean {
        currentCoroutineContext().ensureActive()
        return isOpen()
    }

    override fun isOpen(): Boolean =
        !revoked.get() && policy.source(feed).value == source && (grant?.isLive() ?: policy.enabled(feed).value)
}
