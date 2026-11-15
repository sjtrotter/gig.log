package cloud.trotter.log.gig.feed

import cloud.trotter.feed.contract.Feed
import cloud.trotter.log.gig.core.data.feed.FeedPolicy
import cloud.trotter.log.gig.core.data.feed.FeedRefresh
import cloud.trotter.log.gig.core.data.feed.FeedRepository
import cloud.trotter.log.gig.core.data.feed.OneShotGrant
import cloud.trotter.log.gig.core.data.feed.RulesCheckOutcome
import cloud.trotter.log.gig.core.data.feed.RulesFeedChecks
import cloud.trotter.log.gig.core.data.feed.RulesFeedStatus
import cloud.trotter.log.gig.core.data.feed.RulesFeedSwitch
import cloud.trotter.log.gig.core.pipeline.rules.GenerationResult
import cloud.trotter.log.gig.core.pipeline.rules.JsonRuleInterpreter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The rules feed client (#640): fetches the signed rules feed through the feed channel ([FeedRepository]) and makes
 * each committed generation live through the batch loader.
 *
 * - **Stage, commit, activate.** [RulesFeedStager] prepares a generation on the bundled floor without activating it;
 *   the feed channel commits it to disk; only then does [activateLatest] hand it to [JsonRuleInterpreter.activate]. A
 *   refused file, a rejected generation or a crash keeps the generation in use: the last good one, else the bundled
 *   rules.
 * - **When it checks.** On a delivery app coming to the front ([onPlatformForeground], a transition into the app,
 *   `PlatformForegrounds`), at most once an hour and only with "Keep recognition rules up to date" on; and on the
 *   dasher's own tap ([checkNow]). Nothing else calls [FeedRepository.refresh], and there is no worker.
 * - **Consent.** The rules feed's own switch ([RulesFeedSwitch]); with it off, only a tap's one-shot grant sends a
 *   request, one fetch per tap.
 * - **Capabilities** of a remote rule land undecided, as every capability does: the loader reconciles them before the
 *   generation goes live and grants nothing.
 *
 * Logging (tag `Feeds`): INFO when a generation goes live, with its sequence and file count; WARN when one cannot.
 * No line carries a URL, an address or file content.
 */
@Singleton
class RulesFeed @Inject constructor(
    private val interpreter: JsonRuleInterpreter,
    private val repository: FeedRepository<RulesCandidate>,
    private val switch: RulesFeedSwitch,
    private val policy: FeedPolicy,
    private val clock: Clock,
) : RulesFeedChecks {

    private val _status = MutableStateFlow(RulesFeedStatus())
    override val status: StateFlow<RulesFeedStatus> = _status.asStateFlow()

    private val activation = Mutex()
    private var active: RulesCandidate? = null

    /** After the bundled rules are live: activates the committed generation, if one re-verifies. No network. */
    suspend fun start() {
        repository.start()
        activateLatest()
    }

    /** A delivery app came to the front. Checks only with the switch on and no foreground check in the last hour. */
    suspend fun onPlatformForeground() {
        if (!switch.claimForegroundCheck(clock.millis())) return
        check(grant = null)
    }

    override suspend fun checkNow(): RulesCheckOutcome {
        val grant = if (switch.enabled.value) null else OneShotGrant.forUserAct(Feed.RULES, policy.source(Feed.RULES).value)
        return check(grant)
    }

    private suspend fun check(grant: OneShotGrant?): RulesCheckOutcome {
        _status.update { it.copy(checking = true) }
        val refresh = try {
            repository.refresh(grant)
        } finally {
            _status.update { it.copy(checking = false) }
        }
        // A refresh may also load a generation committed earlier but never made live, whatever it returns.
        val applied = activateLatest()
        val outcome = when (refresh) {
            is FeedRefresh.Accepted -> if (applied) RulesCheckOutcome.UPDATED else RulesCheckOutcome.FAILED
            FeedRefresh.Unchanged -> RulesCheckOutcome.NO_UPDATE
            FeedRefresh.Denied, FeedRefresh.Cancelled, is FeedRefresh.Failed -> RulesCheckOutcome.FAILED
        }
        _status.update { it.copy(lastCheckAt = clock.millis(), lastOutcome = outcome) }
        return outcome
    }

    /**
     * Makes the feed's committed generation live, unless it already is. A candidate prepared on a generation that is
     * no longer live is prepared again on the current one. True when the feed's generation is live afterwards.
     */
    private suspend fun activateLatest(): Boolean = activation.withLock {
        val state = repository.state.value
        val candidate = state.value ?: return@withLock false
        if (candidate === active) return@withLock true
        val live = interpreter.activate(candidate.prepared) || run {
            val again = interpreter.prepareGeneration(candidate.files) as? GenerationResult.Prepared
            again != null && interpreter.activate(again)
        }
        if (!live) {
            Timber.tag(TAG).w("rules generation not made live sequence=%d; the rules in use stay", candidate.sequence)
            return@withLock false
        }
        active = candidate
        _status.update { it.copy(liveIssuedAt = state.issuedAt) }
        Timber.tag(TAG).i("rules generation live sequence=%d files=%d", candidate.sequence, candidate.files.size)
        true
    }

    private companion object {
        const val TAG = "Feeds"
    }
}
