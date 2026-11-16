package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.core.pipeline.CustomerTextMarkers
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.privacy.MaskTokens
import cloud.trotter.log.gig.domain.privacy.PiiShapes

/** The mask scan's verdict on one frame a shadow candidate matched (#1630). */
enum class MaskScan {
    /** Every check finished on every value and none found raw customer text. */
    CLEAN,

    /** A check found raw customer text the candidate's `redact` left in place. Outweighs [INCOMPLETE]. */
    HIT,

    /** No check found anything, but at least one did not finish: a value over its bound, or the fuel ran out. */
    INCOMPLETE,
}

/**
 * The shadow candidate's mask scan (#1630): would this candidate's own `redact` have left customer text in the
 * envelope of a frame it matched?
 *
 * It applies the candidate's [CompiledRedact] to the frame tree (no `CaptureWriter`, no file, no capture bus), then
 * reads every [UiNode.scrubbableStrings] value of the redacted copy with the rules-independent backstops, each in its
 * own matching mode, on whole values:
 *  - **customer lead-ins** ([CustomerTextMarkers.MARKERS], a case-insensitive prefix): a value that opens with one is
 *    a hit unless everything after the lead-in is masked;
 *  - **customer-PII view ids** ([CustomerTextMarkers.ID_MARKERS]): a value of such a node is a hit unless it ends with
 *    a complete mask ([MaskTokens.endsWithMask], the runtime id scrub's own test);
 *  - **customer-PII shapes** ([PiiShapes.VALUE_SHAPES] in each shape's mode, and the anchored name shape
 *    [PiiShapes.FIRST_LAST_INITIAL] the redact side uses): bounded at [PII_SHAPE_MAX_CHARS]; a longer value is
 *    [MaskScan.INCOMPLETE] for this check, never sliced.
 *
 * Mask-aware: only a complete mask token ([MaskTokens.maskLengthAt]) counts as masked, so a bare `[redacted` is read
 * as text. A value is split at its complete masks and each remaining segment is read whole as its own value, so a
 * mask edge is a word boundary and text before or after a mask stays inspectable.
 *
 * This is a **sampled diagnostic, never proof of redaction**: it reads the frames a candidate happened to match on
 * one phone, with backstops that miss what they were never written to see (a name with no lead-in, no id and no
 * shape). A [MaskScan.CLEAN] says these checks found nothing on this frame, not that the rule redacts correctly.
 *
 * Every step charges the installed [FuelMeter]: a dispatch per node visited while masking plus its characters and a
 * digest per value it will hash, a dispatch per node scanned, the value's characters for the split and the prefix checks, and
 * [PII_SHAPE_UNITS_PER_CHAR] per character a shape pass reads. Running out mid-scan is [MaskScan.INCOMPLETE].
 */
internal object ShadowMaskScan {

    /**
     * The longest segment the shape check reads. The shapes are app-authored JDK/ICU patterns with bounded
     * repetition, but `FULL_ADDRESS`'s `[^,]+` makes a pass quadratic in the worst case, so a longer value is
     * [MaskScan.INCOMPLETE] for the check instead of a scan the budget could not price.
     */
    const val PII_SHAPE_MAX_CHARS = 256

    /** Units per character of one segment's shape pass (eleven patterns), calibrated in `ShadowEngineBenchmarkTest`. */
    const val PII_SHAPE_UNITS_PER_CHAR = 80L

    /** Scan [tree] masked by [redact]. The caller installs the meter ([ShadowFuel.metering]). */
    fun scan(tree: UiNode, redact: CompiledRedact): MaskScan = try {
        chargeMasking(tree, redact)
        val masked = if (redact.isEmpty()) tree else redact.apply(tree)
        val incomplete = BooleanArray(1)
        if (anyHit(masked, incomplete)) MaskScan.HIT else if (incomplete[0]) MaskScan.INCOMPLETE else MaskScan.CLEAN
    } catch (_: FuelExhausted) {
        MaskScan.INCOMPLETE
    }

