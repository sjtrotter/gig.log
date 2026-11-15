package cloud.trotter.log.gig.notice

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import cloud.trotter.feed.contract.Feed
import cloud.trotter.feed.contract.FeedResult
import cloud.trotter.feed.contract.FeedSources
import cloud.trotter.feed.contract.FeedTrust
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.data.feed.RulesCheckOutcome
import cloud.trotter.log.gig.core.data.feed.RulesFeedSwitch
import cloud.trotter.log.gig.domain.pipeline.RecognitionHealthReporter
import cloud.trotter.log.gig.domain.pipeline.RecognitionIncident
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The dasher-visible half of the recognition incident (#937, one incident per platform since #1560):
 * when a platform's screens stop being recognized, or its rules stop reading them, say so out loud
 * instead of quietly narrating nothing.
 *
 * This is the recognition entry in the #909 silent-death family. The effect engine (#914), the
 * bubble (#916) and the odometer (#917) each got a liveness signal; recognition had none, so a
 * platform app update that broke every anchor mid-dash was detectable only at the next desk
 * pull — and once rules ship OTA (#640) to users who never do a desk pull, never at all.
 *
 * **One notice per platform.** Every notice posts on [AppNoticeChannel.Ids.RECOGNITION_HEALTH] tagged
 * by the platform wire, so two platforms' notices coexist and a close withdraws only its own. The notice
 * is posted once per incident and not updated when causes join; Home's card shows every cause.
 *
 * **Rules actions (#640).** This is the one place the notice is built, so its buttons are added here:
 * "Check for new rules", and "Keep rules up to date" while that switch is off ([RulesNoticeActionReceiver]). The
 * answer to a tap replaces the notice through [onRulesCheck]; the `:domain` contract stays data only.
 *
 * **Stateless by design.** The incident, its one-notice gate and the once-a-day limit live in
 * `RecognitionHealthMonitor` (`:core:pipeline`); duplicating a gate here would be a second source of
 * truth for "have we already said this".
 *
 * **Fail-open.** Every path is wrapped: a notification the OS refuses (POST_NOTIFICATIONS not granted)
 * must degrade to the monitor's log lines alone, never to a crashed reporter coroutine.
 *
 * **PII posture:** the copy comes from [RecognitionNoticeCopy]: a platform's own display name
 * (resolved from its wire through the `Platform` registry) and our own shape and field labels. No
 * screen text, no customer data, no store names, no percentage.
 */
@Singleton
class RecognitionHealthNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val notificationManager: NotificationManager,
    private val rulesSwitch: RulesFeedSwitch,
) : RecognitionHealthReporter {

    override fun onIncidentNotice(incident: RecognitionIncident) = posting {
        post(
            incident,
            RecognitionNoticeCopy.title(context.resources, incident),
            RecognitionNoticeCopy.text(context.resources, incident),
            rulesActions(incident),
        )
    }

    /**
     * #640: the answer to a rules action, in place of the incident's notice. New rules in use posts no buttons; no
     * update and a failed check keep "Check for new rules".
     */
    fun onRulesCheck(incident: RecognitionIncident, outcome: RulesCheckOutcome) = posting {
        val res = context.resources
        when (outcome) {
            RulesCheckOutcome.UPDATED -> post(
                incident,
                res.getString(R.string.recognition_rules_updated_title),
                RecognitionNoticeCopy.rulesUpdatedText(res, incident),
                emptyList(),
            )
            RulesCheckOutcome.NO_UPDATE -> post(
                incident,
                RecognitionNoticeCopy.noUpdateTitle(res, incident),
                res.getString(R.string.recognition_no_update_text),
                rulesActions(incident),
            )
            RulesCheckOutcome.FAILED -> post(
                incident,
                res.getString(R.string.recognition_check_failed_title),
                res.getString(R.string.recognition_check_failed_text),
                rulesActions(incident),
            )
        }
    }

    /** None until this build pins a gig.log rules key (#641): a check could only fail. */
    private fun rulesActions(incident: RecognitionIncident): List<NotificationCompat.Action> =
        if (rulesFeedTrusted) {
            RulesNoticeActionReceiver.actions(context, incident, offerKeepUpdated = !rulesSwitch.enabled.value)
        } else {
            emptyList()
        }

    private val rulesFeedTrusted: Boolean by lazy {
        (FeedTrust.shipped() as? FeedResult.Ok)?.value?.trusts(FeedSources.GIG_LOG, Feed.RULES) == true
    }

    private fun post(incident: RecognitionIncident, title: String, text: String, actions: List<NotificationCompat.Action>) {
        AppNoticeChannel.postNotice(
            context = context,
            notificationManager = notificationManager,
            notificationId = AppNoticeChannel.Ids.RECOGNITION_HEALTH,
            requestCode = REQUEST_CODE,
            title = title,
            text = text,
            tag = incident.platformWire,
            actions = actions,
        )
    }

    private inline fun posting(build: () -> Unit) {
        try {
            build()
        } catch (t: Throwable) {
            // Deliberately broad and NOT rethrown (#909): a refused notice costs a log line.
            Timber.tag(TAG).e(t, "Recognition notice could not be posted (#1560)")
        }
    }

    override fun onIncidentClosed(platformWire: String) {
        try {
            AppNoticeChannel.cancelNotice(notificationManager, platformWire, AppNoticeChannel.Ids.RECOGNITION_HEALTH)
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Recognition notice could not be withdrawn (#1560)")
        }
    }

    private companion object {
        const val TAG = "RecognitionHealth"
        const val REQUEST_CODE = 937
    }
}
