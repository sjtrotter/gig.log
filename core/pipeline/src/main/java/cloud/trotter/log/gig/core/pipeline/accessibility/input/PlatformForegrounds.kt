package cloud.trotter.log.gig.core.pipeline.accessibility.input

import android.view.accessibility.AccessibilityEvent
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A delivery app's window coming to the front (#640): each `TYPE_WINDOW_STATE_CHANGED` from a package the platform
 * registry knows, as that platform (principle 8: no package literal). The accessibility listener admits only the
 * packages the dasher turned on (`ListenerGate`), so a platform that is off sends nothing here. Consumers throttle; this
 * stream holds no state and reads no tree.
 */
@Singleton
class PlatformForegrounds @Inject constructor(private val source: AccessibilitySource) {
    val events: Flow<Platform> = source.events
        .filter { it.type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED }
        .map { Platform.fromPackage(it.packageName) }
        .filter { it != Platform.Unknown }
}
