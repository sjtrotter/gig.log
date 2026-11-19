package cloud.trotter.log.gig.core.data.fuel

import cloud.trotter.log.gig.domain.fuel.FuelPriceStamp
import cloud.trotter.log.gig.domain.fuel.ResolvedFuelPrice

/** Whether the stored gas price must follow the feed: the one decision behind the fuel client's follow. */
object FuelPriceFollow {
    data class Write(val price: Float, val stamp: FuelPriceStamp)

    /**
     * The write that brings the stored price and stamp to the feed's, or null: with auto off, with no price resolved
     * for the chosen area and fuel (or no generation, so no [issuedAt]), or when the stored pair already matches.
     */
    fun next(
        auto: Boolean,
        resolved: ResolvedFuelPrice?,
        issuedAt: Long?,
        storedPrice: Float?,
        storedStamp: FuelPriceStamp?,
    ): Write? {
        if (!auto || resolved == null || issuedAt == null) return null
        val write = Write(resolved.price.toFloat(), FuelPriceStamp(resolved.period, issuedAt))
        return write.takeUnless { it.price == storedPrice && it.stamp == storedStamp }
    }
}
