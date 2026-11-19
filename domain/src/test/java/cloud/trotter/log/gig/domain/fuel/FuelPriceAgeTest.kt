package cloud.trotter.log.gig.domain.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

/** A stored feed price reads "older" from its two anchors and the current time alone. */
class FuelPriceAgeTest {
    private val day = 24L * 60 * 60 * 1000

    /** 2026-11-16T00:00Z, a Monday. */
    private val period = 1_794_787_200_000L

    @Test fun `a generation 21 days old is current, and a millisecond more is older`() {
        val issuedAt = period + 2 * day
        // A week that started after the issue time, so only the generation's age decides here.
        val stamp = FuelPriceStamp(issuedAt + 8 * day, issuedAt)
        assertFalse(FuelPriceAge.isOlder(stamp, issuedAt + 21 * day))
        assertTrue(FuelPriceAge.isOlder(stamp, issuedAt + 21 * day + 1))
    }

    @Test fun `a week that started 14 days ago is current, and a millisecond more is older`() {
        val stamp = FuelPriceStamp(period, period + 2 * day)
        assertFalse(FuelPriceAge.isOlder(stamp, period + 14 * day))
        assertTrue(FuelPriceAge.isOlder(stamp, period + 14 * day + 1))
    }

    @Test fun `a clock behind the generation's issue time reads current`() {
        val stamp = FuelPriceStamp(period, period + 2 * day)
        assertFalse(FuelPriceAge.isOlder(stamp, period + day))
        assertFalse(FuelPriceAge.isOlder(stamp, period - 30 * day))
    }

    @Test fun `the week is named by its Monday in UTC, whatever the phone's zone`() {
        val saved = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Chicago"))
            assertEquals(LocalDate.of(2026, 11, 16), FuelPriceAge.weekOf(FuelPriceStamp(period, period + 2 * day)))
        } finally {
            TimeZone.setDefault(saved)
        }
    }
}
