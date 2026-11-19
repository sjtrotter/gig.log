package cloud.trotter.log.gig.core.designsystem.text

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cloud.trotter.log.gig.core.designsystem.R

/**
 * The source line of an automatic gas price, one owner for Settings, the bubble and setup. [week] is the EIA week's
 * Monday, already formatted; [older] is derived by the caller from the price's stamp and the ticking clock.
 */
@Composable
fun fuelPriceSourceText(week: String, older: Boolean): String =
    stringResource(if (older) R.string.fuel_price_older_week else R.string.fuel_price_source_week, week)

/** The short "Older price" label, for a surface with no room for the week. */
@Composable
fun fuelPriceOlderText(): String = stringResource(R.string.fuel_price_older)
