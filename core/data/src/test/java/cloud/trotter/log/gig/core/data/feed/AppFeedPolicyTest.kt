package cloud.trotter.log.gig.core.data.feed

import cloud.trotter.feed.contract.Feed
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.datastore.feed.RulesFeedDataSource
import cloud.trotter.log.gig.core.network.feed.FeedSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/** #1565, #640: network is opt-in per feature; each feed follows its own switch, and constants stay off. */
class AppFeedPolicyTest {
    private val auto = MutableStateFlow(false)
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val rules = RulesFeedSwitch(RulesFeedDataSource(MemoryPreferences()), scope)
    private val policy = AppFeedPolicy(mock<AppPreferencesRepository> { on { isGasPriceAuto } doReturn auto }, rules, scope)

    @After fun tearDown() = scope.cancel()

    @Test fun `fuel follows the auto gas price switch, read as a current value`() {
        assertFalse(policy.enabled(Feed.FUEL).value)
        auto.value = true
        assertTrue(policy.enabled(Feed.FUEL).value)
    }

    @Test fun `the fuel switch never gates rules, and constants stay off until their own switch exists`() {
        auto.value = true
        assertFalse(policy.enabled(Feed.CONSTANTS).value)
        assertFalse(policy.enabled(Feed.RULES).value)
    }

    @Test fun `rules follow their own switch, which never gates fuel or constants`() = runBlocking {
        assertTrue(rules.set(true))
        assertTrue(policy.enabled(Feed.RULES).value)
        assertFalse(policy.enabled(Feed.FUEL).value)
        assertFalse(policy.enabled(Feed.CONSTANTS).value)
        assertTrue(rules.set(false))
        assertFalse(policy.enabled(Feed.RULES).value)
    }

    @Test fun `every feed reads gig_log's site`() {
        for (feed in Feed.entries) assertEquals(FeedSource.GigLog, policy.source(feed).value)
    }
}
