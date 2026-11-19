package cloud.trotter.log.gig.domain.fuel

import cloud.trotter.log.gig.domain.model.vehicle.FuelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** The app applies the publisher's bounds per pair (`docs/feeds/FORMAT.md` § Bounds per pair). */
class FuelPriceAcceptanceTest {
    private val day = 24L * 60 * 60 * 1000
    private val week = 7 * day

    /** 2026-11-16T00:00Z, a Monday. */
    private val p0 = 1_794_787_200_000L
    private val p1 = p0 + week
    private val p2 = p1 + week

    private fun issued(period: Long) = period + 2 * day

    private fun payload(period: Long, vararg pairs: Pair<Pair<FuelArea, FuelType>, String>): FuelPayload {
        val prices = LinkedHashMap<FuelArea, MutableMap<FuelType, BigDecimal>>()
        for ((key, price) in pairs) prices.getOrPut(key.first) { LinkedHashMap() }[key.second] = BigDecimal(price)
        return FuelPayload(period, prices)
    }

    private fun stage(payload: FuelPayload, live: FuelCarry? = null): FuelAcceptance.Ready =
        FuelPriceAcceptance.stage(payload, issued(payload.period), live) as FuelAcceptance.Ready

    private fun rejected(payload: FuelPayload, issuedAt: Long = issued(payload.period), live: FuelCarry? = null): String =
        (FuelPriceAcceptance.stage(payload, issuedAt, live) as FuelAcceptance.Rejected).reason

    private val usRegular = FuelArea.US to FuelType.REGULAR
    private val usDiesel = FuelArea.US to FuelType.DIESEL

    private fun FuelAcceptance.Ready.price(area: FuelArea, fuel: FuelType): Double? = prices.prices[area]?.get(fuel)

    @Test fun `prices outside 0_50 to 15_00 are omitted, and resolution falls up the chain`() {
        val ready = stage(
            payload(
                p0,
                usRegular to "0.49",
                (FuelArea.US to FuelType.MIDGRADE) to "0.50",
                (FuelArea.US to FuelType.PREMIUM) to "15.00",
                usDiesel to "15.01",
                (FuelArea.TEXAS to FuelType.REGULAR) to "0.49",
                (FuelArea.GULF_COAST to FuelType.REGULAR) to "3.00",
            ),
        )
        assertEquals(null, ready.price(FuelArea.US, FuelType.REGULAR))
        assertEquals(0.50, ready.price(FuelArea.US, FuelType.MIDGRADE))
        assertEquals(15.00, ready.price(FuelArea.US, FuelType.PREMIUM))
        assertEquals(null, ready.price(FuelArea.US, FuelType.DIESEL))
        assertEquals(
            ResolvedFuelPrice(3.00, FuelArea.GULF_COAST, p0),
            FuelPriceResolution.resolve(ready.prices, FuelArea.TEXAS, FuelType.REGULAR),
        )
    }

    @Test fun `a move of exactly 40 percent passes, and one cent more is omitted`() {
        val live = stage(payload(p0, usRegular to "3.500")).carry
        assertEquals(4.900, stage(payload(p1, usRegular to "4.900"), live).price(FuelArea.US, FuelType.REGULAR))
        assertEquals(null, stage(payload(p1, usRegular to "4.901"), live).price(FuelArea.US, FuelType.REGULAR))
        assertEquals(2.100, stage(payload(p1, usRegular to "2.100"), live).price(FuelArea.US, FuelType.REGULAR))
        assertEquals(null, stage(payload(p1, usRegular to "2.099"), live).price(FuelArea.US, FuelType.REGULAR))
    }

    @Test fun `corrections of one week compare to the same anchor, so they cannot walk a price`() {
        val first = stage(payload(p0, usRegular to "3.00")).carry
        val week1 = stage(payload(p1, usRegular to "4.20"), first)
        assertEquals(4.20, week1.price(FuelArea.US, FuelType.REGULAR))
        val corrected = stage(payload(p1, usRegular to "5.88"), week1.carry)
        assertEquals(null, corrected.price(FuelArea.US, FuelType.REGULAR))
        assertEquals(FuelAnchor(p0, BigDecimal("3.00")), corrected.carry.anchors[FuelArea.US]?.get(FuelType.REGULAR))
    }

    @Test fun `a new week rolls the live week into the anchors`() {
        val first = stage(payload(p0, usRegular to "3.00")).carry
        val week1 = stage(payload(p1, usRegular to "3.20"), first).carry
        val week2 = stage(payload(p2, usRegular to "3.25"), week1)
        assertEquals(FuelAnchor(p1, BigDecimal("3.20")), week2.carry.anchors[FuelArea.US]?.get(FuelType.REGULAR))
        assertEquals(payload(p2, usRegular to "3.25"), week2.carry.week)
    }

