package cloud.trotter.log.gig.domain.census

import cloud.trotter.census.contract.WireTokenGrammars
import cloud.trotter.census.contract.health.HealthReportDto
import cloud.trotter.census.contract.health.HealthReportSchema
import kotlinx.serialization.Serializable

/** UTC day, platform wire and observed platform app version identify one report. */
@Serializable
data class HealthKey(val day: String, val platform: String, val platformAppVersion: String) {
    override fun toString(): String = "$day|$platform|$platformAppVersion"

    companion object {
        fun parse(value: String): HealthKey {
            val parts = value.split('|')
            require(parts.size == 3)
            return HealthKey(parts[0], parts[1], parts[2])
        }
    }
}

/** Revision receipts belong to the complete row, including its complete rule map. */
@Serializable
data class HealthRow(
    val admitted: Int = 0,
    val unknown: Int = 0,
    val trips: Int = 0,
    val ruleCounts: Map<String, Int> = emptyMap(),
    val revision: Long = 0,
    val postedRevision: Long = -1,
    val refusedRevision: Long = -1,
    /**
     * Per rule id, the row's last [HealthReportSchema.PARSE_SAMPLE] eligible parse attempts (#1571), oldest first:
     * `'0'` read every required field, `'1'` left one null. Never longer than PARSE_SAMPLE.
     */
    val parseSamples: Map<String, String> = emptyMap(),
    /**
     * The row's shadow roster (#1630): per candidate, keyed `<generation>:<digest>` (16 hex each), its full-day
     * counters. A candidate joins when this row records a shadow frame while it is active; at most
     * [ShadowTally.MAX_SHADOW_ROSTER].
     */
    val shadow: Map<String, ShadowCounters> = emptyMap(),
    /** A candidate arrived at a full roster and was not registered. */
    val shadowTruncated: Boolean = false,
    /** The persisted shadow map failed validation and was dropped: the row sends no `shadowDaily` for the day. */
    val shadowUnavailable: Boolean = false,
)

/** Immutable, JSON object keyed ledger scoped to one census identity generation. */
@Serializable
data class HealthLedger(val rows: Map<String, HealthRow> = emptyMap(), val generation: Long = 0) {
    fun record(key: HealthKey, ruleId: String?): HealthLedger = update(key) { row ->
        if (ruleId == null) row.copy(unknown = increment(row.unknown), revision = row.revision + 1)
        else row.copy(
            admitted = increment(row.admitted),
            // Review receipt (#1197): saturated admitted freezes hit totals too; retain every rule key.
            ruleCounts = row.ruleCounts + (ruleId to if (row.admitted >= MAX_COUNT) (row.ruleCounts[ruleId] ?: 0)
                else increment(row.ruleCounts[ruleId] ?: 0)),
            revision = row.revision + 1,
        )
    }

    fun trip(key: HealthKey): HealthLedger = update(key) {
        it.copy(trips = increment(it.trips), revision = it.revision + 1)
    }

    /**
     * One eligible parse attempt of [ruleId] (#1571). Returns this ledger unchanged for a rule id the report could not
     * carry, for a new rule once the row holds [HealthReportSchema.MAX_PARSE_SHORTFALL_RULES], and for a new row past
     * [MAX_ROWS].
     */
    fun attempt(key: HealthKey, ruleId: String, shortfall: Boolean): HealthLedger {
        if (!isReportableRuleId(ruleId)) return this
        val row = rows[key.toString()]
        if (row != null && ruleId !in row.parseSamples &&
            row.parseSamples.size >= HealthReportSchema.MAX_PARSE_SHORTFALL_RULES
        ) {
            return this
        }
        return update(key) {
            val sample = (it.parseSamples[ruleId].orEmpty() + if (shortfall) SHORTFALL else HEALTHY)
                .takeLast(HealthReportSchema.PARSE_SAMPLE)
            it.copy(parseSamples = it.parseSamples + (ruleId to sample), revision = it.revision + 1)
        }
    }

    /**
     * One shadow frame (#1630) on [key]'s row: each active candidate joins the roster, and a definite outcome counts as
     * evaluated. A row is never created for a frame that changes nothing, nor past [MAX_ROWS].
     */
    fun shadowFrame(key: HealthKey, generation: String, results: List<ShadowResult>): HealthLedger {
        val row = rows[key.toString()]
        if (row == null && rows.size >= MAX_ROWS) return this
        val before = row ?: HealthRow()
        val after = ShadowTally.record(before, key.platform, generation, results)
        return if (after === before) this else copy(rows = rows + (key.toString() to after))
    }

    /**
     * One closed shadow visit, credited once to the row it started on ([startKey]) under roster entry [entryKey]; this
     * ledger unchanged when that row or entry is gone. The revision moves, so a closed row posts again.
     */
    fun creditVisit(startKey: HealthKey, entryKey: String, fold: VisitFold): HealthLedger {
        val row = rows[startKey.toString()] ?: return this
        val after = ShadowTally.credit(row, entryKey, fold)
        return if (after === row) this else copy(rows = rows + (startKey.toString() to after))
    }

    fun pruneBefore(dayExclusive: String): HealthLedger = copy(
        rows = rows.filterKeys { HealthKey.parse(it).day >= dayExclusive },
    )

