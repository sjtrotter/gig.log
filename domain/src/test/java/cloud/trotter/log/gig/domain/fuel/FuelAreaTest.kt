package cloud.trotter.log.gig.domain.fuel

import cloud.trotter.log.gig.domain.model.vehicle.FuelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The area table against the feed's own list (`docs/feeds/FORMAT.md` § Areas). */
class FuelAreaTest {
    private val formatAreas = listOf(
        "NUS", "R10", "R1X", "R1Y", "R1Z", "R20", "R30", "R40", "R50", "R5XCA",
        "SCA", "SCO", "SFL", "SMA", "SMN", "SNY", "SOH", "STX", "SWA",
        "YBOS", "YORD", "YCLE", "YDEN", "Y44HO", "Y05LA", "YMIA", "Y35NY", "Y05SF", "Y48SE",
    )

    @Test fun `the table holds the feed's 29 areas, each code once`() {
        assertEquals(29, FuelArea.entries.size)
        val codes = FuelArea.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        assertEquals(formatAreas.toSet(), codes.toSet())
    }

    @Test fun `every chain ends at the U_S_ within five steps`() {
        for (area in FuelArea.entries) {
            var current = area
            var steps = 0
            while (true) {
                current = current.parent ?: break
                steps++
            }
            assertEquals(area.name, FuelArea.US, current)
            assertTrue("${area.name} takes $steps steps", steps <= 5)
        }
    }

    @Test fun `each parent is declared before its child`() {
        for (area in FuelArea.entries) {
            val parent = area.parent ?: continue
            assertTrue(area.name, parent.ordinal < area.ordinal)
        }
    }

    @Test fun `codes resolve both ways, and an unknown code is null`() {
        for (area in FuelArea.entries) assertEquals(area, FuelArea.fromCode(area.code))
        assertNull(FuelArea.fromCode("SZZ"))
        assertNull(FuelArea.fromCode("nus"))
    }

    @Test fun `each region lists its member states`() {
        for (area in FuelArea.entries.filter { it.kind == FuelArea.Kind.REGION }) {
            assertTrue(area.name, area.memberStates.isNotEmpty())
        }
    }

    @Test fun `fuel keys match the feed, and electricity has none`() {
        assertEquals("regular", FuelKey.of(FuelType.REGULAR))
        assertEquals("midgrade", FuelKey.of(FuelType.MIDGRADE))
        assertEquals("premium", FuelKey.of(FuelType.PREMIUM))
        assertEquals("diesel", FuelKey.of(FuelType.DIESEL))
        assertNull(FuelKey.of(FuelType.ELECTRICITY))
        assertEquals(FuelType.DIESEL, FuelKey.fuelOf("diesel"))
        assertNull(FuelKey.fuelOf("electricity"))
    }
}
