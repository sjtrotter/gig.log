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

import kotlinx.serialization.Serializable

/**
 * One item of `POST /v1/health`: an install's recognition-health counts for one UTC day, platform and platform app
 * version. The property order is the wire key order. Bounds and grammars live in [HealthReportSchema].
 *
 * [trips] counts recognition-health alarm trips, not deliveries; the report carries no count that reads as work done.
 */
@Serializable
data class HealthReportDto(
    val day: String,
    val platform: String,
    val platformAppVersion: String,
    val appVersion: String,
    val rulesetVersion: String,
    val admitted: Int,
    val unknown: Int,
    val trips: Int,
    val ruleCounts: Map<String, Int>,
    /**
     * Per-rule parse health for the day, keyed by rule id (census#64). Optional: a report without it is valid, and
     * a null value is left off the wire, so an older report serializes byte for byte as before.
     */
    val parseShortfall: Map<String, RuleParseCountDto>? = null,
)

/**
 * One rule's day: [eligible] matched frames that count toward the denominator (not pre-render frames), of which
 * [shortfall] left a shape-required field null. `0 <= shortfall <= eligible`.
 */
@Serializable
data class RuleParseCountDto(val shortfall: Int, val eligible: Int)

/** Why the server refused one health report. The first failing check wins, in this order. */
enum class HealthReportRejection(val wire: String) {
    BAD_ITEM("bad_item"),
    UNKNOWN_FIELD("unknown_field"),
    BAD_DAY("bad_day"),
    STALE_DAY("stale_day"),
    BAD_PLATFORM("bad_platform"),
    BAD_VERSION("bad_version"),
    BAD_COUNT("bad_count"),
    BAD_RULE_ID("bad_rule_id"),
    TOO_LARGE("too_large"),
}

/** The outcome of [HealthReportSchema.check]. */
sealed interface HealthReportCheck {
    data class Valid(val report: HealthReportDto) : HealthReportCheck
    data class Invalid(val reason: HealthReportRejection) : HealthReportCheck
}
