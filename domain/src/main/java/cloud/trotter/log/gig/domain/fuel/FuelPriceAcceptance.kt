package cloud.trotter.log.gig.domain.fuel

import cloud.trotter.log.gig.domain.model.vehicle.FuelType
import java.math.BigDecimal

internal const val DAY_MS = 24L * 60 * 60 * 1000

/** A fuel payload as read: each price is the decimal built from the JSON number's own text. */
data class FuelPayload(val period: Long, val prices: Map<FuelArea, Map<FuelType, BigDecimal>>)

/** A pair's price in an earlier EIA week, the reference its next price is bounded against. */
data class FuelAnchor(val period: Long, val price: BigDecimal)

/**
 * What travels with an accepted fuel generation: [week] is its accepted prices, [anchors] the working anchors it was
 * checked against (after the drops of anchors more than 8 weeks old).
 */
data class FuelCarry(val week: FuelPayload, val anchors: Map<FuelArea, Map<FuelType, FuelAnchor>>)

sealed interface FuelAcceptance {
    data class Ready(val prices: FuelPrices, val carry: FuelCarry) : FuelAcceptance

    /** The generation is refused whole; the feed keeps its last-good. [reason] is a closed code. */
    data class Rejected(val reason: String) : FuelAcceptance
}

/**
 * The fuel feed's bounds, the same rules the publisher applies (`docs/feeds/FORMAT.md` § Bounds per pair):
 *
 * 1. A generation whose `period` is not 00:00 UTC of a Monday, is before 1990, or is later than its `issued_at` is
 *    refused before any anchor moves; so is one whose week is older than the live one ([stage] only).
 * 2. A new week rolls the live week into the anchors: each pair it carried becomes that pair's anchor, and a pair it
 *    omitted keeps its older anchor. A correction of the live week (the same `period`) keeps the live anchors, so
 *    repeated corrections cannot walk a price.
 * 3. A pair is omitted unless its price is within 0.50 to 15.00. An anchor more than 8 weeks older than `period` is
 *    dropped; with an anchor left, a pair is omitted when it moved more than 40 % from it. Prices compare as decimals.
 *
 * An empty accepted set is still ready: a bad week stalls no one, and the anchors survive it.
 */
object FuelPriceAcceptance {
    val MIN_PRICE: BigDecimal = BigDecimal("0.50")
    val MAX_PRICE: BigDecimal = BigDecimal("15.00")
    val MAX_MOVE: BigDecimal = BigDecimal("0.40")
    const val ANCHOR_MAX_AGE_MS: Long = 8 * 7 * DAY_MS

    /** 1990-01-01T00:00Z, the earliest week the publisher accepts. */
    private const val FIRST_PERIOD_MS: Long = 7_305 * DAY_MS

    /** Checks a new generation against the [live] one's carry (null when no generation is live). */
    fun stage(payload: FuelPayload, issuedAt: Long, live: FuelCarry?): FuelAcceptance {
        refusal(payload.period, issuedAt)?.let { return FuelAcceptance.Rejected(it) }
        if (live != null && payload.period < live.week.period) return FuelAcceptance.Rejected("period_backwards")
        val working = when {
            live == null -> emptyMap()
            payload.period > live.week.period -> rolled(live)
            else -> live.anchors
        }
        return accept(payload, working)
    }

    /**
     * Rebuilds a committed generation from its [own] carry, whose anchors are already that generation's working set.
     * Gives the same result [stage] gave it.
     */
    fun restore(payload: FuelPayload, issuedAt: Long, own: FuelCarry?): FuelAcceptance {
        refusal(payload.period, issuedAt)?.let { return FuelAcceptance.Rejected(it) }
        return accept(payload, own?.anchors.orEmpty())
    }

    private fun refusal(period: Long, issuedAt: Long): String? = when {
        Math.floorMod(period, DAY_MS) != 0L || Math.floorMod(period / DAY_MS + 3, 7L) != 0L -> "period_not_monday"
        period < FIRST_PERIOD_MS -> "period_too_early"
        period > issuedAt -> "period_after_issue"
        else -> null
    }

    /** The live anchors with every pair of the live week written over them as that week's anchor. */
    private fun rolled(live: FuelCarry): Map<FuelArea, Map<FuelType, FuelAnchor>> {
        val out = mutableAnchors(live.anchors)
        for ((area, fuels) in live.week.prices) {
            val anchors = out.getOrPut(area) { LinkedHashMap() }
            for ((fuel, price) in fuels) anchors[fuel] = FuelAnchor(live.week.period, price)
        }
        return out
    }

    private fun mutableAnchors(
        anchors: Map<FuelArea, Map<FuelType, FuelAnchor>>,
    ): MutableMap<FuelArea, MutableMap<FuelType, FuelAnchor>> {
        val out = LinkedHashMap<FuelArea, MutableMap<FuelType, FuelAnchor>>()
        for ((area, fuels) in anchors) out[area] = LinkedHashMap(fuels)
        return out
    }

    private fun accept(payload: FuelPayload, anchors: Map<FuelArea, Map<FuelType, FuelAnchor>>): FuelAcceptance.Ready {
        val working = mutableAnchors(anchors)
        val accepted = LinkedHashMap<FuelArea, MutableMap<FuelType, BigDecimal>>()
        for ((area, fuels) in payload.prices) {
            for ((fuel, price) in fuels) {
                if (price < MIN_PRICE || price > MAX_PRICE) continue
                var anchor = working[area]?.get(fuel)
                if (anchor != null && payload.period - anchor.period > ANCHOR_MAX_AGE_MS) {
                    working[area]?.remove(fuel)
                    anchor = null
                }
                if (anchor != null && (price - anchor.price).abs() > MAX_MOVE * anchor.price) continue
                accepted.getOrPut(area) { LinkedHashMap() }[fuel] = price
            }
        }
        val prices = accepted.mapValues { (_, fuels) -> fuels.mapValues { (_, price) -> price.toDouble() } }
        val carry = FuelCarry(FuelPayload(payload.period, accepted), working.filterValues { it.isNotEmpty() })
        return FuelAcceptance.Ready(FuelPrices(payload.period, prices), carry)
    }
}
