package cloud.trotter.feed.contract

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.InputStream
import java.util.Collections

/** One file a signed manifest names. [size] and [sha256] are what the file's bytes must match. */
data class ManifestFile(val name: String, val sha256: String, val size: Int)

/**
 * A manifest whose signature verified against the epoch pinned for the requested source and feed, and whose
 * [source] and [feed] equal that request. [verify] is the only way to get one.
 *
 * The envelope is `{"manifest": "<exact manifest JSON as a string>", "key": "<key id>", "sig": "<base64 DER>"}`; the
 * signature covers the exact UTF-8 bytes of the `manifest` string. The key comes from the epoch [FeedTrust] pins for
 * the requested source and feed, never from the envelope: `key` only names which of that epoch's two keys signed.
 * Pure `java.security`, no logger: every refusal is a [FeedRefusal] for the caller to log.
 *
 * @property digest lowercase hex sha256 over the exact manifest bytes that were signed (`FEED_DIGEST` for rules).
 * @property files read-only; [VerifiedFeedFile.verify] checks against this same list.
 */
class VerifiedManifest private constructor(
    val source: String,
    val feed: Feed,
    val epoch: Long,
    val keyId: String,
    val digest: String,
    val sequence: Long,
    val issuedAt: Long,
    val contract: Long,
    val files: List<ManifestFile>,
) {
    /** What the acceptance function decides on. */
    val candidate: FeedCandidate get() = FeedCandidate(source, feed, sequence, issuedAt, contract, digest)

    companion object {
        const val FORMAT_VERSION = 1L

        /** Reads at most [FeedLimits.MAX_ENVELOPE_BYTES] + 1 bytes from [envelope], then verifies. */
        fun verify(trust: FeedTrust, source: String, feed: Feed, envelope: InputStream): FeedResult<VerifiedManifest> {
            val bytes = FeedLimits.readBounded(envelope, FeedLimits.MAX_ENVELOPE_BYTES)
                ?: return FeedResult.Refused(FeedRefusal.TooLarge)
            val root = when (val parsed = FeedJson.parse(bytes, FeedLimits.MAX_STRUCTURE_DEPTH)) {
                is FeedResult.Ok -> parsed.value as? JsonObject ?: return malformed("envelope")
                is FeedResult.Refused -> return parsed
            }
            val manifestText = root.text("manifest") ?: return malformed("manifest")
            val keyId = root.text("key") ?: return malformed("key")
            val signature = root.text("sig") ?: return malformed("sig")
            val manifestBytes = FeedJson.encodeUtf8(manifestText) ?: return FeedResult.Refused(FeedRefusal.NotUtf8)
            val signer = when (val checked = trust.signer(source, feed, keyId, manifestBytes, signature)) {
                is FeedResult.Ok -> checked.value
                is FeedResult.Refused -> return checked
            }
            val fields = when (val read = readManifest(manifestText, source, feed)) {
                is FeedResult.Ok -> read.value
                is FeedResult.Refused -> return read
            }
            return FeedResult.Ok(
                VerifiedManifest(
                    source = source,
                    feed = feed,
                    epoch = signer.epoch,
                    keyId = signer.keyId,
                    digest = FeedKeys.sha256Hex(manifestBytes),
                    sequence = fields.sequence,
                    issuedAt = fields.issuedAt,
                    contract = fields.contract,
                    files = Collections.unmodifiableList(fields.files),
                ),
            )
        }
    }
}

/**
 * One payload file whose size and sha256 matched a [VerifiedManifest]. Immutable: [bytes] returns a copy. [verify] is
 * the only way to get one, so a consumer that takes a [VerifiedFeedFile] cannot be handed unverified bytes, and [feed]
 * and [sourceId] say what the signature bound them to.
 */
