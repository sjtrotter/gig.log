package cloud.trotter.log.gig.domain.census

/**
 * #1573: the limits on matched skeletons, the `uinode.matched.v1` outline of a screen a rule still recognizes while it
 * is in an open parse-rot incident. Counted per UTC day of the item's own `day`, over every platform.
 */
object MatchedSkeletons {
    /** At most this many a day for one rule. */
    const val PER_RULE_PER_DAY: Int = 1

    /** At most this many a day in all. */
    const val PER_DAY: Int = 10

    /** The first `PrivacyDisclosure.REVISION` whose census disclosure names matched skeletons; an older consent sends none. */
    const val MIN_DISCLOSURE_REVISION: Int = 32
}
