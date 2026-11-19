package cloud.trotter.log.gig.core.data.feed

import cloud.trotter.log.gig.core.datastore.settings.AppPreferencesDataSource
import cloud.trotter.log.gig.domain.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The fuel feed's consent, as the feed reads it: "Auto gas price" (the stored `is_gas_price_auto`; manual mode is that
 * flag off). It gates the fuel feed only, never rules or constants.
 *
 * **The in-memory value turns off at the instant a write that may turn auto off starts.** [write] closes [enabled]
 * before anything suspends, then saves; so the next check of a `RefreshGate` (before every request and inside every
 * exchange) is already closed, whatever the store is doing. No collector mirrors the store: it is read once at start,
 * and a choice made before that read lands wins over it. Turning on counts once the stored value says on, so a failed
 * save never leaves the feed on.
 *
 * Every write of `is_gas_price_auto` goes through `AppPreferencesRepository`, which routes each one through [write];
 * a write of that key that bypasses the repository would reopen the gap between the tap and a closed gate. The phone
 * wipe closes the switch with [closeNow] just before Android clears the app's storage. Screens keep reading the
 * stored flag; only the feed layer reads [enabled].
 */
@Singleton
class FuelFeedSwitch @Inject constructor(
    private val store: AppPreferencesDataSource,
    @ApplicationScope scope: CoroutineScope,
) {
    private val _enabled = MutableStateFlow(false)

    /** Off until the stored flag is read (fail closed). */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Counts writes and closes; a write turns the switch on only while it is still the latest one. */
    private val choices = AtomicLong(0)
    private val writes = Mutex()

    /** Makes "still the latest choice, so on" one step against a concurrent [closeNow]. */
    private val latest = Any()

    init {
        scope.launch {
            val stored = try {
                store.isGasPriceAuto.first()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.tag(TAG).w("auto gas price unreadable (%s); fuel updates stay off", t.javaClass.simpleName)
                false
            }
            writes.withLock {
                if (stored) openIfLatest(0L)
            }
        }
    }

    /** Turns the switch off in memory at once, without saving. Never suspends. */
    fun closeNow() {
        close()
    }

    private fun close(): Long = synchronized(latest) {
        _enabled.value = false
        choices.incrementAndGet()
    }

    private fun openIfLatest(choice: Long) {
        synchronized(latest) {
            val isLatest = choices.get() == choice
            _enabled.value = _enabled.value || isLatest
        }
    }

    /**
     * Runs [write], a save that touches `is_gas_price_auto`, and sets [enabled] from the stored flag afterwards: off when
     * it is off, on only when it is on and no later write or close came in meanwhile. With [mayClose] the switch closes
     * before anything suspends. An exception from [write] propagates and leaves [enabled] as it was set before the write
     * (closed, for a [mayClose] write).
     */
    suspend fun <R> write(mayClose: Boolean, write: suspend () -> R): R {
        val choice = if (mayClose) close() else choices.incrementAndGet()
        return writes.withLock { saveAndSettle(choice, write) }
    }

    /** The count of writes and closes so far: [writeIfUnchanged] compares against it. */
    fun revision(): Long = choices.get()

    /**
     * Runs [write] (a save that may turn auto on, never off) only when no other write or close came after
     * [since], a [revision] read before the work that led to it began; null when one did, and nothing is written.
     * The check and the new choice number are one step under the same lock as every write, so a choice the dasher
     * makes meanwhile is never overwritten: one made before the lock is taken skips this write, and one made after it
     * lands after this one. A skipped write takes no choice number, so it never stops the write it lost to from
     * turning the switch on.
     */
    suspend fun <R> writeIfUnchanged(since: Long, write: suspend () -> R): R? = writes.withLock {
        val choice = synchronized(latest) { if (choices.get() == since) choices.incrementAndGet() else null }
        if (choice == null) null else saveAndSettle(choice, write)
    }

    /** Must run under [writes]. */
    private suspend fun <R> saveAndSettle(choice: Long, write: suspend () -> R): R {
        val result = write()
        val stored = store.isGasPriceAuto.first()
        if (stored) {
            openIfLatest(choice)
        } else {
            _enabled.value = false
        }
        return result
    }

    private companion object {
        const val TAG = "Feeds"
    }
}
