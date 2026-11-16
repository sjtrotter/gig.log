package cloud.trotter.log.gig.domain.census

/**
 * What the shadow collector keeps of one admitted screen frame (#1630): the frame's key fields, the ids of the
 * candidates that were active on it and each one's outcome. Never the tree, its text or a parsed value.
 *
 * [generationId] is the 16-hex wire form of the live generation that classified the frame. [results] lists every
 * candidate active for the platform; an empty list is a boundary (the frame's generation has none), which closes the
 * open visits of candidates no longer active. [liveWon] is true when a live rule won the frame and false when the frame
 * was live UNKNOWN; a visit folds it into `tied` and `onUnknown`. [resetToken] is [HealthSink.resetToken] as read when
 * the pipeline handed the frame over; the sink drops a record whose token a census reset has since replaced.
 */
data class ShadowFrameRecord(
    val timestampMillis: Long,
    val platformWire: String,
    val platformAppVersion: String?,
    val generationId: String,
    val results: List<ShadowResult>,
    val liveWon: Boolean,
    val resetToken: Long,
)

/** One active candidate's outcome on one frame: its rule id, the 16-hex wire form of its digest, and the outcome. */
data class ShadowResult(val ruleId: String, val digest: String, val outcome: ShadowOutcome)

/** A candidate's outcome on one frame, as the collector needs it. */
sealed interface ShadowOutcome {

    /** Evaluated to the end, and no branch matched. */
    data object NoMatch : ShadowOutcome

    /**
     * A branch matched. [requiredOk] is null when the diagnostic branch's shape declares no required fields, else
     * whether every one read; [maskScan] is the candidate's own `redact` checked on the frame.
     */
    data class Matched(val requiredOk: Boolean?, val maskScan: MaskScanResult) : ShadowOutcome

    /** Budget, expiry or failure: never evidence either way, and it changes no visit state. */
    data object Unevaluated : ShadowOutcome
}

/** The mask scan of a matched frame: [HIT] outweighs [INCOMPLETE]; only [CLEAN] and [HIT] make a visit assessable. */
enum class MaskScanResult { CLEAN, HIT, INCOMPLETE }