    @Test fun `a pair omitted for a week keeps its older anchor`() {
        val first = stage(payload(p0, usRegular to "3.00", usDiesel to "4.00")).carry
        val week1 = stage(payload(p1, usDiesel to "4.10"), first).carry
        val week2 = stage(payload(p2, usRegular to "4.30"), week1)
        assertEquals("4.30 moved over 40 % from the week-0 anchor", null, week2.price(FuelArea.US, FuelType.REGULAR))
        assertEquals(FuelAnchor(p0, BigDecimal("3.00")), week2.carry.anchors[FuelArea.US]?.get(FuelType.REGULAR))
        assertEquals(FuelAnchor(p1, BigDecimal("4.10")), week2.carry.anchors[FuelArea.US]?.get(FuelType.DIESEL))
    }

    @Test fun `an anchor exactly 8 weeks old applies, and one past 8 weeks is dropped`() {
        fun live(anchorPeriod: Long) = FuelCarry(
            week = payload(p0),
            anchors = mapOf(FuelArea.US to mapOf(FuelType.REGULAR to FuelAnchor(anchorPeriod, BigDecimal("3.00")))),
        )
        val atLimit = stage(payload(p1, usRegular to "5.00"), live(p1 - 8 * week))
        assertEquals(null, atLimit.price(FuelArea.US, FuelType.REGULAR))
        assertEquals(FuelAnchor(p1 - 8 * week, BigDecimal("3.00")), atLimit.carry.anchors[FuelArea.US]?.get(FuelType.REGULAR))

        val past = stage(payload(p1, usRegular to "5.00"), live(p1 - 8 * week - 1))
        assertEquals("only the absolute range applies", 5.00, past.price(FuelArea.US, FuelType.REGULAR))
        assertTrue(past.carry.anchors.isEmpty())
        assertEquals(null, stage(payload(p1, usRegular to "15.01"), live(p1 - 8 * week - 1)).price(FuelArea.US, FuelType.REGULAR))
    }

    @Test fun `a bad period refuses the whole generation`() {
        assertEquals("period_not_monday", rejected(payload(p0 + day, usRegular to "3.00")))
        assertEquals("period_not_monday", rejected(payload(p0 + 1, usRegular to "3.00")))
        assertEquals("period_after_issue", rejected(payload(p0, usRegular to "3.00"), issuedAt = p0 - 1))
        val live = stage(payload(p1, usRegular to "3.00")).carry
        assertEquals("period_backwards", rejected(payload(p0, usRegular to "3.00"), live = live))
        // 1989-12-25, a Monday before the publisher's first accepted week.
        val early = 7_298 * day
        assertEquals("period_too_early", rejected(payload(early, usRegular to "3.00")))
    }

    @Test fun `an empty accepted set is still ready, and its anchors survive`() {
        val first = stage(payload(p0, usRegular to "3.00")).carry
        val bad = stage(payload(p1, usRegular to "99.00"), first)
        assertTrue(bad.prices.prices.isEmpty())
        assertEquals(p1, bad.prices.period)
        assertEquals(FuelAnchor(p0, BigDecimal("3.00")), bad.carry.anchors[FuelArea.US]?.get(FuelType.REGULAR))
    }

    @Test fun `restore from the generation's own carry gives what stage gave`() {
        val live = FuelCarry(
            week = payload(p0, usRegular to "3.00", usDiesel to "4.00"),
            anchors = mapOf(
                FuelArea.TEXAS to mapOf(FuelType.REGULAR to FuelAnchor(p1 - 9 * week, BigDecimal("2.00"))),
                FuelArea.US to mapOf(FuelType.PREMIUM to FuelAnchor(p0 - week, BigDecimal("4.00"))),
            ),
        )
        val next = payload(
            p1,
            usRegular to "3.10",
            usDiesel to "6.00",
            (FuelArea.US to FuelType.PREMIUM) to "4.05",
            (FuelArea.TEXAS to FuelType.REGULAR) to "3.50",
            (FuelArea.HOUSTON to FuelType.REGULAR) to "0.10",
        )
        val staged = FuelPriceAcceptance.stage(next, issued(p1), live) as FuelAcceptance.Ready
        assertEquals(staged, FuelPriceAcceptance.restore(next, issued(p1), staged.carry))
        assertEquals(null, staged.price(FuelArea.US, FuelType.DIESEL))
        assertEquals(3.50, staged.price(FuelArea.TEXAS, FuelType.REGULAR))
    }

    @Test fun `restore still refuses a bad period, but not a week older than another`() {
        assertTrue(FuelPriceAcceptance.restore(payload(p0 + day), issued(p0), null) is FuelAcceptance.Rejected)
        val newer = stage(payload(p1, usRegular to "3.00")).carry
        assertTrue(FuelPriceAcceptance.restore(payload(p0, usRegular to "3.00"), issued(p0), newer) is FuelAcceptance.Ready)
    }
}
