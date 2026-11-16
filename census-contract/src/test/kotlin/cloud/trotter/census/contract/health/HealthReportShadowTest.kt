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

/** census#104: the optional `shadowDaily` levels and census#102's `probeRefused` list of the health report. */
class HealthReportShadowTest {
    private val generation = "0123456789abcdef"
    private val digest = "fedcba9876543210"
    private val rule = "doordash.screen.offer_shadow"
    private val legacy = HealthReportDto(
        "2026-10-01", "doordash", "7.1.0", "1.2.3+abcdef0.dirty", "dev", 1, 1, 1, mapOf("doordash.screen.offer" to 1),
    )
    private val legacyObject = Json.parseToJsonElement(HealthReportSchema.encode(legacy)).jsonObject

    /** A visited entry: `visits` 1-4, `evaluated` 5-19, the presence pair, then [fields] over them. */
    private fun entry(vararg fields: Pair<String, JsonElement>, ids: Map<String, String> = emptyMap()) = JsonObject(
        mapOf("generation" to generation, "rule" to rule, "digest" to digest).plus(ids).mapValues { JsonPrimitive(it.value) } +
            mapOf("visits" to s("1-4"), "evaluated" to s("5-19"), "tied" to s("none"), "onUnknown" to s("some")) + fields,
    )
    private fun unvisited(vararg fields: Pair<String, JsonElement>) =
        JsonObject(entry().filterKeys { it !in HealthReportSchema.SHADOW_METRICS } + ("visits" to s("0")) + fields)
    private fun shadowDaily(vararg entries: JsonObject) = check("shadowDaily" to JsonArray(entries.toList()))
    private fun check(vararg fields: Pair<String, JsonElement>) = HealthReportSchema.check(JsonObject(legacyObject + fields))
    private fun invalid(reason: HealthReportRejection) = HealthReportCheck.Invalid(reason)
    private fun s(value: String) = JsonPrimitive(value)
    private fun n(value: Int) = JsonPrimitive(value)
    private fun daily(rule: String = this.rule, visits: CountLevel = CountLevel.FEW, generation: String = this.generation) =
        ShadowDailyDto(
            generation, rule, digest, visits, CountLevel.MANY,
            tied = FractionLevel.NONE.takeIf { visits != CountLevel.NONE },
            onUnknown = FractionLevel.ALL.takeIf { visits != CountLevel.NONE },
        )

    @Test
    fun `a report without the shadow fields encodes byte for byte as before`() {
        assertEquals(
            """{"day":"2026-10-01","platform":"doordash","platformAppVersion":"7.1.0","appVersion":"1.2.3+abcdef0.dirty","rulesetVersion":"dev","admitted":1,"unknown":1,"trips":1,"ruleCounts":{"doordash.screen.offer":1},"parseShortfall":{"doordash.screen.offer":2}}""",
            HealthReportSchema.encode(
                legacy.copy(parseShortfall = mapOf("doordash.screen.offer" to 2), shadowDaily = null, shadowDailyTruncated = null, probeRefused = null),
            ),
        )
    }

    @Test
    fun `shadowDaily entries follow parseShortfall on the wire with their levels in order, and round trip`() {
        val report = legacy.copy(
            parseShortfall = mapOf("doordash.screen.offer" to 0),
            shadowDaily = listOf(
                ShadowDailyDto(
                    generation, rule, digest, CountLevel.MANY, CountLevel.MANY,
                    FractionLevel.SOME, FractionLevel.HALF_OR_MORE, FractionLevel.NONE, FractionLevel.ALL,
                ),
            ),
            shadowDailyTruncated = true,
            probeRefused = listOf(ShadowRefDto(generation, "doordash.probe.store_banner", digest)),
        )
        val raw = HealthReportSchema.encode(report)
        val wire = Json.parseToJsonElement(raw).jsonObject
        assertEquals(HealthReportSchema.FIELDS, wire.keys.toList())
        assertEquals(
            HealthReportSchema.SHADOW_REF_FIELDS + HealthReportSchema.SHADOW_COUNTS + HealthReportSchema.SHADOW_METRICS,
            wire.getValue("shadowDaily").jsonArray.single().jsonObject.keys.toList(),
        )
        assertTrue(raw, raw.contains(""""visits":"20+","evaluated":"20+","tied":"some","onUnknown":"half_or_more","emptyParse":"none","maskFail":"all"}],"shadowDailyTruncated":true,"""))
        assertEquals(HealthReportCheck.Valid(report), HealthReportSchema.check(wire))
    }

