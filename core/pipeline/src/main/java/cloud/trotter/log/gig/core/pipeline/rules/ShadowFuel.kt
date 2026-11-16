package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * One candidate's work budget for one frame (#1630). [charge] admits work before it is done: a charge that would take
 * [used] past [limit] is refused, the meter turns [exhausted] for good, and [FuelExhausted] stops the evaluation where
 * it stands. [used] therefore never exceeds [limit]: a lazily built text cache is priced by a node-by-node walk that
 * stops where the fuel does, and a transform's output is admitted before it is allocated.
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
 * the closure it was given (or, for [ancestor], [findDescendant] and [propertyReader], the plain closure live always
 * built), so a live closure does exactly the work it did before metering existed: no wrapper, no flag read, no
 * thread-local lookup per node or per character. `ShadowFuelTest` pins that a live-compiled rule charges nothing even
 * with a meter installed.
 *
 * A metered closure reads the meter from a thread-local ([metering]), so one compiled candidate can be evaluated
 * on any thread; with no meter installed it runs unmetered.
 *
 * Units (one is about 2.5 ns of JVM time; calibrated in `ShadowEngineBenchmarkTest`): a dispatch of a compiled
 * predicate, expression, validator or navigation is [DISPATCH_UNITS]; a character a string predicate reads is 1; a
 * read of a subtree's text pays [TEXT_NODE_UNITS] per node, 2 per string and its characters, node by node before the
 * lazy cache is read (as if this evaluation built it: nothing tells whether live classification built it first); a
 * cached joined-text scan is a unit per [JOINED_CHARS_PER_UNIT] characters on top; a regex reads each character at
 * [regexWeight]; a transform step reads each input character at [TRANSFORM_UNITS_PER_CHAR] (a `regex` step at its
 * weight) and admits its worst-case output before it runs; a navigation pays per node it walks; a parse result costs
 * its size ([materialization]). The mask scan adds its own node and character charges (`ShadowMaskScan`).
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

    /** One node of a subtree text walk ([chargeTextWalk]), its characters aside. */
    const val TEXT_NODE_UNITS = 2L

    /**
     * RE2J simulates the compiled program, so a match costs up to the program size per input character. A rule
     * regex reads each character at `1 + programSize / REGEX_PROGRAM_PER_UNIT` units (calibrated in
     * `ShadowEngineBenchmarkTest`; recorded in `docs/architecture/02-rule-engine.md`).
     */
    const val REGEX_PROGRAM_PER_UNIT = 16

    private val compiling = ThreadLocal<Boolean?>()
    private val transformRegexWeights = ConcurrentHashMap<String, Long>()
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
            "hasAnyTextMatchesRegex" -> weight().let { w -> { n -> allTextCost(n, w) } }
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

    /** The transform applier a parse expression captures: [TransformRegistry.applyAny], [applyMetered] when metered. */
    fun transform(): (JsonElement, String) -> Any? =
        if (!compilingMetered) TransformRegistry::applyAny else ::applyMetered

    /**
     * [TransformRegistry.applyAny] one step at a time. Each step is charged on its actual input (a dispatch, its
     * characters, a digest for `sha256`) plus its worst-case output ([outputBound]) before it runs, so a chain that
     * grows its value (thirty `replace` steps doubling it) runs out of fuel, not memory. A `regex` step's `then` is a
     * nested spec, metered the same way.
     */
    private fun applyMetered(spec: JsonElement, value: String?): Any? {
        if (value == null) return null
        if (spec is JsonArray) {
            var current: Any? = value
            for (step in spec) current = applyMetered(step, current?.toString())
            return current
        }
        val length = value.length.toLong()
        val regex = (spec as? JsonObject)?.get("regex")?.jsonObject
        val perChar = regex?.let { transformRegexWeight(it.getValue("pattern").jsonPrimitive.content) }
            ?: TRANSFORM_UNITS_PER_CHAR
        val hash = if ((spec as? JsonPrimitive)?.content == "sha256") HASH_UNITS else 0L
        charge(DISPATCH_UNITS + length * perChar + hash + outputBound(spec, length))
        val then = regex?.get("then") ?: return TransformRegistry.applyAny(spec, value)
        val extracted = TransformRegistry.applyAny(JsonObject(mapOf("regex" to JsonObject(regex - "then"))), value)
        return applyMetered(then, extracted as String?)
    }

    /** The most characters one transform step (not a chain) can allocate from a [length]-character input. */
    private fun outputBound(step: JsonElement, length: Long): Long = when (step) {
        is JsonPrimitive -> when (step.content) {
            "sha256" -> 64L
            // Case mapping can widen one character to three ("ß" upper-cases to "SS", "ΐ" to three).
            "lower", "upper", "normalizeCustomerName" -> 3 * length
            "trim", "stripDeadlinePrefix" -> length
            else -> 1L // a parsed number
        }
        is JsonObject -> when (step.keys.single()) {
            // An empty pattern matches at every one of length + 1 positions.
            "replace" -> {
                val replacement = step.getValue("replace").jsonObject["replacement"]?.jsonPrimitive?.content.orEmpty()
                length + (length + 1) * replacement.length
            }
            // The parts, and a list entry for each of up to length + 2 of them.
            "split" -> 2 * length + 2
            else -> length // a substring: the strip, extract and regex steps
        }
        is JsonArray -> error("a chain is metered step by step")
    }

    private fun transformRegexWeight(pattern: String): Long =
        transformRegexWeights.getOrPut(pattern) { regexWeight(pattern) }

    /** A compiled validator, charged one dispatch plus the parsed map's size. */
    fun validator(validate: (Map<String, Any?>) -> ValidateOutcome): (Map<String, Any?>) -> ValidateOutcome =
        if (!compilingMetered) validate else { fields -> charge(DISPATCH_UNITS + fields.size); validate(fields) }

    /** A compiled navigation step, charged one dispatch plus [cost]. */
    fun navigation(nav: (UiNode) -> UiNode?, cost: (UiNode) -> Long): (UiNode) -> UiNode? =
        if (!compilingMetered) nav else { node -> charge(DISPATCH_UNITS + cost(node)); nav(node) }

    /** `ancestor(n)`: a unit per step up, stopping at the root, when metered. */
    fun ancestor(n: Int): (UiNode) -> UiNode? =
        if (!compilingMetered) { node -> node.ancestor(n) } else navigation({ meteredAncestor(it, n) }, NO_COST)

    /** [UiNode.ancestor] charging a unit per step; it stops at the root instead of counting the rest of [n]. */
    fun meteredAncestor(node: UiNode, n: Int): UiNode? {
        var current = node
        repeat(n) {
            charge(1L)
            current = current.parent ?: return null
        }
        return current
    }

    /** `findChild(id)`: each child's id compared, when metered. */
    fun findChild(idSuffix: String): (UiNode) -> UiNode? {
        val live: (UiNode) -> UiNode? = { node -> node.findChildById(idSuffix) }
        return navigation(live) { node -> node.children.size * (1L + idSuffix.length) }
    }

    /** `findDescendant(id)`: a charge per node the search visits, before it visits it, when metered. */
    fun findDescendant(idSuffix: String): (UiNode) -> UiNode? =
        if (!compilingMetered) {
            { node -> node.findDescendantById(idSuffix) }
        } else {
            navigation({ meteredFindDescendant(it, idSuffix) }, NO_COST)
        }

    /** [UiNode.findDescendantById]'s pre-order search, charging each node's id comparison first. */
    private fun meteredFindDescendant(node: UiNode, idSuffix: String): UiNode? {
        charge(1L + idSuffix.length)
        if (node.matchesId(idSuffix)) return node
        for (child in node.children) meteredFindDescendant(child, idSuffix)?.let { return it }
        return null
    }

    /**
     * A compiled property read ([readProperty]). A metered `allText` read walks the node's subtree (built, then joined)
     * before the lazy cache is read.
     */
    fun propertyReader(prop: String): (UiNode) -> String? =
        if (compilingMetered && prop == "allText") {
            { node -> chargeTextWalk(node, 2L); readProperty(node, prop) }
        } else {
            { node -> readProperty(node, prop) }
        }

    // ── Prices ───────────────────────────────────────────────────────────────────────────────────

    private val NO_COST: (UiNode) -> Long = { 0L }

    /** Per-character units of a rule regex: `1 + programSize / REGEX_PROGRAM_PER_UNIT`. */
    fun regexWeight(regex: BoundedRegex): Long = 1L + regex.programSize() / REGEX_PROGRAM_PER_UNIT

    private fun regexWeight(pattern: String): Long = regexWeight(RegexSafety.compileRegex(pattern))

    /**
     * Charges a walk of [node]'s subtree, node by node and before any lazy text cache is read: [TEXT_NODE_UNITS] per
     * node, and [weight] for each character and twice for each string `allText` keeps (a regex sets up a search per
     * string). A walk that runs out stops part way, so a nearly spent candidate never builds a cold tree's cache.
     * Returns the length of the subtree's joined text.
     */
    fun chargeTextWalk(node: UiNode, weight: Long): Long {
        var joined = -1L
        val pending = ArrayDeque<UiNode>().apply { addLast(node) }
        while (pending.isNotEmpty()) {
            val next = pending.removeLast()
            // Raw lengths are charged before the blank checks read them; only non-blank strings join.
            charge(TEXT_NODE_UNITS + ((next.text?.length ?: 0).toLong() + (next.contentDescription?.length ?: 0)) * weight)
            val text = next.text?.takeIf { it.isNotBlank() }?.length ?: -1
            val desc = next.contentDescription?.takeIf { it.isNotBlank() }?.length ?: -1
            val strings = (if (text >= 0) 1 else 0) + (if (desc >= 0) 1 else 0)
            charge(2L * strings * weight)
            joined += maxOf(text, 0).toLong() + maxOf(desc, 0) + strings
            next.children.forEach(pending::addLast)
        }
        return maxOf(joined, 0L)
    }

    /** One read of [node]'s `allText` at [weight] per character: [chargeTextWalk] charges it all, so it returns 0. */
    fun allTextCost(node: UiNode, weight: Long = 1L): Long {
        chargeTextWalk(node, weight)
        return 0L
    }

    /** [scans] substring scans of [tree]'s joined text, plus its join and lowercase, after the walk that builds it. */
    fun joinedScanCost(tree: UiNode, scans: Int): Long =
        chargeTextWalk(tree, 1L) * (scans + 1) / JOINED_CHARS_PER_UNIT

    /** The size of a parse result: a string's characters, a collection's or map's entries and their contents. */
    fun materialization(value: Any?): Long = when (value) {
        null -> 0L
        is String -> value.length.toLong()
        is Collection<*> -> value.size + value.sumOf { materialization(it) }
        is Map<*, *> -> value.size + value.values.sumOf { materialization(it) }
        else -> 1L
    }

    private fun len(s: String?): Long = s?.length?.toLong() ?: 0L

    private fun siblingCount(node: UiNode): Long = node.parent?.children?.size?.toLong() ?: 0L
}
