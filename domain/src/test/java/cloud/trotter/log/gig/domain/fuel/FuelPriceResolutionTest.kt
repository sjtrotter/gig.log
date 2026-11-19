package cloud.trotter.log.gig.domain.fuel

import cloud.trotter.log.gig.domain.model.vehicle.FuelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A price missing for the chosen area comes from the next area up that has one. */
class FuelPriceResolutionTest {
    private val period = 1_794_787_200_000L

    private val prices = FuelPrices(
        period,
        mapOf(
            FuelArea.US to mapOf(FuelType.REGULAR to 3.05, FuelType.DIESEL to 3.71),
            FuelArea.GULF_COAST to mapOf(FuelType.REGULAR to 2.70, FuelType.DIESEL to 3.40),
            FuelArea.TEXAS to mapOf(FuelType.MIDGRADE to 3.10),
            FuelArea.HOUSTON to mapOf(FuelType.REGULAR to 2.60),
            FuelArea.WEST_COAST to mapOf(FuelType.DIESEL to 4.90),
            FuelArea.WEST_COAST_EXCEPT_CALIFORNIA to mapOf(FuelType.DIESEL to 4.50),
            FuelArea.SEATTLE to mapOf(FuelType.REGULAR to 4.20),
        ),
    )

    @Test fun `Texas diesel falls to the Gulf Coast`() {
        assertEquals(ResolvedFuelPrice(3.40, FuelArea.GULF_COAST, period), FuelPriceResolution.resolve(prices, FuelArea.TEXAS, FuelType.DIESEL))
    }

    @Test fun `Houston diesel falls through Texas to the Gulf Coast`() {
        assertEquals(ResolvedFuelPrice(3.40, FuelArea.GULF_COAST, period), FuelPriceResolution.resolve(prices, FuelArea.HOUSTON, FuelType.DIESEL))
    }

    @Test fun `Seattle diesel falls to the West Coast except California`() {
        assertEquals(
            ResolvedFuelPrice(4.50, FuelArea.WEST_COAST_EXCEPT_CALIFORNIA, period),
            FuelPriceResolution.resolve(prices, FuelArea.SEATTLE, FuelType.DIESEL),
        )
    }

    @Test fun `an omitted pair falls to the next area up`() {
        assertEquals(ResolvedFuelPrice(2.70, FuelArea.GULF_COAST, period), FuelPriceResolution.resolve(prices, FuelArea.TEXAS, FuelType.REGULAR))
    }

    @Test fun `the chosen area's own pair wins`() {
        assertEquals(ResolvedFuelPrice(2.60, FuelArea.HOUSTON, period), FuelPriceResolution.resolve(prices, FuelArea.HOUSTON, FuelType.REGULAR))
        assertEquals(ResolvedFuelPrice(3.10, FuelArea.TEXAS, period), FuelPriceResolution.resolve(prices, FuelArea.TEXAS, FuelType.MIDGRADE))
    }

    @Test fun `nothing in the chain is null`() {
        assertNull(FuelPriceResolution.resolve(prices, FuelArea.HOUSTON, FuelType.PREMIUM))
        assertNull(FuelPriceResolution.resolve(FuelPrices(period, emptyMap()), FuelArea.US, FuelType.REGULAR))
    }

    @Test fun `electricity never resolves`() {
        val withAll = FuelPrices(period, mapOf(FuelArea.US to FuelType.entries.associateWith { 1.0 }))
        assertNull(FuelPriceResolution.resolve(withAll, FuelArea.US, FuelType.ELECTRICITY))
    }
}
