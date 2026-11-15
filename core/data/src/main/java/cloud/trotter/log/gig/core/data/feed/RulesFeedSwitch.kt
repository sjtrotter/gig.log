package cloud.trotter.log.gig.core.data.feed

import cloud.trotter.log.gig.core.datastore.feed.RulesFeedDataSource
import cloud.trotter.log.gig.domain.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Keep recognition rules up to date" (#640), the rules feed's own consent: it gates the rules feed only, never fuel
 * or constants, and no other switch gates it. Off by default in every build.
 *
 * **The in-memory value is the one the feed reads, and it turns off at the instant the dasher turns it off.** [turnOff]
 * sets [enabled] before anything suspends, then saves; so the next check of a `RefreshGate` (before every request and
 * inside every exchange) is already closed, whatever the store is doing. No collector mirrors the store: it is read once
 * at start, and a choice made before that read lands wins over it. Turning on takes effect once it is saved, so a
 * failed save never leaves the feed on. Every path that saves the switch goes through here: Settings, the setup
 * wizard, the notice action and the phone wipe ([forgetNow]).
 *
 * It also owns the foreground check's hourly limit ([claimForegroundCheck]), persisted beside the switch so a restart
 * does not buy an extra check.
 */
@Singleton
class RulesFeedSwitch @Inject constructor(
    private val store: RulesFeedDataSource,
    @ApplicationScope scope: CoroutineScope,
) {
    private val _enabled = MutableStateFlow(false)

    /** Off until the stored choice is read (fail closed). */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Counts the dasher's choices; a save turns the switch on only while it is still the latest one. */
    private val choices = AtomicLong(0)
    private val writes = Mutex()
    private val loaded = CompletableDeferred<Unit>()
    private val throttle = Mutex()
    private var lastForegroundCheckAt: Long? = null

    init {
        scope.launch {
            val stored = try {
                store.read()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.tag(TAG).w("rules feed choice unreadable (%s); updates stay off", t.javaClass.simpleName)
                null
            }
            throttle.withLock { lastForegroundCheckAt = stored?.lastForegroundCheckAt }
            writes.withLock {
                if (choices.get() == 0L && stored?.enabled == true) _enabled.value = true
            }
            loaded.complete(Unit)
        }
    }

    /** The choice once the stored one has been read: for a screen that shows it and saves it back (the wizard). */
    suspend fun current(): Boolean {
        loaded.await()
        return _enabled.value
    }

    /** Saves the dasher's choice. False when the store refused it; a refused "off" stays off in memory. */
    suspend fun set(on: Boolean): Boolean = if (on) turnOn() else turnOff()

    private suspend fun turnOn(): Boolean {
        val choice = choices.incrementAndGet()
        return writes.withLock {
            val saved = save(true)
            if (saved && choices.get() == choice) _enabled.value = true
            saved
        }
    }

    private suspend fun turnOff(): Boolean {
        forgetNow()
        return writes.withLock { save(false) }
    }

    /**
     * Turns the switch off in memory at once, without saving: the phone wipe calls it just before Android clears the
     * app's storage, so no feed request starts in between.
     */
    fun forgetNow() {
        choices.incrementAndGet()
        _enabled.value = false
    }

    private suspend fun save(on: Boolean): Boolean = try {
        store.setEnabled(on)
        Timber.tag(TAG).i("rules updates turned %s", if (on) "on" else "off")
        true
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Timber.tag(TAG).w("rules updates choice not saved (%s)", t.javaClass.simpleName)
        false
    }

    /**
     * Whether a foreground check may run at [now]: the switch is on and no foreground check started in the hour
     * before. A true answer stamps [now] first (persisted; a failed write still counts in memory), so two foregrounds
     * cannot both pass.
     */
    suspend fun claimForegroundCheck(now: Long): Boolean {
        loaded.await()
        if (!_enabled.value) return false
        val claimed = throttle.withLock {
            val last = lastForegroundCheckAt
            if (last != null && now >= last && now - last < FOREGROUND_INTERVAL_MS) return@withLock false
            lastForegroundCheckAt = now
            true
        }
        if (!claimed) return false
        try {
            store.setLastForegroundCheckAt(now)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Timber.tag(TAG).w("foreground check time not saved (%s)", t.javaClass.simpleName)
        }
        return _enabled.value
    }

    companion object {
        /** At most one foreground check an hour. */
        const val FOREGROUND_INTERVAL_MS = 60L * 60 * 1000

        private const val TAG = "Feeds"
    }
}
