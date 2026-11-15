package cloud.trotter.log.gig.core.pipeline.accessibility.input

import android.view.accessibility.AccessibilityEvent
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.time.ElapsedRealtime
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #640: a foreground is the first event from a delivery app the dasher turned on after at least 10 minutes with no
 * event from that app. Active use never re-triggers; a return after a break does; a platform that is off never does.
 * It reads no window: one clock read and one compare per event.
 */
class PlatformForegroundsTest {
    private var now = 1_000_000L
    private var enabled = setOf(DD, UBER)
    private val foregrounds = PlatformForegrounds(emptyFlow(), { enabled }, ElapsedRealtime { now })

    private fun event(pkg: String?, type: Int = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) =
        AccEvent(type, windowId = 1, packageName = pkg, className = null, contentChangeTypes = 0, eventTimeMs = 0L)

    private fun after(ms: Long, pkg: String?): Platform? {
        now += ms
        return foregrounds.observe(event(pkg))
    }

    @Test fun `active use is one foreground`() {
        assertEquals(Platform.DoorDash, after(0, DD))
        repeat(200) { assertNull("an event every 9 minutes keeps the app in use", after(9 * MINUTE, DD)) }
    }

    @Test fun `the first event after a 10-minute gap is a foreground`() {
        after(0, DD)
        assertNull(after(9 * MINUTE + 59_000, DD))
        assertEquals(Platform.DoorDash, after(10 * MINUTE, DD))
        assertNull(after(1_000, DD))
    }

    @Test fun `each delivery app keeps its own gap`() {
        assertEquals(Platform.DoorDash, after(0, DD))
        assertEquals(Platform.Uber, after(1_000, UBER))
        assertNull(after(1_000, DD))
    }

    @Test fun `a platform that is off, an unknown package or no package is never a foreground`() {
        enabled = setOf(DD)
        assertNull(after(0, UBER))
        assertNull(after(0, "com.example.launcher"))
        assertNull(after(0, null))
        assertNull(foregrounds.observe(event(null, AccessibilityEvent.TYPE_WINDOWS_CHANGED)))
        enabled = emptySet()
        assertNull(after(0, DD))
    }

    @Test fun `the stream emits the foregrounds only`() = runBlocking {
        val stream = PlatformForegrounds(flowOf(event(DD), event(DD), event(UBER), event("com.example.other")), { enabled }, ElapsedRealtime { now })
        assertEquals(listOf(Platform.DoorDash, Platform.Uber), stream.events.toList())
    }

    private companion object {
        val DD = Platform.DoorDash.packageName!!
        val UBER = Platform.Uber.packageName!!
        const val MINUTE = 60_000L
    }
}
