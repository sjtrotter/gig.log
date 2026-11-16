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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** census#104: the committed level vectors, labelled (k, n). */
class ShadowLevelsTest {
    @Test
    fun `fraction levels split k hits among n visits at none, under half, half or more and all`() {
        val vectors = listOf(
            Triple(0, 1, FractionLevel.NONE),
            Triple(1, 1, FractionLevel.ALL),
            Triple(1, 2, FractionLevel.HALF_OR_MORE),
            Triple(1, 3, FractionLevel.SOME),
            Triple(2, 3, FractionLevel.HALF_OR_MORE),
            Triple(3, 25, FractionLevel.SOME),
            Triple(25, 25, FractionLevel.ALL),
        )
        for ((k, n, level) in vectors) assertEquals("($k,$n)", level, ShadowLevels.fractionLevel(k, n))
    }

    @Test
    fun `the half comparison is exact at the Int bound`() {
        val max = Int.MAX_VALUE
        assertEquals(FractionLevel.SOME, ShadowLevels.fractionLevel(max / 2, max))
        assertEquals(FractionLevel.HALF_OR_MORE, ShadowLevels.fractionLevel(max / 2 + 1, max))
        assertEquals(FractionLevel.HALF_OR_MORE, ShadowLevels.fractionLevel(max - 1, max))
    }

    @Test
    fun `count levels are 0, 1-4, 5-19 and 20+`() {
        val vectors = listOf(
            0 to CountLevel.NONE,
            1 to CountLevel.FEW,
            4 to CountLevel.FEW,
            5 to CountLevel.SEVERAL,
            19 to CountLevel.SEVERAL,
            20 to CountLevel.MANY,
            HealthReportSchema.MAX_COUNT to CountLevel.MANY,
        )
        for ((c, level) in vectors) assertEquals("$c", level, ShadowLevels.countLevel(c))
    }

    @Test
    fun `invalid inputs throw`() {
        assertThrows(IllegalArgumentException::class.java) { ShadowLevels.countLevel(-1) }
        for ((k, n) in listOf(0 to 0, 1 to 0, 2 to 1, -1 to 1, 0 to -1, Int.MIN_VALUE to 1)) {
            assertThrows("($k,$n)", IllegalArgumentException::class.java) { ShadowLevels.fractionLevel(k, n) }
        }
    }
}
