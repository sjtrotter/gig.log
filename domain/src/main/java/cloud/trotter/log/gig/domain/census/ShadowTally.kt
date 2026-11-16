package cloud.trotter.log.gig.domain.census

import cloud.trotter.census.contract.WireTokenGrammars
import cloud.trotter.census.contract.health.HealthReportSchema
import cloud.trotter.census.contract.health.ShadowDailyDto
import cloud.trotter.census.contract.health.ShadowLevels
import kotlinx.serialization.Serializable

/**
 * One shadow candidate's full-day counters on one health row (#1630), keyed in [HealthRow.shadow] by generation and
 * digest. Exact counts stay on the phone; the report sends levels ([ShadowLevels]).
 *
 * [visits] counts visits that started on this row. [evaluated] counts this row's frames with a definite outcome (a
 * match or a non-match), saturating at [HealthReportSchema.MAX_COUNT]. Each metric family is `k` hits among `n`
 * visits: presence ([tied], [onUnknown]) over every visit, `emptyParse` over the visits whose diagnostic shape declares
 * required fields, `maskFail` over the visits with a finished mask scan. [unavailable] marks an entry whose counter
 * would have overflowed; the row then sends no `shadowDaily`.
 */
@Serializable
data class ShadowCounters(
    val rule: String,
    val visits: Int = 0,
    val evaluated: Int = 0,
    val tied: Int = 0,
    val onUnknown: Int = 0,
    val emptyParseN: Int = 0,
    val emptyParseK: Int = 0,
    val maskFailN: Int = 0,
    val maskFailK: Int = 0,
    val unavailable: Boolean = false,
)

/** What one visit found over its frames (design F1): booleans only. */
data class VisitFold(
    /** Some frame had a live winner. */
    val tied: Boolean = false,
    /** Some frame was live UNKNOWN. */
    val onUnknown: Boolean = false,
    /** Some frame's diagnostic shape declares required fields. */
    val parseApplicable: Boolean = false,
    /** Some applicable frame read every required field. */
    val parseHealthy: Boolean = false,
    /** Some frame's mask scan finished. */
    val maskAssessable: Boolean = false,
    /** Some frame's mask scan was a hit. */
    val maskHit: Boolean = false,
) {
    fun plus(liveWon: Boolean, frame: ShadowOutcome.Matched): VisitFold = VisitFold(
        tied = tied || liveWon,
        onUnknown = onUnknown || !liveWon,
        parseApplicable = parseApplicable || frame.requiredOk != null,
        parseHealthy = parseHealthy || frame.requiredOk == true,
        maskAssessable = maskAssessable || frame.maskScan != MaskScanResult.INCOMPLETE,
        maskHit = maskHit || frame.maskScan == MaskScanResult.HIT,
    )
}

/** The row-level shadow arithmetic (#1630): roster, counters, load validation and the report's entries. */
internal object ShadowTally {
    /** Candidates one row registers; a candidate arriving at a full roster is not registered and the row is truncated. */
    const val MAX_SHADOW_ROSTER = 64

    private val HEX16 = Regex("[0-9a-f]{16}")

    fun entryKey(generation: String, digest: String): String = "$generation:$digest"

    /**
     * [row] after one shadow frame of [platform] under [generation]: each reportable candidate joins the roster, and a
     * definite outcome adds one to its `evaluated`. Returns [row] itself when nothing changed.
     */
    fun record(row: HealthRow, platform: String, generation: String, results: List<ShadowResult>): HealthRow {
        if (row.shadowUnavailable || !HEX16.matches(generation)) return row
        val shadow = row.shadow.toMutableMap()
        var truncated = row.shadowTruncated
        var changed = false
        for (result in results) {
            if (!HEX16.matches(result.digest) || !reportable(platform, result.ruleId)) continue
            val key = entryKey(generation, result.digest)
            val existing = shadow[key]
            if (existing == null && shadow.size >= MAX_SHADOW_ROSTER) {
                if (!truncated) changed = true
                truncated = true
                continue
            }
            val entry = existing ?: ShadowCounters(result.ruleId)
            val counted = result.outcome != ShadowOutcome.Unevaluated && !entry.unavailable &&
                entry.evaluated < HealthReportSchema.MAX_COUNT
            if (existing == null || counted) {
                shadow[key] = if (counted) entry.copy(evaluated = entry.evaluated + 1) else entry
                changed = true
            }
        }
        if (!changed) return row
        return row.copy(shadow = shadow, shadowTruncated = truncated, revision = row.revision + 1)
    }

