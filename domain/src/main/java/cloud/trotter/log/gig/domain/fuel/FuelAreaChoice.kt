package cloud.trotter.log.gig.domain.fuel

/**
 * The dasher's fuel price area setting (#1687): [Automatic], the area the phone last worked out from a shift's GPS
 * fixes ([FuelAreaLocator]), or an area [Picked] by hand. [effective] is the area the gas price reads.
 */
sealed interface FuelAreaChoice {
    val effective: FuelArea

    /** [detected] is null until the first fix places in an area; the U.S. price applies until then. */
    data class Automatic(val detected: FuelArea?) : FuelAreaChoice {
        override val effective: FuelArea get() = detected ?: FuelArea.US
    }

    data class Picked(val area: FuelArea) : FuelAreaChoice {
        override val effective: FuelArea get() = area
    }
}
