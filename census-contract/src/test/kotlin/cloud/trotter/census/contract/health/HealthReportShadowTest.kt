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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** census#102: the optional `shadow` and `probeRefused` lists of the health report. */
class HealthReportShadowTest {
    private val generation = "0123456789abcdef"
    private val digest = "fedcba9876543210"
    private val rule = "doordash.screen.offer_shadow"
    private val legacy = HealthReportDto(
        "2026-10-01", "doordash", "7.1.0", "1.2.3+abcdef0.dirty", "dev", 1, 1, 1, mapOf("doordash.screen.offer" to 1),
    )
    private val legacyObject = Json.parseToJsonElement(HealthReportSchema.encode(legacy)).jsonObject

    private fun entry(vararg metrics: Pair<String, JsonElement>, ids: Map<String, String> = emptyMap()) = JsonObject(
        mapOf("generation" to generation, "rule" to rule, "digest" to digest).plus(ids).mapValues { JsonPrimitive(it.value) } +
            metrics,
    )
    private fun shadow(vararg entries: JsonObject) = check("shadow" to JsonArray(entries.toList()))
    private fun check(vararg fields: Pair<String, JsonElement>) = HealthReportSchema.check(JsonObject(legacyObject + fields))
    private fun invalid(reason: HealthReportRejection) = HealthReportCheck.Invalid(reason)
    private fun n(value: Int) = JsonPrimitive(value)

    @Test
    fun `a report without the shadow fields encodes byte for byte as before`() {
        assertEquals(
            """{"day":"2026-10-01","platform":"doordash","platformAppVersion":"7.1.0","appVersion":"1.2.3+abcdef0.dirty","rulesetVersion":"dev","admitted":1,"unknown":1,"trips":1,"ruleCounts":{"doordash.screen.offer":1},"parseShortfall":{"doordash.screen.offer":2}}""",
            HealthReportSchema.encode(legacy.copy(parseShortfall = mapOf("doordash.screen.offer" to 2), shadow = null, probeRefused = null)),
        )
    }

    @Test
    fun `shadow entries follow parseShortfall on the wire with their metrics in order, and round trip`() {
        val report = legacy.copy(
            parseShortfall = mapOf("doordash.screen.offer" to 0),
            shadow = listOf(ShadowTallyDto(generation, rule, digest, tied = 3, onUnknown = 1, emptyParse = 0, maskFail = 2)),
            probeRefused = listOf(ShadowRefDto(generation, "doordash.probe.store_banner", digest)),
        )
        val wire = Json.parseToJsonElement(HealthReportSchema.encode(report)).jsonObject
        assertEquals(HealthReportSchema.FIELDS, wire.keys.toList())
        assertEquals(
            HealthReportSchema.SHADOW_REF_FIELDS + HealthReportSchema.SHADOW_METRICS,
            wire.getValue("shadow").jsonArray.single().jsonObject.keys.toList(),
        )
        assertEquals(HealthReportCheck.Valid(report), HealthReportSchema.check(wire))
        // An unavailable metric is left off the wire, never sent as null.
        val partial = legacy.copy(shadow = listOf(ShadowTallyDto(generation, rule, digest, maskFail = 0)))
        assertTrue(HealthReportSchema.encode(partial).endsWith(""""shadow":[{"generation":"$generation","rule":"$rule","digest":"$digest","maskFail":0}]}"""))
    }

    @Test
    fun `every metric is a JSON integer over the fixed sample and nothing else`() {
        for (metric in listOf("emptyParse", "maskFail")) {
            for (value in 0..HealthReportSchema.PARSE_SAMPLE) assertTrue(shadow(entry(metric to n(value))) is HealthReportCheck.Valid)
            for (bad in listOf(n(21), n(-1), JsonPrimitive("1"), JsonPrimitive(1.5), JsonPrimitive(true), JsonNull)) {
                assertEquals("$metric=$bad", invalid(HealthReportRejection.BAD_COUNT), shadow(entry(metric to bad)))
            }
        }
        assertTrue(shadow(entry("tied" to n(20), "onUnknown" to n(20))) is HealthReportCheck.Valid)
        assertEquals(invalid(HealthReportRejection.BAD_COUNT), shadow(entry("tied" to n(21), "onUnknown" to n(0))))
        assertEquals(invalid(HealthReportRejection.BAD_COUNT), shadow(entry("tied" to JsonNull, "onUnknown" to n(0))))
    }

    @Test
    fun `tied and onUnknown come as a pair, and an entry carries at least one metric`() {
        assertEquals(invalid(HealthReportRejection.BAD_COUNT), shadow(entry("tied" to n(1))))
        assertEquals(invalid(HealthReportRejection.BAD_COUNT), shadow(entry("onUnknown" to n(1), "maskFail" to n(0))))
        assertEquals(invalid(HealthReportRejection.BAD_COUNT), shadow(entry()))
        val partial = shadow(entry("maskFail" to n(4))) as HealthReportCheck.Valid
        assertEquals(listOf(ShadowTallyDto(generation, rule, digest, maskFail = 4)), partial.report.shadow)
    }

