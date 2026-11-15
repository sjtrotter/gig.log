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
     * Per-rule parse health (census#64): for each rule with at least [HealthReportSchema.PARSE_SAMPLE] eligible
     * attempts that UTC day, how many of the last [HealthReportSchema.PARSE_SAMPLE] of them left a shape-required field
     * null, `0..PARSE_SAMPLE`. A rule below the sample size is left out, so the field reveals no attempt count beyond
     * "at least PARSE_SAMPLE today". Optional: a null value is left off the wire, so an older report serializes byte
     * for byte as before.
     */
    val parseShortfall: Map<String, Int>? = null,
    /**
     * Shadow-rule tallies (census#102, gig.log#1630): per candidate rule, at most [HealthReportSchema.MAX_SHADOW_ENTRIES]
     * entries, unique by (generation, rule, digest). Optional; a null value is left off the wire.
     */
    val shadow: List<ShadowTallyDto>? = null,
    /** Probes the phone refused to evaluate (census#102): configuration status, not screen evidence. Optional. */
    val probeRefused: List<ShadowRefDto>? = null,
)

/**
 * One shadow rule's tallies for the day. [generation] names the live rules the phone ran and [digest] the candidate's
 * definition, each the first 16 hex of a sha256; [rule] is the candidate's rule id. Each metric is the count among the
 * last [HealthReportSchema.PARSE_SAMPLE] visits where that metric could be measured, `0..PARSE_SAMPLE`; a null metric is
 * unavailable (its sample was not full) and is left off the wire. [tied] and [onUnknown] share one sample, so they are
 * both present or both absent, and an entry carries at least one metric.
 */
@Serializable
data class ShadowTallyDto(
    val generation: String,
    val rule: String,
    val digest: String,
    val tied: Int? = null,
    val onUnknown: Int? = null,
    val emptyParse: Int? = null,
    val maskFail: Int? = null,
)

/** One refused probe (census#102), named as a [ShadowTallyDto] is. */
@Serializable
data class ShadowRefDto(
    val generation: String,
    val rule: String,
    val digest: String,
)

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
