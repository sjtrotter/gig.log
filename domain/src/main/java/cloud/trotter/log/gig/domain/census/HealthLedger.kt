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

        private fun report(key: HealthKey, row: HealthRow, appVersion: String, rulesetVersion: String) = HealthReportDto(
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
        )
    }
}
