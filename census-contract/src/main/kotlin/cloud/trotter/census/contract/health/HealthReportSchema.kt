/*
 * Copyright 2026 Stephen Trotter
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Census wire contract (ADR-0011, #1173). This Apache-2.0 included build depends
 * on nothing but the JDK and kotlinx-serialization.
 */
package cloud.trotter.census.contract.health

import cloud.trotter.census.contract.WireTokenGrammars
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The health report's one owner (census#64): the phone encodes through [encode] and checks [fits] before it sends;
 * the server accepts through [check], supplying its own day window and platform allowlist.
 */
object HealthReportSchema {
    const val MAX_REPORT_BYTES = 8192
    const val MAX_RULE_COUNTS = 512

    /**
     * The fixed sample behind each [HealthReportDto.parseShortfall] value: the rule's last PARSE_SAMPLE eligible
     * attempts of the day. The value counts the shortfalls among them, so it is never more than PARSE_SAMPLE.
     */
    const val PARSE_SAMPLE = 20

    /** At most this many rules in [HealthReportDto.parseShortfall]. */
    const val MAX_PARSE_SHORTFALL_RULES = 128

    /** A [HealthReportDto.parseShortfall] rule id is at most this long; `ruleCounts` keeps its legacy bound. */
    const val MAX_PARSE_RULE_ID_LENGTH = 96

    /** Every daily count is an integer in `0..MAX_COUNT`. */
    const val MAX_COUNT = 1_000_000

    /** At most this many entries in [HealthReportDto.shadow], and again in [HealthReportDto.probeRefused]. */
    const val MAX_SHADOW_ENTRIES = 32

    /** The wire keys, in order. */
    val FIELDS: List<String> = listOf(
        "day", "platform", "platformAppVersion", "appVersion", "rulesetVersion",
        "admitted", "unknown", "trips", "ruleCounts", "parseShortfall", "shadow", "probeRefused",
    )

    /** The keys of one [ShadowRefDto], in wire order. */
    val SHADOW_REF_FIELDS: List<String> = listOf("generation", "rule", "digest")

    /** The metrics of one [ShadowTallyDto], in wire order after [SHADOW_REF_FIELDS]. */
    val SHADOW_METRICS: List<String> = listOf("tied", "onUnknown", "emptyParse", "maskFail")

    private val DAY = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    private val HEX16 = Regex("[0-9a-f]{16}")
    private val json = Json { encodeDefaults = false; explicitNulls = false }

    fun encode(report: HealthReportDto): String = json.encodeToString(HealthReportDto.serializer(), report)

