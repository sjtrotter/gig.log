package cloud.trotter.log.gig.domain.pipeline

/** Frame-time windows the visit counters share: #1571's `ParseRotTracker` and #1630's shadow visits. */
object VisitTiming {
    /** The pre-render discard window, and the minimum spacing of one rule's visit starts (frame time). */
    const val PRE_RENDER_WINDOW_MS = 3_000L
}
