package cloud.trotter.log.gig.domain.fuel

import cloud.trotter.log.gig.domain.model.vehicle.FuelType

/**
 * The areas gig.log's fuel feed publishes, each under EIA's area code exactly as the feed spells it
 * (`docs/feeds/FORMAT.md` § Areas). Nothing else in the app spells an area code.
 *
 * [parent] is the next area up the chain city, state, sub-PADD, PADD, U.S.; a price missing for an area is looked up
 * along that chain ([FuelPriceResolution]). Entries are declared parents first, so an entry names only earlier ones.
 * [memberStates] are the postal codes of a region's states: the area picker's hint and [FuelAreaLocator]'s state to
 * region join; resolution never reads them. [statePostal] is a state series' own postal code, spelled out rather than
 * derived from the EIA code, and null for every other kind.
 */
enum class FuelArea(
    val code: String,
    val parent: FuelArea?,
    val kind: Kind,
    val memberStates: List<String> = emptyList(),
    val statePostal: String? = null,
) {
    US("NUS", null, Kind.COUNTRY),
    EAST_COAST(
        "R10", US, Kind.REGION,
        listOf("CT", "ME", "MA", "NH", "RI", "VT", "DE", "DC", "MD", "NJ", "NY", "PA", "FL", "GA", "NC", "SC", "VA", "WV"),
    ),
    NEW_ENGLAND("R1X", EAST_COAST, Kind.REGION, listOf("CT", "ME", "MA", "NH", "RI", "VT")),
    CENTRAL_ATLANTIC("R1Y", EAST_COAST, Kind.REGION, listOf("DE", "DC", "MD", "NJ", "NY", "PA")),
    LOWER_ATLANTIC("R1Z", EAST_COAST, Kind.REGION, listOf("FL", "GA", "NC", "SC", "VA", "WV")),
    MIDWEST(
        "R20", US, Kind.REGION,
        listOf("IL", "IN", "IA", "KS", "KY", "MI", "MN", "MO", "NE", "ND", "SD", "OH", "OK", "TN", "WI"),
    ),
    GULF_COAST("R30", US, Kind.REGION, listOf("AL", "AR", "LA", "MS", "NM", "TX")),
    ROCKY_MOUNTAIN("R40", US, Kind.REGION, listOf("CO", "ID", "MT", "UT", "WY")),
    WEST_COAST("R50", US, Kind.REGION, listOf("AK", "AZ", "CA", "HI", "NV", "OR", "WA")),
    WEST_COAST_EXCEPT_CALIFORNIA("R5XCA", WEST_COAST, Kind.REGION, listOf("AK", "AZ", "HI", "NV", "OR", "WA")),
    CALIFORNIA("SCA", WEST_COAST, Kind.STATE, statePostal = "CA"),
    COLORADO("SCO", ROCKY_MOUNTAIN, Kind.STATE, statePostal = "CO"),
    FLORIDA("SFL", LOWER_ATLANTIC, Kind.STATE, statePostal = "FL"),
    MASSACHUSETTS("SMA", NEW_ENGLAND, Kind.STATE, statePostal = "MA"),
    MINNESOTA("SMN", MIDWEST, Kind.STATE, statePostal = "MN"),
    NEW_YORK("SNY", CENTRAL_ATLANTIC, Kind.STATE, statePostal = "NY"),
    OHIO("SOH", MIDWEST, Kind.STATE, statePostal = "OH"),
    TEXAS("STX", GULF_COAST, Kind.STATE, statePostal = "TX"),
    WASHINGTON("SWA", WEST_COAST_EXCEPT_CALIFORNIA, Kind.STATE, statePostal = "WA"),
    BOSTON("YBOS", MASSACHUSETTS, Kind.CITY),
    CHICAGO("YORD", MIDWEST, Kind.CITY),
    CLEVELAND("YCLE", OHIO, Kind.CITY),
    DENVER("YDEN", COLORADO, Kind.CITY),
    HOUSTON("Y44HO", TEXAS, Kind.CITY),
    LOS_ANGELES("Y05LA", CALIFORNIA, Kind.CITY),
    MIAMI("YMIA", FLORIDA, Kind.CITY),
    NEW_YORK_CITY("Y35NY", NEW_YORK, Kind.CITY),
    SAN_FRANCISCO("Y05SF", CALIFORNIA, Kind.CITY),
    SEATTLE("Y48SE", WASHINGTON, Kind.CITY),
    ;

    enum class Kind { COUNTRY, REGION, STATE, CITY }

    companion object {
        private val byCode = entries.associateBy { it.code }

        /** The area the feed calls [code], or null for a code this build does not know. */
        fun fromCode(code: String): FuelArea? = byCode[code]
    }
}

/** The fuel feed's key for each fuel type (`regular`, `midgrade`, `premium`, `diesel`). Electricity has none. */
object FuelKey {
    fun of(fuel: FuelType): String? = when (fuel) {
        FuelType.REGULAR -> "regular"
        FuelType.MIDGRADE -> "midgrade"
        FuelType.PREMIUM -> "premium"
        FuelType.DIESEL -> "diesel"
        FuelType.ELECTRICITY -> null
    }

    private val byKey: Map<String, FuelType> = FuelType.entries.mapNotNull { fuel -> of(fuel)?.let { it to fuel } }.toMap()

    /** The fuel type the feed calls [key], or null for a key this build does not know. */
    fun fuelOf(key: String): FuelType? = byKey[key]
}
