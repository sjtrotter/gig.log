package cloud.trotter.log.gig.core.pipeline.census

import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.SkeletonVersion
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.log.gig.core.pipeline.census.SkeletonBuilder.Outcome
import cloud.trotter.log.gig.core.pipeline.census.SkeletonBuilder.Refusal
import cloud.trotter.log.gig.core.pipeline.rules.CompiledRedact
import cloud.trotter.log.gig.core.pipeline.rules.CompiledRedactEntry
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * #1573 A2: a matched skeleton is the v2 build of the same raw tree through the same filters, with the matched rule's
 * own `redact` withheld on top: an id-less customer name the filters would hash is withheld, and so is the same value
 * anywhere else in the frame; the NAME-id seeds are unchanged, so is the fingerprint.
 */
class MatchedSkeletonBuilderTest : SkeletonBuilderTestBase() {

    private val rule = "doordash.screen.delivery_summary_expanded"
    private val name = "Tobias Wren"

    /** The rule's redact selects only the node of this class: an id-less customer row, no lead-in, no PII id. */
    private val redactedClass = "android.widget.CheckedTextView"
    private val redact = CompiledRedact(listOf(CompiledRedactEntry(find = { it.className == redactedClass })))

    private fun frame(redacted: String = name) = UiNode(
        className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row",
        children = listOf(
            UiNode(className = "android.widget.TextView", viewIdResourceName = "com.x:id/customer_name", text = "Adam"),
            UiNode(className = "android.widget.Button", viewIdResourceName = "com.x:id/chipAdam", text = "Continue"),
            UiNode(className = redactedClass, text = redacted),
            UiNode(className = "android.widget.LinearLayout", children = listOf(UiNode(className = "android.widget.TextView", text = name))),
        ),
    ).restoreParents()

    private fun unknown(tree: UiNode) = (SkeletonBuilder.outcome(tree, null, meta, platform, day, SkeletonVersion.V2) as Outcome.Built).skeleton
    private fun matched(tree: UiNode, redact: CompiledRedact? = this.redact) =
        SkeletonBuilder.matchedOutcome(tree, null, meta, platform, day, rule, redact)

    @Test fun `the rule's redact withholds a name the filters alone would hash, wherever the frame repeats it`() {
        val tree = frame()
        val plain = unknown(tree)
        val withRule = (matched(tree) as Outcome.Built).skeleton

        val plainName = plain.root.children[2].text.getValue("text")
        assertEquals("the census filters alone hash the name", "words:2", plainName.kind)
        assertNotNull(plainName.h)
        assertEquals("words:2", plain.root.children[3].children[0].text.getValue("text").kind)

        assertEquals(TextSlot.WITHHELD, withRule.root.children[2].text.getValue("text"))
        assertEquals("the same value elsewhere is withheld too", TextSlot.WITHHELD, withRule.root.children[3].children[0].text.getValue("text"))
    }

    @Test fun `the NAME-id seed, the fingerprint and the rest of the frame are unchanged`() {
        val tree = frame()
        val plain = unknown(tree)
        val withRule = (matched(tree) as Outcome.Built).skeleton
        assertEquals("chipAdam is withheld from the NAME id's run in both builds", "~", plain.root.children[1].id)
        assertEquals("~", withRule.root.children[1].id)
        assertEquals(plain.fingerprint, withRule.fingerprint)
        assertEquals(plain.root.children[1].text, withRule.root.children[1].text)
        assertEquals(TextSlot.WITHHELD, withRule.root.children[0].text.getValue("text"))
    }

    @Test fun `the item is a matched v2 body tagged with its rule`() {
        val out = matched(frame()) as Outcome.Built
        val item = out.skeleton
        assertEquals(SkeletonSchema.SCHEMA_ID_MATCHED, item.schemaId)
        assertEquals(rule, item.ruleId)
        assertEquals(SkeletonVersion.V2, item.version)
        assertEquals(SkeletonSchema.serialize(item), out.json)
        assertEquals(null, out.reviewWords)
        assertEquals(item, SkeletonSchema.deserialize(out.json))
    }

    @Test fun `no redact leaves the census filters as the floor`() {
        val tree = frame()
        val plain = unknown(tree)
        val bare = (matched(tree, redact = null) as Outcome.Built).skeleton
        assertEquals(plain.root, bare.root)
    }

    @Test fun `a sensitive marker in the raw tree refuses the frame even where the redact masks it`() {
        assertEquals(Outcome.Refused(Refusal.SENSITIVE_FRAME), matched(frame(redacted = "123-45-6789")))
    }

    @Test fun `a rule of another platform is an invalid envelope`() {
        val out = SkeletonBuilder.matchedOutcome(frame(), null, meta, platform, day, "uber.screen.offer", redact)
        assertEquals(Outcome.Refused(Refusal.INVALID_ENVELOPE), out)
    }
}
