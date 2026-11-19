package cloud.trotter.log.gig.feature.setup.wizard

import cloud.trotter.log.gig.core.data.fuel.FetchedGasPrice
import cloud.trotter.log.gig.core.data.fuel.FuelLookup
import cloud.trotter.log.gig.core.data.fuel.FuelPriceRepository
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.data.state.AppStateRepository
import cloud.trotter.log.gig.core.data.strategy.StrategyRepository
import cloud.trotter.log.gig.core.data.vehicle.Garage
import cloud.trotter.log.gig.core.data.vehicle.GarageRepository
import cloud.trotter.log.gig.core.data.vehicle.VehicleRepository
import cloud.trotter.log.gig.domain.evaluation.UserEconomy
import cloud.trotter.log.gig.domain.fuel.FuelPriceStamp
import cloud.trotter.log.gig.domain.model.vehicle.FuelType
import cloud.trotter.log.gig.domain.model.vehicle.Vehicle
import cloud.trotter.log.gig.domain.model.vehicle.VehicleProfile
import cloud.trotter.log.gig.feature.setup.wizard.model.GasPriceSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** The wizard's gas price says where it came from, and a failed lookup keeps the figure and says so. */
@OptIn(ExperimentalCoroutinesApi::class)
class WizardGasPriceSourceTest {
    private val strategyRepository: StrategyRepository = mock()
    private val appPreferencesRepository: AppPreferencesRepository = mock()
    private val appStateRepository: AppStateRepository = mock()
    private val vehicleRepository: VehicleRepository = mock()
    private val gasPriceRepository: FuelPriceRepository = mock()
    private val garageRepository: GarageRepository = mock()
    private val rulesFeedSwitch = rulesFeedSwitchStub()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun TestScope.wizard(auto: Boolean, price: Float?, stamp: FuelPriceStamp?): WizardViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        whenever(garageRepository.garage).thenReturn(flowOf(Garage(emptyList(), null, true)))
        whenever(garageRepository.addVehicle(isNull(), any(), any(), any())).thenReturn(Vehicle("new-id", null, 1L))
        whenever(garageRepository.updateProfile(any(), any<VehicleProfile>())).thenReturn(true)
        whenever(appPreferencesRepository.vehicleYear).thenReturn(flowOf(null))
        whenever(appPreferencesRepository.vehicleLookupAllowed).thenReturn(flowOf(false))
        whenever(appPreferencesRepository.vehicleMake).thenReturn(flowOf(null))
        whenever(appPreferencesRepository.vehicleModel).thenReturn(flowOf(null))
        whenever(appPreferencesRepository.vehicleTrim).thenReturn(flowOf(null))
        whenever(appPreferencesRepository.estimatedMpg).thenReturn(flowOf(null))
        whenever(appPreferencesRepository.fuelType).thenReturn(flowOf(FuelType.REGULAR))
        whenever(appPreferencesRepository.isGasPriceAuto).thenReturn(flowOf(auto))
        whenever(appPreferencesRepository.gasPrice).thenReturn(flowOf(price))
        whenever(appPreferencesRepository.gasPriceStamp).thenReturn(flowOf(stamp))
        whenever(appPreferencesRepository.userEconomy).thenReturn(flowOf(UserEconomy()))
        whenever(strategyRepository.protectStatsMode).thenReturn(flowOf(false))
        whenever(strategyRepository.scoringRules).thenReturn(flowOf(emptyList()))
        whenever(vehicleRepository.getYears()).thenReturn(emptyList())
        lookupFails()
        val vm = WizardViewModel(strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository, garageRepository, rulesFeedSwitch)
        testScheduler.advanceUntilIdle()
        return vm
    }

    private suspend fun lookupFails() {
        whenever(gasPriceRepository.lookUp()).thenReturn(FuelLookup(emptyMap()))
    }

    private suspend fun lookupReturns(vararg prices: Pair<FuelType, Float>) {
        whenever(gasPriceRepository.lookUp()).thenReturn(FuelLookup(prices.associate { (fuel, price) -> fuel to FetchedGasPrice(price, CURRENT) }))
    }

    @Test
    fun `auto on with a stored stamp loads as a lookup with that stamp`() = runTest {
        val vm = wizard(auto = true, price = 3.29f, stamp = EARLIER)
        assertEquals(GasPriceSource.Lookup(EARLIER), vm.state.value.gasPriceSource)
    }

    @Test
    fun `auto on with no stored stamp loads as a saved price`() = runTest {
        val vm = wizard(auto = true, price = 3.29f, stamp = null)
        assertEquals(GasPriceSource.Saved, vm.state.value.gasPriceSource)
    }

    @Test
    fun `auto off loads as a typed price and nothing is looked up`() = runTest {
        val vm = wizard(auto = false, price = 3.29f, stamp = EARLIER)
        assertEquals(GasPriceSource.Typed, vm.state.value.gasPriceSource)
        assertEquals(false, vm.state.value.gasPriceLookupFailed)
        verify(gasPriceRepository, never()).lookUp()
    }

    @Test
    fun `a lookup that returns a price shows it with the feed's stamp`() = runTest {
        val vm = wizard(auto = true, price = 3.29f, stamp = EARLIER)
        lookupReturns(FuelType.REGULAR to 3.10f)
        vm.attemptAutoGasPriceFetch()
        testScheduler.advanceUntilIdle()

        assertEquals(3.10f, vm.state.value.gasPrice, 0f)
        assertEquals(GasPriceSource.Lookup(CURRENT), vm.state.value.gasPriceSource)
        assertEquals(false, vm.state.value.gasPriceLookupFailed)
    }

    @Test
    fun `a failed lookup keeps the figure and its source and marks the failure`() = runTest {
        val vm = wizard(auto = true, price = 3.29f, stamp = EARLIER)

        assertEquals(3.29f, vm.state.value.gasPrice, 0f)
        assertEquals(GasPriceSource.Lookup(EARLIER), vm.state.value.gasPriceSource)
        assertEquals(true, vm.state.value.gasPriceLookupFailed)
        assertEquals(false, vm.state.value.isFetchingGasPrice)
    }

    @Test
    fun `typing a price after a failed lookup turns auto off, makes it typed and clears the failure`() = runTest {
        val vm = wizard(auto = true, price = 3.29f, stamp = EARLIER)
        assertEquals(true, vm.state.value.gasPriceLookupFailed)

        vm.updateGasPrice(3.75f)

        assertEquals(3.75f, vm.state.value.gasPrice, 0f)
        assertEquals(false, vm.state.value.isGasPriceAuto)
        assertEquals(GasPriceSource.Typed, vm.state.value.gasPriceSource)
        assertEquals(false, vm.state.value.gasPriceLookupFailed)
    }

    @Test
    fun `retry looks the price up again`() = runTest {
        val vm = wizard(auto = true, price = 3.29f, stamp = EARLIER)
        assertEquals(true, vm.state.value.gasPriceLookupFailed)

        lookupReturns(FuelType.REGULAR to 3.10f)
        vm.attemptAutoGasPriceFetch()
        testScheduler.advanceUntilIdle()

        verify(gasPriceRepository, times(2)).lookUp()
        assertEquals(3.10f, vm.state.value.gasPrice, 0f)
        assertEquals(GasPriceSource.Lookup(CURRENT), vm.state.value.gasPriceSource)
        assertEquals(false, vm.state.value.gasPriceLookupFailed)
    }

    @Test
    fun `turning auto off makes the price typed`() = runTest {
        val vm = wizard(auto = true, price = 3.29f, stamp = EARLIER)
        vm.toggleAutoGasPrice(false)

        assertEquals(GasPriceSource.Typed, vm.state.value.gasPriceSource)
        assertEquals(false, vm.state.value.gasPriceLookupFailed)
    }

    @Test
    fun `switching fuel reads the one lookup's map, and a fuel missing from it keeps the figure`() = runTest {
        val vm = wizard(auto = true, price = 3.29f, stamp = EARLIER)
        lookupReturns(FuelType.REGULAR to 3.10f, FuelType.PREMIUM to 3.90f)
        vm.attemptAutoGasPriceFetch()
        testScheduler.advanceUntilIdle()

        vm.updateFuelType(FuelType.PREMIUM)
        assertEquals(3.90f, vm.state.value.gasPrice, 0f)
        assertEquals(GasPriceSource.Lookup(CURRENT), vm.state.value.gasPriceSource)

        vm.updateFuelType(FuelType.DIESEL)
        testScheduler.advanceUntilIdle()
        assertEquals(3.90f, vm.state.value.gasPrice, 0f)
        assertEquals(GasPriceSource.Lookup(CURRENT), vm.state.value.gasPriceSource)
        assertEquals(true, vm.state.value.gasPriceLookupFailed)
        // The load's lookup and the attempt above; the fuel switches looked nothing up.
        verify(gasPriceRepository, times(2)).lookUp()
    }

    @Test
    fun `finish saves the lookup stamp with a looked-up price and none with a typed one`() = runTest {
        val looked = wizard(auto = true, price = 3.29f, stamp = EARLIER)
        lookupReturns(FuelType.REGULAR to 3.10f)
        looked.attemptAutoGasPriceFetch()
        testScheduler.advanceUntilIdle()
        looked.save()
        testScheduler.advanceUntilIdle()
        verify(appPreferencesRepository).mirrorEconomySettings(any(), any(), any(), any(), any(), eq(true), eq(3.10f), eq(CURRENT))

        looked.updateGasPrice(3.75f)
        looked.save()
        testScheduler.advanceUntilIdle()
        verify(appPreferencesRepository).mirrorEconomySettings(any(), any(), any(), any(), any(), eq(false), eq(3.75f), isNull())
        verify(appPreferencesRepository, times(2)).mirrorEconomySettings(any(), any(), any(), any(), any(), any(), any(), anyOrNull())
    }

    @Test
    fun `a price typed then auto on again with a failed lookup saves with no stamp and reloads as saved`() = runTest {
        val vm = wizard(auto = true, price = 3.29f, stamp = EARLIER)
        vm.updateGasPrice(3.75f)
        vm.toggleAutoGasPrice(true)
        testScheduler.advanceUntilIdle()
        assertEquals(true, vm.state.value.gasPriceLookupFailed)
        assertEquals(GasPriceSource.Typed, vm.state.value.gasPriceSource)

        vm.save()
        testScheduler.advanceUntilIdle()

        verify(appPreferencesRepository).mirrorEconomySettings(any(), any(), any(), any(), any(), eq(true), eq(3.75f), isNull())
        // The data source removes the stamp on a null save, so a reload reads auto on with no stamp.
        assertEquals(GasPriceSource.Saved, GasPriceSource.stored(isAuto = true, stamp = null))
    }

    @Test
    fun `turning auto off or typing a price cancels the lookup in flight`() = runTest {
        val vm = wizard(auto = false, price = 3.29f, stamp = null)
        var cancelled = 0
        whenever(gasPriceRepository.lookUp()).doSuspendableAnswer {
            try {
                awaitCancellation()
            } finally {
                cancelled++
            }
        }

        vm.toggleAutoGasPrice(true)
        testScheduler.advanceUntilIdle()
        assertEquals(true, vm.state.value.isFetchingGasPrice)
        vm.toggleAutoGasPrice(false)
        testScheduler.advanceUntilIdle()
        assertEquals(1, cancelled)
        assertEquals(false, vm.state.value.isFetchingGasPrice)

        vm.toggleAutoGasPrice(true)
        testScheduler.advanceUntilIdle()
        vm.updateGasPrice(3.75f)
        testScheduler.advanceUntilIdle()
        assertEquals(2, cancelled)
        assertEquals(false, vm.state.value.isFetchingGasPrice)
        assertEquals(GasPriceSource.Typed, vm.state.value.gasPriceSource)
    }

    @Test
    fun `a fuel change while the lookup runs shares it, and its answer is the new fuel's`() = runTest {
        val vm = wizard(auto = false, price = 3.29f, stamp = null)
        val answer = CompletableDeferred<FuelLookup>()
        whenever(gasPriceRepository.lookUp()).doSuspendableAnswer { answer.await() }

        vm.toggleAutoGasPrice(true)
        testScheduler.advanceUntilIdle()
        vm.updateFuelType(FuelType.PREMIUM)
        testScheduler.advanceUntilIdle()
        answer.complete(FuelLookup(mapOf(
            FuelType.REGULAR to FetchedGasPrice(3.10f, CURRENT),
            FuelType.PREMIUM to FetchedGasPrice(3.90f, CURRENT),
        )))
        testScheduler.advanceUntilIdle()

        verify(gasPriceRepository, times(1)).lookUp()
        assertEquals(FuelType.PREMIUM, vm.state.value.fuelType)
        assertEquals(3.90f, vm.state.value.gasPrice, 0f)
        assertEquals(GasPriceSource.Lookup(CURRENT), vm.state.value.gasPriceSource)
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000

        /** 2026-11-16T00:00Z, a Monday, issued two days later. */
        val CURRENT = FuelPriceStamp(1_794_787_200_000L, 1_794_787_200_000L + 2 * DAY)

        /** Three weeks before [CURRENT]. */
        val EARLIER = FuelPriceStamp(CURRENT.period - 21 * DAY, CURRENT.issuedAt - 21 * DAY)
    }
}
