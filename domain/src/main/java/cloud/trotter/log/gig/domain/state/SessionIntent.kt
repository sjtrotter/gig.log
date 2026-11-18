package cloud.trotter.log.gig.domain.state

/**
 * Wire identifiers for session intents. One source of truth shared by the ruleset click
 * classification (`ClickFields.intent`) and the stepper, as [TaskIntent] is for tasks.
 * Load validation of click intents is #1322.
 */
object SessionIntent {
    /**
     * The dasher confirmed the end of the shift (the platform's last end-shift tap, after which the
     * dasher can no longer cancel). The stepper arms the non-authoritative `SESSION_END` grace on it (#1668).
     */
    const val END_SHIFT_CONFIRM = "end_shift_confirm"
}
