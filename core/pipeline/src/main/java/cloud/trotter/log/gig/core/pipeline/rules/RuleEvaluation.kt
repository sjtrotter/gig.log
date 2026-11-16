package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.pipeline.ParseShortfall

/**
 * One branch that predicate-matched (#1630): its bindings resolved, no reject fired and its require predicate held. It
 * then parsed into [rawFields] (the parser output before any validator acted) and its validators ran: [skipped] when a
 * `Skip` validator discarded it, [dropParsed] when a `DropParsed` one emptied its fields.
 */
internal class BranchEvaluation<TInput>(
    val branch: CompiledBranch<TInput>,
    val bindings: Bindings,
    val rawFields: Map<String, Any?>,
    val skipped: Boolean,
    val dropParsed: Boolean,
)

/**
 * The per-rule evaluation (phases 1-5: bindings, rejects, require, parse, validators), shared by live
 * [Ruleset.matchFirst] and the shadow candidate path (#1630) so the two can never disagree about what a rule matched.
 * Each caller keeps its own answer for what the evaluation cannot settle: a parse exception, a rule regex that could
 * not be evaluated, and what to do with each predicate-matched branch.
 */
internal object RuleEvaluation {

    /**
     * Evaluate [rule] against [input] and return its first predicate-matched branch that no `Skip` validator
     * discarded, or null.
     *
     * [onPredicateMatched] sees every predicate-matched branch in order, skipped ones included, before the skip is
     * acted on. [onParseError] decides a thrown parse: live answers with an empty map, the candidate path rethrows.
     * A [RegexEvaluationFailed] from a predicate propagates; each caller decides its own safe answer.
     */
    inline fun <TInput> firstSurvivingBranch(
        rule: CompiledRule<TInput>,
        input: TInput,
        screenTarget: String?,
        onParseError: (Exception) -> Map<String, Any?>,
        onPredicateMatched: (BranchEvaluation<TInput>) -> Unit,
    ): BranchEvaluation<TInput>? {
        // Phase 1: Resolve rule-level bindings (screen rules only)
        val ruleBindings = resolveBindings(input, rule.bindings) ?: return null

        for (branch in rule.branches) {
            // Screen constraint filter (click rules)
            // #1104: `screenIs` is a SET of accepted targets (one entry is the common case).
            // A null constraint is unconstrained; a null screenTarget satisfies nothing.
            if (branch.screenIs != null && screenTarget !in branch.screenIs) continue

            // Phase 1b: Resolve branch-level bindings
            val branchBindings = resolveBindings(input, branch.bindings) ?: continue
            val allBindings = ruleBindings + branchBindings

            // Phase 2: Reject checks
            if (branch.rejectChecks.any { it(input) }) continue

            // Phase 3: Require check
            if (branch.predicate != null && !branch.predicate.invoke(input)) continue

            // Phase 4: Parse
            val rawFields = try {
                branch.parser(input, allBindings)
            } catch (e: Exception) {
                onParseError(e)
            }

            // Phase 5: Validate
            var branchSkip = false
            var dropParsed = false
            for (validator in branch.validators) {
                when (validator(rawFields)) {
                    is ValidateOutcome.Pass -> { /* continue */ }
                    is ValidateOutcome.Skip -> {
                        branchSkip = true
                        break
                    }
                    is ValidateOutcome.DropParsed -> {
                        dropParsed = true
                        break
                    }
                }
            }

            val evaluation = BranchEvaluation(branch, allBindings, rawFields, branchSkip, dropParsed)
            onPredicateMatched(evaluation)
            if (branchSkip) continue
            return evaluation
        }
        return null
    }

    /**
     * Resolve bindings against [input]. Returns null if a mandatory binding
     * can't be resolved (indicating the rule/branch should be skipped).
     *
     * Only screen rules have bindings; for other rule types, this returns
     * an empty map immediately.
     */
    fun <TInput> resolveBindings(input: TInput, bindings: List<Binding>): Bindings? {
        if (bindings.isEmpty()) return emptyMap()
        // Bindings require a UiNode tree — if TInput isn't UiNode, skip
        val tree = input as? UiNode ?: return emptyMap()
        val result = mutableMapOf<String, UiNode?>()
        for (binding in bindings) {
            val found = binding.find(tree)
            if (found == null && !binding.optional) {
                return null // mandatory binding failed
            }
            result[binding.name] = found
        }
        return result
    }

    /**
     * The shape-required fields of [branch] that [rawFields] left null (#1036 partial trigger), measured on the raw
     * parser output, before any validator acted. Checked for null only: every shape-required field today is a
     * scalar, and an emptiness rule for one would belong to the shape contract, not here.
     */
    fun nullRequiredFields(branch: CompiledBranch<*>, rawFields: Map<String, Any?>): List<String> =
        branch.requiredParseFields.filter { rawFields[it] == null }

    /**
     * The #1036 shortfall for one matched branch, or null when its parse is healthy.
     *
     * Two independent triggers, either or both (see [ParseShortfall]):
     *  - **total** — the branch declares at least one evidence field and EVERY one of them came
     *    back unresolved, judged per field by its compiled [ParseFieldKind] (an `each`/`findAll`
     *    field is unresolved when its list is EMPTY, which is what a plain null check missed).
     *  - **partial** — a field the parse's shape contract names as load-bearing is null
     *    ([nullRequiredFields]).
     */
    fun shortfallOf(
        ruleId: String,
        branch: CompiledBranch<*>,
        rawFields: Map<String, Any?>,
        bindings: Bindings,
        branchSkipped: Boolean,
    ): ParseShortfall? {
        val evidence = branch.parseEvidenceFields
        val allNull = evidence.isNotEmpty() &&
            evidence.all { (name, kind) -> kind.isUnresolved(rawFields[name]) }
        val nullRequired = nullRequiredFields(branch, rawFields)
        // Only an OPTIONAL bind can be present-and-null here: a mandatory miss already skipped
        // the rule in resolveBindings (#1093).
        val unresolvedBinds = bindings.filterValues { it == null }.keys.sorted()
        if (!allNull && nullRequired.isEmpty() && unresolvedBinds.isEmpty()) return null
        return ParseShortfall(
            ruleId = ruleId,
            allNullFieldCount = if (allNull) evidence.size else 0,
            nullRequiredFields = nullRequired,
            unresolvedOptionalBindings = unresolvedBinds,
            shape = branch.shape,
            branchSkipped = branchSkipped,
        )
    }
}
