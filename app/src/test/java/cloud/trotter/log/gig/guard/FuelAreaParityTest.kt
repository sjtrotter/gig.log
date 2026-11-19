package cloud.trotter.log.gig.guard

import cloud.trotter.log.gig.domain.fuel.FuelArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app's area table and the fuel publisher name the same areas: [FuelArea]'s codes equal the publisher's `AREAS`
 * tuple, and every area it publishes diesel for (`DIESEL_AREAS`) is one the app knows. A code only one side knows
 * would be a price the app drops or an area it offers with nothing behind it.
 */
class FuelAreaParityTest {

    private val publisher = File(RepoRoot.locate(), "tools/feeds/publish_fuel.py").readText()

    private fun tuple(name: String): List<String> {
        val body = requireNotNull(Regex("""(?m)^$name = \(([^)]*)\)""").find(publisher)) { "$name not found" }.groupValues[1]
        val withoutComments = body.lines().joinToString("\n") { it.substringBefore('#') }
        return Regex("\"([A-Z0-9]+)\"").findAll(withoutComments).map { it.groupValues[1] }.toList()
    }

    @Test fun `the app's area codes equal the publisher's AREAS`() {
        val areas = tuple("AREAS")
        assertEquals(29, areas.size)
        assertEquals(areas.sorted(), FuelArea.entries.map { it.code }.sorted())
    }

    @Test fun `every diesel area the publisher names is an app area`() {
        val diesel = tuple("DIESEL_AREAS")
        assertTrue(diesel.isNotEmpty())
        assertEquals(emptyList<String>(), diesel.filter { FuelArea.fromCode(it) == null })
    }
}
