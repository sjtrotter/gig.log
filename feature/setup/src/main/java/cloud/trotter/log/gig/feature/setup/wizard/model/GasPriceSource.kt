package cloud.trotter.log.gig.feature.setup.wizard.model

import cloud.trotter.log.gig.domain.fuel.FuelPriceStamp

/**
 * Where the wizard's gas price figure came from. State holds this anchor; the card derives its line from it and the
 * composition clock, so "older" is decided at render time.
 */
sealed interface GasPriceSource {
    /** A fuel feed lookup returned the figure; [stamp] is its EIA week and feed generation. */
    data class Lookup(val stamp: FuelPriceStamp) : GasPriceSource

    /** The dasher typed the figure, or auto is off. */
    data object Typed : GasPriceSource

    /** Auto is on and no feed stamp is stored (a price saved before the stamp existed). */
    data object Saved : GasPriceSource

    companion object {
        /** The source of a stored price: auto off marks a typed one, the stamp a looked-up one. */
        fun stored(isAuto: Boolean, stamp: FuelPriceStamp?): GasPriceSource = when {
            !isAuto -> Typed
            stamp != null -> Lookup(stamp)
            else -> Saved
        }
    }
}
