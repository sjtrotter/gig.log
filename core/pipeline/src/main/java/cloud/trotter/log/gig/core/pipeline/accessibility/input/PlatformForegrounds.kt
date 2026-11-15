package cloud.trotter.log.gig.core.pipeline.accessibility.input

import android.view.accessibility.AccessibilityEvent
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A delivery app coming to the front (#640): a transition INTO a platform the dasher turned on, from any other
 * package, resolved through the platform registry (principle 8: no package literal). Staying in the app is no new
 * foreground, whatever its windows do, and gig.log's own windows (the bubble) never count as leaving it.
 *
 * The listener admits events only from the platforms that are on, so a leave is seen on the active window instead:
 * - on a topology event (`TYPE_WINDOWS_CHANGED`, which reaches the app only with Screen events allowed), at most one
 *   read a second by event time;
 * - on one probe [quietProbeMs] after the last event, so a dasher who leaves for another app is seen as gone even
 *   in the filtered posture. A leave seen by neither (back within the quiet period) is no new foreground.
 *
 * One read of the active window's package per probe; nothing else reads a tree. Consumers throttle (the rules feed
 * checks at most once an hour).
 */
@Singleton
class PlatformForegrounds(
    private val source: Flow<AccEvent>,
    private val activePackage: () -> String?,
    private val ownPackage: () -> String?,
    private val quietProbeMs: Long = QUIET_PROBE_MS,
) {
    @Inject constructor(source: AccessibilitySource) :
        this(source.events, source::activeWindowPackage, source::ownPackage)

    private val lock = Any()
    private var front: Platform? = null
    private var lastTopologyReadMs = Long.MIN_VALUE

    val events: Flow<Platform> = channelFlow {
        var probe: Job? = null
        source.collect { event ->
            probe?.cancel()
            observe(event)?.let { send(it) }
            probe = launch {
                delay(quietProbeMs)
                observeActive(activePackage())?.let { send(it) }
            }
        }
    }

    /** One event: the platform it brings to the front, or null when it is no transition. */
    fun observe(event: AccEvent): Platform? {
        if (event.type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return observeActive(event.packageName)
        val read = synchronized(lock) {
            val due = lastTopologyReadMs == Long.MIN_VALUE || event.eventTimeMs - lastTopologyReadMs >= TOPOLOGY_READ_MS
            if (due) lastTopologyReadMs = event.eventTimeMs
            due
        }
        return if (read) observeActive(activePackage()) else null
    }

    private fun observeActive(pkg: String?): Platform? {
        if (pkg == null || pkg == ownPackage()) return null
        val platform = Platform.fromPackage(pkg).takeIf { it != Platform.Unknown }
        return synchronized(lock) {
            val entered = platform != null && platform != front
            front = platform
            platform.takeIf { entered }
        }
    }

    companion object {
        /** How long after the last event the active window is read once. */
        const val QUIET_PROBE_MS = 30_000L

        /** At most one active-window read a second on topology events. */
        const val TOPOLOGY_READ_MS = 1_000L
    }
}
