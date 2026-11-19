package cloud.trotter.log.gig.ui.main.settings

import cloud.trotter.log.gig.core.data.feed.FeedState
import cloud.trotter.log.gig.core.data.fuel.FuelPriceRepository
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.domain.fuel.FuelArea
import cloud.trotter.log.gig.domain.fuel.FuelPriceStamp
import cloud.trotter.log.gig.domain.fuel.FuelPrices
import cloud.trotter.log.gig.domain.fuel.ResolvedFuelPrice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** A fuel client with no feed generation, or the given state, for the Costs screen's view model. */
fun fuelPricesStub(
    resolved: Flow<ResolvedFuelPrice?> = flowOf(null),
    feedState: StateFlow<FeedState<FuelPrices>> = MutableStateFlow(FeedState()),
): FuelPriceRepository = mock<FuelPriceRepository>().also {
    whenever(it.resolved).thenReturn(resolved)
    whenever(it.feedState).thenReturn(feedState)
}

/** Stubs the stored fuel price area and stamp a preferences mock is read for. */
fun AppPreferencesRepository.stubFuelPrice(
    area: Flow<FuelArea> = flowOf(FuelArea.US),
    stamp: Flow<FuelPriceStamp?> = flowOf(null),
): AppPreferencesRepository = also {
    whenever(it.fuelPriceArea).thenReturn(area)
    whenever(it.gasPriceStamp).thenReturn(stamp)
}
