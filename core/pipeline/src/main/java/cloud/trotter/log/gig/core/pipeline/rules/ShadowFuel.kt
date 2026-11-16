package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * One candidate's work budget for one frame (#1630). [charge] admits work before it is done: a charge that would take
 * [used] past [limit] is refused, the meter turns [exhausted] for good, and [FuelExhausted] stops the evaluation where
 * it stands. [used] therefore never exceeds [limit]; the only work done past it is the unit whose size the meter read
 * to price it (a lazily built text cache).
 *
 * The flags are sticky so a catch inside compiled rule code (a field that fails null) cannot hide an exhaustion or a
 * regex failure from the candidate engine, which reads them after the evaluation.
 */
internal class FuelMeter(val limit: Long) {
    var used: Long = 0L
        private set

    /** How many charges were attempted, the refused one included: the test evidence that a scan stopped part way. */
    var charges: Int = 0
        private set

    var exhausted: Boolean = false
        private set

    /** A rule regex could not be evaluated during this evaluation ([BoundedRegex.evaluationFailed]). */
    var regexFailed: Boolean = false
        private set

    fun charge(units: Long) {
        charges++
        if (exhausted || units > limit - used) {
            exhausted = true
            throw FuelExhausted()
        }
        used += units
    }

    fun noteRegexFailure() {
        regexFailed = true
    }
}

/** Control flow only: a shadow candidate ran out of fuel. Never thrown on the live path. */
internal class FuelExhausted : RuntimeException("shadow candidate fuel exhausted") {
    override fun fillInStackTrace(): Throwable = this
}

/**
 * Metering for shadow candidates (#1630), chosen at compile time so the live path never pays for it.
 *
 * A candidate compiles inside [compileMetered]. While that scope is open, the predicate, parse-expression, transform,
 * validator and navigation compilers wrap each closure they build with one of the functions below, which charge the
 * thread's installed [FuelMeter] before the closure runs. Outside the scope (every live rule) each function returns
 * the closure it was given, so a live closure is the same object it was before metering existed: no wrapper, no
 * flag read, no thread-local lookup per node or per character. `ShadowFuelTest` pins that a live-compiled rule charges
 * nothing even with a meter installed.
 *
 * A metered closure reads the meter from a thread-local ([metering]), so one compiled candidate can be evaluated
 * on any thread; with no meter installed it runs unmetered.
 *
 * Units (one is about 2.5 ns of JVM time; calibrated in `ShadowEngineBenchmarkTest`): a dispatch of a compiled
 * predicate, expression, validator or navigation is [DISPATCH_UNITS]; a character a string predicate reads is 1; a
 * cached joined-text scan is a unit per [JOINED_CHARS_PER_UNIT] characters, and a cache's construction is charged again
 * as if this evaluation built it (nothing tells whether live classification built it first); a regex reads each
 * character at [regexWeight]; a transform reads each input character at [TRANSFORM_UNITS_PER_CHAR] per step (the
 * `sha256` hash included); a parse result costs its size ([materialization]). The mask scan adds its own node and
 * character charges (`ShadowMaskScan`).
 */
internal object ShadowFuel {

    /** One compiled-closure dispatch: a predicate, expression, validator or navigation call. */
    const val DISPATCH_UNITS = 6L

    /** Units per transform input character per step, the `sha256` digest included. */
    const val TRANSFORM_UNITS_PER_CHAR = 1L

    /**
     * One sha256 digest (a `sha256` transform step or a hashed mask), whatever its input: the census-contract owner
     * formats each of the 32 digest bytes with `String.format`, a fixed cost that dwarfs the hashing itself.
     */
    const val HASH_UNITS = 8_000L

    /** Characters of a cached joined-text scan (one `String.contains`) per unit. */
    const val JOINED_CHARS_PER_UNIT = 4L

    /**
     * RE2J simulates the compiled program, so a match costs up to the program size per input character. A rule
     * regex reads each character at `1 + programSize / REGEX_PROGRAM_PER_UNIT` units (calibrated in
     * `ShadowEngineBenchmarkTest`; recorded in `docs/architecture/02-rule-engine.md`).
     */
    const val REGEX_PROGRAM_PER_UNIT = 16

