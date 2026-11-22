package cloud.trotter.log.gig.domain.state

import cloud.trotter.census.contract.CensusPlatforms
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1709: the platform registry and the census contract hold one list of platform wire ids. A platform added to
 * [Platform] without its [CensusPlatforms] entry (or the other way round) fails here, before the census server can
 * refuse that platform's screens.
 */
class PlatformWireContractTest {

    @Test
    fun `the registry's wires are the census contract's platform ids`() {
        assertEquals(CensusPlatforms.ALL.toSet(), Platform.entries.map { it.wire }.toSet())
        assertEquals(Platform.entries.size, Platform.entries.map { it.wire }.toSet().size)
    }

    @Test
    fun `the unresolved bucket is the contract's unknown id`() {
        assertEquals(CensusPlatforms.UNKNOWN, Platform.Unknown.wire)
    }
}
