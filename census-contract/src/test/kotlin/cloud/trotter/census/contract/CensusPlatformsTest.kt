package cloud.trotter.census.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1709: the one list of platform wire ids, which the app's registry and the server's policy both read. */
class CensusPlatformsTest {

    @Test
    fun `the list holds the five platform ids, each once`() {
        assertEquals(listOf("doordash", "uber", "instacart", "walmart_spark", "_unknown"), CensusPlatforms.ALL)
        assertEquals(CensusPlatforms.ALL.size, CensusPlatforms.ALL.toSet().size)
    }

    @Test
    fun `every id passes the platform grammar`() {
        CensusPlatforms.ALL.forEach { assertTrue(it, WireTokenGrammars.platform.matches(it)) }
    }

    @Test
    fun `the platform grammar refuses what the server refuses`() {
        listOf("", "DoorDash", "door.dash", "9doordash", "a".repeat(33), "door-dash").forEach {
            assertFalse(it, WireTokenGrammars.platform.matches(it))
        }
        assertTrue(WireTokenGrammars.platform.matches("a".repeat(32)))
    }
}
