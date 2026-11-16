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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class HealthReportSchemaTest {
    private val legacy = HealthReportDto(
        "2026-10-01", "doordash", "7.1.0", "1.2.3+abcdef0.dirty", "dev", 1, 1, 1, mapOf("doordash.screen.offer" to 1),
    )

    @Test
    fun `a report without parse counts encodes exactly as the nine-key report before census 64`() {
        val raw = HealthReportSchema.encode(legacy)
        assertEquals(
            """{"day":"2026-10-01","platform":"doordash","platformAppVersion":"7.1.0","appVersion":"1.2.3+abcdef0.dirty","rulesetVersion":"dev","admitted":1,"unknown":1,"trips":1,"ruleCounts":{"doordash.screen.offer":1}}""",
            raw,
        )
        assertEquals(HealthReportCheck.Valid(legacy), HealthReportSchema.check(Json.parseToJsonElement(raw)))
        assertTrue(HealthReportSchema.fits(raw))
    }

    @Test
    fun `parse counts follow ruleCounts on the wire as one sample count per rule id, and round trip`() {
        val report = legacy.copy(parseShortfall = mapOf("doordash.screen.offer" to 2))
        val raw = HealthReportSchema.encode(report)
        assertTrue(raw.endsWith(""","parseShortfall":{"doordash.screen.offer":2}}"""))
        assertEquals(HealthReportSchema.FIELDS.takeWhile { it != "shadowDaily" }, Json.parseToJsonElement(raw).jsonObject.keys.toList())
        assertEquals(HealthReportCheck.Valid(report), HealthReportSchema.check(Json.parseToJsonElement(raw)))
    }

    @Test
    fun `every conformance vector gets its verdict and every accepted one re-encodes byte for byte`() {
        // Included-build and repository-root invocations both resolve the same checked-in vectors.
        val lines = sequenceOf(File("conformance/health-report-vectors.jsonl"), File("census-contract/conformance/health-report-vectors.jsonl"))
            .first { it.isFile }.readLines().filter { it.isNotBlank() }
        val verdicts = mutableSetOf<String>()
        for (line in lines) {
            val row = Json.parseToJsonElement(line).jsonObject
            val name = row.getValue("name").jsonPrimitive.content
            val verdict = row.getValue("verdict").jsonPrimitive.content
            val report = row.getValue("report")
            verdicts += verdict
            when (val result = HealthReportSchema.check(report)) {
                is HealthReportCheck.Valid -> {
                    assertEquals(name, "accepted", verdict)
                    assertEquals(name, report.toString(), HealthReportSchema.encode(result.report))
                    assertTrue(name, HealthReportSchema.fits(report.toString()))
                }
                is HealthReportCheck.Invalid -> assertEquals(name, verdict, result.reason.wire)
            }
        }
        assertEquals(setOf("accepted", "bad_count", "bad_rule_id", "unknown_field"), verdicts)
    }

    @Test
    fun `the receiver's policy decides the day window and the platform`() {
        val element = Json.parseToJsonElement(HealthReportSchema.encode(legacy))
        val day = LocalDate.parse(legacy.day)
        assertEquals(invalid(HealthReportRejection.STALE_DAY), HealthReportSchema.check(element, dayAccepted = { it != day }))
        assertEquals(invalid(HealthReportRejection.BAD_PLATFORM), HealthReportSchema.check(element, platformAccepted = { it == "uber" }))
        assertEquals(invalid(HealthReportRejection.BAD_ITEM), HealthReportSchema.check(JsonArray(emptyList())))
        val obj = element.jsonObject
        assertEquals(invalid(HealthReportRejection.BAD_DAY), HealthReportSchema.check(JsonObject(obj + ("day" to JsonPrimitive("2026-02-30")))))
        assertEquals(invalid(HealthReportRejection.BAD_VERSION), HealthReportSchema.check(JsonObject(obj - "appVersion")))
    }

    @Test
    fun `ruleCounts keep their bounds and their sum stays within admitted`() {
        val obj = Json.parseToJsonElement(HealthReportSchema.encode(legacy)).jsonObject
        fun with(key: String, value: JsonElement) = HealthReportSchema.check(JsonObject(obj + (key to value)))
        assertEquals(invalid(HealthReportRejection.BAD_COUNT), with("ruleCounts", JsonObject(mapOf("a.b" to JsonPrimitive(2)))))
        assertEquals(invalid(HealthReportRejection.BAD_RULE_ID), with("ruleCounts", JsonObject(mapOf("a..b" to JsonPrimitive(0)))))
        assertEquals(
            invalid(HealthReportRejection.BAD_COUNT),
            with("ruleCounts", JsonObject((0..HealthReportSchema.MAX_RULE_COUNTS).associate { "a.b$it" to JsonPrimitive(0) })),
        )
        assertEquals(
            invalid(HealthReportRejection.TOO_LARGE),
            with("ruleCounts", JsonObject((0..100).associate { "a.${"b".repeat(90)}$it" to JsonPrimitive(0) })),
        )
    }

    @Test
    fun `fits refuses too many rules or too many UTF-8 bytes`() {
        val many = HealthReportSchema.encode(legacy.copy(admitted = 600, ruleCounts = (1..600).associate { "a.r$it" to 1 }))
        assertTrue(many.toByteArray().size < HealthReportSchema.MAX_REPORT_BYTES)
        assertFalse(HealthReportSchema.fits(many))
        val parse = (0..HealthReportSchema.MAX_PARSE_SHORTFALL_RULES).associate { "a.r$it" to 0 }
        assertFalse(HealthReportSchema.fits(HealthReportSchema.encode(legacy.copy(parseShortfall = parse))))
        assertTrue(HealthReportSchema.fits(HealthReportSchema.encode(legacy.copy(parseShortfall = parse - "a.r0"))))
        assertFalse(HealthReportSchema.fits(HealthReportSchema.encode(legacy.copy(ruleCounts = mapOf("a.${"x".repeat(8500)}" to 1)))))
        val unicode = HealthReportSchema.encode(legacy.copy(ruleCounts = mapOf("a.${"é".repeat(4100)}" to 1)))
        assertTrue(unicode.length < HealthReportSchema.MAX_REPORT_BYTES)
        assertFalse(HealthReportSchema.fits(unicode))
    }

    @Test
    fun `a parse count is a shortfall tally of the fixed sample, never an attempt total`() {
        val obj = Json.parseToJsonElement(HealthReportSchema.encode(legacy)).jsonObject
        fun with(rule: String, value: JsonElement) =
            HealthReportSchema.check(JsonObject(obj + ("parseShortfall" to JsonObject(mapOf(rule to value)))))
        assertEquals(20, HealthReportSchema.PARSE_SAMPLE)
        for (value in 0..HealthReportSchema.PARSE_SAMPLE) {
            assertTrue(with("a.b", JsonPrimitive(value)) is HealthReportCheck.Valid)
        }
        assertEquals(invalid(HealthReportRejection.BAD_COUNT), with("a.b", JsonPrimitive(HealthReportSchema.PARSE_SAMPLE + 1)))
        assertEquals(invalid(HealthReportRejection.BAD_COUNT), with("a.b", JsonObject(mapOf("shortfall" to JsonPrimitive(0), "eligible" to JsonPrimitive(40)))))
        val longest = "a." + "b".repeat(HealthReportSchema.MAX_PARSE_RULE_ID_LENGTH - 2)
        assertTrue(with(longest, JsonPrimitive(0)) is HealthReportCheck.Valid)
        assertEquals(invalid(HealthReportRejection.BAD_RULE_ID), with(longest + "b", JsonPrimitive(0)))
        // The length bound is the new field's own: a legacy rule count keeps its id unbounded but for the report size.
        val legacyLong = JsonObject(obj + ("ruleCounts" to JsonObject(mapOf(longest + "b" to JsonPrimitive(1)))))
        assertTrue(HealthReportSchema.check(legacyLong) is HealthReportCheck.Valid)
    }

    @Test
    fun `token grammars pin the server's version and rule id shapes`() {
        for (version in listOf("8.99.20", "4.599.10004")) assertTrue(WireTokenGrammars.platformAppVersion.matches(version))
        for (version in listOf("8.97.8|prod", "unknown", "")) assertFalse(WireTokenGrammars.platformAppVersion.matches(version))
        for (version in listOf("dev", "corpus", "v1.2.3-rc1")) assertTrue(WireTokenGrammars.rulesetReleaseTag.matches(version))
        for (version in listOf("0.230.0+a41f0965", "1.2.3+nogit", "test")) assertTrue(WireTokenGrammars.appVersion.matches(version))
        assertFalse(WireTokenGrammars.appVersion.matches("0.230.0+zz"))
        for (rule in listOf("a.b", "doordash.screen.offer", "a.b.c.d.e")) assertTrue(WireTokenGrammars.ruleId.matches(rule))
        for (rule in listOf("one", "DoorDash.screen", "a.b.c.d.e.f", "a..b", "a.b-")) assertFalse(WireTokenGrammars.ruleId.matches(rule))
    }

    private fun invalid(reason: HealthReportRejection) = HealthReportCheck.Invalid(reason)
}
