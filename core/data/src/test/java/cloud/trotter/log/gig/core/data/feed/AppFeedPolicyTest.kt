package cloud.trotter.log.gig.core.data.feed

import cloud.trotter.feed.contract.Feed
import cloud.trotter.log.gig.core.datastore.feed.RulesFeedDataSource
import cloud.trotter.log.gig.core.datastore.settings.AppPreferencesDataSource
import cloud.trotter.log.gig.core.network.feed.FeedSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1565, #640, #1566: network is opt-in per feature; each feed follows its own switch, and constants stay off. */
class AppFeedPolicyTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val store = AppPreferencesDataSource(MemoryPreferences())
    private val fuel = FuelFeedSwitch(store, scope)
    private val rules = RulesFeedSwitch(RulesFeedDataSource(MemoryPreferences()), scope)
    private val policy = AppFeedPolicy(fuel, rules)

    @After fun tearDown() = scope.cancel()

    @Test fun `fuel follows the fuel switch, read as a current value`() = runBlocking {
        assertFalse(policy.enabled(Feed.FUEL).value)
        fuel.write(mayClose = false) { store.setGasPriceAuto(true) }
        assertTrue(policy.enabled(Feed.FUEL).value)
        fuel.closeNow()
        assertFalse(policy.enabled(Feed.FUEL).value)
    }

    @Test fun `the fuel switch never gates rules, and constants stay off until their own switch exists`() = runBlocking {
        fuel.write(mayClose = false) { store.setGasPriceAuto(true) }
        assertTrue(policy.enabled(Feed.FUEL).value)
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
