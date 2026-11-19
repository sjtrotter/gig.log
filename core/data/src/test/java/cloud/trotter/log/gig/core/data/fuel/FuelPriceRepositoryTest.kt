package cloud.trotter.log.gig.core.data.fuel

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.feed.contract.Feed
import cloud.trotter.log.gig.core.data.feed.FakeFeedPolicy
import cloud.trotter.log.gig.core.data.feed.FeedError
import cloud.trotter.log.gig.core.data.feed.FeedRefresh
import cloud.trotter.log.gig.core.data.feed.FeedRepository
import cloud.trotter.log.gig.core.data.feed.FeedSite
import cloud.trotter.log.gig.core.data.feed.FeedTestServer
import cloud.trotter.log.gig.core.data.feed.FuelFeedSwitch
import cloud.trotter.log.gig.core.data.feed.HOUR
import cloud.trotter.log.gig.core.data.feed.MemoryPreferences
import cloud.trotter.log.gig.core.data.feed.NOW
import cloud.trotter.log.gig.core.data.feed.SignedSource
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.data.vehicle.GarageRepository
import cloud.trotter.log.gig.core.datastore.settings.AppPreferencesDataSource
import cloud.trotter.log.gig.core.datastore.vehicle.VehicleDataSource
import cloud.trotter.log.gig.core.network.feed.FeedClient
import cloud.trotter.log.gig.core.network.feed.FeedFetchFailure
import cloud.trotter.log.gig.domain.fuel.FuelArea
import cloud.trotter.log.gig.domain.fuel.FuelPriceStamp
import cloud.trotter.log.gig.domain.fuel.FuelPrices
import cloud.trotter.log.gig.domain.model.vehicle.FuelType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The fuel client over the real feed channel and a fake feed site: the follow applies the chosen area's price from the
 * cache, "Use auto price" turns auto on only with a price in hand, and nothing is sent with the switch off.
 */
class FuelPriceRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fake = FeedTestServer()
    private val site = FeedSite().also { fake.server.dispatcher = it }
    private val fuel = SignedSource(Feed.FUEL)
    private val policy = FakeFeedPolicy(enabled = true, source = fuel.source)
    private val clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)

    /** 2026-11-09T00:00Z, the Monday before [NOW]. */
    private val period = 1_794_182_400_000L
    private val issuedAt = NOW - 3 * HOUR
    private val stamp = FuelPriceStamp(period, issuedAt)

    /** U.S., Gulf Coast and Texas prices; Texas has no diesel, as in the live feed. */
    private val generation = fuel.generation(
        issuedAt,
        ("""{"period":$period,"prices":{"NUS":{"regular":3.051,"diesel":3.7},"R30":{"regular":2.8,"diesel":3.4},""" +
            """"STX":{"regular":2.7}},"source":"EIA","unit":"USD/gal"}""").toByteArray(),
    )

    private val store = AppPreferencesDataSource(
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.root, "prefs.preferences_pb") }),
    )
    private val preferences = AppPreferencesRepository(
        store, GarageRepository(VehicleDataSource(MemoryPreferences())), FuelFeedSwitch(store, scope),
    )
    private val feed = FeedRepository<FuelPrices>(
        Feed.FUEL, FuelFeedStager(), FeedClient(fake.client) { }, policy, File(tmp.root, "feeds"), clock,
        buildTimeMs = NOW - 30 * 24 * HOUR,
    )
    private val repository = FuelPriceRepository(feed, policy, preferences, scope)

    @After fun tearDown() {
        site.release()
        scope.cancel()
        fake.close()
    }

    private fun envelopeRequests() = site.requests.count { it.url.encodedPath.endsWith("/envelope.json") }

    private suspend fun awaitStored(price: Float, stamp: FuelPriceStamp = this.stamp) = withTimeout(5_000) {
        store.gasPrice.first { it == price }
        store.gasPriceStamp.first { it == stamp }
    }

    @Test fun `refresh applies the chosen area's price and stamp`() = runBlocking {
        preferences.setGasPriceAuto(true)
        repository.start()
        site.publish(Feed.FUEL, generation)

        assertEquals(FuelRefreshOutcome.Updated, repository.refresh())

        awaitStored(3.051f)
    }

    @Test fun `an area change and a fuel change apply from the cache with no request`() = runBlocking {
        preferences.setGasPriceAuto(true)
        repository.start()
        site.publish(Feed.FUEL, generation)
        assertEquals(FuelRefreshOutcome.Updated, repository.refresh())
        awaitStored(3.051f)
        val sent = fake.server.requestCount

        repository.setArea(FuelArea.TEXAS)
        awaitStored(2.7f)
        // Texas has no diesel: the Gulf Coast's is used.
        preferences.mirrorFuelType(FuelType.DIESEL)
        awaitStored(3.4f)
        assertEquals(FuelArea.GULF_COAST, withTimeout(5_000) { repository.resolved.first { it?.price == 3.4 } }?.area)

        assertEquals(sent, fake.server.requestCount)
    }

    @Test fun `use auto price with the switch off sends one fetch and turns auto on with the price`() = runBlocking {
        policy.enabled.value = false
        preferences.updateGasPriceManual(2.5f)
        repository.start()
        site.publish(Feed.FUEL, generation)

        assertEquals(FuelRefreshOutcome.Updated, repository.resumeAuto(FuelType.REGULAR))

        assertEquals(1, envelopeRequests())
        assertTrue(store.isGasPriceAuto.first())
        assertEquals(3.051f, store.gasPrice.first())
        assertEquals(stamp, store.gasPriceStamp.first())
    }

    @Test fun `a failed use auto price writes nothing and stays manual`() = runBlocking {
        policy.enabled.value = false
        preferences.updateGasPriceManual(2.5f)
        repository.start()
        site.redirect = true

        assertEquals(FuelRefreshOutcome.Failed(retry = false), repository.resumeAuto(FuelType.REGULAR))

        assertFalse(store.isGasPriceAuto.first())
        assertEquals(2.5f, store.gasPrice.first())
        assertNull(store.gasPriceStamp.first())
    }

    @Test fun `refresh with the switch off sends nothing`() = runBlocking {
        policy.enabled.value = false
        repository.start()
        site.publish(Feed.FUEL, generation)

        assertEquals(FuelRefreshOutcome.Off, repository.refresh())

        assertEquals(0, fake.server.requestCount)
    }

    @Test fun `setup's lookup prices every gas fuel for the stored area from one fetch`() = runBlocking {
        policy.enabled.value = false
        repository.start()
        site.publish(Feed.FUEL, generation)
        preferences.setFuelPriceArea(FuelArea.TEXAS)

        val lookup = repository.lookUp()

        assertEquals(1, envelopeRequests())
        assertEquals(FetchedGasPrice(2.7f, stamp), lookup.prices[FuelType.REGULAR])
        assertEquals(FetchedGasPrice(3.4f, stamp), lookup.prices[FuelType.DIESEL])
        assertNull("no midgrade anywhere in the chain", lookup.prices[FuelType.MIDGRADE])
        assertNull(lookup.prices[FuelType.ELECTRICITY])
    }

    @Test fun `a refresh outcome maps to the worker's retry`() {
        fun retry(failure: FeedFetchFailure) = FuelPriceRepository.outcomeOf(FeedRefresh.Failed(FeedError.Fetch(failure)))
        assertEquals(FuelRefreshOutcome.Failed(retry = true), retry(FeedFetchFailure.TRANSPORT))
        assertEquals(FuelRefreshOutcome.Failed(retry = true), retry(FeedFetchFailure.HTTP_ERROR))
        assertEquals(FuelRefreshOutcome.Failed(retry = true), retry(FeedFetchFailure.NOT_FOUND))
        assertEquals(FuelRefreshOutcome.Failed(retry = false), retry(FeedFetchFailure.REDIRECT))
        assertEquals(FuelRefreshOutcome.Failed(retry = false), FuelPriceRepository.outcomeOf(FeedRefresh.Failed(FeedError.Storage)))
        assertEquals(FuelRefreshOutcome.Updated, FuelPriceRepository.outcomeOf(FeedRefresh.Accepted(1L)))
        assertEquals(FuelRefreshOutcome.Unchanged, FuelPriceRepository.outcomeOf(FeedRefresh.Unchanged))
        assertEquals(FuelRefreshOutcome.Off, FuelPriceRepository.outcomeOf(FeedRefresh.Denied))
        assertEquals(FuelRefreshOutcome.Off, FuelPriceRepository.outcomeOf(FeedRefresh.Cancelled))
    }
}
