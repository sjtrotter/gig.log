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
 * The platform wire ids, the one list of the delivery apps gig.log knows (#1709). An item's, a health report's and a
 * rule id's platform is one of these. The app's platform registry (`Platform` in gig.log's `:domain`) takes each
 * entry's wire id from here, and a domain test pins the two equal; the server derives the policy's `acceptedPlatforms`
 * from [ALL]. Adding a delivery app is one entry here and one in the registry. Each id passes
 * [WireTokenGrammars.platform].
 */
object CensusPlatforms {
    const val DOORDASH: String = "doordash"
    const val UBER: String = "uber"
    const val INSTACART: String = "instacart"
    const val WALMART_SPARK: String = "walmart_spark"

    /** The bucket of a screen or a notification whose app resolved to no known platform. */
    const val UNKNOWN: String = "_unknown"

    /** Every platform wire id, in the registry's order. */
    val ALL: List<String> = listOf(DOORDASH, UBER, INSTACART, WALMART_SPARK, UNKNOWN)
}
