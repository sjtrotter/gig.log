package cloud.trotter.log.gig.domain.census

/**
 * #1634: the limits on click evidence, the `uinode.clicked.v1` outline of the window an unrecognized tap landed in with
 * the tapped button named by its position. Counted per UTC observation day (ADR-0011 §19).
 */
object ClickEvidence {
    /** At most this many candidates are kept for one day: the smallest selection keys, whatever the tap order. */
    const val CANDIDATES_PER_DAY: Int = 64

    /** At most this many of a day's candidates are sent, the smallest selection keys, once the day has ended. */
    const val SENT_PER_DAY: Int = 10

    /** Candidates, closed days and frozen batches older than this many days before today (UTC) are deleted. */
    const val KEPT_DAYS: Long = 7

    /** The first `PrivacyDisclosure.REVISION` whose census disclosure names taps; an older consent sends none. */
    const val MIN_DISCLOSURE_REVISION: Int = 33
}
