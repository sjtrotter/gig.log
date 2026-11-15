package cloud.trotter.feed.contract

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Every bound of the feed format, one owner each. Byte caps count the decompressed stream. The 1 MB rules file cap is
 * also the rule compiler's file cap and `RulesetCrypto.MAX_BUNDLE_BYTES`; both reference [MAX_RULES_FILE_BYTES].
 */
object FeedLimits {
    const val MAX_ENVELOPE_BYTES = 32_000
    const val MAX_FUEL_BYTES = 64_000
    const val MAX_CONSTANTS_BYTES = 16_000
    const val MAX_RULES_FILE_BYTES = 1_000_000
    const val MAX_RULES_GENERATION_BYTES = 4_000_000

    /** The pin file is read from the classpath, never the network; the cap only keeps the reader bounded. */
    const val MAX_PIN_FILE_BYTES = 32_000

    /** Nesting depth of the envelope, the manifest, the pin file and the fuel and constants payloads. */
    const val MAX_STRUCTURE_DEPTH = 4

    const val MAX_FILES_PER_MANIFEST = 16

    /** A P-256 DER signature is about 96 base64 characters; the cap stops a hostile one before it is decoded. */
    const val MAX_SIGNATURE_BASE64_CHARS = 512

    /** Every time in the format is Unix milliseconds; anything smaller is seconds or garbage. */
    const val MIN_EPOCH_MS = 1_000_000_000_000L

    /** How far past `max(wall clock, build time)` an `issued_at` may be. */
    const val ADMISSION_WINDOW_MS = 24L * 60 * 60 * 1000

    /**
     * Reads at most [cap] + 1 bytes from [input] and returns them, or null when the stream holds more than [cap].
     * The caller closes [input].
     */
    fun readBounded(input: InputStream, cap: Int): ByteArray? {
        require(cap >= 0)
        // A plain read loop: InputStream.readNBytes is API 33 and the app's minSdk is 30.
        val out = ByteArrayOutputStream(minOf(cap + 1, BUFFER_BYTES))
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0
        while (total <= cap) {
            val n = input.read(buffer, 0, minOf(buffer.size, cap + 1 - total))
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return if (total > cap) null else out.toByteArray()
    }

    private const val BUFFER_BYTES = 8192
}
