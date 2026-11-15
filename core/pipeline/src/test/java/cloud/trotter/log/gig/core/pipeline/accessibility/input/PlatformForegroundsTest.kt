package cloud.trotter.log.gig.core.pipeline.accessibility.input

import android.view.accessibility.AccessibilityEvent
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #640: a delivery app's window coming to the front, resolved through the platform registry; nothing else. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlatformForegroundsTest {
    private fun event(type: Int, pkg: String?) = AccessibilityEvent(type).apply { packageName = pkg }

    @Test fun `a window state change from a registry package is that platform's foreground`() = runTest {
        val source = AccessibilitySource()
        val seen = mutableListOf<Platform>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { PlatformForegrounds(source).events.collect { seen += it } }

        source.emit(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, Platform.DoorDash.packageName))
        source.emit(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, Platform.DoorDash.packageName))
        source.emit(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.example.other"))
        source.emit(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, null))
        source.emit(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, Platform.Uber.packageName))

        assertEquals(listOf(Platform.DoorDash, Platform.Uber), seen)
        job.cancel()
    }
}
