package cloud.trotter.log.gig.core.pipeline.accessibility.input

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import cloud.trotter.log.gig.core.pipeline.PipelineStats
import cloud.trotter.log.gig.core.pipeline.accessibility.mapper.TreeLimits
import cloud.trotter.log.gig.core.pipeline.census.ClickEvidenceRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1634 D4: the tap's window comes from the source node's own window and root, never a parent climb, and the tapped
 * node is found by identity during that one mapping, its path over the MAPPED children. Mocked live nodes only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ClickWindowAcquisitionTest {

    private val pkg = "com.doordash.driverapp"
    private val source = AccessibilitySource(PipelineStats())

    private fun node(text: String? = null, windowId: Int = 7, packageName: String = pkg, vararg children: AccessibilityNodeInfo?): AccessibilityNodeInfo {
        val node = mock<AccessibilityNodeInfo> {
            on { this.packageName } doReturn packageName
            on { this.text } doReturn text
            on { this.windowId } doReturn windowId
            on { childCount } doReturn children.size
        }
        children.forEachIndexed { i, child -> whenever(node.getChild(i)).thenReturn(child) }
        return node
    }

    private fun window(id: Int, root: AccessibilityNodeInfo?, title: String? = null): AccessibilityWindowInfo = mock {
        on { this.id } doReturn id
        on { this.root } doReturn root
        on { this.title } doReturn title
    }

    private fun AccessibilityNodeInfo.inWindow(window: AccessibilityWindowInfo?): AccessibilityNodeInfo =
        also { whenever(it.window).thenReturn(window) }

    private fun acquired(result: AccessibilitySource.ClickAcquisition) = (result as AccessibilitySource.ClickAcquisition.Acquired).window

    @Test fun `the path counts mapped children only, and the window's own root is the tree`() {
        val label = node("Decline")
        val button = node(null, 7, pkg, label)
        // Index 0 is a null child the mapper skips: the button is MAPPED child 0, not raw child 1.
        val root = node("Quiet Panel", 7, pkg, null, button)
        label.inWindow(window(7, root, title = "Driver"))
        val window = acquired(source.clickWindow(label, pkg, boundary = 3L))
        assertEquals(listOf(0, 0), window.sourcePath)
        assertEquals("Quiet Panel", window.tree.text)
        assertEquals("Decline", window.source()?.text)
        assertEquals("Driver", window.windowTitle)
        assertEquals(3L, window.boundary)
    }

    @Test fun `a source whose parent is unreadable still gets its window root, never a climb`() {
        val tapped = node("Continue")
        val container = node(null, 7, pkg, tapped)
        val root = node("Available Balance", 7, pkg, container)
        whenever(tapped.parent).thenReturn(null)
        tapped.inWindow(window(7, root))
        val window = acquired(source.clickWindow(tapped, pkg, boundary = 0L))
        assertEquals("the blocked screen's root is what gets classified", "Available Balance", window.tree.text)
        verify(tapped, never()).parent
    }

    @Test fun `no window or no root is no evidence`() {
        val tapped = node("Continue").inWindow(null)
        assertEquals(ClickEvidenceRefusal.NO_WINDOW, refused(source.clickWindow(tapped, pkg, 0L)))
        val orphan = node("Continue").inWindow(window(7, null))
        assertEquals(ClickEvidenceRefusal.NO_WINDOW, refused(source.clickWindow(orphan, pkg, 0L)))
    }

    @Test fun `a source in a second window of the same package is read from that window`() {
        val tapped = node("Continue", windowId = 9)
        val second = node("Sheet", 9, pkg, tapped)
        tapped.inWindow(window(9, second))
        val window = acquired(source.clickWindow(tapped, pkg, 0L))
        assertEquals("Sheet", window.tree.text)
        assertEquals(listOf(0), window.sourcePath)
    }

    @Test fun `a window, root or package that does not match the event is no evidence`() {
        val tapped = node("Continue")
        tapped.inWindow(window(8, node("Other", 7, pkg, tapped)))
        assertEquals(ClickEvidenceRefusal.NO_WINDOW, refused(source.clickWindow(tapped, pkg, 0L)))
        tapped.inWindow(window(7, node("Other", 8, pkg, tapped)))
        assertEquals(ClickEvidenceRefusal.NO_WINDOW, refused(source.clickWindow(tapped, pkg, 0L)))
        tapped.inWindow(window(7, node("Other", 7, "com.example.other", tapped)))
        assertEquals(ClickEvidenceRefusal.NO_WINDOW, refused(source.clickWindow(tapped, pkg, 0L)))
        tapped.inWindow(window(7, node("Other", 7, pkg, tapped)))
        assertEquals(ClickEvidenceRefusal.NO_WINDOW, refused(source.clickWindow(tapped, null, 0L)))
    }

    @Test fun `a source absent after a window change is no evidence`() {
        val tapped = node("Continue")
        tapped.inWindow(window(7, node("New screen", 7, pkg, node("Something else"))))
        assertEquals(ClickEvidenceRefusal.SOURCE_GONE, refused(source.clickWindow(tapped, pkg, 0L)))
    }

    @Test fun `a source found twice, or a tree cut by a cap, is no evidence`() {
        val tapped = node("Continue")
        tapped.inWindow(window(7, node("Twice", 7, pkg, tapped, tapped)))
        assertEquals(ClickEvidenceRefusal.NO_WINDOW, refused(source.clickWindow(tapped, pkg, 0L)))

        val deep = node("Continue")
        var root = deep
        repeat(TreeLimits.MAX_TREE_DEPTH + 1) { root = node(null, 7, pkg, root) }
        deep.inWindow(window(7, root))
        assertEquals(ClickEvidenceRefusal.NO_WINDOW, refused(source.clickWindow(deep, pkg, 0L)))
    }

    @Test fun `a window cut by the node budget is no evidence, whatever lies past the cut`() {
        // The source maps first; a fan spends the whole node budget; the blocked screen's text sits after it, unread.
        val tapped = node("Continue")
        val leaf = node("Row")
        val fan = mock<AccessibilityNodeInfo> {
            on { packageName } doReturn pkg
            on { windowId } doReturn 7
            on { childCount } doReturn TreeLimits.MAX_TREE_NODES
            on { getChild(any()) } doReturn leaf
        }
        val root = node(null, 7, pkg, tapped, fan, node("Available Balance"))
        tapped.inWindow(window(7, root))
        assertEquals(ClickEvidenceRefusal.NO_WINDOW, refused(source.clickWindow(tapped, pkg, 0L)))
    }

    @Test fun `a window cut by the child-index cap is no evidence, whatever lies past the cut`() {
        // A fan of unreadable children spends no budget, so only the index cap stops it; its last child goes unread.
        val tapped = node("Continue")
        val fan = mock<AccessibilityNodeInfo> {
            on { packageName } doReturn pkg
            on { windowId } doReturn 7
            on { childCount } doReturn TreeLimits.MAX_TREE_NODES + 1
            on { getChild(TreeLimits.MAX_TREE_NODES) } doReturn node("Available Balance")
        }
        val root = node(null, 7, pkg, tapped, fan)
        tapped.inWindow(window(7, root))
        assertEquals(ClickEvidenceRefusal.NO_WINDOW, refused(source.clickWindow(tapped, pkg, 0L)))
    }

    @Test fun `the mapped tree keeps the source's node identity in the window tree`() {
        val tapped = node("Continue")
        val root = node("Panel", 7, pkg, tapped)
        tapped.inWindow(window(7, root))
        val window = acquired(source.clickWindow(tapped, pkg, 0L))
        assertSame(window.tree.children.single(), window.source())
        assertTrue(window.source()?.parent === window.tree)
    }

    private fun refused(result: AccessibilitySource.ClickAcquisition) =
        (result as AccessibilitySource.ClickAcquisition.Refused).reason
}