    fun closedRowsToPost(todayUtc: String, oldestInclusive: String): List<Pair<HealthKey, HealthRow>> = rows
        .map { (key, row) -> HealthKey.parse(key) to row }
        .filter { (key, row) -> key.day >= oldestInclusive && key.day < todayUtc &&
            row.postedRevision < row.revision && row.refusedRevision != row.revision }
        .sortedWith(compareBy<Pair<HealthKey, HealthRow>> { it.first.day }.thenBy { it.first.toString() })

    fun markPosted(key: HealthKey, revision: Long): HealthLedger = acknowledge(key, revision) {
        it.copy(postedRevision = revision)
    }

    fun markRefused(key: HealthKey, revision: Long): HealthLedger = acknowledge(key, revision) {
        it.copy(refusedRevision = revision)
    }

    fun clear(): HealthLedger = HealthLedger(generation = generation + 1)

    private fun update(key: HealthKey, transform: (HealthRow) -> HealthRow): HealthLedger {
        val row = rows[key.toString()]
        if (row == null && rows.size >= MAX_ROWS) return this
        return copy(rows = rows + (key.toString() to transform(row ?: HealthRow())))
    }

    private fun acknowledge(key: HealthKey, revision: Long, transform: (HealthRow) -> HealthRow): HealthLedger {
        val row = rows[key.toString()] ?: return copy()
        return if (row.revision == revision) update(key, transform) else copy()
    }

    companion object {
        const val MAX_ROWS = 256
        private const val MAX_COUNT = HealthReportSchema.MAX_COUNT
        private const val HEALTHY = '0'
        private const val SHORTFALL = '1'
        private fun increment(value: Int): Int = if (value >= MAX_COUNT) MAX_COUNT else value + 1

        private fun isReportableRuleId(ruleId: String): Boolean =
            ruleId.length <= HealthReportSchema.MAX_PARSE_RULE_ID_LENGTH && WireTokenGrammars.ruleId.matches(ruleId)

        /**
         * Whether a persisted row's [HealthRow.parseSamples] is one [attempt] could have built: reportable rule ids, at
         * most [HealthReportSchema.MAX_PARSE_SHORTFALL_RULES] of them, each sample non-empty, `0`/`1` only and at most
         * [HealthReportSchema.PARSE_SAMPLE] long.
         */
        fun wellFormedSamples(row: HealthRow): Boolean =
            row.parseSamples.size <= HealthReportSchema.MAX_PARSE_SHORTFALL_RULES &&
                row.parseSamples.all { (rule, sample) ->
                    isReportableRuleId(rule) && sample.length in 1..HealthReportSchema.PARSE_SAMPLE &&
                        sample.all { it == HEALTHY || it == SHORTFALL }
                }

        /**
         * [row] as loaded for [key]: unchanged when its shadow map is one the collector could have built, else without it
         * and marked unavailable for the day (the base counts are kept).
         */
        fun withValidShadow(key: HealthKey, row: HealthRow): HealthRow =
            if (ShadowTally.wellFormed(key.platform, row)) row
            else row.copy(shadow = emptyMap(), shadowTruncated = false, shadowUnavailable = true)

        /**
         * Per rule with a full sample, its shortfalls among the last [HealthReportSchema.PARSE_SAMPLE] attempts; null
         * when no rule has a full sample, so the report leaves the field off.
         */
        fun parseShortfall(row: HealthRow): Map<String, Int>? = row.parseSamples
            .filterValues { it.length == HealthReportSchema.PARSE_SAMPLE }
            .mapValues { (_, sample) -> sample.count { it == SHORTFALL } }
            .toSortedMap()
            .takeIf { it.isNotEmpty() }

        /**
         * The wire report for one closed row; [HealthReportSchema] owns its shape and bounds. With [withParseShortfall]
         * false the report leaves `parseShortfall` off.
         */
        fun reportJson(
            key: HealthKey,
            row: HealthRow,
            appVersion: String,
            rulesetVersion: String,
            withParseShortfall: Boolean = true,
        ): String = HealthReportSchema.encode(
            report(key, row, appVersion, rulesetVersion).let { if (withParseShortfall) it else it.copy(parseShortfall = null) },
        )

        /**
         * The report to send for one closed row: the largest form that passes [HealthReportSchema.fits], in the
         * schema's own drop order ([HealthReportSchema.encodeFitting]); null when the row is refused as oversized.
         */
        fun sendableReportJson(key: HealthKey, row: HealthRow, appVersion: String, rulesetVersion: String): String? =
            HealthReportSchema.encodeFitting(report(key, row, appVersion, rulesetVersion))

        private fun report(key: HealthKey, row: HealthRow, appVersion: String, rulesetVersion: String): HealthReportDto {
            val shadow = ShadowTally.daily(row)
            return HealthReportDto(
                day = key.day,
                platform = key.platform,
                platformAppVersion = key.platformAppVersion,
                appVersion = appVersion,
                rulesetVersion = rulesetVersion,
                admitted = row.admitted,
                unknown = row.unknown,
                trips = row.trips,
                ruleCounts = row.ruleCounts,
                parseShortfall = parseShortfall(row),
                shadowDaily = shadow?.first,
                shadowDailyTruncated = shadow?.second?.takeIf { it },
            )
        }
    }
}
