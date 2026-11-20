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
package cloud.trotter.census.contract

/**
 * The grammars of the short tokens the server keeps from a report, a matched skeleton or a clicked skeleton: version
 * stamps, rule ids and short digests (census#64, #1573, #1634). The phone and the server both read them from here.
 */
object WireTokenGrammars {
    /** A platform app's numeric version, such as `8.99.20`. */
    val platformAppVersion = Regex("^[0-9]{1,5}(\\.[0-9]{1,5}){0,3}$")

    /** gig.log's own version name: `<base>`, `<base>+<git sha>[.dirty]`, `<base>+nogit`, or the server's `test` fixture. */
    val appVersion = Regex("^([0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}(\\+([0-9a-f]{7,40}(\\.dirty)?|nogit))?|test)$")

    /** A ruleset release tag: `corpus`, `dev`, or a version with an optional short suffix. */
    val rulesetReleaseTag = Regex("^(corpus|dev|v?[0-9]{1,5}(\\.[0-9]{1,5}){0,3}(-[a-z0-9]{1,12})?)$")

    /** A recognition rule id: two to five lowercase dotted segments, such as `doordash.screen.offer`. */
    val ruleId = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){1,4}$")

    /** The longest rule id a health report's per-rule entries or a matched skeleton may carry. */
    const val MAX_RULE_ID_LENGTH: Int = 96

    /**
     * True when [ruleId] is a rule id of [platform]: at most [MAX_RULE_ID_LENGTH] characters, the [ruleId] grammar,
     * and the `"<platform>."` prefix. The one owner of that check for the health report's shadow entries and the
     * matched skeleton's `ruleId` (#1573).
     */
    fun isRuleIdFor(platform: String, ruleId: String): Boolean =
        ruleId.length <= MAX_RULE_ID_LENGTH && this.ruleId.matches(ruleId) && ruleId.startsWith("$platform.")

    /**
     * The first 16 lowercase hex of a sha256: a rules generation's wire id (`RuleIdentifiers.wire`), which a health
     * report's shadow entries and a clicked skeleton's `tap.generation` carry, and a shadow candidate's `digest`.
     */
    val shortDigest = Regex("^[0-9a-f]{16}$")
}
