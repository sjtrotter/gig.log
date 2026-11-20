package cloud.trotter.log.gig.domain.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** #1687: the bundled state outlines decode, place known points in the right state, and reject corrupt input. */
class StateOutlinesTest {
    private val outlines = StateOutlines.bundled()

    private fun bundledBytes(): ByteArray =
        requireNotNull(StateOutlines::class.java.getResourceAsStream(StateOutlines.RESOURCE)).use { it.readBytes() }

    @Test fun `the bundled file holds the 50 states, DC and Puerto Rico`() {
        assertEquals(52, outlines.postalCodes.size)
        assertEquals(outlines.postalCodes.sorted(), outlines.postalCodes)
        assertEquals(true, "DC" in outlines.postalCodes && "PR" in outlines.postalCodes)
    }

    @Test fun `known points land in their state`() {
        assertEquals("TX", outlines.stateAt(30.2672, -97.7431)) // Austin
        assertEquals("GA", outlines.stateAt(33.7490, -84.3880)) // Atlanta
        assertEquals("IL", outlines.stateAt(39.7817, -89.6501)) // Springfield
        assertEquals("HI", outlines.stateAt(21.3069, -157.8583)) // Honolulu
        assertEquals("AK", outlines.stateAt(61.2181, -149.9003)) // Anchorage
        assertEquals("DC", outlines.stateAt(38.8977, -77.0365)) // Washington
        assertEquals("PR", outlines.stateAt(18.4655, -66.1057)) // San Juan
    }

    @Test fun `points outside every state are null`() {
        assertNull(outlines.stateAt(26.0, -90.0)) // Gulf of Mexico
        assertNull(outlines.stateAt(43.6532, -79.3832)) // Toronto
        assertNull(outlines.stateAt(Double.NaN, -90.0))
        assertNull(outlines.stateAt(95.0, -90.0))
        assertNull(outlines.stateAt(30.0, Double.POSITIVE_INFINITY))
    }

    @Test fun `a bad magic is rejected`() {
        val bytes = bundledBytes().also { it[0] = 'X'.code.toByte() }
        assertThrows(IllegalArgumentException::class.java) { StateOutlines.decode(bytes) }
    }

    @Test fun `a bad version is rejected`() {
        val bytes = bundledBytes().also { it[4] = 2 }
        assertThrows(IllegalArgumentException::class.java) { StateOutlines.decode(bytes) }
    }

    @Test fun `truncated data is rejected`() {
        val bytes = bundledBytes()
        for (size in listOf(0, 3, 9, bytes.size / 2, bytes.size - 1)) {
            assertThrows(IllegalArgumentException::class.java) { StateOutlines.decode(bytes.copyOf(size)) }
        }
    }

    @Test fun `trailing bytes are rejected`() {
        val bytes = bundledBytes()
        assertThrows(IllegalArgumentException::class.java) { StateOutlines.decode(bytes + byteArrayOf(0)) }
    }

    @Test fun `a state count over 64 is rejected`() {
        // magic, version 1, scale 10000 (0x90 0x4E), count 65.
        val bytes = byteArrayOf('G'.code.toByte(), 'L'.code.toByte(), 'S'.code.toByte(), 'O'.code.toByte(), 1, 0x90.toByte(), 0x4E, 65)
        val error = assertThrows(IllegalArgumentException::class.java) { StateOutlines.decode(bytes) }
        assertEquals(true, error.message!!.contains("count"))
    }

    @Test fun `a ring over 64k points is rejected`() {
        val header = byteArrayOf('G'.code.toByte(), 'L'.code.toByte(), 'S'.code.toByte(), 'O'.code.toByte(), 1, 0x90.toByte(), 0x4E, 1)
        // "AA", bbox 0,0,0,0, one ring of 65,537 points (0x81 0x80 0x04).
        val state = byteArrayOf('A'.code.toByte(), 'A'.code.toByte(), 0, 0, 0, 0, 1, 0x81.toByte(), 0x80.toByte(), 0x04)
        val error = assertThrows(IllegalArgumentException::class.java) { StateOutlines.decode(header + state) }
        assertEquals(true, error.message!!.contains("ring size"))
    }

    @Test fun `a postal code that is not two capital letters is rejected`() {
        val header = byteArrayOf('G'.code.toByte(), 'L'.code.toByte(), 'S'.code.toByte(), 'O'.code.toByte(), 1, 0x90.toByte(), 0x4E, 1)
        val state = byteArrayOf('a'.code.toByte(), '1'.code.toByte(), 0, 0, 0, 0, 1, 3, 0, 0, 0, 0, 0, 0)
        val error = assertThrows(IllegalArgumentException::class.java) { StateOutlines.decode(header + state) }
        assertEquals(true, error.message!!.contains("postal"))
    }

    @Test fun `a hand-built square with a hole decodes and casts rays across both rings`() {
        val header = byteArrayOf('G'.code.toByte(), 'L'.code.toByte(), 'S'.code.toByte(), 'O'.code.toByte(), 1, 0x90.toByte(), 0x4E, 1)
        // "AA": outer square 0..4 x 0..4 (scaled units), hole 1..3 x 1..3. Zigzag: 2n for n >= 0, 2|n|-1 for n < 0.
        val outer = byteArrayOf(4, 0, 0, 8, 0, 0, 8, 7, 0) // (0,0) (4,0) (4,4) (0,4): deltas (0,0) (4,0) (0,4) (-4,0)
        val hole = byteArrayOf(4, 2, 2, 4, 0, 0, 4, 3, 0)  // (1,1) (3,1) (3,3) (1,3): deltas (1,1) (2,0) (0,2) (-2,0)
        val state = byteArrayOf('A'.code.toByte(), 'A'.code.toByte(), 0, 0, 8, 8, 2) + outer + hole
        val square = StateOutlines.decode(header + state)
        val unit = 1e-4 // one scaled unit in degrees
        assertEquals("AA", square.stateAt(0.5 * unit, 0.5 * unit))
        assertNull("inside the hole", square.stateAt(2 * unit, 2 * unit))
        assertNull("outside", square.stateAt(5 * unit, 5 * unit))
    }
}