    /** One value of a node; [idMarked] when the node's view id is a customer-PII id ([CustomerTextMarkers.ID_MARKERS]). */
    fun scanValue(value: String, idMarked: Boolean): MaskScan {
        ShadowFuel.charge(1L + value.length)
        val segments = segmentsOf(value)
        if (leadInHit(segments)) return MaskScan.HIT
        if (idMarked && value.isNotBlank() && !MaskTokens.endsWithMask(value.trimEnd())) return MaskScan.HIT
        var incomplete = false
        for (segment in segments) {
            val text = segment.trim()
            if (text.isEmpty()) continue
            if (text.length > PII_SHAPE_MAX_CHARS) {
                incomplete = true
                continue
            }
            ShadowFuel.charge(text.length * PII_SHAPE_UNITS_PER_CHAR)
            if (PiiShapes.FIRST_LAST_INITIAL.matches(text) || PiiShapes.VALUE_SHAPES.any { it.hits(text) }) {
                return MaskScan.HIT
            }
        }
        return if (incomplete) MaskScan.INCOMPLETE else MaskScan.CLEAN
    }

    /** [value]'s text between its complete mask tokens, in order; the first segment is the text before any mask. */
    fun segmentsOf(value: String): List<String> {
        val segments = mutableListOf<String>()
        var segmentStart = 0
        var i = value.indexOf(MaskTokens.REDACTED_PREFIX)
        while (i >= 0) {
            val length = MaskTokens.maskLengthAt(value, i)
            if (length > 0) {
                segments += value.substring(segmentStart, i)
                segmentStart = i + length
                i = value.indexOf(MaskTokens.REDACTED_PREFIX, segmentStart)
            } else {
                i = value.indexOf(MaskTokens.REDACTED_PREFIX, i + 1)
            }
        }
        segments += value.substring(segmentStart)
        return segments
    }

    /**
     * A segment opens with a customer lead-in and something after it is not masked. The first segment is matched as
     * the value starts (the backstop's own prefix test); a later one after the whitespace a mask edge leaves.
     */
    private fun leadInHit(segments: List<String>): Boolean {
        for ((index, segment) in segments.withIndex()) {
            val text = if (index == 0) segment else segment.trimStart()
            val marker = CustomerTextMarkers.MARKERS.firstOrNull { text.startsWith(it, ignoreCase = true) } ?: continue
            if (text.substring(marker.length).isNotBlank()) return true
            if ((index + 1 until segments.size).any { segments[it].isNotBlank() }) return true
        }
        return false
    }

    private fun anyHit(node: UiNode, incomplete: BooleanArray): Boolean {
        ShadowFuel.charge(ShadowFuel.DISPATCH_UNITS)
        val idMarked = CustomerTextMarkers.idMarkerSuffix(node.viewIdResourceName) != null
        for ((_, value) in node.scrubbableStrings()) {
            if (value.isNullOrEmpty()) continue
            when (scanValue(value, idMarked)) {
                MaskScan.HIT -> return true
                MaskScan.INCOMPLETE -> incomplete[0] = true
                MaskScan.CLEAN -> Unit
            }
        }
        return node.children.any { anyHit(it, incomplete) }
    }

    /**
     * The masking walk's price, paid before [CompiledRedact.apply] runs: a dispatch per node plus its characters, and a
     * digest ([ShadowFuel.HASH_UNITS]) per value of a node an entry will mask with a hash. Which node that is comes from
     * evaluating the entries' (metered) `find`s here once, first match wins as in `maskNode`; this decides only the
     * price, never the mask.
     */
    private fun chargeMasking(node: UiNode, redact: CompiledRedact) {
        val values = node.scrubbableStrings()
        ShadowFuel.charge(ShadowFuel.DISPATCH_UNITS + values.sumOf { it.second?.length?.toLong() ?: 0L })
        if (!redact.isEmpty()) {
            val entry = try {
                redact.entries.firstOrNull { it.find(node) }
            } catch (_: RegexEvaluationFailed) {
                null // maskNode writes the plain mask: no digest
            }
            if (entry != null && !entry.plainMask) ShadowFuel.charge(ShadowFuel.HASH_UNITS * values.count { it.second != null })
        }
        node.children.forEach { chargeMasking(it, redact) }
    }
}
