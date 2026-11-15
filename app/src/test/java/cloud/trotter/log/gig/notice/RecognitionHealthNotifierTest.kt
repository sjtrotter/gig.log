package cloud.trotter.log.gig.notice

import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cloud.trotter.log.gig.core.data.feed.RulesCheckOutcome
import cloud.trotter.log.gig.core.data.feed.RulesFeedSwitch
import cloud.trotter.log.gig.core.datastore.feed.RulesFeedDataSource
import cloud.trotter.log.gig.domain.pipeline.ParseRotCause
import cloud.trotter.log.gig.domain.pipeline.RecognitionIncident
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The dasher-visible half of the recognition incident (#937, #1560) — the mirror of
 * `LocaleBoundaryNotifierTest`.
 *
 * Robolectric supplies a real `Context` (the notice needs `getString`); the
 * `NotificationManager` is a mock so the post is directly observable. Each platform's notice posts
 * under the one recognition id, tagged by its wire.
 */
@RunWith(RobolectricTestRunner::class)
class RecognitionHealthNotifierTest {

    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        notificationManager = mock()
    }

    private val rulesStore = object : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
    private val rulesSwitch = RulesFeedSwitch(RulesFeedDataSource(rulesStore), CoroutineScope(Dispatchers.Unconfined))

    private fun notifier() = RecognitionHealthNotifier(
        context = RuntimeEnvironment.getApplication(),
        notificationManager = notificationManager,
        rulesSwitch = rulesSwitch,
    )

    private fun posted(): Notification {
        val cap = argumentCaptor<Notification>()
        verify(notificationManager, atLeastOnce()).notify(any<String>(), any<Int>(), cap.capture())
        return cap.lastValue
    }

    private fun Notification.actionTitles(): List<String> = actions.orEmpty().map { it.title.toString() }

    private fun unrecognized(wire: String) =
        RecognitionIncident(platformWire = wire, openedAt = 1_000L, screensUnrecognized = true)

    private fun offerRot(wire: String) = RecognitionIncident(
        platformWire = wire,
        openedAt = 1_000L,
        parseRot = listOf(ParseRotCause("$wire.screen.offer_card", "offer", listOf("payAmount", "distance"), 1_000L)),
    )

    private fun postedText(): String {
        val cap = argumentCaptor<Notification>()
        verify(notificationManager).notify(any<String>(), any<Int>(), cap.capture())
        val n = cap.lastValue
        val title = n.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString().orEmpty()
        val body = n.extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString().orEmpty()
        return "$title\n$body"
    }

    @Test
    fun `an incident posts one notice on the shared app-notice channel, tagged by wire`() {
        notifier().onIncidentNotice(unrecognized("doordash"))

        verify(notificationManager).createNotificationChannel(any())
        verify(notificationManager).notify(
            eq("doordash"), eq(AppNoticeChannel.Ids.RECOGNITION_HEALTH), any<Notification>(),
        )
    }

    @Test
    fun `the notice names the platform - and nothing else`() {
        notifier().onIncidentNotice(offerRot(Platform.DoorDash.wire))

        val text = postedText()
        assertTrue("expected the platform's display name in $text", text.contains("DoorDash"))
        // Principle 7 / the Pledges: a liveness notice carries platform facts, never screen text.
        assertFalse("a wire is not user-facing copy", text.contains("doordash"))
        assertFalse("no percentage or count in the copy: $text", text.any { it.isDigit() })
    }

    @Test
    fun `an unrecognised wire still notifies, with generic wording`() {
        notifier().onIncidentNotice(unrecognized("some_future_platform"))

        val text = postedText()
        assertTrue("expected the generic fallback in $text", text.contains("Delivery app"))
        assertFalse("never fabricate a brand name: $text", text.contains("some_future_platform"))
    }

    @Test
    fun `a closed incident withdraws its own notice`() {
        notifier().onIncidentClosed("doordash")

        verify(notificationManager).cancel("doordash", AppNoticeChannel.Ids.RECOGNITION_HEALTH)
    }

    @Test
    fun `two platforms keep two notices under their own tags`() {
        val notifier = notifier()
        notifier.onIncidentNotice(offerRot("doordash"))
        notifier.onIncidentNotice(offerRot("uber"))

        verify(notificationManager).notify(eq("doordash"), eq(AppNoticeChannel.Ids.RECOGNITION_HEALTH), any<Notification>())
        verify(notificationManager).notify(eq("uber"), eq(AppNoticeChannel.Ids.RECOGNITION_HEALTH), any<Notification>())
    }

    @Test
    fun `the app notices do not collide`() {
        assertEquals(
            "every notice on the shared channel needs its own id or they overwrite each other",
            3,
            setOf(
                AppNoticeChannel.Ids.LOCALE_BOUNDARY,
                AppNoticeChannel.Ids.RECOGNITION_HEALTH,
                AppNoticeChannel.Ids.TTS_ENGINE_HEALTH,
            ).size,
        )
    }

    @Test
    fun `the notice offers a rules check, and Keep rules up to date while that switch is off`() {
        // The unit-test classpath pins a test gig.log rules key (root feedTestPins), so this build "trusts" a rules feed.
        notifier().onIncidentNotice(offerRot("doordash"))
        assertEquals(listOf("Check for new rules", "Keep rules up to date"), posted().actionTitles())

        runBlocking { assertTrue(rulesSwitch.set(true)) }
        notifier().onIncidentNotice(offerRot("uber"))
        assertEquals(listOf("Check for new rules"), posted().actionTitles())
    }

    @Test
    fun `the answers to a rules check replace the notice under its own tag`() {
        val notifier = notifier()
        notifier.onRulesCheck(offerRot("doordash"), RulesCheckOutcome.NO_UPDATE)
        val noUpdate = posted()
        assertEquals("No update yet for DoorDash", noUpdate.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
        assertEquals(listOf("Check for new rules", "Keep rules up to date"), noUpdate.actionTitles())

        notifier.onRulesCheck(offerRot("doordash"), RulesCheckOutcome.UPDATED)
        val updated = posted()
        assertEquals("New recognition rules in use", updated.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
        assertTrue(updated.actionTitles().isEmpty())
        verify(notificationManager, atLeastOnce()).notify(eq("doordash"), eq(AppNoticeChannel.Ids.RECOGNITION_HEALTH), any<Notification>())
    }

    @Test
    fun `a refused post is swallowed - the reporter coroutine never sees it`() {
        doThrow(SecurityException("POST_NOTIFICATIONS not granted"))
            .whenever(notificationManager).notify(any<String>(), any<Int>(), any<Notification>())

        // Must not throw: the incident owner's ordered reporter coroutine calls this.
        notifier().onIncidentNotice(unrecognized("doordash"))
    }
}