    /** [row] with one closed visit of entry [key] credited, once, with checked increments; [row] when not registered. */
    fun credit(row: HealthRow, key: String, fold: VisitFold): HealthRow {
        val entry = row.shadow[key] ?: return row
        if (row.shadowUnavailable || entry.unavailable) return row
        val credited = try {
            entry.copy(
                visits = Math.addExact(entry.visits, 1),
                tied = Math.addExact(entry.tied, fold.tied.int),
                onUnknown = Math.addExact(entry.onUnknown, fold.onUnknown.int),
                emptyParseN = Math.addExact(entry.emptyParseN, fold.parseApplicable.int),
                emptyParseK = Math.addExact(entry.emptyParseK, (fold.parseApplicable && !fold.parseHealthy).int),
                maskFailN = Math.addExact(entry.maskFailN, fold.maskAssessable.int),
                maskFailK = Math.addExact(entry.maskFailK, (fold.maskAssessable && fold.maskHit).int),
            )
        } catch (_: ArithmeticException) {
            entry.copy(unavailable = true)
        }
        return row.copy(shadow = row.shadow + (key to credited), revision = row.revision + 1)
    }

    /**
     * Whether a persisted row's shadow map is one [record] and [credit] could have built: at most [MAX_SHADOW_ROSTER]
     * entries, 16-hex ids, reportable rule ids of [platform], and `0 <= k <= n <= visits` per family.
     */
    fun wellFormed(platform: String, row: HealthRow): Boolean =
        row.shadow.size <= MAX_SHADOW_ROSTER && row.shadow.all { (key, c) ->
            val ids = key.split(':')
            ids.size == 2 && ids.all(HEX16::matches) && reportable(platform, c.rule) &&
                c.visits >= 0 && c.evaluated in 0..HealthReportSchema.MAX_COUNT &&
                c.tied in 0..c.visits && c.onUnknown in 0..c.visits &&
                c.emptyParseN in 0..c.visits && c.emptyParseK in 0..c.emptyParseN &&
                c.maskFailN in 0..c.visits && c.maskFailK in 0..c.maskFailN
        }

    /**
     * The row's `shadowDaily` entries and its `shadowDailyTruncated` flag, or null when the row sends neither: no
     * roster, or an entry or the whole map unavailable. Entries are ordered by visits level (highest first), then rule,
     * generation and digest, and the first [HealthReportSchema.MAX_SHADOW_ENTRIES] go.
     */
    fun daily(row: HealthRow): Pair<List<ShadowDailyDto>, Boolean>? {
        if (row.shadowUnavailable || row.shadow.isEmpty() || row.shadow.values.any { it.unavailable }) return null
        val all = row.shadow.map { (key, counters) -> entry(key, counters) }
            .sortedWith(
                // CountLevel declares its levels lowest first, so the highest ordinal is the highest level.
                compareByDescending<ShadowDailyDto> { it.visits.ordinal }
                    .thenBy { it.rule }.thenBy { it.generation }.thenBy { it.digest },
            )
        val sent = all.take(HealthReportSchema.MAX_SHADOW_ENTRIES)
        return sent to (row.shadowTruncated || sent.size < all.size)
    }

    private fun entry(key: String, c: ShadowCounters): ShadowDailyDto {
        val visited = c.visits > 0
        return ShadowDailyDto(
            generation = key.substringBefore(':'),
            rule = c.rule,
            digest = key.substringAfter(':'),
            visits = ShadowLevels.countLevel(c.visits),
            evaluated = ShadowLevels.countLevel(c.evaluated),
            tied = if (visited) ShadowLevels.fractionLevel(c.tied, c.visits) else null,
            onUnknown = if (visited) ShadowLevels.fractionLevel(c.onUnknown, c.visits) else null,
            emptyParse = if (c.emptyParseN > 0) ShadowLevels.fractionLevel(c.emptyParseK, c.emptyParseN) else null,
            maskFail = if (c.maskFailN > 0) ShadowLevels.fractionLevel(c.maskFailK, c.maskFailN) else null,
        )
    }

    /** A rule id the report can carry under [platform]: the contract grammar, its length bound and the platform prefix. */
    private fun reportable(platform: String, rule: String): Boolean =
        rule.length <= HealthReportSchema.MAX_PARSE_RULE_ID_LENGTH && WireTokenGrammars.ruleId.matches(rule) &&
            rule.startsWith("$platform.")

    private val Boolean.int: Int get() = if (this) 1 else 0
}
