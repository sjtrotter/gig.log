package cloud.trotter.log.gig.domain.fuel

/**
 * Simplified U.S. state outlines (the 50 states, DC and Puerto Rico), bundled in the app and read on the phone to
 * place a GPS fix in a state for the automatic fuel price area ([FuelAreaLocator]). Nothing here touches the network,
 * and no fix is kept.
 *
 * The data is the Census Bureau's 2024 1:20,000,000 cartographic boundary file, encoded by
 * `tools/fuel-areas/gen_state_outlines.py` (format v1, described there and in `tools/fuel-areas/README.md`). The
 * simplification can place a fix within a few km of a state line in the neighbouring state.
 *
 * Coordinates are held as degrees x [SCALE]; [stateAt] scales its input to match rather than converting every vertex.
 */
class StateOutlines private constructor(private val states: List<State>) {

    private class State(
        val postal: String,
        val minLon: Int,
        val minLat: Int,
        val maxLon: Int,
        val maxLat: Int,
        /** Each ring as interleaved (lon, lat) pairs, scaled. */
        val rings: List<IntArray>,
    )

    /** The postal codes in the file, sorted. */
    val postalCodes: List<String> get() = states.map { it.postal }

    /**
     * The postal code of the state whose outline holds this point, or null outside every outline (ocean, Canada,
     * Mexico) or for a non-finite or out-of-range input. A point exactly on an edge may land on either side.
     */
    fun stateAt(latitude: Double, longitude: Double): String? {
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        val x = longitude * SCALE
        val y = latitude * SCALE
        for (state in states) {
            if (x < state.minLon || x > state.maxLon || y < state.minLat || y > state.maxLat) continue
            if (contains(state, x, y)) return state.postal
        }
        return null
    }

    /** Even-odd ray casting across every ring of the state at once, so holes and separate parts both count. */
    private fun contains(state: State, x: Double, y: Double): Boolean {
        var inside = false
        for (ring in state.rings) {
            val n = ring.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val xi = ring[2 * i].toDouble()
                val yi = ring[2 * i + 1].toDouble()
                val xj = ring[2 * j].toDouble()
                val yj = ring[2 * j + 1].toDouble()
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
        }
        return inside
    }

    companion object {
        const val RESOURCE = "/fuel/state-outlines.bin"
        private const val VERSION = 1
        private const val SCALE = 10_000
        private const val MAX_STATES = 64
        private const val MAX_RINGS = 65_536
        private const val MAX_POINTS_PER_RING = 65_536
        private const val MAX_LON = 180 * SCALE
        private const val MAX_LAT = 90 * SCALE
        private val MAGIC = byteArrayOf('G'.code.toByte(), 'L'.code.toByte(), 'S'.code.toByte(), 'O'.code.toByte())

        private val bundledOutlines: StateOutlines by lazy {
            val stream = requireNotNull(StateOutlines::class.java.getResourceAsStream(RESOURCE)) {
                "Missing state outlines: $RESOURCE"
            }
            decode(stream.use { it.readBytes() })
        }

        /** The outlines bundled in the app, decoded once on first use. Throws when the file is missing or corrupt. */
        fun bundled(): StateOutlines = bundledOutlines

        /** Reads format v1. Throws [IllegalArgumentException] on anything malformed; shipped data must fail loud. */
        fun decode(bytes: ByteArray): StateOutlines {
            val reader = Reader(bytes)
            require(bytes.size >= MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }) { "Bad outline magic" }
            reader.position = MAGIC.size
            require(reader.varint() == VERSION) { "Unsupported outline version" }
            require(reader.varint() == SCALE) { "Unsupported outline scale" }
            val count = reader.varint()
            require(count in 1..MAX_STATES) { "Outline state count $count out of range" }
            val states = ArrayList<State>(count)
            repeat(count) {
                val postal = reader.postal()
                require(states.isEmpty() || states.last().postal < postal) { "Outline codes not sorted and unique" }
                val minLon = reader.zigzag()
                val minLat = reader.zigzag()
                val maxLon = reader.zigzag()
                val maxLat = reader.zigzag()
                require(minLon in -MAX_LON..maxLon && maxLon <= MAX_LON) { "Bad outline bounds for $postal" }
                require(minLat in -MAX_LAT..maxLat && maxLat <= MAX_LAT) { "Bad outline bounds for $postal" }
                val ringCount = reader.varint()
                require(ringCount in 1..MAX_RINGS) { "Outline ring count out of range for $postal" }
                val rings = ArrayList<IntArray>(ringCount)
                repeat(ringCount) {
                    val points = reader.varint()
                    require(points in 3..MAX_POINTS_PER_RING) { "Outline ring size out of range for $postal" }
                    val ring = IntArray(points * 2)
                    var lon = 0L
                    var lat = 0L
                    for (p in 0 until points) {
                        lon += reader.zigzag()
                        lat += reader.zigzag()
                        require(lon in minLon..maxLon && lat in minLat..maxLat) { "Outline point outside bounds for $postal" }
                        ring[2 * p] = lon.toInt()
                        ring[2 * p + 1] = lat.toInt()
                    }
                    rings += ring
                }
                states += State(postal, minLon, minLat, maxLon, maxLat, rings)
            }
            require(reader.position == bytes.size) { "Trailing bytes after the outlines" }
            return StateOutlines(states)
        }
    }

    private class Reader(private val bytes: ByteArray) {
        var position = 0

        private fun byte(): Int {
            require(position < bytes.size) { "Outlines truncated" }
            return bytes[position++].toInt() and 0xFF
        }

        /** An unsigned LEB128 varint that fits a non-negative Int. */
        fun varint(): Int {
            var value = 0L
            var shift = 0
            while (true) {
                val b = byte()
                value = value or ((b and 0x7F).toLong() shl shift)
                require(value <= Int.MAX_VALUE) { "Outline varint too large" }
                if (b and 0x80 == 0) return value.toInt()
                shift += 7
                require(shift <= 28) { "Outline varint too long" }
            }
        }

        fun zigzag(): Int {
            val raw = varint()
            return (raw ushr 1) xor -(raw and 1)
        }

        fun postal(): String {
            val first = byte()
            val second = byte()
            require(first in 'A'.code..'Z'.code && second in 'A'.code..'Z'.code) { "Bad outline postal code" }
            return "${first.toChar()}${second.toChar()}"
        }
    }
}
