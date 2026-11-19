package cloud.trotter.log.gig.domain.fuel

import cloud.trotter.feed.contract.Feed
import cloud.trotter.feed.contract.FeedFreshness
import java.time.LocalDate

/**
 * Where a stored feed price came from: [period] is its EIA week (00:00 UTC of that Monday) and [issuedAt] the feed
 * generation's `issued_at`. The UI derives every label from these two anchors and the current time.
 */
data class FuelPriceStamp(val period: Long, val issuedAt: Long)

object FuelPriceAge {
    /** A price whose EIA week started more than this long ago is labelled older, whatever the feed's age. */
    const val MAX_PERIOD_AGE_MS: Long = 14 * DAY_MS

    /**
     * True when the feed generation is past its freshness window ([FeedFreshness], which owns the 21 days) or the
     * week itself started more than [MAX_PERIOD_AGE_MS] before [nowMs]. A clock behind [FuelPriceStamp.issuedAt]
     * reads current.
     */
    fun isOlder(stamp: FuelPriceStamp, nowMs: Long): Boolean =
        FeedFreshness.of(Feed.FUEL, stamp.issuedAt, nowMs) == FeedFreshness.OLD || nowMs - stamp.period > MAX_PERIOD_AGE_MS

    /** The Monday that names the week, in UTC: the phone's zone would show the Sunday before in U.S. zones. */
    fun weekOf(stamp: FuelPriceStamp): LocalDate = LocalDate.ofEpochDay(Math.floorDiv(stamp.period, DAY_MS))
}
