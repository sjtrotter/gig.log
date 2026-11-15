package cloud.trotter.feed.contract

import kotlinx.serialization.json.JsonElement

/** A label, never a refusal: an old generation stays in use as last-good. */
enum class FeedFreshness {
    CURRENT,
    OLD,
    ;

    companion object {
        /**
         * [newestAcceptedIssuedAt] is the `issued_at` of the newest generation an [FeedDecision.Accept] took; an equal
         * sequence or an HTTP 304 never renews it. A wall clock behind `issued_at` (a negative age) is current.
         */
        fun of(feed: Feed, newestAcceptedIssuedAt: Long, wallClockMs: Long): FeedFreshness =
            if (wallClockMs - newestAcceptedIssuedAt <= feed.maxAgeMs) CURRENT else OLD
    }
}

object FeedFiles {
    /**
     * The manifest files this client reads. Fuel and constants read their one [Feed.fileName]. Rules names go through
     * [rulesName] (the app passes its platform registry's wire lookup), so this build holds no platform list; a name
     * nothing resolves is ignored.
     */
    fun select(manifest: VerifiedManifest, rulesName: (String) -> Boolean): List<ManifestFile> {
        val fixed = manifest.feed.fileName
        return manifest.files.filter { if (fixed != null) it.name == fixed else rulesName(it.name) }
    }

    /**
     * A fuel or constants payload as a JSON tree, through the same pre-pass as the manifest at the feed's depth bound.
     * Rules payloads are refused here: the rule compiler parses them under its own bounds.
     */
    fun parsePayload(file: VerifiedFeedFile): FeedResult<JsonElement> {
        val depth = file.feed.payloadMaxDepth ?: return FeedResult.Refused(FeedRefusal.WrongFeed)
        return FeedJson.parse(file.bytes(), depth)
    }
}
