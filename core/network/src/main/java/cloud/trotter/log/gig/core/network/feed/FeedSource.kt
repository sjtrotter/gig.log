package cloud.trotter.log.gig.core.network.feed

import cloud.trotter.feed.contract.FeedKeys
import cloud.trotter.feed.contract.FeedSources
import cloud.trotter.feed.contract.UserPin

/**
 * Where a feed is fetched from and who it is (ADR-0013 §1, §7). [feedsUrl] is the site's feeds directory; a feed
 * lives at `<feedsUrl><feed>/envelope.json` and its payloads beside it. Trust never comes from the URL: [id] picks the
 * pinned epoch, and a custom source's id is derived from its key, so moving a feed to a new URL keeps its history.
 */
sealed interface FeedSource {
    val id: String
    val feedsUrl: String

    /** The key the dasher pasted for a custom source; gig.log's keys ship in the app's pin file. */
    val userPin: UserPin?

    /** gig.log's own site. The only source that may receive a conditional request (security X8). */
    data object GigLog : FeedSource {
        const val FEEDS_URL = "https://gig.log.trotter.cloud/feeds/"
        override val id: String = FeedSources.GIG_LOG
        override val feedsUrl: String = FEEDS_URL
        override val userPin: UserPin? = null
    }

    /** A feed the dasher runs or chose: an https URL and its public key. Its id is `custom:<key id>`. */
    class Custom private constructor(override val feedsUrl: String, override val userPin: UserPin, override val id: String) :
        FeedSource {
        override fun equals(other: Any?): Boolean =
            other is Custom && other.id == id && other.feedsUrl == feedsUrl && other.userPin == userPin

        override fun hashCode(): Int = 31 * id.hashCode() + feedsUrl.hashCode()

        /** No URL in the string form: a custom URL is the dasher's choice and never reaches a log. */
        override fun toString(): String = "Custom($id)"

        companion object {
            /** Null when [pin] is not a canonical P-256 key. The URL is checked by [FeedClient] before every request. */
            fun of(feedsUrl: String, pin: UserPin): Custom? =
                FeedKeys.customSourceId(pin.publicKeyBase64Der)?.let { Custom(feedsUrl, pin, it) }
        }
    }
}
