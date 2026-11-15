package cloud.trotter.feed.contract

/** Why the contract refused a feed input. The caller keeps last-good and logs the reason; no reason carries feed text. */
sealed interface FeedRefusal {
    // Reading
    data object TooLarge : FeedRefusal
    data object NotUtf8 : FeedRefusal
    data object MalformedJson : FeedRefusal
    data object DuplicateKey : FeedRefusal
    data object TooDeep : FeedRefusal
    data object NonFiniteNumber : FeedRefusal
    data class Malformed(val field: String) : FeedRefusal
    data object UnsupportedFormatVersion : FeedRefusal

    // Trust and signature
    data object NoTrustedEpoch : FeedRefusal
    data object UnknownKey : FeedRefusal
    data object BadSignature : FeedRefusal

    // Files
    data object TooManyFiles : FeedRefusal
    data object DuplicateFileName : FeedRefusal
    data object FileTooLarge : FeedRefusal
    data object GenerationTooLarge : FeedRefusal
    data object FileNotInManifest : FeedRefusal
    data object SizeMismatch : FeedRefusal
    data object HashMismatch : FeedRefusal

    // Acceptance
    data object WrongSource : FeedRefusal
    data object WrongFeed : FeedRefusal
    data object NotMilliseconds : FeedRefusal
    data object SequenceNotIssuedAt : FeedRefusal

    /** `issued_at` is more than a day past `max(wall clock, build time)`: the phone clock is behind. */
    data object ClockBehind : FeedRefusal
    data object Rollback : FeedRefusal
    data object ReusedSequence : FeedRefusal
    data object UnsupportedContract : FeedRefusal
}

sealed interface FeedResult<out T> {
    data class Ok<out T>(val value: T) : FeedResult<T>
    data class Refused(val reason: FeedRefusal) : FeedResult<Nothing>
}
