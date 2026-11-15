package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.feed.contract.Feed
import cloud.trotter.feed.contract.FeedSources
import cloud.trotter.feed.contract.VerifiedFeedFile
import cloud.trotter.log.gig.domain.state.Platform
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * One remote rules file whose signature and hash checked out, the only remote input [JsonRuleInterpreter] accepts
 * (#416, #640). The constructor is private and [fromFeedFile] is the only mint, so compiling remote bytes is
 * unreachable without the feed contract's verifier: a [VerifiedFeedFile] exists only after its manifest verified
 * against the epoch pinned for its source and feed, and its bytes matched that manifest's size and sha256.
 *
 * Bundled rules never pass through here: they load from `assets/rules/` through [JsonRuleInterpreter.loadDefaults],
 * covered by the APK signature.
 *
 * @property source the file's provenance, `feed:<source id>/<platform wire>`; never `asset:`-prefixed, so a
 *   remote rule's capabilities are told apart from the bundled ones. Display only: capabilities land undecided
 *   whatever their source (#843).
 * @property platform the platform the file is named for, through the registry (principle 8).
 * @property sequence the feed generation that carried it.
 */
class VerifiedRulesetBytes private constructor(
    /** The verified file as text (a strict UTF-8 decode of the exact signed bytes). */
    val json: String,
    val source: String,
    val platform: Platform,
    val sequence: Long,
) {
    companion object {
        private const val TAG = "Rules"

        /** The sources allowed to serve rules: gig.log's own in v1 (custom rules sources are #1570). */
        private val RULES_SOURCES = setOf(FeedSources.GIG_LOG)

        /**
         * The rules file [file] carries, or null (fail closed, with a WARN naming only the feed, the source id and
         * the reason, never file content) when it is not a rules file, comes from a source not allowed to serve
         * rules, is named for no platform this build carries, is over the compiler's file cap, or is not UTF-8.
         */
        fun fromFeedFile(file: VerifiedFeedFile): VerifiedRulesetBytes? {
            val refusal = when {
                file.feed != Feed.RULES -> "not the rules feed"
                file.sourceId !in RULES_SOURCES -> "source may not serve rules"
                else -> null
            }
            if (refusal != null) return refused(file, refusal)
            val platform = Platform.fromWire(file.name)?.takeIf { it != Platform.Unknown }
                ?: return refused(file, "file name names no platform")
            val bytes = file.bytes()
            if (bytes.size > MAX_RULE_FILE_BYTES) return refused(file, "over the rules file cap")
            val json = decodeUtf8(bytes) ?: return refused(file, "not UTF-8")
            return VerifiedRulesetBytes(json, "feed:${file.sourceId}/${platform.wire}", platform, file.sequence)
        }

        private fun decodeUtf8(bytes: ByteArray): String? = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }

        private fun refused(file: VerifiedFeedFile, reason: String): VerifiedRulesetBytes? {
            Timber.tag(TAG).w("rules file refused feed=%s source=%s: %s", file.feed.wire, file.sourceId, reason)
            return null
        }
    }
}
