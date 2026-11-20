package cloud.trotter.log.gig.core.pipeline

import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.model.notification.RawNotificationData
import cloud.trotter.log.gig.core.pipeline.accessibility.TreeSnapshot

/**
 * Pre-classification discriminated union for all pipeline input events.
 *
 * Each variant carries raw data from its source sub-pipe. Classification
 * transforms a [PipelineEvent] into an [Observation].
 */
sealed interface PipelineEvent {
    val timestamp: Long

    /** Full-screen tree snapshot from the window sub-pipes (content-changed / state-changed). */
    data class Screen(
        override val timestamp: Long,
        val tree: UiNode,
        val snapshot: TreeSnapshot,
        val packageName: String? = null,
    ) : PipelineEvent

    /** A click on a single node, extracted from TYPE_VIEW_CLICKED. */
    data class Click(
        override val timestamp: Long,
        val node: UiNode,
        val packageName: String? = null,
        /**
         * #1634: the tapped node's own window, read at click time only while click evidence is armed; null otherwise or
         * when it could not be read. Census evidence only: classification and capture never read it.
         */
        val window: ClickWindow? = null,
    ) : PipelineEvent

    /** A notification from the notification listener service. */
    data class Notification(
        override val timestamp: Long,
        val raw: RawNotificationData,
    ) : PipelineEvent
}

/**
 * #1634: the window a tap landed in, mapped from that window's own root at click time. [sourcePath] is the tapped node's
 * child-index path over [tree] (root = empty). [windowTitle] is the window's title, scanned like a screen's. [boundary]
 * is the census clear boundary read BEFORE the window was read, so evidence taken before a census deletion is dropped
 * however late it is published. Not a data class: the tree is compared by identity, never by value.
 */
class ClickWindow(
    val tree: UiNode,
    val sourcePath: List<Int>,
    val windowTitle: String?,
    val boundary: Long,
) {
    /** The tapped node inside [tree]; null when [sourcePath] leaves it. */
    fun source(): UiNode? = sourcePath.fold<Int, UiNode?>(tree) { node, index -> node?.children?.getOrNull(index) }

    override fun toString(): String = "ClickWindow([redacted])"
}
