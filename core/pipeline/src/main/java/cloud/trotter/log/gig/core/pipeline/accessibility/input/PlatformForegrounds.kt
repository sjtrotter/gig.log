package cloud.trotter.log.gig.core.pipeline.accessibility.input

import cloud.trotter.log.gig.domain.settings.PlatformPreferences
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.time.ElapsedRealtime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import java.util.EnumMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A delivery app opened or returned to (#640): the first accessibility event from a platform the dasher turned on
 * after at least [RETURN_GAP_MS] with no event from that platform, by elapsed time. Active use never re-triggers; a
 * return after a break does; a platform that is off never does. Packages resolve through the platform registry
 * (principle 8: no package literal), and the enabled set is read as it is now.
 *
 * On the event stream's hot path, so it reads no window and no tree: a set lookup, a clock read and one compare per
 * event. Consumers throttle (the rules feed checks at most once an hour).
 */
@Singleton
class PlatformForegrounds(
    private val source: Flow<AccEvent>,
    private val enabledPackages: () -> Set<String>,
    private val elapsed: ElapsedRealtime,
) {
    @Inject constructor(source: AccessibilitySource, preferences: PlatformPreferences, elapsed: ElapsedRealtime) :
        this(source.events, { preferences.enabledPackages.value }, elapsed)

    private val lastEventAt = EnumMap<Platform, Long>(Platform::class.java)

    val events: Flow<Platform> = source.mapNotNull(::observe)

    /** One event: its platform when it opens or returns to that platform, else null. */
    @Synchronized
    fun observe(event: AccEvent): Platform? {
        val pkg = event.packageName ?: return null
        if (pkg !in enabledPackages()) return null
        val platform = Platform.fromPackage(pkg).takeIf { it != Platform.Unknown } ?: return null
        val now = elapsed.millis()
        val last = lastEventAt.put(platform, now)
        return platform.takeIf { last == null || now - last >= RETURN_GAP_MS }
    }

    companion object {
        /** A break this long with no event from a delivery app makes the next one a return. */
        const val RETURN_GAP_MS = 10L * 60 * 1000
    }
}
