package cloud.trotter.feed.contract

/**
 * The feeds of the signed channel and what differs between them. A per-feed value lives here as data, never as a
 * branch on the feed at a call site.
 *
 * @property maxFileBytes the cap on one payload file.
 * @property maxGenerationBytes the cap on the sum of a manifest's file sizes.
 * @property payloadMaxDepth the nesting bound of a payload, or null when another owner bounds it (rules payloads keep
 *   the rule compiler's bounds, since shipped offer rules already nest 16 deep).
 * @property maxAgeMs how long after its `issued_at` a generation is labelled current.
 * @property fileName the one payload file name, or null when names resolve through a caller's resolver (rules).
 * @property acceptsUserPins whether a user may pin a custom source for this feed.
 */
enum class Feed(
    val wire: String,
    val maxFileBytes: Int,
    val maxGenerationBytes: Int,
    val payloadMaxDepth: Int?,
    val maxAgeMs: Long,
    val fileName: String?,
    val acceptsUserPins: Boolean,
) {
    FUEL(
        wire = "fuel",
        maxFileBytes = FeedLimits.MAX_FUEL_BYTES,
        maxGenerationBytes = FeedLimits.MAX_FUEL_BYTES,
        payloadMaxDepth = FeedLimits.MAX_STRUCTURE_DEPTH,
        maxAgeMs = days(21),
        fileName = "prices",
        acceptsUserPins = true,
    ),
    CONSTANTS(
        wire = "constants",
        maxFileBytes = FeedLimits.MAX_CONSTANTS_BYTES,
        maxGenerationBytes = FeedLimits.MAX_CONSTANTS_BYTES,
        payloadMaxDepth = FeedLimits.MAX_STRUCTURE_DEPTH,
        maxAgeMs = days(400),
        fileName = "values",
        acceptsUserPins = true,
    ),
    RULES(
        wire = "rules",
        maxFileBytes = FeedLimits.MAX_RULES_FILE_BYTES,
        maxGenerationBytes = FeedLimits.MAX_RULES_GENERATION_BYTES,
        payloadMaxDepth = null,
        maxAgeMs = days(90),
        fileName = null,
        acceptsUserPins = false,
    ),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }

        fun fromWire(wire: String): Feed? = byWire[wire]
    }
}

private fun days(n: Long): Long = n * 24 * 60 * 60 * 1000

/** Source ids. gig.log's is a literal; a custom source's id comes from its key, so it can never equal [GIG_LOG]. */
object FeedSources {
    const val GIG_LOG = "gig.log"
    const val CUSTOM_PREFIX = "custom:"

    fun custom(keyId: String): String = CUSTOM_PREFIX + keyId
}
