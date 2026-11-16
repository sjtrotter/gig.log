package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.state.Platform

/** What one shadow candidate made of one frame (#1630). */
sealed interface CandidateOutcome {

    /** Evaluated to the end, and no branch predicate-matched: a definite non-match. */
    data object NoMatch : CandidateOutcome

    /**
     * A branch predicate-matched. [shape] is the diagnostic branch's parse shape (null when it declares none);
     * [requiredOk] is null when that shape declares no required fields, else whether every one read (measured on the
     * raw parse, before validation); [maskScan] is the candidate's own `redact` checked on this frame.
     */
    data class Matched(val shape: String?, val requiredOk: Boolean?, val maskScan: MaskScan) : CandidateOutcome

    /** Not evaluated to an answer. Never evidence either way. */
    data class Unevaluated(val reason: Reason) : CandidateOutcome

    enum class Reason {
        /** The candidate's or the frame's fuel ran out. */
        BUDGET,

        /** An exception (a parse exception included), a rule regex that could not be evaluated, or a stack overflow. */
        FAILED,
    }
}

/** One candidate's [outcome] on one frame, and the fuel it charged. */
class CandidateEvaluation(val candidate: ShadowCandidate, val outcome: CandidateOutcome, val fuelUsed: Long)

/**
 * Evaluates one shadow candidate against one frame (#1630) through the per-rule evaluation live recognition uses
 * ([RuleEvaluation.firstSurvivingBranch]), under a [FuelMeter].
 *
 * The **diagnostic branch** is the first predicate-matched branch that no `Skip` validator discarded (evaluation stops
 * there, as live does); when every predicate-matched branch skips, it is the first of them. Its own raw parse alone
 * decides `requiredOk`, so a healthy branch never inherits a skipped sibling's shortfall.
 *
 * It never builds a [RuleMatchResult]: no effects, transition overrides, targets, node references or capability keys
 * are materialized, and no live sink is called (the `onParseShortfall` sink, `ParseRotTracker`, `RecognitionHealth`,
 * the incident book). A failure is an [CandidateOutcome.Unevaluated] the caller counts locally; nothing is logged.
 */
internal object ShadowCandidateEngine {

    /** [candidate] on [tree], charging [meter]. The caller holds the frame's transform clock scope. */
    fun evaluate(candidate: ShadowCandidate, tree: UiNode, meter: FuelMeter): CandidateOutcome =
        evaluateRule(candidate.rule, tree, meter)

    /** [rule] (compiled metered, [ShadowFuel.compileMetered]) on [tree], charging [meter]. */
    fun evaluateRule(rule: CompiledRule<UiNode>, tree: UiNode, meter: FuelMeter): CandidateOutcome =
        ShadowFuel.metering(meter) { evaluateMetered(rule, tree, meter) }

    private fun evaluateMetered(rule: CompiledRule<UiNode>, tree: UiNode, meter: FuelMeter): CandidateOutcome {
        var firstMatched: BranchEvaluation<UiNode>? = null
        val diagnostic = try {
            RuleEvaluation.firstSurvivingBranch(
                rule, tree, screenTarget = null,
                onParseError = { e -> throw e },
                onPredicateMatched = { if (firstMatched == null) firstMatched = it },
            ) ?: firstMatched
        } catch (_: StackOverflowError) {
            return unevaluated(meter)
        } catch (_: Exception) {
            return unevaluated(meter)
        }
        // A catch inside compiled parse code (a field that fails null) can swallow either; the meter keeps both.
        if (meter.exhausted || meter.regexFailed) return unevaluated(meter)
        if (diagnostic == null) return CandidateOutcome.NoMatch

        val requiredOk = if (diagnostic.branch.requiredParseFields.isEmpty()) {
            null
        } else {
            RuleEvaluation.nullRequiredFields(diagnostic.branch, diagnostic.rawFields).isEmpty()
        }
        val scan = try {
            ShadowMaskScan.scan(tree, rule.redact)
        } catch (_: StackOverflowError) {
            return failed
        } catch (_: Exception) {
            return failed
        }
        val maskScan = if (meter.exhausted && scan != MaskScan.HIT) MaskScan.INCOMPLETE else scan
        return CandidateOutcome.Matched(diagnostic.branch.shape, requiredOk, maskScan)
    }

    private val failed = CandidateOutcome.Unevaluated(CandidateOutcome.Reason.FAILED)
    private val budget = CandidateOutcome.Unevaluated(CandidateOutcome.Reason.BUDGET)

    private fun unevaluated(meter: FuelMeter): CandidateOutcome.Unevaluated = if (meter.exhausted) budget else failed
}

/**
 * Runs a platform's shadow candidates over one frame under the work budget (#1630): [perFrameFuel] shared by every
 * candidate of the frame, at most [perCandidateFuel] for any one of them. A candidate starts with whatever the frame
 * has left, up to its own cap, so a frame that runs dry ends its later candidates [CandidateOutcome.Unevaluated]
 * with [CandidateOutcome.Reason.BUDGET], never [CandidateOutcome.NoMatch].
 *
 * The order rotates per platform, frame to frame: when the frame's fuel starved a candidate (it ran out on less than
 * its own cap), the first one starved opens the next frame; otherwise the start moves one place. A costly candidate
 * early in the list cannot starve the rest every frame.
 *
 * Not thread-safe: the rotation is per-instance state, so one runner serves one pipeline thread.
 */
class ShadowFrameRunner(
    private val perFrameFuel: Long = PER_FRAME_FUEL,
    private val perCandidateFuel: Long = PER_CANDIDATE_FUEL,
) {
    private val nextStart = mutableMapOf<Platform, Int>()

    /**
     * Evaluate [set]'s candidates for [platform] on [tree], with time transforms on [clock] (the frame's classification
     * clock). [set] is the shadow set of the generation that classified the frame. Results are in candidate order.
     */
    fun evaluate(
        set: ShadowSet,
        platform: Platform,
        tree: UiNode,
        clock: TransformRegistry.TransformClock,
    ): List<CandidateEvaluation> {
        val candidates = set.candidatesFor(platform)
        if (candidates.isEmpty()) return emptyList()
        val count = candidates.size
        val first = (nextStart[platform] ?: 0) % count
        val results = arrayOfNulls<CandidateEvaluation>(count)
        var remaining = perFrameFuel
        var firstStarved = -1
        TransformRegistry.withClock(clock) {
            for (step in 0 until count) {
                val index = (first + step) % count
                val limit = minOf(perCandidateFuel, remaining)
                val meter = FuelMeter(limit)
                val outcome = if (limit > 0) {
                    ShadowCandidateEngine.evaluate(candidates[index], tree, meter)
                } else {
                    CandidateOutcome.Unevaluated(CandidateOutcome.Reason.BUDGET)
                }
                remaining -= meter.used
                val starved = limit <= 0 || (meter.exhausted && limit < perCandidateFuel)
                if (firstStarved < 0 && starved) firstStarved = index
                results[index] = CandidateEvaluation(candidates[index], outcome, meter.used)
            }
        }
        nextStart[platform] = if (firstStarved >= 0) firstStarved else (first + 1) % count
        return results.map { requireNotNull(it) }
    }

    companion object {
        /** See `docs/architecture/02-rule-engine.md` § Shadow rules for the benchmark these come from. */
        const val PER_CANDIDATE_FUEL = 300_000L
        const val PER_FRAME_FUEL = 600_000L
    }
}
