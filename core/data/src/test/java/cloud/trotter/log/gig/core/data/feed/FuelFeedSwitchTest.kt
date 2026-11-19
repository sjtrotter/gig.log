package cloud.trotter.log.gig.core.data.feed

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import cloud.trotter.feed.contract.Feed
import cloud.trotter.log.gig.core.data.fuel.FuelFeedStager
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.data.vehicle.GarageRepository
import cloud.trotter.log.gig.core.datastore.feed.RulesFeedDataSource
import cloud.trotter.log.gig.core.datastore.settings.AppPreferencesDataSource
import cloud.trotter.log.gig.core.datastore.settings.StoredGasPrice
import cloud.trotter.log.gig.core.datastore.settings.StoredPreference
import cloud.trotter.log.gig.core.datastore.vehicle.VehicleDataSource
import cloud.trotter.log.gig.core.network.feed.FeedClient
import cloud.trotter.log.gig.core.network.feed.FeedReply
import cloud.trotter.log.gig.core.network.feed.FeedSource
import cloud.trotter.log.gig.domain.fuel.FuelPriceStamp
import cloud.trotter.log.gig.domain.fuel.FuelPrices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.Clock

/**
 * #1566: "Auto gas price" is the fuel feed's consent, and the feed's copy of it turns off at the instant a write that
 * may turn auto off starts: no store write, and no collector of the store, stands between the tap and a closed gate.
 * Each write path of `is_gas_price_auto` is checked with the store held back.
 */
class FuelFeedSwitchTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val fake = FeedTestServer()
    private val site = FeedSite().also { fake.server.dispatcher = it }
    private val client = FeedClient(fake.client) { }

    private val autoKey = booleanPreferencesKey("is_gas_price_auto")
    private val priceKey = floatPreferencesKey("gas_price")

    @After fun tearDown() {
        site.release()
        scope.cancel()
        fake.close()
    }

    /** One phone: the preferences store, the switch over it, and the feed channel reading the real policy. */
    private inner class Phone(auto: Boolean, holdReads: Boolean = false) {
        val store = MemoryPreferences(
            if (auto) preferencesOf(autoKey to true, priceKey to 3.0f) else preferencesOf(priceKey to 3.0f),
        ).also { if (holdReads) it.holdReads() }
        val source = AppPreferencesDataSource(store)
        val switch = FuelFeedSwitch(source, scope)
        val repository = AppPreferencesRepository(source, GarageRepository(VehicleDataSource(MemoryPreferences())), switch)
        val policy = AppFeedPolicy(switch, RulesFeedSwitch(RulesFeedDataSource(MemoryPreferences()), scope))
        val feed = FeedRepository<FuelPrices>(
            Feed.FUEL, FuelFeedStager(), client, policy, File(tmp.root, "feeds"), Clock.systemUTC(), buildTimeMs = 0L,
        )
    }

    /** The stored snapshot of the starting state (auto on, 3.00, no stamp), as the undo paths capture it. */
    private val autoOnSnapshot = StoredGasPrice(
        price = StoredPreference(3.0f, userSet = false),
        auto = StoredPreference(true, userSet = false),
        period = StoredPreference(null, userSet = false),
        issuedAt = StoredPreference(null, userSet = false),
    )

    /**
     * Starts [write] with the store held back and checks, while the save is still held, that the feed is already
     * closed: the policy reads off, a gate is closed, an exchange is refused and a refresh sends nothing.
     */
    private fun closesAtOnce(write: suspend AppPreferencesRepository.() -> Unit) = runBlocking {
        val phone = Phone(auto = true)
        assertTrue(phone.switch.enabled.value)
        val gate = RefreshGate(Feed.FUEL, phone.policy, FeedSource.GigLog, grant = null)
        assertTrue(gate.isOpen())

        phone.store.holdWrites()
        val saving = launch(start = CoroutineStart.UNDISPATCHED) { phone.repository.write() }
        assertTrue("the save is still held", saving.isActive)

        assertFalse(phone.policy.enabled(Feed.FUEL).value)
        assertFalse(gate.isOpen())
        assertEquals(FeedReply.Denied, client.envelope(FeedSource.GigLog, Feed.FUEL, etag = null, gate = gate))
        assertEquals(FeedRefresh.Denied, phone.feed.refresh())
        assertEquals(0, fake.server.requestCount)

        phone.store.releaseWrites()
        saving.join()
        assertEquals(false, phone.store.current[autoKey] ?: false)
        assertFalse(phone.switch.enabled.value)
    }

    @Test fun `turning auto off in Settings closes the feed before the save lands`() =
        closesAtOnce { setGasPriceAuto(false) }

    @Test fun `undoing auto on closes the feed before the save lands`() =
        closesAtOnce { restoreGasPriceAuto(StoredPreference(null, userSet = false), autoOnSnapshot.auto) }

    @Test fun `a typed or manual price closes the feed before the save lands`() =
        closesAtOnce { updateGasPriceManual(2.5f) }

    @Test fun `undoing back to a manual price closes the feed before the save lands`() = closesAtOnce {
        val manual = StoredGasPrice(StoredPreference(2.5f, userSet = true), StoredPreference(false, userSet = false),
            StoredPreference(null, userSet = false), StoredPreference(null, userSet = false))
        restoreGasPriceManual(manual, autoOnSnapshot)
    }

    @Test fun `a setup save with auto off closes the feed before the save lands`() = closesAtOnce {
        mirrorEconomySettings("2020", "Make", "Model", "Trim", 30f, isGasAuto = false, price = 3.0f, stamp = null)
    }

    @Test fun `a Costs reset closes the feed before the save lands`() = closesAtOnce { resetSharedEconomyDefaults() }

    @Test fun `clearing the preferences closes the feed before the save lands`() = closesAtOnce { clearPreferences() }

    @Test fun `closeNow closes the feed without a save`() = runBlocking {
        val phone = Phone(auto = true)
        val gate = RefreshGate(Feed.FUEL, phone.policy, FeedSource.GigLog, grant = null)
        assertTrue(gate.isOpen())
        phone.switch.closeNow()
        assertFalse(phone.policy.enabled(Feed.FUEL).value)
        assertFalse(gate.isOpen())
        assertEquals(FeedReply.Denied, client.envelope(FeedSource.GigLog, Feed.FUEL, etag = null, gate = gate))
        assertEquals(FeedRefresh.Denied, phone.feed.refresh())
        assertEquals(0, fake.server.requestCount)
        assertEquals(true, phone.store.current[autoKey])
    }

    @Test fun `turning auto on opens the feed only once the save lands`() = runBlocking {
        val phone = Phone(auto = false)
        assertFalse(phone.switch.enabled.value)
        phone.store.holdWrites()
        val saving = launch(start = CoroutineStart.UNDISPATCHED) { phone.repository.setGasPriceAuto(true) }
        assertFalse(phone.switch.enabled.value)
        phone.store.releaseWrites()
        saving.join()
        assertTrue(phone.switch.enabled.value)
        assertTrue(phone.policy.enabled(Feed.FUEL).value)
    }

    @Test fun `use auto price opens the feed once its save lands`() = runBlocking {
        val phone = Phone(auto = false)
        phone.repository.updateGasPriceAuto(3.1f, FuelPriceStamp(0L, 1L))
        assertTrue(phone.switch.enabled.value)
    }

    @Test fun `a failed save of auto on stays off`() = runBlocking {
        val phone = Phone(auto = false)
        phone.store.failWrites = true
        val failure = runCatching { phone.repository.setGasPriceAuto(true) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(phone.switch.enabled.value)
    }

    @Test fun `a failed save of auto off stays closed`() = runBlocking {
        val phone = Phone(auto = true)
        phone.store.failWrites = true
        assertTrue(runCatching { phone.repository.setGasPriceAuto(false) }.isFailure)
        assertFalse(phone.switch.enabled.value)
    }

    @Test fun `an off that lands while an on is saving stays off`() = runBlocking {
        val phone = Phone(auto = false)
        phone.store.holdWrites()
        val on = launch(start = CoroutineStart.UNDISPATCHED) { phone.repository.setGasPriceAuto(true) }
        val off = launch(start = CoroutineStart.UNDISPATCHED) { phone.repository.setGasPriceAuto(false) }
        assertFalse(phone.switch.enabled.value)
        phone.store.releaseWrites()
        on.join()
        off.join()
        assertFalse(phone.switch.enabled.value)
        assertEquals(false, phone.store.current[autoKey])
    }

    @Test fun `a use auto price write that loses to a Costs on leaves the feed on`() = runBlocking {
        val phone = Phone(auto = false)
        val since = phone.repository.gasAutoRevision()
        phone.store.holdWrites()
        val costs = launch(start = CoroutineStart.UNDISPATCHED) { phone.repository.setGasPriceAuto(true) }
        val resume = async(start = CoroutineStart.UNDISPATCHED) {
            phone.repository.updateGasPriceAutoIfUnchanged(3.1f, FuelPriceStamp(0L, 1L), since)
        }
        phone.store.releaseWrites()
        costs.join()
        assertFalse("the Costs choice came after the revision was read", resume.await())
        assertEquals(true, phone.store.current[autoKey])
        assertTrue(phone.switch.enabled.value)
    }

    @Test fun `the stored flag is read once at start, and no collector follows the store`() = runBlocking {
        val on = Phone(auto = true)
        assertTrue(on.switch.enabled.value)
        on.source.setGasPriceAuto(false)
        assertTrue("a write that bypasses the repository is not followed", on.switch.enabled.value)

        val off = Phone(auto = false)
        off.source.setGasPriceAuto(true)
        assertFalse(off.switch.enabled.value)
    }

    @Test fun `a close made before the stored flag is read wins over it`() = runBlocking {
        val phone = Phone(auto = true, holdReads = true)
        phone.switch.closeNow()
        phone.store.releaseReads()
        assertFalse(phone.switch.enabled.value)
    }

    @Test fun `a refresh in flight is cancelled when the switch closes with the write held`() = runBlocking {
        val phone = Phone(auto = true)
        phone.feed.start()
        site.hold("/feeds/fuel/envelope.json")
        val refresh = async { phone.feed.refresh() }
        assertTrue("the envelope request is in flight", withContext(Dispatchers.IO) { site.awaitHeld() })

        phone.store.holdWrites()
        val saving = launch(start = CoroutineStart.UNDISPATCHED) { phone.repository.setGasPriceAuto(false) }
        assertEquals(FeedRefresh.Cancelled, withTimeout(5_000) { refresh.await() })
        assertTrue("the save is still held", saving.isActive)
        assertTrue(
            "no payload was requested",
            site.requests.all { it.url.encodedPath == "/feeds/fuel/envelope.json" },
        )

        site.release()
        phone.store.releaseWrites()
        saving.join()
    }
}
