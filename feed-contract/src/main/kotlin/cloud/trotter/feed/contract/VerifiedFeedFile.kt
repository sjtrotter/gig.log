package cloud.trotter.feed.contract

/** One file a signed manifest names. [size] and [sha256] are what the file's bytes must match. */
data class ManifestFile(val name: String, val sha256: String, val size: Int)

/**
 * A manifest whose signature verified against the epoch pinned for the requested source and feed, and whose
 * [source] and [feed] equal that request. Only [FeedVerifier] creates one.
 *
 * @property digest lowercase hex sha256 over the exact manifest bytes that were signed (`FEED_DIGEST` for rules).
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

    internal companion object {
        fun mint(
            source: String,
            feed: Feed,
            epoch: FeedEpoch,
            keyId: String,
            digest: String,
            sequence: Long,
            issuedAt: Long,
            contract: Long,
            files: List<ManifestFile>,
        ) = VerifiedManifest(source, feed, epoch.number, keyId, digest, sequence, issuedAt, contract, files.toList())
    }
}

/**
 * One payload file whose size and sha256 matched a [VerifiedManifest]. Immutable: [bytes] returns a copy. Only
 * [FeedVerifier] creates one, so a consumer that takes a [VerifiedFeedFile] cannot be handed unverified bytes, and
 * [feed] and [sourceId] say what the signature bound them to.
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

    internal companion object {
        fun mint(manifest: VerifiedManifest, file: ManifestFile, content: ByteArray) = VerifiedFeedFile(
            sourceId = manifest.source,
            feed = manifest.feed,
            epoch = manifest.epoch,
            keyId = manifest.keyId,
            sequence = manifest.sequence,
            name = file.name,
            sha256 = file.sha256,
            content = content,
        )
    }
}
