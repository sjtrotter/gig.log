package cloud.trotter.log.gig.ui.main.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.text.fuelPriceSourceText
import cloud.trotter.log.gig.core.designsystem.time.rememberNow
import cloud.trotter.log.gig.domain.format.formatMonthDay
import cloud.trotter.log.gig.domain.fuel.FuelArea
import cloud.trotter.log.gig.domain.fuel.FuelPriceAge
import cloud.trotter.log.gig.domain.fuel.FuelPriceStamp

/**
 * The Costs screen's fuel price state, anchors only: the chosen [area], the stored price's [stamp], the area whose
 * price is used when it is not the chosen one ([usedArea]), and whether the feed refused a generation because the
 * phone's clock is behind. The labels derive from these and the ticking clock.
 */
data class FuelPriceUiState(
    val area: FuelArea = FuelArea.US,
    val stamp: FuelPriceStamp? = null,
    val usedArea: FuelArea? = null,
    val clockBehind: Boolean = false,
)

/** Each area's display name. */
@StringRes
fun FuelArea.nameRes(): Int = when (this) {
    FuelArea.US -> R.string.fuel_area_us
    FuelArea.EAST_COAST -> R.string.fuel_area_east_coast
    FuelArea.NEW_ENGLAND -> R.string.fuel_area_new_england
    FuelArea.CENTRAL_ATLANTIC -> R.string.fuel_area_central_atlantic
    FuelArea.LOWER_ATLANTIC -> R.string.fuel_area_lower_atlantic
    FuelArea.MIDWEST -> R.string.fuel_area_midwest
    FuelArea.GULF_COAST -> R.string.fuel_area_gulf_coast
    FuelArea.ROCKY_MOUNTAIN -> R.string.fuel_area_rocky_mountain
    FuelArea.WEST_COAST -> R.string.fuel_area_west_coast
    FuelArea.WEST_COAST_EXCEPT_CALIFORNIA -> R.string.fuel_area_west_coast_except_california
    FuelArea.CALIFORNIA -> R.string.fuel_area_california
    FuelArea.COLORADO -> R.string.fuel_area_colorado
    FuelArea.FLORIDA -> R.string.fuel_area_florida
    FuelArea.MASSACHUSETTS -> R.string.fuel_area_massachusetts
    FuelArea.MINNESOTA -> R.string.fuel_area_minnesota
    FuelArea.NEW_YORK -> R.string.fuel_area_new_york
    FuelArea.OHIO -> R.string.fuel_area_ohio
    FuelArea.TEXAS -> R.string.fuel_area_texas
    FuelArea.WASHINGTON -> R.string.fuel_area_washington
    FuelArea.BOSTON -> R.string.fuel_area_boston
    FuelArea.CHICAGO -> R.string.fuel_area_chicago
    FuelArea.CLEVELAND -> R.string.fuel_area_cleveland
    FuelArea.DENVER -> R.string.fuel_area_denver
    FuelArea.HOUSTON -> R.string.fuel_area_houston
    FuelArea.LOS_ANGELES -> R.string.fuel_area_los_angeles
    FuelArea.MIAMI -> R.string.fuel_area_miami
    FuelArea.NEW_YORK_CITY -> R.string.fuel_area_new_york_city
    FuelArea.SAN_FRANCISCO -> R.string.fuel_area_san_francisco
    FuelArea.SEATTLE -> R.string.fuel_area_seattle
}

/** The "Fuel price area" row; a tap opens the single-choice list. */
@Composable
fun FuelAreaRow(area: FuelArea, enabled: Boolean, onPick: (FuelArea) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.economy_fuel_area_title)) },
        supportingContent = { Text(stringResource(area.nameRes())) },
        modifier = Modifier.clickable(enabled = enabled) { picking = true },
    )
    if (picking) {
        FuelAreaDialog(area, onPick = { picking = false; onPick(it) }, onDismiss = { picking = false })
    }
}

/** U.S. first, then regions (with their states), states and cities, each group alphabetical by its shown name. */
@Composable
private fun FuelAreaDialog(selected: FuelArea, onPick: (FuelArea) -> Unit, onDismiss: () -> Unit) {
    val groups = listOf(
        null to listOf(FuelArea.US),
        R.string.economy_fuel_area_group_regions to FuelArea.entries.filter { it.kind == FuelArea.Kind.REGION },
        R.string.economy_fuel_area_group_states to FuelArea.entries.filter { it.kind == FuelArea.Kind.STATE },
        R.string.economy_fuel_area_group_cities to FuelArea.entries.filter { it.kind == FuelArea.Kind.CITY },
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.economy_fuel_area_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for ((header, areas) in groups) {
                    if (header != null) {
                        Text(
                            stringResource(header),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    val named = areas.map { it to stringResource(it.nameRes()) }.sortedBy { it.second }
                    for ((area, name) in named) {
                        ListItem(
                            headlineContent = { Text(name) },
                            supportingContent = area.memberStates.takeIf { it.isNotEmpty() }?.let { { Text(it.joinToString(", ")) } },
                            leadingContent = { RadioButton(selected = area == selected, onClick = null) },
                            modifier = Modifier.fillMaxWidth()
                                .selectable(selected = area == selected, role = Role.RadioButton) { onPick(area) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
    )
}

/**
 * The lines under the gas price while auto is on: the price's source (older once its feed lapsed or its week is
 * more than 14 days old), the area used when the chosen one has no price for this fuel, and the clock-behind line.
 */
@Composable
fun FuelPriceLines(state: FuelPriceUiState) {
    val now by rememberNow(tickMs = 60_000)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    state.stamp?.let { stamp ->
        Text(fuelPriceSourceText(formatMonthDay(FuelPriceAge.weekOf(stamp)), FuelPriceAge.isOlder(stamp, now)), color = muted)
    }
    state.usedArea?.let { used ->
        Text(stringResource(R.string.economy_fuel_area_using, stringResource(used.nameRes())), color = muted)
    }
    if (state.clockBehind) {
        Text(stringResource(R.string.economy_fuel_clock_behind), color = MaterialTheme.colorScheme.error)
    }
}
