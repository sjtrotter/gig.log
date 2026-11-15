package cloud.trotter.log.gig.core.pipeline.accessibility.input

import android.view.accessibility.AccessibilityEvent
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #640: a foreground is a transition INTO a delivery app the dasher turned on, from any other package. Staying in the
 * app, whatever its windows do, is no new foreground; gig.log's own windows never count as leaving it. A leave is seen
 * when the active window belongs to another package: on a topology event, or on the probe once events go quiet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlatformForegroundsTest {
    private val own = "cloud.trotter.log.gig"
    private val launcher = "com.example.launcher"
    private val dd = Platform.DoorDash.packageName
    private val uber = Platform.Uber.packageName
    private var active: String? = null
    private var at = 0L

    private fun ev(type: Int, pkg: String?) = AccEvent(type, windowId = 1, packageName = pkg, className = null,
        contentChangeTypes = 0, eventTimeMs = at.also { at += 10_000 })

    private val state = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
    private val content = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    private val windows = AccessibilityEvent.TYPE_WINDOWS_CHANGED

    @Test fun `window changes inside the app are one foreground, leaving and coming back is another`() = runTest {
        val source = MutableSharedFlow<AccEvent>(extraBufferCapacity = 64)
        val foregrounds = PlatformForegrounds(source, { active }, { own }, quietProbeMs = 30_000L)
        val seen = mutableListOf<Platform>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { foregrounds.events.collect { seen += it } }

        active = dd
        repeat(5) { source.emit(ev(state, dd)) }
        source.emit(ev(content, dd))
        advanceTimeBy(60_000L); runCurrent()
        source.emit(ev(state, dd))
        assertEquals("staying in the app is one foreground", listOf(Platform.DoorDash), seen)

        active = own // the bubble in front: still in the delivery app
        source.emit(ev(windows, null))
        advanceTimeBy(60_000L); runCurrent()
        source.emit(ev(state, dd))
        assertEquals(listOf(Platform.DoorDash), seen)

        active = launcher // left, seen on the quiet probe
        advanceTimeBy(60_000L); runCurrent()
        active = dd
        source.emit(ev(state, dd))
        assertEquals(listOf(Platform.DoorDash, Platform.DoorDash), seen)

        active = launcher // left, seen on a topology event
        source.emit(ev(windows, null))
        active = dd
        source.emit(ev(state, dd))
        assertEquals(3, seen.size)

        active = uber // another delivery app's window comes to the front
        source.emit(ev(state, uber))
        assertEquals(Platform.Uber, seen.last())
        job.cancel()
    }

    @Test fun `a package the registry does not know is never a foreground`() = runTest {
        val source = MutableSharedFlow<AccEvent>(extraBufferCapacity = 64)
        val seen = mutableListOf<Platform>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            PlatformForegrounds(source, { launcher }, { own }).events.collect { seen += it }
        }
        source.emit(ev(state, launcher))
        source.emit(ev(state, null))
        source.emit(ev(windows, null))
        assertEquals(emptyList<Platform>(), seen)
        job.cancel()
    }
}