    /** The phone's pre-send bounds: UTF-8 size and map entry counts. A report outside them is never truncated. */
    fun fits(encoded: String): Boolean {
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_REPORT_BYTES) return false
        val obj = Json.parseToJsonElement(encoded) as? JsonObject ?: return false
        val rules = obj["ruleCounts"] as? JsonObject ?: return false
        val parse = obj["parseShortfall"]
        return rules.size <= MAX_RULE_COUNTS &&
            (parse == null || (parse as? JsonObject)?.let { it.size <= MAX_PARSE_SHORTFALL_RULES } == true) &&
            listOf(obj["shadow"], obj["probeRefused"]).all { list ->
                list == null || (list as? JsonArray)?.let { it.size <= MAX_SHADOW_ENTRIES } == true
            }
    }

    /**
     * The report the phone sends: [report] whole when it [fits], else without `shadow`, then also without
     * `probeRefused`, then also without `parseShortfall`; null when even the base report does not fit.
     */
    fun encodeFitting(report: HealthReportDto): String? = sequenceOf(
        report,
        report.copy(shadow = null),
        report.copy(shadow = null, probeRefused = null),
        report.copy(shadow = null, probeRefused = null, parseShortfall = null),
    ).map(::encode).firstOrNull(::fits)

    /**
     * Validates one report element. [dayAccepted] and [platformAccepted] are the receiver's policy (the server's
     * date window and accepted platforms); the checks run in [HealthReportRejection] order and the first miss wins.
     * Unknown keys inside `shadow` and `probeRefused` entries are found with the top-level ones. Each optional field is
     * then checked in wire order: shape, size and values first (`bad_count`), then identifiers (`bad_rule_id`).
     */
    fun check(
        element: JsonElement,
        dayAccepted: (LocalDate) -> Boolean = { true },
        platformAccepted: (String) -> Boolean = { true },
    ): HealthReportCheck {
        if (element !is JsonObject) return invalid(HealthReportRejection.BAD_ITEM)
        if (element.keys.any { it !in FIELDS } ||
            element.entryKeys("shadow").any { it !in SHADOW_REF_FIELDS && it !in SHADOW_METRICS } ||
            element.entryKeys("probeRefused").any { it !in SHADOW_REF_FIELDS }
        ) return invalid(HealthReportRejection.UNKNOWN_FIELD)
        val day = element.string("day")?.takeIf { DAY.matches(it) } ?: return invalid(HealthReportRejection.BAD_DAY)
        val date = try { LocalDate.parse(day) } catch (_: DateTimeParseException) { return invalid(HealthReportRejection.BAD_DAY) }
        if (!dayAccepted(date)) return invalid(HealthReportRejection.STALE_DAY)
        val platform = element.string("platform")?.takeIf(platformAccepted) ?: return invalid(HealthReportRejection.BAD_PLATFORM)
        val platformAppVersion = element.version("platformAppVersion", WireTokenGrammars.platformAppVersion)
            ?: return invalid(HealthReportRejection.BAD_VERSION)
        val appVersion = element.version("appVersion", WireTokenGrammars.appVersion)
            ?: return invalid(HealthReportRejection.BAD_VERSION)
        val rulesetVersion = element.version("rulesetVersion", WireTokenGrammars.rulesetReleaseTag)
            ?: return invalid(HealthReportRejection.BAD_VERSION)
        val admitted = element["admitted"].count() ?: return invalid(HealthReportRejection.BAD_COUNT)
        val unknown = element["unknown"].count() ?: return invalid(HealthReportRejection.BAD_COUNT)
        val trips = element["trips"].count() ?: return invalid(HealthReportRejection.BAD_COUNT)
        val counts = element["ruleCounts"] as? JsonObject ?: return invalid(HealthReportRejection.BAD_COUNT)
        if (counts.size > MAX_RULE_COUNTS) return invalid(HealthReportRejection.BAD_COUNT)
        val ruleCounts = linkedMapOf<String, Int>()
        for ((rule, value) in counts) {
            if (!WireTokenGrammars.ruleId.matches(rule)) return invalid(HealthReportRejection.BAD_RULE_ID)
            ruleCounts[rule] = value.count() ?: return invalid(HealthReportRejection.BAD_COUNT)
        }
        if (ruleCounts.values.sumOf { it.toLong() } > admitted) return invalid(HealthReportRejection.BAD_COUNT)
        val parseShortfall = if ("parseShortfall" !in element) null else {
            val parse = element["parseShortfall"] as? JsonObject ?: return invalid(HealthReportRejection.BAD_COUNT)
            if (parse.size > MAX_PARSE_SHORTFALL_RULES) return invalid(HealthReportRejection.BAD_COUNT)
            val rules = linkedMapOf<String, Int>()
            for ((rule, value) in parse) {
                if (rule.length > MAX_PARSE_RULE_ID_LENGTH || !WireTokenGrammars.ruleId.matches(rule)) {
                    return invalid(HealthReportRejection.BAD_RULE_ID)
                }
                rules[rule] = value.sampleCount() ?: return invalid(HealthReportRejection.BAD_COUNT)
            }
            rules
        }
        val shadow = if ("shadow" !in element) null else {
            val entries = element.entries("shadow") ?: return invalid(HealthReportRejection.BAD_COUNT)
            val metrics = entries.map { entry ->
                val values = SHADOW_METRICS.map { key ->
                    if (key !in entry) null else entry[key].sampleCount() ?: return invalid(HealthReportRejection.BAD_COUNT)
                }
                // tied and onUnknown share one sample; an entry with no metric says nothing.
                if ((values[0] == null) != (values[1] == null) || values.all { it == null }) {
                    return invalid(HealthReportRejection.BAD_COUNT)
                }
                values
            }
            val refs = entries.refs(platform) ?: return invalid(HealthReportRejection.BAD_RULE_ID)
            refs.zip(metrics) { ref, (tied, onUnknown, emptyParse, maskFail) ->
                ShadowTallyDto(ref.generation, ref.rule, ref.digest, tied, onUnknown, emptyParse, maskFail)
            }
        }
        val probeRefused = if ("probeRefused" !in element) null else {
            val entries = element.entries("probeRefused") ?: return invalid(HealthReportRejection.BAD_COUNT)
            entries.refs(platform) ?: return invalid(HealthReportRejection.BAD_RULE_ID)
        }
        if (Json.encodeToString(JsonElement.serializer(), element).toByteArray(Charsets.UTF_8).size > MAX_REPORT_BYTES) {
            return invalid(HealthReportRejection.TOO_LARGE)
        }
        return HealthReportCheck.Valid(HealthReportDto(
            day, platform, platformAppVersion, appVersion, rulesetVersion, admitted, unknown, trips, ruleCounts, parseShortfall,
            shadow, probeRefused,
        ))
    }

    private fun invalid(reason: HealthReportRejection) = HealthReportCheck.Invalid(reason)
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.version(key: String, grammar: Regex): String? = string(key)?.takeIf { grammar.matches(it) }

    /** The keys of every object entry of the array at [key]; a malformed field is left to its own check. */
    private fun JsonObject.entryKeys(key: String): Sequence<String> =
        (this[key] as? JsonArray).orEmpty().asSequence().filterIsInstance<JsonObject>().flatMap { it.keys }

    /** The array at [key] as objects, at most [MAX_SHADOW_ENTRIES]; null for an explicit null or any other shape. */
    private fun JsonObject.entries(key: String): List<JsonObject>? = (this[key] as? JsonArray)
        ?.takeIf { it.size <= MAX_SHADOW_ENTRIES }
        ?.map { it as? JsonObject ?: return null }

    /**
     * Each entry's (generation, rule, digest): 16 lowercase hex, a rule id of at most [MAX_PARSE_RULE_ID_LENGTH]
     * under the report's own platform, 16 lowercase hex. Null when one is malformed or two entries repeat a triple.
     */
    private fun List<JsonObject>.refs(platform: String): List<ShadowRefDto>? {
        val refs = map { entry ->
            val generation = entry.string("generation")?.takeIf { HEX16.matches(it) } ?: return null
            val rule = entry.string("rule")?.takeIf {
                it.length <= MAX_PARSE_RULE_ID_LENGTH && WireTokenGrammars.ruleId.matches(it) && it.startsWith("$platform.")
            } ?: return null
            val digest = entry.string("digest")?.takeIf { HEX16.matches(it) } ?: return null
            ShadowRefDto(generation, rule, digest)
        }
        return refs.takeIf { it.toSet().size == it.size }
    }

    /** A JSON integer in `0..PARSE_SAMPLE`: one count over a fixed sample. */
    private fun JsonElement?.sampleCount(): Int? = count()?.takeIf { it <= PARSE_SAMPLE }

    /** A JSON integer (never a string, fraction or boolean) in `0..MAX_COUNT`. */
    private fun JsonElement?.count(): Int? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in 0..MAX_COUNT }
}
