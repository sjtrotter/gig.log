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

    /**
     * The dasher asked the platform to go offline (a one-tap offline toggle with no confirm sheet). The
     * stepper arms the same graced end as [END_SHIFT_CONFIRM] (#1697). The literal stays `go_offline` so
     * captures keep their folder.
     */
    const val GO_OFFLINE = "go_offline"
}
