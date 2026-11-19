package cloud.trotter.log.gig.ui.main.setup.wizard

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.fuel.FuelPriceStamp
import cloud.trotter.log.gig.feature.setup.wizard.WizardViewModel
import cloud.trotter.log.gig.feature.setup.wizard.model.GasPriceSource
import cloud.trotter.log.gig.feature.setup.wizard.model.SaveStatus
import cloud.trotter.log.gig.feature.setup.wizard.model.WizardState
import cloud.trotter.log.gig.feature.setup.wizard.model.WizardStep
import cloud.trotter.log.gig.feature.setup.wizard.vehicle.VehicleDraft
import cloud.trotter.log.gig.feature.setup.wizard.vehicle.VehicleDraftState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** The setup card's gas price line, rendered through the wizard host against a clock the test owns. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1600dp")
class GasPriceCardTest {
    @get:Rule val compose = createComposeRule()
    private val day = 24L * 60 * 60 * 1000

    /** 2026-11-16T00:00Z, a Monday, issued two days later; and the week three weeks before. */
    private val nov16 = FuelPriceStamp(1_794_787_200_000L, 1_794_787_200_000L + 2 * day)
    private val oct26 = FuelPriceStamp(nov16.period - 21 * day, nov16.issuedAt - 21 * day)

    private val clock = MutableClock(nov16.issuedAt + day)
    private val state = MutableStateFlow(WizardState(isGasPriceAuto = true, gasPrice = 3.29f,
        gasPriceSource = GasPriceSource.Lookup(nov16)))
    private val vm: WizardViewModel = mock()

    private fun show() {
        val draft: VehicleDraft = mock()
        whenever(draft.state).thenReturn(MutableStateFlow(VehicleDraftState()))
        whenever(vm.vehicleDraft).thenReturn(draft)
        whenever(vm.state).thenReturn(state)
        whenever(vm.steps).thenReturn(MutableStateFlow(listOf(WizardStep.GAS_PRICE)))
        whenever(vm.saveStatus).thenReturn(MutableStateFlow(SaveStatus.Idle))
        whenever(vm.toggleAutoGasPrice(any())).thenAnswer {
            state.value = state.value.copy(isGasPriceAuto = it.getArgument(0))
        }
        whenever(vm.updateGasPrice(any())).thenAnswer {
            state.value = state.value.copy(gasPrice = it.getArgument(0), isGasPriceAuto = false,
                gasPriceSource = GasPriceSource.Typed, gasPriceLookupFailed = false)
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    WizardScreen(vm, onComplete = {}, onExit = {})
                }
            }
        }
        settle()
    }

    // A 1,000 ms advance rounds to whole frames and can miss the recomposition; 2,000 ms leaves headroom.
    private fun settle() = compose.mainClock.advanceTimeBy(2_000)

    private fun given(next: WizardState) {
        state.value = next
        settle()
    }

    private fun assertLine(text: String, retry: Boolean) {
        compose.onNodeWithText(text).assertExists()
        if (retry) compose.onNodeWithText("Retry").assertExists() else compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun `each source reads its own line and only a failure offers retry`() {
        show()
        assertLine("EIA, week of Nov 16", retry = false)

        given(state.value.copy(gasPriceSource = GasPriceSource.Lookup(oct26)))
        assertLine("Older price: EIA, week of Oct 26", retry = false)

        given(state.value.copy(gasPriceLookupFailed = true))
        assertLine("Older price: EIA, week of Oct 26", retry = true)
        compose.onNodeWithText("Couldn't check for a new price.").assertExists()

        given(state.value.copy(gasPriceSource = GasPriceSource.Saved))
        assertLine("Couldn't get a price. Enter your price.", retry = true)
        compose.onNodeWithText("EIA, week of", substring = true).assertDoesNotExist()

        given(state.value.copy(gasPriceLookupFailed = false))
        assertLine("Saved price", retry = false)

        given(state.value.copy(isGasPriceAuto = false, gasPriceSource = GasPriceSource.Typed))
        assertLine("Price you entered", retry = false)

        given(state.value.copy(isGasPriceAuto = true, gasPriceSource = GasPriceSource.Lookup(nov16),
            isFetchingGasPrice = true))
        compose.onNodeWithText("EIA, week of", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    private val priceInput = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)

    @Test
    fun `with auto on the price input appears only after a failed lookup`() {
        show()
        compose.onNode(priceInput).assertDoesNotExist()

        given(state.value.copy(isFetchingGasPrice = true))
        compose.onNode(priceInput).assertDoesNotExist()

        given(state.value.copy(isFetchingGasPrice = false, gasPriceLookupFailed = true))
        compose.onNode(priceInput).assertExists()
        assertLine("EIA, week of Nov 16", retry = true)
        compose.onNodeWithText("Couldn't check for a new price.").assertExists()
    }

    @Test
    fun `typing a price after a failed lookup turns the auto row off`() {
        state.value = state.value.copy(gasPriceLookupFailed = true)
        show()
        val row = compose.onNode(isToggleable() and hasText("Update gas price daily"))
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))

        compose.onNode(priceInput).performSemanticsAction(SemanticsActions.SetProgress) { it(4.0f) }
        settle()

        verify(vm).updateGasPrice(any())
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        assertLine("Price you entered", retry = false)
        compose.onNode(priceInput).assertExists()
    }

    @Test
    fun `retry looks the price up again`() {
        state.value = state.value.copy(gasPriceLookupFailed = true)
        show()
        compose.onNodeWithText("Retry").performClick()
        verify(vm).attemptAutoGasPriceFetch()
    }

    @Test
    fun `a card left open turns older with the clock, with no state change`() {
        clock.millis = nov16.period + 14 * day
        show()
        compose.onNodeWithText("EIA, week of Nov 16").assertExists()

        clock.millis = nov16.period + 14 * day + 30_000
        compose.mainClock.advanceTimeBy(61_000)

        compose.onNodeWithText("Older price: EIA, week of Nov 16").assertExists()
        compose.onNodeWithText("EIA, week of Nov 16").assertDoesNotExist()
    }

    @Test
    fun `the auto row is one switch named by its label`() {
        show()
        val row = compose.onNode(isToggleable() and hasText("Update gas price daily"))
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        // The switch inside is not a second control.
        compose.onNode(isToggleable() and hasAnyDescendant(hasText("Update gas price daily")), useUnmergedTree = true)
            .onChildren().filter(isToggleable()).assertCountEquals(0)

        compose.onNodeWithText("Update gas price daily", useUnmergedTree = true).performClick()
        settle()

        verify(vm).toggleAutoGasPrice(false)
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
    }

    private class MutableClock(var millis: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneId.systemDefault()
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(millis)
        override fun millis(): Long = millis
    }
}
