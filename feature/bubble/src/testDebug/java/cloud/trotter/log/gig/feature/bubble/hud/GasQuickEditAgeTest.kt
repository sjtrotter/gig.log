package cloud.trotter.log.gig.feature.bubble.hud

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.fuel.FuelPriceStamp
import cloud.trotter.log.gig.feature.bubble.MutableClock
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** The bubble's gas row derives its source and age from the price's stamp and the ticking clock, with no new model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GasQuickEditAgeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val day = 24L * 60 * 60 * 1000

    /** 2026-11-16T00:00Z, a Monday, issued two days later. */
    private val stamp = FuelPriceStamp(1_794_787_200_000L, 1_794_787_200_000L + 2 * day)
    private val clock = MutableClock(stamp.issuedAt + day)
    private lateinit var locale: Locale

    @Before fun setup() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        compose.mainClock.autoAdvance = false
    }

    @After fun cleanup() { Locale.setDefault(locale) }

    private fun show(model: GasRowModel) {
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    GasRow(model, onSetPrice = {}, onRefresh = {}, onResumeAuto = {})
                }
            }
        }
        settle()
    }

    private fun settle() = compose.mainClock.advanceTimeBy(2_000)

    /** The feed generation passes 21 days; the ticker reads it within a minute. */
    private fun ageThePrice() {
        clock.millis = stamp.issuedAt + 21 * day + 1
        compose.mainClock.advanceTimeBy(61_000)
    }

    @Test fun `the auto caption reads the week and turns older with the clock`() {
        show(GasRowModel(3.05f, auto = true, stamp = stamp))
        compose.onNodeWithText("Auto").performClick()
        settle()
        // DIAGNOSTIC (remove before merge): the tree the assertion below searches.
        println(compose.onRoot(useUnmergedTree = true).printToString())
        compose.onNodeWithText("EIA, week of Nov 16").assertExists()

        ageThePrice()

        compose.onNodeWithText("Older price: EIA, week of Nov 16").assertExists()
        compose.onNodeWithText("EIA, week of Nov 16").assertDoesNotExist()
    }

    @Test fun `with no stamp the auto caption stays Auto`() {
        show(GasRowModel(3.05f, auto = true))
        compose.onNodeWithText("Auto").performClick()
        settle()
        compose.onNodeWithText("EIA, week of", substring = true).assertDoesNotExist()
    }

    @Test fun `the collapsed row shows Older price in place of Auto once the price is older`() {
        show(GasRowModel(3.05f, auto = true, stamp = stamp))
        compose.onNodeWithText("Auto").assertExists()

        ageThePrice()

        compose.onNodeWithText("Older price").assertExists()
        compose.onNodeWithText("Auto").assertDoesNotExist()
    }

    @Test fun `a manual price never reads older`() {
        show(GasRowModel(3.05f, auto = false, stamp = stamp))
        ageThePrice()
        compose.onNodeWithText("Manual").assertExists()
        compose.onNodeWithText("Older price").assertDoesNotExist()
    }
}
