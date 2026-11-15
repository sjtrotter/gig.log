package cloud.trotter.log.gig.domain.census

/** Fed by the pipeline per admitted SCREEN frame; implementations must never throw (the pipeline wraps anyway). */
interface HealthSink {
    fun onScreen(timestampMillis: Long, platformWire: String, platformAppVersion: String?, ruleId: String?)
    fun onTrip(timestampMillis: Long, platformWire: String, platformAppVersion: String?)

    /**
     * One eligible attempt of [ruleId] (#1571, `ParseRotTracker`): [shortfall] when a required field came back null.
     * [timestampMillis] and [platformAppVersion] are the attempt's start frame's.
     */
    fun onParseAttempt(
        timestampMillis: Long,
        platformWire: String,
        platformAppVersion: String?,
        ruleId: String,
        shortfall: Boolean,
    )

    object NoOp : HealthSink {
        override fun onScreen(timestampMillis: Long, platformWire: String, platformAppVersion: String?, ruleId: String?) = Unit
        override fun onTrip(timestampMillis: Long, platformWire: String, platformAppVersion: String?) = Unit
        override fun onParseAttempt(
            timestampMillis: Long,
            platformWire: String,
            platformAppVersion: String?,
            ruleId: String,
            shortfall: Boolean,
        ) = Unit
    }
}
