package cloud.trotter.log.gig.core.data.fuel

import cloud.trotter.feed.contract.Feed
import cloud.trotter.log.gig.core.data.feed.FeedError
import cloud.trotter.log.gig.core.data.feed.FeedPolicy
import cloud.trotter.log.gig.core.data.feed.FeedRefresh
import cloud.trotter.log.gig.core.data.feed.FeedRepository
import cloud.trotter.log.gig.core.data.feed.FeedState
import cloud.trotter.log.gig.core.data.feed.OneShotGrant
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.network.feed.FeedFetchFailure
import cloud.trotter.log.gig.domain.di.ApplicationScope
import cloud.trotter.log.gig.domain.fuel.FuelArea
import cloud.trotter.log.gig.domain.fuel.FuelPriceResolution
import cloud.trotter.log.gig.domain.fuel.FuelPriceStamp
import cloud.trotter.log.gig.domain.fuel.FuelPrices
import cloud.trotter.log.gig.domain.fuel.ResolvedFuelPrice
import cloud.trotter.log.gig.domain.model.vehicle.FuelType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The fuel client: the gas price comes from gig.log's signed fuel feed ([FeedRepository]) for the area the dasher
 * picked. Every phone downloads the same file, all areas; the area is chosen on the phone and never sent.
 *
 * - **The follow** ([start]): while auto is on, the stored gas price follows the feed price resolved for the chosen
 *   area and the active fuel type ([FuelPriceFollow]). A new generation, an area change, a fuel type change and auto
 *   turning on all apply from the cache through this one path, with no request.
 * - **When it fetches.** [refresh] (the daily worker, the bubble's refresh) runs only with "Auto gas price" on.
 *   [resumeAuto] and [lookUp] are a dasher's act: each mints a one-shot grant for one fetch while the switch is off.
 * - **Consent** is `FuelFeedSwitch`, which the feed reads through [FeedPolicy].
 *
 * Logging (tag `FuelPrice`): INFO when a price is applied, with the fuel, the week and whether it fell back to an
 * area above the chosen one, never the area (a city is the dasher's location); DEBUG adds the area codes. WARN only
 * for a failed save or a refresh that threw.
 */
@Singleton
class FuelPriceRepository @Inject constructor(
    private val feed: FeedRepository<FuelPrices>,
    private val policy: FeedPolicy,
    private val preferences: AppPreferencesRepository,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    /** The channel's state: the live generation's prices and their anchors, and why the last refresh kept last-good. */
    val feedState: StateFlow<FeedState<FuelPrices>> = feed.state

    /** The price for the chosen area and the active fuel type; null when none resolves (always for electricity). */
    val resolved: Flow<ResolvedFuelPrice?> =
        combine(feed.state, preferences.fuelPriceArea, preferences.fuelType) { state, area, fuel ->
            state.value?.let { FuelPriceResolution.resolve(it, area, fuel) }
        }

    private val following = AtomicBoolean(false)

    /** Starts the follow, once, and loads the committed generation (no network). Called at app start. */
    suspend fun start() {
        if (following.compareAndSet(false, true)) scope.launch { follow() }
        feed.start()
    }

    private suspend fun follow() {
        val stored = preferences.gasPrice.combine(preferences.gasPriceStamp) { price, stamp -> price to stamp }
        try {
            combine(preferences.isGasPriceAuto, feed.state, preferences.fuelPriceArea, preferences.fuelType, stored) {
                    auto, state, area, fuel, (price, stamp) ->
                val resolved = state.value?.let { FuelPriceResolution.resolve(it, area, fuel) }
                Step(area, fuel, resolved, FuelPriceFollow.next(auto, resolved, state.issuedAt, price, stamp))
            }.collect { step -> step.write?.let { save(step, it) } }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "fuel price follow stopped; the stored gas price stays as it is")
        }
    }

    private class Step(
        val chosen: FuelArea,
        val fuel: FuelType,
        val resolved: ResolvedFuelPrice?,
        val write: FuelPriceFollow.Write?,
    )

    private suspend fun save(step: Step, write: FuelPriceFollow.Write) {
        val used = step.resolved?.area ?: return
        val written = try {
            preferences.updateGasPrice(write.price, write.stamp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w("fuel price not saved (%s)", e.javaClass.simpleName)
            return
        }
        if (!written) return
        Timber.tag(TAG).i("fuel price applied fuel=%s period=%d fellBack=%b", step.fuel.name, write.stamp.period, used != step.chosen)
        Timber.tag(TAG).d("fuel price areas chosen=%s used=%s", step.chosen.code, used.code)
    }

    /** One background or bubble refresh, only with the switch on. The follow writes any new price. */
    suspend fun refresh(): FuelRefreshOutcome = outcomeOf(refreshSafely(grant = null))

    /**
     * "Use auto price": one fetch on the dasher's tap (a one-shot grant minted in the caller's coroutine), then auto
     * turns on with the price for [fuel] in one write, only when the fetch succeeded or found nothing new and a price
     * resolves. Anything else writes nothing, and the price stays manual.
     */
    suspend fun resumeAuto(fuel: FuelType): FuelRefreshOutcome {
        val grant = OneShotGrant.forUserAct(Feed.FUEL, policy.source(Feed.FUEL).value)
        val outcome = outcomeOf(refreshSafely(grant))
        if (outcome != FuelRefreshOutcome.Updated && outcome != FuelRefreshOutcome.Unchanged) return outcome
        val price = stamped(feed.state.value, preferences.fuelPriceArea.first(), fuel)
            ?: return FuelRefreshOutcome.Failed(retry = false)
        try {
            preferences.updateGasPriceAuto(price.price, price.stamp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w("auto gas price not saved (%s)", e.javaClass.simpleName)
            return FuelRefreshOutcome.Failed(retry = false)
        }
        return outcome
    }

    /**
     * Setup's lookup: one refresh (with a one-shot grant while the switch is off, since the wizard's auto toggle is
     * the dasher's act), then the price of every gas fuel for the stored area, from the cache even when the refresh
     * failed. One call serves every fuel type.
     */
    suspend fun lookUp(): FuelLookup {
        val grant = if (policy.enabled(Feed.FUEL).value) null else OneShotGrant.forUserAct(Feed.FUEL, policy.source(Feed.FUEL).value)
        refreshSafely(grant)
        val state = feed.state.value
        val area = preferences.fuelPriceArea.first()
        return FuelLookup(FuelType.entries.mapNotNull { fuel -> stamped(state, area, fuel)?.let { fuel to it } }.toMap())
    }

    /** Saves the chosen area; the follow applies its price from the cache. */
    suspend fun setArea(area: FuelArea) = preferences.setFuelPriceArea(area)

    private suspend fun refreshSafely(grant: OneShotGrant?): FeedRefresh = try {
        feed.refresh(grant)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.tag(TAG).w("fuel refresh failed (%s)", e.javaClass.simpleName)
        FeedRefresh.Failed(FeedError.Storage)
    }

    private fun stamped(state: FeedState<FuelPrices>, area: FuelArea, fuel: FuelType): FetchedGasPrice? {
        val prices = state.value ?: return null
        val issuedAt = state.issuedAt ?: return null
        val resolved = FuelPriceResolution.resolve(prices, area, fuel) ?: return null
        return FetchedGasPrice(resolved.price.toFloat(), FuelPriceStamp(resolved.period, issuedAt))
    }

    companion object {
        private const val TAG = "FuelPrice"

        /** A transport failure, a server error or a file not on the edge yet is worth a WorkManager retry. */
        private val RETRIED = setOf(FeedFetchFailure.TRANSPORT, FeedFetchFailure.HTTP_ERROR, FeedFetchFailure.NOT_FOUND)

        fun outcomeOf(refresh: FeedRefresh): FuelRefreshOutcome = when (refresh) {
            is FeedRefresh.Accepted -> FuelRefreshOutcome.Updated
            FeedRefresh.Unchanged -> FuelRefreshOutcome.Unchanged
            FeedRefresh.Denied, FeedRefresh.Cancelled -> FuelRefreshOutcome.Off
            is FeedRefresh.Failed -> FuelRefreshOutcome.Failed(retry = refresh.error.let { it is FeedError.Fetch && it.failure in RETRIED })
        }
    }
}

/** The outcome of one fuel refresh, as the daily worker and the bubble read it. */
sealed interface FuelRefreshOutcome {
    /** A new generation went live; the follow applies its price. */
    data object Updated : FuelRefreshOutcome

    /** The site holds what the phone holds. */
    data object Unchanged : FuelRefreshOutcome

    /** Nothing was sent, or the work stopped: the switch is off, or it turned off while the work ran. */
    data object Off : FuelRefreshOutcome

    /** The refresh failed; [retry] is true for a failure worth a WorkManager retry. */
    data class Failed(val retry: Boolean) : FuelRefreshOutcome
}

/** One fuel's feed price and its stamp. */
data class FetchedGasPrice(val price: Float, val stamp: FuelPriceStamp)

/** Setup's lookup: each gas fuel's price for the stored area; a fuel with no price along the area's chain is absent. */
data class FuelLookup(val prices: Map<FuelType, FetchedGasPrice>) {
    val nothingResolved: Boolean get() = prices.isEmpty()
}