    private val compiling = ThreadLocal<Boolean?>()
    private val active = ThreadLocal<FuelMeter?>()

    /** True while a shadow candidate compiles on this thread. Read at compile time only. */
    val compilingMetered: Boolean get() = compiling.get() == true

    /** Compile [block]'s rules metered. */
    fun <T> compileMetered(block: () -> T): T {
        val previous = compiling.get()
        compiling.set(true)
        try {
            return block()
        } finally {
            if (previous == null) compiling.remove() else compiling.set(previous)
        }
    }

    /** Run [block] with [meter] installed on this thread. */
    fun <T> metering(meter: FuelMeter, block: () -> T): T {
        val previous = active.get()
        active.set(meter)
        try {
            return block()
        } finally {
            if (previous == null) active.remove() else active.set(previous)
        }
    }

    /** Charge the installed meter, if any. Called only from metered closures and the mask scan. */
    fun charge(units: Long) {
        active.get()?.charge(units)
    }

    /** Called from [BoundedRegex]'s cold failure path: marks the installed meter, if any. */
    fun noteRegexFailure() {
        active.get()?.noteRegexFailure()
    }

    // ── Compile-time wrappers: identity unless compiling a candidate ─────────────────────────────

    /** [pred], charged one dispatch plus [cost] per call when compiled metered. */
    fun predicate(pred: (UiNode) -> Boolean, cost: (UiNode) -> Long): (UiNode) -> Boolean =
        if (!compilingMetered) pred else { node -> charge(DISPATCH_UNITS + cost(node)); pred(node) }

    /** A compiled tree predicate ([PredicateCompiler.compileTreePred]) for [key], priced by what it reads. */
    fun treePredicate(key: String, value: JsonElement, pred: (UiNode) -> Boolean): (UiNode) -> Boolean {
        if (!compilingMetered) return pred
        val cost: (UiNode) -> Long = when (key) {
            "allTextContains" -> { tree -> joinedScanCost(tree, 1) }
            "allTextContainsAll", "allTextContainsAny" -> {
                val scans = (value as? JsonArray)?.size ?: 1
                ;{ tree -> joinedScanCost(tree, scans) }
            }
            // `exists`/`notExists` traverse through a metered node predicate; the combinators through metered children.
            else -> NO_COST
        }
        return predicate(pred, cost)
    }

    /** A compiled node predicate ([PredicateCompiler.compileNodePred]) for [key], priced by what it reads. */
    fun nodePredicate(key: String, value: JsonElement, pred: (UiNode) -> Boolean): (UiNode) -> Boolean {
        if (!compilingMetered) return pred
        return predicate(pred, nodeCost(key, value))
    }

    private fun nodeCost(key: String, value: JsonElement): (UiNode) -> Long {
        fun weight(): Long = regexWeight((value as JsonPrimitive).content)
        return when (key) {
            "hasIdSuffix", "hasIdExact", "hasIdContaining", "hasNoId" -> { n -> len(n.viewIdResourceName) }
            "hasText", "hasTextCaseSensitive", "hasTextContaining", "hasTextStartsWith" -> { n -> len(n.text) }
            "hasTextMatchesRegex" -> weight().let { w -> { n -> len(n.text) * w } }
            "hasPrecedingSiblingText" -> {
                val literal = (value as JsonPrimitive).content.length.toLong()
                ;{ n -> siblingCount(n) + literal }
            }
            "hasFollowingSiblingTextMatchesRegex" -> weight().let { w ->
                { n -> siblingCount(n) + len(n.followingSiblings().firstOrNull()?.text) * w }
            }
            "hasAnyText", "hasAnyTextStartsWith" -> { n -> allTextCost(n) }
            "hasAnyTextMatchesRegex" -> weight().let { w -> { n -> allTextCost(n) * w } }
            "hasDesc", "hasDescContaining" -> { n -> len(n.contentDescription) }
            "hasStateDescription", "hasStateDescriptionContaining" -> { n -> len(n.stateDescription) }
            "hasClassName", "hasClassNameEndsWith" -> { n -> len(n.className) }
            "hasPaneTitle", "hasPaneTitleContaining" -> { n -> len(n.paneTitle) }
            "hasRoleDescription" -> { n -> len(n.roleDescription) }
            "hasClickActionLabel", "hasClickActionLabelContaining" -> { n -> len(n.clickActionLabel) }
            "hasHintText", "hasHintTextContaining" -> { n -> len(n.hintText) }
            "isClickable", "isEnabled", "isChecked", "hasChildren", "isLeaf", "isVisibleToUser", "isSelected",
            "isCheckable", "isHeading", "hasClickAction", "all", "any", "not",
            -> NO_COST
            // A predicate added without a price here is charged every string the node carries: over, never under.
            else -> { n -> n.scrubbableStrings().sumOf { len(it.second) } + len(n.viewIdResourceName) + len(n.className) }
        }
    }

