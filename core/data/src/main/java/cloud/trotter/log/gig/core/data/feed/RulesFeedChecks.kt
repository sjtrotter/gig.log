package cloud.trotter.log.gig.core.data.feed

import kotlinx.coroutines.flow.StateFlow

/**
 * The rules feed as the screens see it (#640): "Check now" and what the last check found. The app's rules client
 * implements it, so a feature module reaches the feed without depending on `:app`.
 */
interface RulesFeedChecks {
    val status: StateFlow<RulesFeedStatus>

    /**
     * One check the dasher asked for. With "Keep recognition rules up to date" off it runs on a one-shot grant minted
     * in the caller's coroutine, so it dies with that coroutine and allows one fetch. With the switch on it skips the
     * hourly limit.
     */
    suspend fun checkNow(): RulesCheckOutcome
}

enum class RulesCheckOutcome {
    /** A new generation was accepted and is live. */
    UPDATED,

    /** The site holds what the phone holds. */
    NO_UPDATE,

    /** The check could not finish (offline, a refused file, storage); the rules in use stay. */
    FAILED,
}

/**
 * Anchors only (Reactive UI rule 2): the UI derives "how long ago" from [lastCheckAt] with `rememberNow()`.
 *
 * @property liveIssuedAt `issued_at` of the downloaded rules in use, or null while the built-in rules are in use.
 * @property lastCheckAt when the last check this process ran finished; null before the first.
 */
data class RulesFeedStatus(
    val liveIssuedAt: Long? = null,
    val lastCheckAt: Long? = null,
    val lastOutcome: RulesCheckOutcome? = null,
    val checking: Boolean = false,
)
