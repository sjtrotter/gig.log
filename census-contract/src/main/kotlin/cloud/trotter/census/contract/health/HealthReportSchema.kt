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

    /** At most this many rules in [HealthReportDto.parseShortfall]. */
    const val MAX_PARSE_SHORTFALL_RULES = 128

    /** Every count, including each shortfall and eligible value, is an integer in `0..MAX_COUNT`. */
    const val MAX_COUNT = 1_000_000

    /** The wire keys, in order. */
    val FIELDS: List<String> = listOf(
        "day", "platform", "platformAppVersion", "appVersion", "rulesetVersion",
        "admitted", "unknown", "trips", "ruleCounts", "parseShortfall",
    )

    private val PARSE_COUNT_FIELDS = setOf("shortfall", "eligible")
    private val DAY = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    private val json = Json { encodeDefaults = false; explicitNulls = false }

    fun encode(report: HealthReportDto): String = json.encodeToString(HealthReportDto.serializer(), report)

    /** The phone's pre-send bounds: UTF-8 size and map entry counts. A report outside them is never truncated. */
    fun fits(encoded: String): Boolean {
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_REPORT_BYTES) return false
        val obj = Json.parseToJsonElement(encoded) as? JsonObject ?: return false
        val rules = obj["ruleCounts"] as? JsonObject ?: return false
        val parse = obj["parseShortfall"]
        return rules.size <= MAX_RULE_COUNTS &&
            (parse == null || (parse as? JsonObject)?.let { it.size <= MAX_PARSE_SHORTFALL_RULES } == true)
    }

    /**
     * Validates one report element. [dayAccepted] and [platformAccepted] are the receiver's policy (the server's
     * date window and accepted platforms); the checks run in [HealthReportRejection] order and the first miss wins.
     */
    fun check(
        element: JsonElement,
        dayAccepted: (LocalDate) -> Boolean = { true },
        platformAccepted: (String) -> Boolean = { true },
    ): HealthReportCheck {
        if (element !is JsonObject) return invalid(HealthReportRejection.BAD_ITEM)
        if (element.keys.any { it !in FIELDS }) return invalid(HealthReportRejection.UNKNOWN_FIELD)
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
            val rules = linkedMapOf<String, RuleParseCountDto>()
            for ((rule, value) in parse) {
                if (!WireTokenGrammars.ruleId.matches(rule)) return invalid(HealthReportRejection.BAD_RULE_ID)
                rules[rule] = value.parseCount() ?: return invalid(HealthReportRejection.BAD_COUNT)
            }
            rules
        }
        if (Json.encodeToString(JsonElement.serializer(), element).toByteArray(Charsets.UTF_8).size > MAX_REPORT_BYTES) {
            return invalid(HealthReportRejection.TOO_LARGE)
        }
        return HealthReportCheck.Valid(HealthReportDto(
            day, platform, platformAppVersion, appVersion, rulesetVersion, admitted, unknown, trips, ruleCounts, parseShortfall,
        ))
    }

    private fun invalid(reason: HealthReportRejection) = HealthReportCheck.Invalid(reason)
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.version(key: String, grammar: Regex): String? = string(key)?.takeIf { grammar.matches(it) }

    /** A JSON integer (never a string, fraction or boolean) in `0..MAX_COUNT`. */
    private fun JsonElement?.count(): Int? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in 0..MAX_COUNT }

    private fun JsonElement.parseCount(): RuleParseCountDto? {
        val obj = this as? JsonObject ?: return null
        if (obj.keys != PARSE_COUNT_FIELDS) return null
        val shortfall = obj["shortfall"].count() ?: return null
        val eligible = obj["eligible"].count() ?: return null
        return if (shortfall <= eligible) RuleParseCountDto(shortfall, eligible) else null
    }
}
