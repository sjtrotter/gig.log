package cloud.trotter.log.gig.domain.fuel

import cloud.trotter.log.gig.domain.model.vehicle.FuelType

/**
 * One accepted week of the fuel feed: every (area, fuel) price that passed the bounds, in U.S. dollars per gallon.
 * [period] is 00:00 UTC of the Monday EIA dates the week by. A pair the feed omitted or the bounds refused is absent.
 */
data class FuelPrices(val period: Long, val prices: Map<FuelArea, Map<FuelType, Double>>)

/** The price used for a chosen area and fuel: [area] is where it was found, the chosen one or an area above it. */
data class ResolvedFuelPrice(val price: Double, val area: FuelArea, val period: Long)

object FuelPriceResolution {
    /**
     * Walks [chosen], then each [FuelArea.parent] up to [FuelArea.US], and returns the first area that has a price for
     * [fuel]. Null when no area in the chain has one, and always for [FuelType.ELECTRICITY].
     */
    fun resolve(prices: FuelPrices, chosen: FuelArea, fuel: FuelType): ResolvedFuelPrice? {
        if (FuelKey.of(fuel) == null) return null
        var area: FuelArea? = chosen
        while (area != null) {
            val price = prices.prices[area]?.get(fuel)
            if (price != null) return ResolvedFuelPrice(price, area, prices.period)
            area = area.parent
        }
        return null
    }
}
