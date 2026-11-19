package cloud.trotter.log.gig.core.data.feed

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import cloud.trotter.feed.contract.Feed
import cloud.trotter.log.gig.core.datastore.feed.RulesFeedDataSource
import cloud.trotter.log.gig.core.datastore.settings.AppPreferencesDataSource
import cloud.trotter.log.gig.core.network.feed.FeedClient
import cloud.trotter.log.gig.core.network.feed.FeedGate
import cloud.trotter.log.gig.core.network.feed.FeedReply
import cloud.trotter.log.gig.core.network.feed.FeedSource
import cloud.trotter.log.gig.domain.time.ElapsedRealtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * #640: "Keep recognition rules up to date" is the rules feed's own consent. Its in-memory value, the one every
 * `RefreshGate` check reads, turns off at the instant the dasher turns it off: no store write, and no collector of the
 * store, stands between the tap and a closed gate.
 */
class RulesFeedSwitchTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val fake = FeedTestServer()
    private val client = FeedClient(fake.client) { }

    @After fun tearDown() {
        scope.cancel()
        fake.close()
    }

    private val enabledKey = booleanPreferencesKey("rules_updates_enabled")
    private val autoKey = booleanPreferencesKey("is_gas_price_auto")
    private val MINUTE = 60_000L
    private val lastCheckKey = longPreferencesKey("last_foreground_check_at")

    private fun switchOn(store: MemoryPreferences = MemoryPreferences(preferencesOf(enabledKey to true))) =
        RulesFeedSwitch(RulesFeedDataSource(store), scope)

    private fun policy(switch: RulesFeedSwitch) =
        AppFeedPolicy(FuelFeedSwitch(AppPreferencesDataSource(MemoryPreferences(preferencesOf(autoKey to true))), scope), switch)

    @Test fun `off by default, and read once from the store`() = runBlocking {
        assertFalse(RulesFeedSwitch(RulesFeedDataSource(MemoryPreferences()), scope).current())
        assertTrue(switchOn().current())
    }

    @Test fun `turned off with the store held back, the next exchange is refused and nothing reaches the server`() = runBlocking {
        val store = MemoryPreferences(preferencesOf(enabledKey to true))
        val switch = switchOn(store)
        assertTrue(switch.current())
        val policy = policy(switch)
        val gate = RefreshGate(Feed.RULES, policy, FeedSource.GigLog, grant = null)
        assertTrue(gate.isOpen())

        store.holdWrites()
        val saving = launch(start = CoroutineStart.UNDISPATCHED) { switch.set(false) }
        assertTrue("the save is still held", saving.isActive)

        assertFalse(switch.enabled.value)
        assertFalse(gate.isOpen())
        assertEquals(FeedReply.Denied, client.envelope(FeedSource.GigLog, Feed.RULES, etag = null, gate = gate))
        val repository = FeedRepository(Feed.RULES, OneFileStager(), client, policy, tmp.root, Clock.systemUTC(), buildTimeMs = 0L)
        assertEquals(FeedRefresh.Denied, repository.refresh())
        assertEquals(0, fake.server.requestCount)

        store.releaseWrites()
        saving.join()
        assertEquals(false, store.current[enabledKey])
    }

    @Test fun `turned off between a request's check and its exchange, the exchange is refused`() = runBlocking {
        val switch = switchOn()
        assertTrue(switch.current())
        val gate = RefreshGate(Feed.RULES, policy(switch), FeedSource.GigLog, grant = null)
        val offBetween = object : FeedGate {
            override suspend fun allows(): Boolean = gate.allows().also { switch.forgetNow() }
            override fun isOpen(): Boolean = gate.isOpen()
        }
        assertEquals(FeedReply.Denied, client.envelope(FeedSource.GigLog, Feed.RULES, etag = null, gate = offBetween))
        assertEquals(0, fake.server.requestCount)
    }

    @Test fun `a choice made before the stored one is read wins over it`() = runBlocking {
        val store = MemoryPreferences(preferencesOf(enabledKey to true))
        store.holdReads()
        val switch = switchOn(store)
        switch.forgetNow()
        store.releaseReads()
        assertFalse(switch.current())
    }

    @Test fun `turning on counts once saved, and a refused save leaves it off`() = runBlocking {
        val store = MemoryPreferences()
        val switch = RulesFeedSwitch(RulesFeedDataSource(store), scope)
        store.failWrites = true
        assertFalse(switch.set(true))
        assertFalse(switch.enabled.value)
        store.failWrites = false
        assertTrue(switch.set(true))
        assertTrue(switch.enabled.value)
        assertEquals(true, store.current[enabledKey])
    }

    @Test fun `an off that lands while an on is saving stays off`() = runBlocking {
        val store = MemoryPreferences()
        val switch = RulesFeedSwitch(RulesFeedDataSource(store), scope)
        assertFalse(switch.current())
        store.holdWrites()
        val on = launch(start = CoroutineStart.UNDISPATCHED) { switch.set(true) }
        val off = launch(start = CoroutineStart.UNDISPATCHED) { switch.set(false) }
        assertFalse(switch.enabled.value)
        store.releaseWrites()
        on.join()
        off.join()
        assertFalse(switch.enabled.value)
        assertEquals(false, store.current[enabledKey])
    }

    private var wall = Instant.parse("2026-11-15T12:00:00Z").toEpochMilli()
    private var elapsed = 5_000_000L
    private val wallClock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(wall)
    }

    private fun clocked(store: MemoryPreferences) =
        RulesFeedSwitch(RulesFeedDataSource(store), scope, wallClock, ElapsedRealtime { elapsed })

    private fun advance(ms: Long) {
        wall += ms
        elapsed += ms
    }

    @Test fun `a foreground check runs at most once an hour, and the limit survives a restart`() = runBlocking {
        val store = MemoryPreferences(preferencesOf(enabledKey to true))
        val switch = clocked(store)
        assertTrue(switch.claimForegroundCheck())
        val first = wall
        advance(59 * MINUTE)
        assertFalse(switch.claimForegroundCheck())
        advance(MINUTE)
        assertTrue(switch.claimForegroundCheck())
        assertEquals(first + 60 * MINUTE, store.current[lastCheckKey])

        advance(MINUTE)
        val restarted = clocked(store)
        assertFalse(restarted.claimForegroundCheck())
        advance(59 * MINUTE)
        assertTrue(restarted.claimForegroundCheck())
    }

    @Test fun `a wall clock set back never buys an extra check`() = runBlocking {
        val switch = clocked(MemoryPreferences(preferencesOf(enabledKey to true)))
        assertTrue(switch.claimForegroundCheck())
        wall -= 2 * MINUTE // the phone's clock is corrected backwards; elapsed time keeps counting
        elapsed += MINUTE
        assertFalse(switch.claimForegroundCheck())
        elapsed += 59 * MINUTE
        assertTrue(switch.claimForegroundCheck())
    }

    @Test fun `a stored last check in the future counts as a check just now`() = runBlocking {
        val store = MemoryPreferences(preferencesOf(enabledKey to true, lastCheckKey to wall + 5 * 24 * 60 * MINUTE))
        val switch = clocked(store)
        assertFalse(switch.claimForegroundCheck())
        advance(59 * MINUTE)
        assertFalse(switch.claimForegroundCheck())
        advance(MINUTE)
        assertTrue(switch.claimForegroundCheck())
    }

    @Test fun `with the switch off no foreground check is claimed`() = runBlocking {
        assertFalse(clocked(MemoryPreferences()).claimForegroundCheck())
    }

    /** Never reached in these tests: every refresh is refused before a request. */
    private class OneFileStager : FeedStager<String> {
        override val contracts: Set<Long> = setOf(1L)
        override fun stage(files: List<cloud.trotter.feed.contract.VerifiedFeedFile>, carry: kotlinx.serialization.json.JsonObject?) =
            FeedStage.Rejected("unused")
        override fun restore(files: List<cloud.trotter.feed.contract.VerifiedFeedFile>, carry: kotlinx.serialization.json.JsonObject?) =
            FeedStage.Rejected("unused")
    }
}
