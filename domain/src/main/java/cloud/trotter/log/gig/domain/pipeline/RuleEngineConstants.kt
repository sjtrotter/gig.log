package cloud.trotter.log.gig.domain.pipeline

/**
 * Version constants for the rule engine (ADR-0003 Layer 3).
 */
object RuleEngineConstants {
    const val VERSION = 1
    const val MAX_SUPPORTED_FORMAT_VERSION = 2

    /**
     * The `format_version` a `<platform>_shadow` rules file declares (#1630). It is above
     * [MAX_SUPPORTED_FORMAT_VERSION], so a reader built before shadow files rejects the file, and only
     * the shadow reader accepts it, for `_shadow` files alone. The matchers build writes the same
     * number; `ShadowFormatVersionPinTest` (`:core:pipeline`) holds the two equal.
     */
    const val SHADOW_FORMAT_VERSION = MAX_SUPPORTED_FORMAT_VERSION + 1

    /**
     * The rules feed's `contract` number (#1568, ADR-0013): the version of the vocabulary the rule
     * compiler accepts. A rules manifest whose contract this app does not support is refused and
     * the last good rules stay. `RulesContractPinTest` (`:core:pipeline`) fails when the
     * vocabulary changes, so a change bumps this number or re-pins it on purpose.
     */
    const val RULES_CONTRACT = 1
}
