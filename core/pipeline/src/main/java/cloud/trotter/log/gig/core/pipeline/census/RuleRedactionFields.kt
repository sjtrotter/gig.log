package cloud.trotter.log.gig.core.pipeline.census

import cloud.trotter.log.gig.core.pipeline.rules.CompiledRedact
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.model.accessibility.UiNodeTextField
import java.util.IdentityHashMap

/**
 * #1573: the fields of a RAW tree that a matched rule's own `redact` masks, so a matched skeleton withholds them on top
 * of the census filters ([FrameFilter]). The skeleton is still built from the raw tree: a mask never seeds, and
 * building from the masked tree would drop the NAME-id seeds the frame-level rule needs.
 *
 * It applies [CompiledRedact.apply] to the tree and walks the raw and masked trees in parallel (the masker keeps
 * children one for one); a (node, field) is rule-withheld when its masked value differs from the raw one. A redact
 * whose pattern cannot be evaluated masks its whole node, so that node withholds every field (fail closed). Nodes are
 * keyed by identity, never by value, so two equal-looking nodes are told apart.
 */
internal class RuleRedactionFields private constructor(
    private val byNode: IdentityHashMap<UiNode, Set<UiNodeTextField>>,
) {
    /** The fields of [node] (a node of the tree this was computed on) the rule's redact masks; empty for any other. */
    fun of(node: UiNode): Set<UiNodeTextField> = byNode[node].orEmpty()

    /** How many fields the redact masks in the whole tree. */
    val count: Int get() = byNode.values.sumOf { it.size }

    companion object {
        /** No rule redaction: the census filters alone, exactly as for an UNKNOWN frame. */
        val NONE = RuleRedactionFields(IdentityHashMap())

        /**
         * The fields [redact] masks in [tree]; [NONE] when the rule declares no redact. Throws when the masked copy does
         * not mirror the tree node for node, which the caller turns into a refused build (fail closed).
         */
        fun of(tree: UiNode, redact: CompiledRedact?): RuleRedactionFields {
            if (redact == null || redact.isEmpty()) return NONE
            val masked = redact.apply(tree)
            val byNode = IdentityHashMap<UiNode, Set<UiNodeTextField>>()
            fun walk(raw: UiNode, copy: UiNode) {
                check(raw.children.size == copy.children.size) { "masked tree does not mirror the raw tree" }
                val fields = raw.scrubbableStrings().zip(copy.scrubbableStrings())
                    .filter { (rawField, maskedField) -> rawField.second != maskedField.second }
                    .mapTo(LinkedHashSet()) { it.first.first }
                if (fields.isNotEmpty()) byNode[raw] = fields
                raw.children.zip(copy.children).forEach { (r, c) -> walk(r, c) }
            }
            walk(tree, masked)
            return RuleRedactionFields(byNode)
        }
    }
}
