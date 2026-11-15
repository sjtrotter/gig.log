package cloud.trotter.log.gig.feature.setup.wizard

import cloud.trotter.log.gig.core.data.feed.RulesFeedSwitch
import kotlinx.coroutines.flow.MutableStateFlow
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/** A rules switch that reads [initial] and saves anything (#640). */
internal fun rulesFeedSwitchStub(initial: Boolean = false): RulesFeedSwitch = mock {
    on { enabled } doReturn MutableStateFlow(initial)
    on { current() } doReturn initial
    on { set(any()) } doReturn true
}