    @Test
    fun `every level encodes as its own wire token`() {
        for (level in CountLevel.entries) {
            val raw = HealthReportSchema.encode(legacy.copy(shadowDaily = listOf(daily().copy(evaluated = level))))
            assertTrue(level.wire, raw.contains(""""evaluated":"${level.wire}""""))
            assertEquals(level, CountLevel.fromWire(level.wire))
        }
        for (level in FractionLevel.entries) {
            val raw = HealthReportSchema.encode(legacy.copy(shadowDaily = listOf(daily().copy(maskFail = level))))
            assertTrue(level.wire, raw.contains(""""maskFail":"${level.wire}""""))
            assertEquals(level, FractionLevel.fromWire(level.wire))
        }
        assertEquals(listOf("0", "1-4", "5-19", "20+"), CountLevel.entries.map { it.wire })
        assertEquals(listOf("none", "some", "half_or_more", "all"), FractionLevel.entries.map { it.wire })
    }

    @Test
    fun `a zero-visit entry carries only its evaluated level`() {
        for (evaluated in CountLevel.entries) {
            val result = shadowDaily(unvisited("evaluated" to s(evaluated.wire))) as HealthReportCheck.Valid
            assertEquals(
                listOf(ShadowDailyDto(generation, rule, digest, CountLevel.NONE, evaluated)),
                result.report.shadowDaily,
            )
        }
        for (metric in HealthReportSchema.SHADOW_METRICS) {
            assertEquals(metric, invalid(HealthReportRejection.BAD_COUNT), shadowDaily(unvisited(metric to s("none"))))
        }
        assertEquals(invalid(HealthReportRejection.BAD_COUNT), shadowDaily(unvisited("tied" to s("none"), "onUnknown" to s("none"))))
    }

    @Test
    fun `tied and onUnknown come as a pair on every visited entry, and the other metrics are optional`() {
        for (visits in CountLevel.entries - CountLevel.NONE) {
            assertTrue(visits.wire, shadowDaily(entry("visits" to s(visits.wire))) is HealthReportCheck.Valid)
            assertEquals(invalid(HealthReportRejection.BAD_COUNT), shadowDaily(JsonObject(entry("visits" to s(visits.wire)) - "onUnknown")))
            assertEquals(invalid(HealthReportRejection.BAD_COUNT), shadowDaily(JsonObject(entry("visits" to s(visits.wire)) - "tied")))
            assertEquals(
                invalid(HealthReportRejection.BAD_COUNT),
                shadowDaily(JsonObject(entry("visits" to s(visits.wire), "maskFail" to s("all")) - "tied" - "onUnknown")),
            )
        }
        val full = shadowDaily(entry("emptyParse" to s("half_or_more"), "maskFail" to s("some"))) as HealthReportCheck.Valid
        assertEquals(FractionLevel.HALF_OR_MORE, full.report.shadowDaily?.single()?.emptyParse)
        assertEquals(FractionLevel.SOME, full.report.shadowDaily?.single()?.maskFail)
    }

    @Test
    fun `every level is one of its exact lowercase tokens and nothing else`() {
        val notCount = listOf(s("1"), s("20"), s("21+"), s("1-5"), s("NONE"), s("none"), s(""), n(0), n(3), JsonPrimitive(true), JsonNull)
        for (key in HealthReportSchema.SHADOW_COUNTS) {
            for (bad in notCount) assertEquals("$key=$bad", invalid(HealthReportRejection.BAD_COUNT), shadowDaily(entry(key to bad)))
            assertEquals(key, invalid(HealthReportRejection.BAD_COUNT), shadowDaily(JsonObject(entry() - key)))
        }
        val notFraction = listOf(s("None"), s("ALL"), s("half"), s("half-or-more"), s("0"), s(""), n(0), n(1), JsonPrimitive(0.5), JsonPrimitive(false), JsonNull)
        for (key in HealthReportSchema.SHADOW_METRICS) {
            for (bad in notFraction) assertEquals("$key=$bad", invalid(HealthReportRejection.BAD_COUNT), shadowDaily(entry(key to bad)))
        }
    }

    @Test
    fun `identifiers are 16 lowercase hex and a bounded rule id under the report's own platform`() {
        for (bad in listOf(generation.take(15), generation + "0", generation.uppercase(), "g".repeat(16))) {
            assertEquals(bad, invalid(HealthReportRejection.BAD_RULE_ID), shadowDaily(entry(ids = mapOf("generation" to bad))))
            assertEquals(bad, invalid(HealthReportRejection.BAD_RULE_ID), shadowDaily(entry(ids = mapOf("digest" to bad))))
        }
        val longest = "doordash." + "b".repeat(HealthReportSchema.MAX_PARSE_RULE_ID_LENGTH - "doordash.".length)
        assertTrue(shadowDaily(entry(ids = mapOf("rule" to longest))) is HealthReportCheck.Valid)
        for (bad in listOf(longest + "b", "uber.screen.offer", "doordashx.screen.offer", "doordash", "doordash..offer")) {
            assertEquals(bad, invalid(HealthReportRejection.BAD_RULE_ID), shadowDaily(entry(ids = mapOf("rule" to bad))))
        }
        assertEquals(invalid(HealthReportRejection.BAD_RULE_ID), shadowDaily(JsonObject(entry() - "digest")))
    }

    @Test
    fun `each list is unique by generation, rule and digest, and bounded`() {
        assertEquals(invalid(HealthReportRejection.BAD_RULE_ID), shadowDaily(entry(), entry("maskFail" to s("all"))))
        val otherGeneration = entry(ids = mapOf("generation" to "00000000000000aa"))
        assertEquals(2, (shadowDaily(entry(), otherGeneration) as HealthReportCheck.Valid).report.shadowDaily?.size)
        val ref = JsonObject(mapOf("generation" to generation, "rule" to "doordash.probe.p", "digest" to digest).mapValues { JsonPrimitive(it.value) })
        assertEquals(invalid(HealthReportRejection.BAD_RULE_ID), check("probeRefused" to JsonArray(listOf(ref, ref))))
        val many = (0..HealthReportSchema.MAX_SHADOW_ENTRIES).map { entry(ids = mapOf("rule" to "doordash.screen.c$it")) }
        assertEquals(invalid(HealthReportRejection.BAD_COUNT), shadowDaily(*many.toTypedArray()))
        assertTrue(shadowDaily(*many.drop(1).toTypedArray()) is HealthReportCheck.Valid)
        for (shape in listOf(JsonNull, JsonObject(emptyMap()), JsonArray(listOf(n(1))))) {
            assertEquals(invalid(HealthReportRejection.BAD_COUNT), check("shadowDaily" to shape))
            assertEquals(invalid(HealthReportRejection.BAD_COUNT), check("probeRefused" to shape))
        }
    }

    @Test
    fun `shadowDailyTruncated is only the literal true, and only beside shadowDaily`() {
        val list = "shadowDaily" to JsonArray(listOf(entry()))
        val truncated = check(list, "shadowDailyTruncated" to JsonPrimitive(true)) as HealthReportCheck.Valid
        assertEquals(true, truncated.report.shadowDailyTruncated)
        assertEquals(true, (check("shadowDaily" to JsonArray(emptyList()), "shadowDailyTruncated" to JsonPrimitive(true)) as HealthReportCheck.Valid).report.shadowDailyTruncated)
        assertNull((check(list) as HealthReportCheck.Valid).report.shadowDailyTruncated)
        for (bad in listOf(JsonPrimitive(false), s("true"), n(1), JsonNull, JsonArray(emptyList()))) {
            assertEquals("$bad", invalid(HealthReportRejection.BAD_COUNT), check(list, "shadowDailyTruncated" to bad))
        }
        assertEquals(invalid(HealthReportRejection.BAD_COUNT), check("shadowDailyTruncated" to JsonPrimitive(true)))
    }

    @Test
    fun `the retired census 102 shadow field is an unknown key`() {
        val tally = JsonObject(
            mapOf("generation" to s(generation), "rule" to s(rule), "digest" to s(digest), "tied" to n(3), "onUnknown" to n(1)),
        )
        assertEquals(invalid(HealthReportRejection.UNKNOWN_FIELD), check("shadow" to JsonArray(listOf(tally))))
        assertEquals(invalid(HealthReportRejection.UNKNOWN_FIELD), check("shadow" to JsonArray(emptyList())))
        assertFalse("shadow" in HealthReportSchema.FIELDS)
    }

    @Test
    fun `unknown nested keys are refused before any other check`() {
        val extra = JsonObject(entry() + ("visitCount" to n(40)))
        assertEquals(invalid(HealthReportRejection.UNKNOWN_FIELD), check("shadowDaily" to JsonArray(listOf(extra)), "day" to s("bad")))
        val ref = JsonObject(mapOf("generation" to generation, "rule" to "doordash.probe.p", "digest" to digest, "tied" to "none").mapValues { JsonPrimitive(it.value) })
        assertEquals(invalid(HealthReportRejection.UNKNOWN_FIELD), check("probeRefused" to JsonArray(listOf(ref))))
    }

    @Test
    fun `a report too large drops shadowDaily with its truncation flag, then probeRefused, then parseShortfall`() {
        val full = legacy.copy(
            parseShortfall = mapOf("doordash.screen.offer" to 1),
            shadowDaily = (0 until 8).map { daily(rule = "doordash.screen.c$it") },
            shadowDailyTruncated = true,
            probeRefused = (0 until 8).map { ShadowRefDto(generation, "doordash.probe.p$it", digest) },
        )
        val noShadow = full.copy(shadowDaily = null, shadowDailyTruncated = null)
        val forms = listOf(
            full,
            noShadow,
            noShadow.copy(probeRefused = null),
            noShadow.copy(probeRefused = null, parseShortfall = null),
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
        val dropped = HealthReportSchema.encodeFitting(padded(full, forms[1], HealthReportSchema.MAX_REPORT_BYTES))!!
        assertFalse(dropped.contains("shadowDailyTruncated"))
        assertTrue(dropped.contains("probeRefused"))
        assertNull(HealthReportSchema.encodeFitting(padded(full, forms.last(), HealthReportSchema.MAX_REPORT_BYTES + 1)))
        assertFalse(HealthReportSchema.fits(HealthReportSchema.encode(legacy.copy(shadowDaily = List(33) { daily(rule = "doordash.s.c$it") }))))
    }
}
