package cloud.trotter.log.gig.core.data.fuel

import cloud.trotter.log.gig.domain.fuel.FuelArea
import cloud.trotter.log.gig.domain.fuel.FuelPriceStamp
import cloud.trotter.log.gig.domain.fuel.ResolvedFuelPrice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The follow's one decision: write the feed's price and stamp while auto is on and they differ from the stored pair. */
class FuelPriceFollowTest {
    private val period = 1_794_787_200_000L
    private val issuedAt = period + 2 * 24 * 60 * 60 * 1000L
    private val resolved = ResolvedFuelPrice(3.051, FuelArea.US, period)
    private val stamp = FuelPriceStamp(period, issuedAt)

    @Test fun `auto on and a new resolved price writes it with its stamp`() {
        assertEquals(FuelPriceFollow.Write(3.051f, stamp), FuelPriceFollow.next(true, resolved, issuedAt, 3.00f, null))
    }

    @Test fun `a new generation of the same price writes the new stamp`() {
        val older = FuelPriceStamp(period, issuedAt - 1)
        assertEquals(FuelPriceFollow.Write(3.051f, stamp), FuelPriceFollow.next(true, resolved, issuedAt, 3.051f, older))
    }

    @Test fun `an equal price and stamp writes nothing`() {
        assertNull(FuelPriceFollow.next(true, resolved, issuedAt, 3.051f, stamp))
    }

    @Test fun `auto off writes nothing`() {
        assertNull(FuelPriceFollow.next(false, resolved, issuedAt, 3.00f, null))
    }

    @Test fun `nothing resolved, or no generation, writes nothing`() {
        assertNull(FuelPriceFollow.next(true, null, issuedAt, 3.00f, null))
        assertNull(FuelPriceFollow.next(true, resolved, null, 3.00f, null))
    }
}
