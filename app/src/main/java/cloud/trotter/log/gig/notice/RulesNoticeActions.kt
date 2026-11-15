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
 * The recognition notice's rules actions (#640): "Check for new rules" (one check on a one-shot grant, with "Keep
 * recognition rules up to date" off or on) and "Keep rules up to date" (saves the switch on, then checks at once).
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
                val outcome = when (action) {
                    ACTION_CHECK -> rulesFeed.checkNow()
                    ACTION_KEEP_UPDATED -> rulesFeed.enableAndCheck() ?: RulesCheckOutcome.FAILED
                    else -> return@launch
                }
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
        const val ACTION_KEEP_UPDATED = "cloud.trotter.log.gig.action.RULES_KEEP_UPDATED"
        const val EXTRA_PLATFORM = "rules_platform"
        const val EXTRA_OPENED_AT = "rules_opened_at"
        private const val TAG = "Feeds"
    }
}

/** The notice buttons' broadcast. Not exported; the PendingIntents are immutable and explicit. */
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
         * The buttons for [incident]'s notice: "Check for new rules", and "Keep rules up to date" while the switch is
         * off. Each PendingIntent is keyed by action and platform, so two platforms' notices keep their own.
         */
        fun actions(context: Context, incident: RecognitionIncident, offerKeepUpdated: Boolean): List<NotificationCompat.Action> =
            buildList {
                add(action(context, incident, RulesNoticeActionHandler.ACTION_CHECK, R.string.recognition_action_check))
                if (offerKeepUpdated) {
                    add(action(context, incident, RulesNoticeActionHandler.ACTION_KEEP_UPDATED, R.string.recognition_action_keep_updated))
                }
            }

        private fun action(context: Context, incident: RecognitionIncident, action: String, label: Int): NotificationCompat.Action {
            val intent = Intent(context, RulesNoticeActionReceiver::class.java)
                .setAction(action)
                .putExtra(RulesNoticeActionHandler.EXTRA_PLATFORM, incident.platformWire)
                .putExtra(RulesNoticeActionHandler.EXTRA_OPENED_AT, incident.openedAt)
            val requestCode = (action + incident.platformWire).hashCode()
            val pending = PendingIntent.getBroadcast(
                context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Action.Builder(0, context.getString(label), pending).build()
        }
    }
}