    /** A compiled parse expression, charged one dispatch plus [preCost] before and its result's size after. */
    fun expression(
        eval: (UiNode, Bindings) -> Any?,
        preCost: ((UiNode, Bindings) -> Long)? = null,
    ): (UiNode, Bindings) -> Any? {
        if (!compilingMetered) return eval
        return { tree, bindings ->
            charge(DISPATCH_UNITS + (preCost?.invoke(tree, bindings) ?: 0))
            eval(tree, bindings).also { charge(materialization(it)) }
        }
    }

    /** The transform applier a parse expression captures: [TransformRegistry.applyAny], charged when metered. */
    fun transform(): (JsonElement, String) -> Any? =
        if (!compilingMetered) TransformRegistry::applyAny else { spec, value ->
            val steps = (spec as? JsonArray)?.size ?: 1
            charge(DISPATCH_UNITS + value.length * TRANSFORM_UNITS_PER_CHAR * steps + hashSteps(spec) * HASH_UNITS)
            TransformRegistry.applyAny(spec, value)
        }

    /** A compiled validator, charged one dispatch plus the parsed map's size. */
    fun validator(validate: (Map<String, Any?>) -> ValidateOutcome): (Map<String, Any?>) -> ValidateOutcome =
        if (!compilingMetered) validate else { fields -> charge(DISPATCH_UNITS + fields.size); validate(fields) }

    /** A compiled navigation step, charged one dispatch plus [cost]. */
    fun navigation(nav: (UiNode) -> UiNode?, cost: (UiNode) -> Long): (UiNode) -> UiNode? =
        if (!compilingMetered) nav else { node -> charge(DISPATCH_UNITS + cost(node)); nav(node) }

    // ── Prices ───────────────────────────────────────────────────────────────────────────────────

    private val NO_COST: (UiNode) -> Long = { 0L }

    /** Per-character units of a rule regex: `1 + programSize / REGEX_PROGRAM_PER_UNIT`. */
    fun regexWeight(regex: BoundedRegex): Long = 1L + regex.programSize() / REGEX_PROGRAM_PER_UNIT

    private fun regexWeight(pattern: String): Long = regexWeight(RegexSafety.compileRegex(pattern))

    /** One read of [node]'s `allText`: its strings and characters, plus its construction (one entry per string). */
    fun allTextCost(node: UiNode): Long {
        val texts = node.allText
        return 2L * texts.size + texts.sumOf { it.length.toLong() }
    }

    /** [scans] substring scans of [tree]'s joined text, plus the joined text's construction. */
    fun joinedScanCost(tree: UiNode, scans: Int): Long =
        tree.allTextLowerJoined.length.toLong() * (scans + 1) / JOINED_CHARS_PER_UNIT

    /** The size of a parse result: a string's characters, a collection's or map's entries and their contents. */
    fun materialization(value: Any?): Long = when (value) {
        null -> 0L
        is String -> value.length.toLong()
        is Collection<*> -> value.size + value.sumOf { materialization(it) }
        is Map<*, *> -> value.size + value.values.sumOf { materialization(it) }
        else -> 1L
    }

    private fun hashSteps(spec: JsonElement): Int = when (spec) {
        is JsonPrimitive -> if (spec.content == "sha256") 1 else 0
        is JsonArray -> spec.count { (it as? JsonPrimitive)?.content == "sha256" }
        else -> 0
    }

    private fun len(s: String?): Long = s?.length?.toLong() ?: 0L

    private fun siblingCount(node: UiNode): Long = node.parent?.children?.size?.toLong() ?: 0L
}
