package cloud.trotter.log.gig.notice

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.data.feed.RulesCheckOutcome
import cloud.trotter.log.gig.domain.di.ApplicationScope
import cloud.trotter.log.gig.domain.pipeline.RecognitionIncident
import cloud.trotter.log.gig.domain.pipeline.RecognitionIncidents
import cloud.trotter.log.gig.feed.RulesFeed
import cloud.trotter.log.gig.ui.main.MainActivity
import cloud.trotter.log.gig.ui.main.navigation.Screen
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The recognition notice's "Check for new rules" (#640): one check per tap, on a one-shot grant while "Keep recognition
 * rules up to date" is off (the tap is its own consent). The notice's other button, "Keep rules up to date", only opens
 * Settings > Recognition rules, where the switch sits beside its timing disclosure; nothing here saves the switch.
 *
 * Each tap runs in its own application-scope coroutine, so the grant [RulesFeed.checkNow] mints belongs to that tap
 * and dies with it; the receiver returns at once, since a check with its retries can outlast a broadcast's time limit.
 * The answer replaces the notice only while the incident it was posted for is still open. "No update yet" posts at most
 * once per incident; a later tap that finds no update posts nothing.
 */
@Singleton
class RulesNoticeActionHandler @Inject constructor(
    private val rulesFeed: RulesFeed,
    private val incidents: RecognitionIncidents,
    private val notifier: RecognitionHealthNotifier,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    /** Incidents (wire and opening time) that were already told "No update yet". In memory, as the incidents are. */
    private val toldNoUpdate = ConcurrentHashMap.newKeySet<Pair<String, Long>>()

    fun onAction(action: String, platformWire: String, openedAt: Long) {
        scope.launch {
            try {
                if (action != ACTION_CHECK) return@launch
                val outcome = rulesFeed.checkNow()
                Timber.tag(TAG).i("rules check from the recognition notice on %s: %s", platformWire, outcome)
                answer(platformWire, openedAt, outcome)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "rules check from the recognition notice failed")
            }
        }
    }

    private fun answer(platformWire: String, openedAt: Long, outcome: RulesCheckOutcome) {
        val incident = incidents.open.value.firstOrNull { it.platformWire == platformWire && it.openedAt == openedAt }
            ?: return
        if (outcome == RulesCheckOutcome.NO_UPDATE && !toldNoUpdate.add(platformWire to openedAt)) return
        notifier.onRulesCheck(incident, outcome)
    }

    companion object {
        const val ACTION_CHECK = "cloud.trotter.log.gig.action.RULES_CHECK"
        const val EXTRA_PLATFORM = "rules_platform"
        const val EXTRA_OPENED_AT = "rules_opened_at"
        private const val TAG = "Feeds"
    }
}

/** The "Check for new rules" broadcast. Not exported; the PendingIntents are immutable and explicit. */
class RulesNoticeActionReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun handler(): RulesNoticeActionHandler
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val wire = intent.getStringExtra(RulesNoticeActionHandler.EXTRA_PLATFORM) ?: return
        val openedAt = intent.getLongExtra(RulesNoticeActionHandler.EXTRA_OPENED_AT, -1L).takeIf { it >= 0 } ?: return
        EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
            .handler()
            .onAction(action, wire, openedAt)
    }

    companion object {
        /**
         * The buttons for [incident]'s notice: "Check for new rules", and while the switch is off "Keep rules up to
         * date", which opens Settings > Recognition rules (the switch and its disclosure). The check's PendingIntent is
         * keyed by platform, so two platforms' notices keep their own.
         */
        fun actions(context: Context, incident: RecognitionIncident, offerKeepUpdated: Boolean): List<NotificationCompat.Action> =
            buildList {
                add(check(context, incident))
                if (offerKeepUpdated) add(openSettings(context))
            }

        private fun check(context: Context, incident: RecognitionIncident): NotificationCompat.Action {
            val intent = Intent(context, RulesNoticeActionReceiver::class.java)
                .setAction(RulesNoticeActionHandler.ACTION_CHECK)
                .putExtra(RulesNoticeActionHandler.EXTRA_PLATFORM, incident.platformWire)
                .putExtra(RulesNoticeActionHandler.EXTRA_OPENED_AT, incident.openedAt)
            val pending = PendingIntent.getBroadcast(
                context, (RulesNoticeActionHandler.ACTION_CHECK + incident.platformWire).hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Action.Builder(0, context.getString(R.string.recognition_action_check), pending).build()
        }

        private fun openSettings(context: Context): NotificationCompat.Action {
            val pending = PendingIntent.getActivity(
                context, OPEN_SETTINGS_REQUEST_CODE, MainActivity.routeIntent(context, Screen.RulesUpdates.route),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Action.Builder(0, context.getString(R.string.recognition_action_keep_updated), pending).build()
        }

        private const val OPEN_SETTINGS_REQUEST_CODE = 640
    }
}
