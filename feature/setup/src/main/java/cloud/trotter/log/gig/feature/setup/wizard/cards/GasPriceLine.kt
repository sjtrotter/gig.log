package cloud.trotter.log.gig.feature.setup.wizard.cards

import cloud.trotter.log.gig.domain.fuel.FuelPriceAge
import cloud.trotter.log.gig.feature.setup.wizard.model.GasPriceSource
import java.time.LocalDate

/**
 * The line under the setup card's gas price: where the figure came from, or that the lookup failed. The EIA week is
 * only ever named for a figure a fuel feed lookup returned, with that figure's own week.
 */
sealed interface GasPriceLine {
    data class Current(val week: LocalDate) : GasPriceLine
    data class Older(val week: LocalDate) : GasPriceLine

    /** The lookup failed, and the shown figure is an earlier lookup's: its source line, then the failure line. */
    data class FailedUsing(val week: LocalDate, val older: Boolean) : GasPriceLine
    data object FailedNoLookup : GasPriceLine
    data object Typed : GasPriceLine
    data object Saved : GasPriceLine

    val offersRetry: Boolean get() = this is FailedUsing || this is FailedNoLookup

    companion object {
        /** [nowMillis] is the composition clock's reading, so a card left open relabels an older price itself. */
        fun of(source: GasPriceSource, lookupFailed: Boolean, nowMillis: Long): GasPriceLine {
            val stamp = (source as? GasPriceSource.Lookup)?.stamp
            return when {
                stamp == null && lookupFailed -> FailedNoLookup
                stamp == null -> if (source == GasPriceSource.Saved) Saved else Typed
                lookupFailed -> FailedUsing(FuelPriceAge.weekOf(stamp), FuelPriceAge.isOlder(stamp, nowMillis))
                FuelPriceAge.isOlder(stamp, nowMillis) -> Older(FuelPriceAge.weekOf(stamp))
                else -> Current(FuelPriceAge.weekOf(stamp))
            }
        }
    }
}
