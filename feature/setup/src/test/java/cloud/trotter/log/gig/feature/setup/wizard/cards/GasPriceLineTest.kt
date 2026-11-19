package cloud.trotter.log.gig.feature.setup.wizard.cards

import cloud.trotter.log.gig.domain.fuel.FuelPriceStamp
import cloud.trotter.log.gig.feature.setup.wizard.model.GasPriceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The EIA week is named only for a figure a fuel feed lookup returned, with that figure's week; a failure says so. */
class GasPriceLineTest {
    private val day = 24L * 60 * 60 * 1000

    /** 2026-11-16T00:00Z, a Monday, issued two days later. */
    private val nov16 = LocalDate.of(2026, 11, 16)
    private val stamp = FuelPriceStamp(1_794_787_200_000L, 1_794_787_200_000L + 2 * day)
    private val lookup = GasPriceSource.Lookup(stamp)

    private fun line(source: GasPriceSource, failed: Boolean = false, now: Long = stamp.issuedAt + day) =
        GasPriceLine.of(source, failed, now)

    @Test
    fun `a lookup reads its week, and older once the feed lapsed or the week is more than 14 days old`() {
        assertEquals(GasPriceLine.Current(nov16), line(lookup))
        assertEquals(GasPriceLine.Current(nov16), line(lookup, now = stamp.period + 14 * day))
        assertEquals(GasPriceLine.Older(nov16), line(lookup, now = stamp.period + 14 * day + 1))
        val lateWeek = GasPriceSource.Lookup(FuelPriceStamp(stamp.period, stamp.period + 13 * day))
        assertEquals(GasPriceLine.Older(nov16), line(lateWeek, now = stamp.period + 15 * day))
    }

    @Test
    fun `a failed lookup keeps the figure's week, or asks for a price when none was looked up`() {
        assertEquals(GasPriceLine.FailedUsing(nov16, older = false), line(lookup, failed = true))
        assertEquals(GasPriceLine.FailedUsing(nov16, older = true), line(lookup, failed = true, now = stamp.period + 20 * day))
        assertEquals(GasPriceLine.FailedNoLookup, line(GasPriceSource.Saved, failed = true))
        assertEquals(GasPriceLine.FailedNoLookup, line(GasPriceSource.Typed, failed = true))
    }

    @Test
    fun `typed and saved figures say so`() {
        assertEquals(GasPriceLine.Typed, line(GasPriceSource.Typed))
        assertEquals(GasPriceLine.Saved, line(GasPriceSource.Saved))
    }

    @Test
    fun `only a failure offers retry`() {
        assertTrue(line(GasPriceSource.Saved, failed = true).offersRetry)
        assertTrue(line(lookup, failed = true).offersRetry)
        assertFalse(line(lookup).offersRetry)
        assertFalse(line(GasPriceSource.Typed).offersRetry)
    }

    @Test
    fun `a stored price loads by its auto flag and stamp`() {
        assertEquals(GasPriceSource.Typed, GasPriceSource.stored(isAuto = false, stamp = stamp))
        assertEquals(lookup, GasPriceSource.stored(isAuto = true, stamp = stamp))
        assertEquals(GasPriceSource.Saved, GasPriceSource.stored(isAuto = true, stamp = null))
    }
}