    @Test
    fun `identifiers are 16 lowercase hex and a bounded rule id under the report's own platform`() {
        val mask = "maskFail" to n(0)
        for (bad in listOf(generation.take(15), generation + "0", generation.uppercase(), "g".repeat(16))) {
            assertEquals(bad, invalid(HealthReportRejection.BAD_RULE_ID), shadow(entry(mask, ids = mapOf("generation" to bad))))
            assertEquals(bad, invalid(HealthReportRejection.BAD_RULE_ID), shadow(entry(mask, ids = mapOf("digest" to bad))))
        }
        val longest = "doordash." + "b".repeat(HealthReportSchema.MAX_PARSE_RULE_ID_LENGTH - "doordash.".length)
        assertTrue(shadow(entry(mask, ids = mapOf("rule" to longest))) is HealthReportCheck.Valid)
        for (bad in listOf(longest + "b", "uber.screen.offer", "doordashx.screen.offer", "doordash", "doordash..offer")) {
            assertEquals(bad, invalid(HealthReportRejection.BAD_RULE_ID), shadow(entry(mask, ids = mapOf("rule" to bad))))
        }
        assertEquals(invalid(HealthReportRejection.BAD_RULE_ID), shadow(JsonObject(entry(mask) - "digest")))
    }

    @Test
    fun `each list is unique by generation, rule and digest, and bounded`() {
        assertEquals(invalid(HealthReportRejection.BAD_RULE_ID), shadow(entry("maskFail" to n(0)), entry("maskFail" to n(1))))
        val otherGeneration = entry("maskFail" to n(0), ids = mapOf("generation" to "00000000000000aa"))
        assertEquals(2, (shadow(entry("maskFail" to n(0)), otherGeneration) as HealthReportCheck.Valid).report.shadow?.size)
        val ref = JsonObject(mapOf("generation" to generation, "rule" to "doordash.probe.p", "digest" to digest).mapValues { JsonPrimitive(it.value) })
        assertEquals(invalid(HealthReportRejection.BAD_RULE_ID), check("probeRefused" to JsonArray(listOf(ref, ref))))
        val many = (0..HealthReportSchema.MAX_SHADOW_ENTRIES).map { entry("maskFail" to n(0), ids = mapOf("rule" to "doordash.screen.c$it")) }
        assertEquals(invalid(HealthReportRejection.BAD_COUNT), shadow(*many.toTypedArray()))
        assertTrue(shadow(*many.drop(1).toTypedArray()) is HealthReportCheck.Valid)
        for (shape in listOf(JsonNull, JsonObject(emptyMap()), JsonArray(listOf(n(1))))) {
            assertEquals(invalid(HealthReportRejection.BAD_COUNT), check("shadow" to shape))
            assertEquals(invalid(HealthReportRejection.BAD_COUNT), check("probeRefused" to shape))
        }
    }

    @Test
    fun `unknown nested keys are refused before any other check`() {
        val extra = JsonObject(entry("maskFail" to n(0)) + ("visits" to n(40)))
        assertEquals(invalid(HealthReportRejection.UNKNOWN_FIELD), check("shadow" to JsonArray(listOf(extra)), "day" to JsonPrimitive("bad")))
        val ref = JsonObject(mapOf("generation" to generation, "rule" to "doordash.probe.p", "digest" to digest, "tied" to "0").mapValues { JsonPrimitive(it.value) })
        assertEquals(invalid(HealthReportRejection.UNKNOWN_FIELD), check("probeRefused" to JsonArray(listOf(ref))))
    }

    @Test
    fun `a report too large drops shadow, then probeRefused, then parseShortfall, before it is refused`() {
        val full = legacy.copy(
            parseShortfall = mapOf("doordash.screen.offer" to 1),
            shadow = (0 until 8).map { ShadowTallyDto(generation, "doordash.screen.c$it", digest, maskFail = 0) },
            probeRefused = (0 until 8).map { ShadowRefDto(generation, "doordash.probe.p$it", digest) },
        )
        val forms = listOf(
            full,
            full.copy(shadow = null),
            full.copy(shadow = null, probeRefused = null),
            full.copy(shadow = null, probeRefused = null, parseShortfall = null),
        )
        // Pad ruleCounts with one zero count so that form k is exactly at the byte bound: every larger form is over it.
        fun padded(report: HealthReportDto, form: HealthReportDto, bytes: Int): HealthReportDto {
            val stub = HealthReportSchema.encode(form.copy(ruleCounts = form.ruleCounts + ("doordash.p" to 0))).length
            val padding = "doordash.p" + "a".repeat(bytes - stub)
            return report.copy(ruleCounts = report.ruleCounts + (padding to 0))
        }
        for ((k, form) in forms.withIndex()) {
            val report = padded(full, form, HealthReportSchema.MAX_REPORT_BYTES)
            val expected = forms[k].copy(ruleCounts = report.ruleCounts)
            assertEquals("form $k", HealthReportSchema.encode(expected), HealthReportSchema.encodeFitting(report))
        }
        assertNull(HealthReportSchema.encodeFitting(padded(full, forms.last(), HealthReportSchema.MAX_REPORT_BYTES + 1)))
        assertFalse(HealthReportSchema.fits(HealthReportSchema.encode(legacy.copy(shadow = List(33) { ShadowTallyDto(generation, "doordash.s.c$it", digest, maskFail = 0) }))))
    }
}
