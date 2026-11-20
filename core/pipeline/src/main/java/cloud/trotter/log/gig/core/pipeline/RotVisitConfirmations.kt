package cloud.trotter.log.gig.core.pipeline

/**
 * #1573: told of every confirmed parse-rot visit ([ParseRotTracker]'s `shortfall = true` attempts), so a frame held
 * from that visit can be released. [RecognitionHealthMonitor] passes the facts; the listener never reads the incidents
 * itself, so there is no dependency cycle.
 */
fun interface RotVisitConfirmations {
    /**
     * The visit of [ruleId] on [platformWire] that started at frame time [start] was confirmed (not a pre-render frame).
     * [openRuleIds] are the platform's rules in an open parse-rot incident right after the confirming frame's decision.
     */
    fun onConfirmed(platformWire: String, ruleId: String, start: Long, openRuleIds: Set<String>)

    companion object {
        /** Nobody listens. */
        val NONE = RotVisitConfirmations { _, _, _, _ -> }
    }
}
