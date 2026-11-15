package cloud.trotter.log.gig.core.data.feed

import cloud.trotter.feed.contract.Feed
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.network.feed.FeedSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/** #1565: network is opt-in per feature; fuel follows its existing switch, constants and rules stay off. */
class AppFeedPolicyTest {
    private val auto = MutableStateFlow(false)
    private val policy = AppFeedPolicy(mock<AppPreferencesRepository> { on { isGasPriceAuto } doReturn auto })

    @Test fun `fuel follows the auto gas price switch`() = runTest {
        assertFalse(policy.enabled(Feed.FUEL).first())
        auto.value = true
        assertTrue(policy.enabled(Feed.FUEL).first())
    }

    @Test fun `constants and rules are off until their own switches exist`() = runTest {
        auto.value = true
        assertFalse(policy.enabled(Feed.CONSTANTS).first())
        assertFalse(policy.enabled(Feed.RULES).first())
    }

    @Test fun `every feed reads gig_log's site`() = runTest {
        for (feed in Feed.entries) assertEquals(FeedSource.GigLog, policy.source(feed).first())
    }
}
