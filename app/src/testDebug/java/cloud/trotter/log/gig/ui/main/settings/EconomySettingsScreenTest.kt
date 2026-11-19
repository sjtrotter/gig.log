package cloud.trotter.log.gig.ui.main.settings

import androidx.compose.runtime.CompositionLocalProvider
import cloud.trotter.feed.contract.FeedRefusal
import cloud.trotter.log.gig.core.data.feed.FeedError
import cloud.trotter.log.gig.core.data.feed.FeedState
import cloud.trotter.log.gig.core.data.fuel.FuelPriceRepository
import cloud.trotter.log.gig.core.datastore.settings.StoredPreference
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.fuel.FuelArea
import cloud.trotter.log.gig.domain.fuel.FuelPriceStamp
import cloud.trotter.log.gig.domain.fuel.FuelPrices
import cloud.trotter.log.gig.domain.fuel.ResolvedFuelPrice
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.data.strategy.StrategyRepository
import cloud.trotter.log.gig.core.data.vehicle.*
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.domain.evaluation.UserEconomy
import cloud.trotter.log.gig.domain.model.vehicle.Vehicle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EconomySettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private var opened: String? = null
    private var garageOpened = false
    private lateinit var vm: EconomySettingsViewModel
    private val clock = MutableClock(0L)
    private fun show(
        active: Vehicle?,
        storedPreferences: AppPreferencesRepository? = null,
        fuelPrices: FuelPriceRepository = fuelPricesStub(),
        frozen: Boolean = false,
    ) {
        val garage: GarageRepository = mock { on { garage } doReturn flowOf(Garage(listOfNotNull(active), active?.id, true)) }
        val preferences: AppPreferencesRepository = storedPreferences ?: mock<AppPreferencesRepository> {
            on { userEconomy } doReturn flowOf(UserEconomy())
            on { isGasPriceAuto } doReturn flowOf(true)
        }.stubFuelPrice()
        val strategy: StrategyRepository = mock { on { evaluationConfig } doReturn MutableStateFlow(null) }
        compose.runOnIdle { vm = EconomySettingsViewModel(preferences, strategy, garage, fuelPrices) }
        if (frozen) compose.mainClock.autoAdvance = false
        compose.setContent { GigLogTheme {
            CompositionLocalProvider(LocalNowProvider provides clock) {
                EconomySettingsScreen({}, { garageOpened = true }, { opened = it }, vm)
            }
        } }
        if (frozen) settle() else compose.waitForIdle()
    }

    private fun settle() = compose.mainClock.advanceTimeBy(2_000)
    @Test fun `shared settings have no vehicle cost editors and active row opens its details`() {
        show(Vehicle("car-id", "Current car", 1))
        compose.onNodeWithText("Gas price").assertExists()
        compose.onNodeWithText("Electricity price").assertExists()
        for (label in listOf("Tires", "Oil changes", "Brakes", "Depreciation", "Insurance", "Combined MPG", "Vehicle class")) {
            compose.onNodeWithText(label).assertDoesNotExist()
        }
        compose.onNodeWithText("Current car").performClick()
        compose.waitUntil { opened != null }
        assertEquals("car-id", opened)
        assertFalse(garageOpened)
        // The same host can be revisited after navigating forward to vehicle details.
        compose.onNodeWithText("Reset to estimates").assertIsEnabled()
    }
    /** #1478: the Auto gas price row is one toggle; the label flips it and is read with the switch's state. */
    @Test fun `the auto gas price row is one switch named by its label`() {
        val auto = MutableStateFlow(true)
        val preferences: AppPreferencesRepository = mock {
            on { userEconomy } doReturn flowOf(UserEconomy())
            on { isGasPriceAuto } doReturn auto
        }.stubFuelPrice()
        runBlocking {
            whenever(preferences.setGasPriceAuto(any(), anyOrNull())).doAnswer {
                val previous = auto.value
                auto.value = it.getArgument(0)
                StoredPreference(previous, false)
            }
        }
        show(null, preferences)
        val row = compose.onNode(isToggleable() and hasText("Auto gas price"))
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        // The switch inside is not a second control.
        compose.onNode(isToggleable() and hasAnyDescendant(hasText("Auto gas price")), useUnmergedTree = true)
            .onChildren().filter(isToggleable()).assertCountEquals(0)

        compose.onNodeWithText("Auto gas price", useUnmergedTree = true).performClick()
        compose.waitUntil { !auto.value }
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
    }

    @Test fun `no vehicle row opens Garage`() {
        show(null)
        compose.onNodeWithText("No vehicle").performClick()
        compose.waitUntil { garageOpened }
        assertNull(opened)
    }

    @Test fun `finished phone lines offers snackbar undo restoring shown lines and retaining siblings`() {
        val economy = MutableStateFlow(UserEconomy(phonePlanTotal = 125.0, phonePlanLines = 2, phoneBusinessPercent = 45.0))
        val preferences: AppPreferencesRepository = mock {
            on { userEconomy } doReturn economy
            on { isGasPriceAuto } doReturn flowOf(false)
        }.stubFuelPrice()
        runBlocking {
            whenever(preferences.updatePhoneLines(any())).doAnswer {
                val previous = economy.value.phonePlanLines
                economy.value = economy.value.copy(phonePlanLines = it.getArgument(0))
                StoredPreference(previous, true)
            }
            whenever(preferences.restorePhoneLines(any(), any(), any())).doAnswer {
                val previous = it.getArgument<StoredPreference<Int>>(0)
                economy.value = economy.value.copy(phonePlanLines = requireNotNull(previous.value))
                true
            }
        }
        show(null, preferences)
        compose.onNodeWithText("Phone & data").performScrollTo().performClick()
        val input = compose.onNode(hasSetTextAction() and hasText("Lines"))
        input.performScrollTo().performTextReplacement("3")
        compose.onNodeWithText("Lines saved").assertDoesNotExist()
        input.performImeAction()
        compose.onNodeWithText("Lines saved").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil { economy.value.phonePlanLines == 2 }
        input.assertTextContains("2")
        assertEquals(125.0, economy.value.phonePlanTotal, 0.0)
        assertEquals(45.0, economy.value.phoneBusinessPercent, 0.0)
        compose.onNodeWithText("Lines saved").assertDoesNotExist()
    }


    // --- the fuel price area and the source line ---

    private val day = 24L * 60 * 60 * 1000

    /** 2026-11-16T00:00Z, a Monday, issued two days later. */
    private val stamp = FuelPriceStamp(1_794_787_200_000L, 1_794_787_200_000L + 2 * day)

    private fun fuelPreferences(area: FuelArea = FuelArea.US): AppPreferencesRepository = mock<AppPreferencesRepository> {
        on { userEconomy } doReturn flowOf(UserEconomy())
        on { isGasPriceAuto } doReturn flowOf(true)
    }.stubFuelPrice(area = flowOf(area), stamp = flowOf(stamp))

    @Test fun `the area list names all 29 areas and a pick reaches the fuel client`() {
        val fuelPrices = fuelPricesStub()
        show(null, fuelPreferences(), fuelPrices)
        compose.onNodeWithText("Fuel price area").performClick()
        compose.waitForIdle()
        for (name in listOf("Gulf Coast", "West Coast except California", "Texas", "Houston", "New York City", "Seattle")) {
            compose.onNode(hasText(name) and hasClickAction()).assertExists()
        }
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertCountEquals(29)
        compose.onNodeWithText("Houston").performScrollTo().performClick()
        compose.waitForIdle()
        runBlocking { verify(fuelPrices).setArea(FuelArea.HOUSTON) }
    }

    @Test fun `the source line names the week and turns older with the clock, with no state change`() {
        clock.millis = stamp.issuedAt + day
        show(null, fuelPreferences(), frozen = true)
        compose.onNodeWithText("EIA, week of Nov 16").assertExists()

        // The feed generation more than 21 days old.
        clock.millis = stamp.issuedAt + 21 * day + 1
        compose.mainClock.advanceTimeBy(61_000)
        compose.onNodeWithText("Older price: EIA, week of Nov 16").assertExists()
        compose.onNodeWithText("EIA, week of Nov 16").assertDoesNotExist()
    }

    @Test fun `a week more than 14 days old reads older though its generation is current`() {
        val lateIssue = FuelPriceStamp(stamp.period, stamp.period + 13 * day)
        clock.millis = stamp.period + 14 * day
        val preferences = mock<AppPreferencesRepository> {
            on { userEconomy } doReturn flowOf(UserEconomy())
            on { isGasPriceAuto } doReturn flowOf(true)
        }.stubFuelPrice(stamp = flowOf(lateIssue))
        show(null, preferences, frozen = true)
        compose.onNodeWithText("EIA, week of Nov 16").assertExists()

        clock.millis = stamp.period + 14 * day + 1
        compose.mainClock.advanceTimeBy(61_000)
        compose.onNodeWithText("Older price: EIA, week of Nov 16").assertExists()
    }

    @Test fun `a clock-behind refusal says so`() {
        clock.millis = stamp.issuedAt + day
        val feed = MutableStateFlow(FeedState<FuelPrices>(lastError = FeedError.Refused(FeedRefusal.ClockBehind)))
        show(null, fuelPreferences(), fuelPricesStub(feedState = feed), frozen = true)
        compose.onNodeWithText("Phone clock is behind. New gas prices load once the date and time are right.").assertExists()
    }

    @Test fun `a price from an area above the chosen one names that area`() {
        clock.millis = stamp.issuedAt + day
        val resolved = flowOf(ResolvedFuelPrice(3.4, FuelArea.GULF_COAST, stamp.period))
        show(null, fuelPreferences(FuelArea.TEXAS), fuelPricesStub(resolved = resolved), frozen = true)
        compose.onNodeWithText("Using the Gulf Coast price").assertExists()
        compose.onNodeWithText("Texas").assertExists()
    }

    private class MutableClock(var millis: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneId.systemDefault()
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(millis)
        override fun millis(): Long = millis
    }
}
