package cloud.trotter.feed.contract

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The one trusted key pair for a source and feed: a primary and, for gig.log's pins, a backup. */
class FeedEpoch internal constructor(
    val number: Long,
    internal val primary: PinnedKey,
    internal val backup: PinnedKey?,
) {
    internal fun key(keyId: String): PinnedKey? = when (keyId) {
        primary.keyId -> primary
        backup?.keyId -> backup
        else -> null
    }
}

/** A key the user pasted for a custom source of [feed]. Its source id is assigned from the key, never chosen. */
data class UserPin(val feed: Feed, val publicKeyBase64Der: String)

/** A user pin [FeedTrust] did not file, and why. */
data class UserPinRejection(val pin: UserPin, val reason: Reason) {
    enum class Reason { FEED_TAKES_NO_USER_PINS, NOT_A_P256_KEY, DUPLICATE }
}

/**
 * The pin registry. Built once from the shipped pin file (gig.log's pins, ADR-0013 §8) plus the user's custom pins;
 * the constructor is private and no call takes a key from outside, so which key verifies a feed is decided here.
 *
 * - One epoch per source and feed.
 * - Shipped pins are gig.log's. A user pin is filed under `custom:<key id>`, which can never be gig.log, and never for
 *   a feed that takes no user pins (rules).
 */
class FeedTrust private constructor(
    private val epochs: Map<Pair<String, Feed>, FeedEpoch>,
    val rejectedUserPins: List<UserPinRejection>,
) {
    internal fun epoch(source: String, feed: Feed): FeedEpoch? = epochs[source to feed]

    fun trusts(source: String, feed: Feed): Boolean = epoch(source, feed) != null

    companion object {
        /** Classpath path of the one pin file (`feed-contract/pins/feed-pins.json` in the repository). */
        const val PIN_FILE_RESOURCE = "/feed-pins.json"

        const val PIN_FILE_FORMAT_VERSION = 1L

        /** The registry from the shipped pin file. A missing or malformed pin file is refused, never read as empty. */
        fun shipped(userPins: List<UserPin> = emptyList()): FeedResult<FeedTrust> {
            val bytes = FeedTrust::class.java.getResourceAsStream(PIN_FILE_RESOURCE)
                ?.use { FeedLimits.readBounded(it, FeedLimits.MAX_PIN_FILE_BYTES) }
                ?: return FeedResult.Refused(FeedRefusal.Malformed("pin file"))
            return fromPinFile(bytes, userPins)
        }

        /** Internal so that only this build's tests can stand up a registry with keys of their own. */
        internal fun fromPinFile(pinFile: ByteArray, userPins: List<UserPin>): FeedResult<FeedTrust> {
            val shipped = when (val read = readPinFile(pinFile)) {
                is FeedResult.Ok -> read.value
                is FeedResult.Refused -> return read
            }
            val epochs = HashMap<Pair<String, Feed>, FeedEpoch>()
            for ((feed, epoch) in shipped) epochs[FeedSources.GIG_LOG to feed] = epoch
            val rejected = ArrayList<UserPinRejection>()
            for (pin in userPins) {
                val key = FeedKeys.decode(pin.publicKeyBase64Der)
                val slot = key?.let { FeedSources.custom(it.keyId) to pin.feed }
                val reason = when {
                    !pin.feed.acceptsUserPins -> UserPinRejection.Reason.FEED_TAKES_NO_USER_PINS
                    key == null || slot == null -> UserPinRejection.Reason.NOT_A_P256_KEY
                    slot in epochs -> UserPinRejection.Reason.DUPLICATE
                    else -> {
                        epochs[slot] = FeedEpoch(number = 1, primary = key, backup = null)
                        null
                    }
                }
                if (reason != null) rejected += UserPinRejection(pin, reason)
            }
            return FeedResult.Ok(FeedTrust(epochs, rejected))
        }

        /**
         * The pin file: `{"format_version": 1, "feeds": {"<feed>": {"epoch": n, "primary": "<b64 DER>",
         * "backup": "<b64 DER>"}}}`. Keyed by feed, so the duplicate-key pre-pass already enforces one epoch per feed.
         */
        private fun readPinFile(bytes: ByteArray): FeedResult<Map<Feed, FeedEpoch>> {
            val root = when (val parsed = FeedJson.parse(bytes, FeedLimits.MAX_STRUCTURE_DEPTH)) {
                is FeedResult.Ok -> parsed.value as? JsonObject ?: return malformed("pin file")
                is FeedResult.Refused -> return parsed
            }
            if (root.integer("format_version") != PIN_FILE_FORMAT_VERSION) {
                return FeedResult.Refused(FeedRefusal.UnsupportedFormatVersion)
            }
            val feeds = root["feeds"] as? JsonObject ?: return malformed("feeds")
            val out = LinkedHashMap<Feed, FeedEpoch>()
            for ((wire, entry) in feeds) {
                val feed = Feed.fromWire(wire) ?: return malformed("feed")
                val pin = entry as? JsonObject ?: return malformed("feed")
                val number = pin.integer("epoch")?.takeIf { it >= 1 } ?: return malformed("epoch")
                val primary = pin.text("primary")?.let(FeedKeys::decode) ?: return malformed("primary")
                val backup = pin.text("backup")?.let(FeedKeys::decode) ?: return malformed("backup")
                if (primary.keyId == backup.keyId) return malformed("backup")
                out[feed] = FeedEpoch(number, primary, backup)
            }
            return FeedResult.Ok(out)
        }

        private fun malformed(field: String) = FeedResult.Refused(FeedRefusal.Malformed(field))
    }
}

/** A string member, or null when absent or not a JSON string. */
internal fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** An integer member written as a plain integer literal (no fraction, no exponent), or null. */
internal fun JsonObject.integer(key: String): Long? = this[key].integerOrNull()

internal fun JsonElement?.integerOrNull(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    val content = primitive.content
    if (!INTEGER_LITERAL.matches(content)) return null
    return content.toLongOrNull()
}

private val INTEGER_LITERAL = Regex("-?(0|[1-9][0-9]*)")
