package cloud.trotter.feed.contract

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.Signature
import java.util.Base64
import java.util.Collections

/**
 * A key the user pasted for a custom source of [feed], with an optional backup key. The pair is that source's one
 * epoch; its source id is `custom:<key id of the primary>`, assigned from the key, never chosen.
 */
data class UserPin(val feed: Feed, val publicKeyBase64Der: String, val backupBase64Der: String? = null)

/** A user pin [FeedTrust] did not file, and why. */
data class UserPinRejection(val pin: UserPin, val reason: Reason) {
    enum class Reason { FEED_TAKES_NO_USER_PINS, NOT_A_P256_KEY, SAME_KEY_TWICE, DUPLICATE }
}

/** Which key of a pinned epoch signed: the epoch number and the key id. */
internal class Signer(val epoch: Long, val keyId: String)

/**
 * The pin registry. Built once from the shipped pin file (gig.log's pins, ADR-0013 §8) plus the user's custom pins.
 * The constructor and the pin-file reader are private, and no call takes a key from outside, so which key verifies a
 * feed is decided here.
 *
 * - One epoch per source and feed: a primary and a backup key.
 * - Shipped pins are gig.log's. A user pin is filed under `custom:<key id>`, which can never be gig.log, and never for
 *   a feed that takes no user pins (rules).
 */
class FeedTrust private constructor(
    private val epochs: Map<Pair<String, Feed>, Epoch>,
    val rejectedUserPins: List<UserPinRejection>,
) {
    private class Epoch(val number: Long, val primary: PinnedKey, val backup: PinnedKey?) {
        fun key(keyId: String): PinnedKey? = when (keyId) {
            primary.keyId -> primary
            backup?.keyId -> backup
            else -> null
        }
    }

    fun trusts(source: String, feed: Feed): Boolean = epochs.containsKey(source to feed)

    /**
     * Checks [signatureBase64] over [signed] against the key [keyId] names in the epoch pinned for [source] and [feed].
     * The key always comes from the pins.
     */
    internal fun signer(source: String, feed: Feed, keyId: String, signed: ByteArray, signatureBase64: String): FeedResult<Signer> {
        val epoch = epochs[source to feed] ?: return FeedResult.Refused(FeedRefusal.NoTrustedEpoch)
        val key = epoch.key(keyId) ?: return FeedResult.Refused(FeedRefusal.UnknownKey)
        if (!verifies(key, signed, signatureBase64)) return FeedResult.Refused(FeedRefusal.BadSignature)
        return FeedResult.Ok(Signer(epoch.number, key.keyId))
    }

    private fun verifies(key: PinnedKey, signed: ByteArray, signatureBase64: String): Boolean {
        if (signatureBase64.length > FeedLimits.MAX_SIGNATURE_BASE64_CHARS) return false
        // Broad catch: the signature is hostile DER, and some providers throw unchecked exceptions on malformed ASN.1.
        return try {
            Signature.getInstance(FeedKeys.SIGNATURE_ALGORITHM).run {
                initVerify(key.publicKey)
                update(signed)
                verify(Base64.getDecoder().decode(signatureBase64))
            }
        } catch (_: Exception) {
            false
        }
    }

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

        /** Private: the only bytes it reads in production are the shipped pin file's. Tests reach it by reflection. */
        private fun fromPinFile(pinFile: ByteArray, userPins: List<UserPin>): FeedResult<FeedTrust> {
            val shipped = when (val read = readPinFile(pinFile)) {
                is FeedResult.Ok -> read.value
                is FeedResult.Refused -> return read
            }
            val epochs = HashMap<Pair<String, Feed>, Epoch>()
            for ((feed, epoch) in shipped) epochs[FeedSources.GIG_LOG to feed] = epoch
            val rejected = ArrayList<UserPinRejection>()
            for (pin in userPins) {
                val reason = fileUserPin(pin, epochs)
                if (reason != null) rejected += UserPinRejection(pin, reason)
            }
            return FeedResult.Ok(FeedTrust(epochs, Collections.unmodifiableList(rejected)))
        }

        private fun fileUserPin(pin: UserPin, epochs: MutableMap<Pair<String, Feed>, Epoch>): UserPinRejection.Reason? {
            if (!pin.feed.acceptsUserPins) return UserPinRejection.Reason.FEED_TAKES_NO_USER_PINS
            val primary = FeedKeys.decode(pin.publicKeyBase64Der) ?: return UserPinRejection.Reason.NOT_A_P256_KEY
            val backup = pin.backupBase64Der?.let { FeedKeys.decode(it) ?: return UserPinRejection.Reason.NOT_A_P256_KEY }
            if (backup?.keyId == primary.keyId) return UserPinRejection.Reason.SAME_KEY_TWICE
            val slot = FeedSources.custom(primary.keyId) to pin.feed
            if (slot in epochs) return UserPinRejection.Reason.DUPLICATE
            epochs[slot] = Epoch(number = 1, primary = primary, backup = backup)
            return null
        }

        /**
         * The pin file: `{"format_version": 1, "feeds": {"<feed>": {"epoch": n, "primary": "<b64 DER>",
         * "backup": "<b64 DER>"}}}`. Keyed by feed, so the duplicate-key pre-pass already enforces one epoch per feed.
         */
        private fun readPinFile(bytes: ByteArray): FeedResult<Map<Feed, Epoch>> {
            val root = when (val parsed = FeedJson.parse(bytes, FeedLimits.MAX_STRUCTURE_DEPTH)) {
                is FeedResult.Ok -> parsed.value as? JsonObject ?: return malformed("pin file")
                is FeedResult.Refused -> return parsed
            }
            if (root.integer("format_version") != PIN_FILE_FORMAT_VERSION) {
                return FeedResult.Refused(FeedRefusal.UnsupportedFormatVersion)
            }
            val feeds = root["feeds"] as? JsonObject ?: return malformed("feeds")
            val out = LinkedHashMap<Feed, Epoch>()
            for ((wire, entry) in feeds) {
                val feed = Feed.fromWire(wire) ?: return malformed("feed")
                val pin = entry as? JsonObject ?: return malformed("feed")
                val number = pin.integer("epoch")?.takeIf { it >= 1 } ?: return malformed("epoch")
                val primary = pin.text("primary")?.let(FeedKeys::decode) ?: return malformed("primary")
                val backup = pin.text("backup")?.let(FeedKeys::decode) ?: return malformed("backup")
                if (primary.keyId == backup.keyId) return malformed("backup")
                out[feed] = Epoch(number, primary, backup)
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
