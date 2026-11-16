package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.state.Platform

/**
 * One shadow candidate (#1630): a screen rule marked `shadow: true`, compiled in full under the logical id it will
 * have when live. Nothing evaluates it yet, and nothing ever materializes its effects, targets or capability keys.
 */
class ShadowCandidate internal constructor(
    internal val rule: CompiledRule<UiNode>,
    val platform: Platform,
    /** The full sha256 hex of [RuleIdentifiers.candidateDigest]; the wire carries its first 16 characters. */
    val digest: String,
) {
    val id: String get() = rule.id
}

/**
 * The shadow candidates of the bundled `<platform>_shadow` files, validated against the live generation whose
 * [RuleGeneration.shadow] holds this set and whose id is [generationId]. Kept apart from every live ruleset: it is
 * never in evaluation order, priority, sensitive coverage, bundled protection, capability enumeration or rule counts.
 */
class ShadowSet internal constructor(
    val generationId: String,
    val candidates: List<ShadowCandidate>,
    /** Candidates dropped at load (invalid, duplicate id, or no digest). A local count, never reported. */
    val droppedCandidates: Int,
)
