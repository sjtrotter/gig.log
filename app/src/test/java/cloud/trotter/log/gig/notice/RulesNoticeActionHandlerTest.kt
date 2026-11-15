package cloud.trotter.log.gig.notice

import cloud.trotter.log.gig.core.data.feed.RulesCheckOutcome
import cloud.trotter.log.gig.domain.pipeline.RecognitionIncident
import cloud.trotter.log.gig.domain.pipeline.RecognitionIncidents
import cloud.trotter.log.gig.feed.RulesFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking

/** #640: the notice's rules actions. one tap is one check; "No update yet" posts once per incident; an answer never outlives its incident. */
class RulesNoticeActionHandlerTest {
    private val incident = RecognitionIncident(platformWire = "doordash", openedAt = 1_000L, screensUnrecognized = true)
    private val open = MutableStateFlow(listOf(incident))
    private val incidents = object : RecognitionIncidents { override val open = this@RulesNoticeActionHandlerTest.open }
    private val notifier = mock<RecognitionHealthNotifier>()

    private fun handler(outcome: RulesCheckOutcome): Pair<RulesNoticeActionHandler, RulesFeed> {
        val feed = mock<RulesFeed> {
            on { checkNow() } doReturn outcome
        }
        return RulesNoticeActionHandler(feed, incidents, notifier, CoroutineScope(Dispatchers.Unconfined)) to feed
    }

    @Test fun `No update yet is never posted again for the same incident`() {
        val (handler, feed) = handler(RulesCheckOutcome.NO_UPDATE)
        repeat(3) { handler.onAction(RulesNoticeActionHandler.ACTION_CHECK, "doordash", 1_000L) }
        verifyBlocking(feed, times(3)) { checkNow() }
        verify(notifier, times(1)).onRulesCheck(incident, RulesCheckOutcome.NO_UPDATE)

        val next = incident.copy(openedAt = 9_000L)
        open.value = listOf(next)
        handler.onAction(RulesNoticeActionHandler.ACTION_CHECK, "doordash", 9_000L)
        verify(notifier, times(1)).onRulesCheck(next, RulesCheckOutcome.NO_UPDATE)
    }

    @Test fun `an answer for an incident that closed is not posted`() {
        val (handler, _) = handler(RulesCheckOutcome.UPDATED)
        open.value = emptyList()
        handler.onAction(RulesNoticeActionHandler.ACTION_CHECK, "doordash", 1_000L)
        verify(notifier, never()).onRulesCheck(incident, RulesCheckOutcome.UPDATED)
    }
}
