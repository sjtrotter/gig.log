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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A daily count as the health report carries it (census#104): exact counts stay on the phone. */
@Serializable
enum class CountLevel(val wire: String) {
    @SerialName("0") NONE("0"),
    @SerialName("1-4") FEW("1-4"),
    @SerialName("5-19") SEVERAL("5-19"),
    @SerialName("20+") MANY("20+"),
    ;

    companion object {
        fun fromWire(token: String): CountLevel? = entries.firstOrNull { it.wire == token }
    }
}

/** k hits among n visits where a check was possible, n > 0, as the health report carries it (census#104). */
@Serializable
enum class FractionLevel(val wire: String) {
    /** k = 0. */
    @SerialName("none") NONE("none"),

    /** 0 < 2k < n. */
    @SerialName("some") SOME("some"),

    /** n <= 2k and k < n. */
    @SerialName("half_or_more") HALF_OR_MORE("half_or_more"),

    /** k = n. */
    @SerialName("all") ALL("all"),
    ;

    companion object {
        fun fromWire(token: String): FractionLevel? = entries.firstOrNull { it.wire == token }
    }
}

/**
 * The one owner of the shadow-report levels (census#104, gig.log#1630). The phone computes them at serialization from
 * its exact daily counters; the server only ever sees the level. Integer arithmetic only.
 */
object ShadowLevels {
    fun countLevel(c: Int): CountLevel {
        require(c >= 0) { "countLevel needs c >= 0" }
        return when {
            c == 0 -> CountLevel.NONE
            c < 5 -> CountLevel.FEW
            c < 20 -> CountLevel.SEVERAL
            else -> CountLevel.MANY
        }
    }

    /** [k] hits among [n] visits where the check was possible. */
    fun fractionLevel(k: Int, n: Int): FractionLevel {
        require(n > 0 && k in 0..n) { "fractionLevel needs n > 0 and 0 <= k <= n" }
        return when {
            k == 0 -> FractionLevel.NONE
            k == n -> FractionLevel.ALL
            2L * k < n -> FractionLevel.SOME
            else -> FractionLevel.HALF_OR_MORE
        }
    }
}
