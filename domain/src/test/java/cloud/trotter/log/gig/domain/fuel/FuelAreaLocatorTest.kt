package cloud.trotter.log.gig.domain.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** #1687: a fix picks a city, else its state's series, else the state's deepest region; outside every state, nothing. */
class FuelAreaLocatorTest {

    private fun assertLocates(expected: FuelArea?, latitude: Double, longitude: Double) =
        assertEquals("$latitude,$longitude", expected, FuelAreaLocator.locate(latitude, longitude))

    @Test fun `each city centre is its city`() {
        assertLocates(FuelArea.BOSTON, 42.3601, -71.0589)
        assertLocates(FuelArea.CHICAGO, 41.8781, -87.6298)
        assertLocates(FuelArea.CLEVELAND, 41.4993, -81.6944)
        assertLocates(FuelArea.DENVER, 39.7392, -104.9903)
        assertLocates(FuelArea.HOUSTON, 29.7604, -95.3698)
        assertLocates(FuelArea.LOS_ANGELES, 34.0522, -118.2437)
        assertLocates(FuelArea.MIAMI, 25.7617, -80.1918)
        assertLocates(FuelArea.NEW_YORK_CITY, 40.7128, -74.0060)
        assertLocates(FuelArea.SAN_FRANCISCO, 37.7749, -122.4194)
        assertLocates(FuelArea.SEATTLE, 47.6062, -122.3321)
    }

    @Test fun `a state series covers its state outside the city`() {
        assertLocates(FuelArea.TEXAS, 30.2672, -97.7431) // Austin
        assertLocates(FuelArea.CALIFORNIA, 38.5816, -121.4944) // Sacramento
        assertLocates(FuelArea.FLORIDA, 28.5383, -81.3792) // Orlando
        assertLocates(FuelArea.OHIO, 39.9612, -82.9988) // Columbus
        assertLocates(FuelArea.NEW_YORK, 42.6526, -73.7562) // Albany
        assertLocates(FuelArea.WASHINGTON, 47.6588, -117.4260) // Spokane
        assertLocates(FuelArea.COLORADO, 38.8339, -104.8214) // Colorado Springs
        assertLocates(FuelArea.MINNESOTA, 44.9778, -93.2650) // Minneapolis
    }

    @Test fun `any other state takes its deepest region`() {
        assertLocates(FuelArea.LOWER_ATLANTIC, 33.7490, -84.3880) // Atlanta
        assertLocates(FuelArea.MIDWEST, 39.7817, -89.6501) // Springfield, Illinois
        assertLocates(FuelArea.WEST_COAST_EXCEPT_CALIFORNIA, 39.5296, -119.8138) // Reno
        assertLocates(FuelArea.CENTRAL_ATLANTIC, 38.8977, -77.0365) // Washington, DC
        assertLocates(FuelArea.NEW_ENGLAND, 43.6591, -70.2568) // Portland, Maine
        assertLocates(FuelArea.ROCKY_MOUNTAIN, 40.7608, -111.8910) // Salt Lake City
        assertLocates(FuelArea.GULF_COAST, 32.2988, -90.1848) // Jackson, Mississippi
    }

    @Test fun `a state no region lists is the U_S_`() {
        assertLocates(FuelArea.US, 18.4655, -66.1057) // San Juan
    }

    @Test fun `outside every state, or a bad fix, is null`() {
        assertLocates(null, 26.0, -90.0) // Gulf of Mexico
        assertLocates(null, 43.6532, -79.3832) // Toronto
        assertLocates(null, Double.NaN, -90.0)
        assertLocates(null, 30.0, Double.NEGATIVE_INFINITY)
        assertLocates(null, 95.0, -90.0)
        assertLocates(null, 30.0, -190.0)
    }

    /**
     * Every state in the outlines except Puerto Rico has a price: its own series, or exactly one deepest region. Fails
     * when a state is dropped from every region list or two regions of the same depth both claim it.
     */
    @Test fun `every outlined state but Puerto Rico resolves to one state series or one deepest region`() {
        for (postal in StateOutlines.bundled().postalCodes) {
            val series = FuelArea.entries.filter { it.statePostal == postal }
            val regions = FuelAreaLocator.deepestRegions(postal)
            if (postal == "PR") {
                assertEquals(emptyList<FuelArea>(), series + regions)
                continue
            }
            assertEquals(postal, true, series.size <= 1 && regions.size <= 1)
            assertEquals(postal, 1, if (series.isNotEmpty()) 1 else regions.size)
            val expected = series.singleOrNull() ?: regions.single()
            assertEquals(postal, expected, FuelAreaLocator.areaForState(postal))
        }
    }

    @Test fun `each state series has a postal code and nothing else does`() {
        for (area in FuelArea.entries) {
            assertEquals(area.name, area.kind == FuelArea.Kind.STATE, area.statePostal != null)
        }
        val postals = FuelArea.entries.mapNotNull { it.statePostal }
        assertEquals(postals.size, postals.toSet().size)
        for (postal in postals) assertEquals(postal, true, postal in StateOutlines.bundled().postalCodes)
    }

    /** Cost on the JVM: printed for the record, never asserted (CI machines vary). */
    @Test fun `ten thousand lookups finish`() {
        val points = listOf(
            30.2672 to -97.7431, 33.7490 to -84.3880, 29.7604 to -95.3698, 26.0 to -90.0, 43.6532 to -79.3832,
            39.5296 to -119.8138, 61.2181 to -149.9003, 40.7608 to -111.8910, 42.6526 to -73.7562, 18.4655 to -66.1057,
        )
        repeat(1_000) { val (lat, lon) = points[it % points.size]; FuelAreaLocator.locate(lat, lon) } // warm up
        val calls = 10_000
        var located = 0
        val start = System.nanoTime()
        repeat(calls) {
            val (lat, lon) = points[it % points.size]
            if (FuelAreaLocator.locate(lat, lon) != null) located++
        }
        val meanMicros = (System.nanoTime() - start) / 1_000.0 / calls
        println(String.format(Locale.ROOT, "FuelAreaLocator: %d calls, mean %.2f us per call", calls, meanMicros))
        assertEquals(calls / points.size * 8, located)
    }
}