class VerifiedFeedFile private constructor(
    val sourceId: String,
    val feed: Feed,
    val epoch: Long,
    val keyId: String,
    val sequence: Long,
    val name: String,
    val sha256: String,
    private val content: ByteArray,
) {
    fun bytes(): ByteArray = content.copyOf()

    companion object {
        /**
         * Checks one payload against [manifest]: at most the signed size is read from [content], then its size and
         * sha256 must match before the bytes go anywhere.
         */
        fun verify(manifest: VerifiedManifest, name: String, content: InputStream): FeedResult<VerifiedFeedFile> {
            val file = manifest.files.firstOrNull { it.name == name }
                ?: return FeedResult.Refused(FeedRefusal.FileNotInManifest)
            val bytes = FeedLimits.readBounded(content, file.size)
            if (bytes == null || bytes.size != file.size) return FeedResult.Refused(FeedRefusal.SizeMismatch)
            if (FeedKeys.sha256Hex(bytes) != file.sha256) return FeedResult.Refused(FeedRefusal.HashMismatch)
            return FeedResult.Ok(
                VerifiedFeedFile(
                    sourceId = manifest.source,
                    feed = manifest.feed,
                    epoch = manifest.epoch,
                    keyId = manifest.keyId,
                    sequence = manifest.sequence,
                    name = file.name,
                    sha256 = file.sha256,
                    content = bytes,
                ),
            )
        }
    }
}

private val SHA256_HEX = Regex("[0-9a-f]{64}")
private val FILE_NAME = Regex("[a-z0-9_]{1,64}")
private val FILE_KEYS = setOf("name", "sha256", "size")

private class ManifestFields(val sequence: Long, val issuedAt: Long, val contract: Long, val files: List<ManifestFile>)

/**
 * The strict manifest reader, run only on signed bytes. Unknown top-level keys are ignored (the reserved `advisory`
 * among them); an unknown `format_version` is refused; a file entry takes exactly `name`, `sha256` and `size`.
 */
private fun readManifest(text: String, source: String, feed: Feed): FeedResult<ManifestFields> {
    val root = when (val parsed = FeedJson.parse(text, FeedLimits.MAX_STRUCTURE_DEPTH)) {
        is FeedResult.Ok -> parsed.value as? JsonObject ?: return malformed("manifest")
        is FeedResult.Refused -> return parsed
    }
    val formatVersion = root.integer("format_version") ?: return malformed("format_version")
    if (formatVersion != VerifiedManifest.FORMAT_VERSION) return FeedResult.Refused(FeedRefusal.UnsupportedFormatVersion)
    if (root.text("source") != source) return FeedResult.Refused(FeedRefusal.WrongSource)
    if (root.text("feed") != feed.wire) return FeedResult.Refused(FeedRefusal.WrongFeed)
    val sequence = root.integer("sequence") ?: return malformed("sequence")
    val issuedAt = root.integer("issued_at") ?: return malformed("issued_at")
    val contract = root.integer("contract") ?: return malformed("contract")
    val entries = root["files"] as? JsonArray ?: return malformed("files")
    if (entries.size > FeedLimits.MAX_FILES_PER_MANIFEST) return FeedResult.Refused(FeedRefusal.TooManyFiles)

    val files = ArrayList<ManifestFile>(entries.size)
    var total = 0L
    for (entry in entries) {
        val obj = entry as? JsonObject ?: return malformed("files")
        if (obj.keys != FILE_KEYS) return malformed("files")
        val name = obj.text("name")?.takeIf { FILE_NAME.matches(it) } ?: return malformed("name")
        val sha256 = obj.text("sha256")?.takeIf { SHA256_HEX.matches(it) } ?: return malformed("sha256")
        val size = obj.integer("size")?.takeIf { it >= 0 } ?: return malformed("size")
        if (size > feed.maxFileBytes) return FeedResult.Refused(FeedRefusal.FileTooLarge)
        if (files.any { it.name == name }) return FeedResult.Refused(FeedRefusal.DuplicateFileName)
        total += size
        files += ManifestFile(name, sha256, size.toInt())
    }
    if (total > feed.maxGenerationBytes) return FeedResult.Refused(FeedRefusal.GenerationTooLarge)
    return FeedResult.Ok(ManifestFields(sequence, issuedAt, contract, files))
}

private fun malformed(field: String) = FeedResult.Refused(FeedRefusal.Malformed(field))
