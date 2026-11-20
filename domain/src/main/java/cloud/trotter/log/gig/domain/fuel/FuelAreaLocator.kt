package cloud.trotter.log.gig.domain.fuel

import cloud.trotter.log.gig.domain.model.location.Coordinates

/**
 * Picks the fuel price area for a GPS fix, on the phone, for the automatic area setting (#1687). Pure: no network, no
 * storage, no logging; the caller keeps only the returned area.
 *
 * 1. Within [CITIES]' radius of a city centre → that city (the nearest centre when two overlap).
 * 2. Else the state from [StateOutlines]: a state series ([FuelArea.statePostal]) → it; otherwise the deepest region
 *    whose [FuelArea.memberStates] lists the state (Georgia → Lower Atlantic, Nevada → West Coast except California);
 *    a state no region lists (Puerto Rico) → [FuelArea.US].
 * 3. Outside every state, or a non-finite or out-of-range fix → null, and the caller keeps the area it had.
 */
object FuelAreaLocator {

    private class City(val area: FuelArea, val centre: Coordinates, val radiusMeters: Double)

    private fun city(area: FuelArea, latitude: Double, longitude: Double, radiusKm: Int) =
        City(area, Coordinates(latitude, longitude), radiusKm * 1_000.0)

    /** Approximations of the EIA city series' metro areas: a centre and a radius each. */
    private val CITIES = listOf(
        city(FuelArea.BOSTON, 42.3601, -71.0589, 40),
        city(FuelArea.CHICAGO, 41.8781, -87.6298, 50),
        city(FuelArea.CLEVELAND, 41.4993, -81.6944, 35),
        city(FuelArea.DENVER, 39.7392, -104.9903, 40),
        city(FuelArea.HOUSTON, 29.7604, -95.3698, 60),
        city(FuelArea.LOS_ANGELES, 34.0522, -118.2437, 60),
        city(FuelArea.MIAMI, 25.7617, -80.1918, 45),
        city(FuelArea.NEW_YORK_CITY, 40.7128, -74.0060, 50),
        city(FuelArea.SAN_FRANCISCO, 37.7749, -122.4194, 50),
        city(FuelArea.SEATTLE, 47.6062, -122.3321, 45),
    )

    /** The area for a fix, or null when it places in no state. */
    fun locate(latitude: Double, longitude: Double, outlines: StateOutlines = StateOutlines.bundled()): FuelArea? {
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        val fix = Coordinates(latitude, longitude)
        var nearest: City? = null
        var nearestDistance = Double.POSITIVE_INFINITY
        for (city in CITIES) {
            val distance = fix.distanceTo(city.centre)
            if (distance <= city.radiusMeters && distance < nearestDistance) {
                nearest = city
                nearestDistance = distance
            }
        }
        if (nearest != null) return nearest.area
        return outlines.stateAt(latitude, longitude)?.let(::areaForState)
    }

    private val stateSeries: Map<String, FuelArea> =
        FuelArea.entries.mapNotNull { area -> area.statePostal?.let { it to area } }.toMap()

    /** The area that prices a state: its own series, else its deepest region, else the U.S. */
    internal fun areaForState(postal: String): FuelArea =
        stateSeries[postal] ?: deepestRegions(postal).firstOrNull() ?: FuelArea.US

    /** Every region listing [postal] at the greatest depth; the table is well formed when this has at most one. */
    internal fun deepestRegions(postal: String): List<FuelArea> {
        val listing = FuelArea.entries.filter { it.kind == FuelArea.Kind.REGION && postal in it.memberStates }
        val deepest = listing.maxOfOrNull(::depth) ?: return emptyList()
        return listing.filter { depth(it) == deepest }
    }

    private fun depth(area: FuelArea): Int = generateSequence(area.parent) { it.parent }.count()
}
