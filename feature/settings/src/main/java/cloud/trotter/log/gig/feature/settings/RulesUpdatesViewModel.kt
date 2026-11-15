package cloud.trotter.log.gig.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.gig.core.data.feed.RulesFeedChecks
import cloud.trotter.log.gig.core.data.feed.RulesFeedStatus
import cloud.trotter.log.gig.core.data.feed.RulesFeedSwitch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings > Recognition rules (#640): the "Keep recognition rules up to date" switch and "Check now".
 *
 * Both read their owners' state as it is: [enabled] is the switch's in-memory value, the one the feed reads, and a tap
 * that turns it off flips it before this coroutine first suspends. A "Check now" with the switch off runs on a
 * one-shot grant owned by this screen's scope, so leaving the screen stops it.
 */
@HiltViewModel
class RulesUpdatesViewModel @Inject constructor(
    private val switch: RulesFeedSwitch,
    private val checks: RulesFeedChecks,
) : ViewModel() {
    val enabled: StateFlow<Boolean> = switch.enabled
    val status: StateFlow<RulesFeedStatus> = checks.status

    fun setEnabled(on: Boolean) {
        viewModelScope.launch { switch.set(on) }
    }

    fun checkNow() {
        if (checks.status.value.checking) return
        viewModelScope.launch { checks.checkNow() }
    }
}
