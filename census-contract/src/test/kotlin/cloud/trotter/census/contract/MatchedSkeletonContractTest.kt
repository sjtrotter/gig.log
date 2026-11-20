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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/** `uinode.matched.v1` (#1573, census#69): a v2 body plus a required `ruleId`, under its own schema id. */
class MatchedSkeletonContractTest {

    private val rule = "doordash.screen.delivery_summary_expanded"
    private val root = UiSkeletonNodeDto(
        className = "android.widget.ScrollView",
        id = "com.example.app:id/summary",
        children = listOf(
            UiSkeletonNodeDto(className = "android.widget.TextView", text = mapOf("text" to TextSlot("0123456789abcdef", "words:2"))),
            UiSkeletonNodeDto(className = "android.widget.TextView", text = mapOf("text" to TextSlot.ofShape(ShapeTemplate.MONEY))),
            UiSkeletonNodeDto(className = "android.widget.Button", isClickable = true, hasClickAction = true),
        ),
    )

    private fun screen(schemaId: String, ruleId: String? = null, node: UiSkeletonNodeDto = root) = UiSkeletonDto(
        schemaId = schemaId, hashDomain = 1, filterRev = 1, fingerprint = requireNotNull(CensusFingerprint.of(node)),
        platform = "doordash", engineVersion = 1, day = "2026-11-20", ruleId = ruleId, root = node,
    )

    private fun matched(ruleId: String? = rule) = screen(SkeletonSchema.SCHEMA_ID_MATCHED, ruleId)

    private fun rejects(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    @Test
    fun `C1 v1 and v2 items encode as before, with no ruleId key, across the whole golden corpus`() {
        val v2 = screen(SkeletonSchema.SCHEMA_ID_V2, node = UiSkeletonNodeDto())
        assertEquals(
            """{"schemaId":"uinode.skeleton.v2","hashDomain":1,"filterRev":1,"fingerprint":"${v2.fingerprint}","platform":"doordash","engineVersion":1,"day":"2026-11-20","root":{}}""",
            SkeletonSchema.serialize(v2),
        )
        var screens = 0
        conformance("skeletons.jsonl.gz").forEach { line ->
            val skeleton = Json.parseToJsonElement(line).jsonObject["skeleton"] as? JsonObject ?: return@forEach
            val decoded = CensusSkeletonSchema.deserialize(skeleton.toString())
            assertEquals(skeleton.toString(), CensusSkeletonSchema.serialize(decoded))
            assertFalse("ruleId" in skeleton)
            if (decoded is UiSkeletonDto) {
                screens++
                assertNull(decoded.ruleId)
                assertFalse(decoded.isMatched)
            }
        }
        assertTrue("the golden carries screens", screens > 0)
    }

    @Test
    fun `C2 a matched item decodes with its rule id and re-encodes byte for byte`() {
        val item = matched()
        val wire = SkeletonSchema.serialize(item)
        assertTrue(wire, wire.contains(""""day":"2026-11-20","ruleId":"$rule","root":"""))
        val decoded = CensusSkeletonSchema.deserialize(wire) as UiSkeletonDto
        assertEquals(rule, decoded.ruleId)
        assertEquals(item, decoded)
        assertEquals(wire, CensusSkeletonSchema.serialize(decoded))
        assertTrue(decoded.isMatched)
        assertEquals(SkeletonVersion.V2, decoded.version)
        assertEquals(SkeletonKind.SCREEN, decoded.kind)
    }

    @Test
    fun `C3 the fingerprint ignores the schema and the rule id`() {
        val v2 = screen(SkeletonSchema.SCHEMA_ID_V2)
        val item = matched()
        assertEquals(CensusFingerprint.of(v2), CensusFingerprint.of(item))
        assertEquals(CensusFingerprint.of(v2.root), CensusFingerprint.of(item))
        assertEquals(CensusFingerprint.of(item), CensusFingerprint.of(matched("doordash.screen.offer")))
    }

    @Test
    fun `C4 a rule id rides only the matched schema, as a rule of the item's platform, and never projects down`() {
        val v2Wire = SkeletonSchema.serialize(screen(SkeletonSchema.SCHEMA_ID_V2))
        val v1Wire = SkeletonSchema.serialize(screen(SkeletonSchema.SCHEMA_ID_V2).toV1())
        val anchor = "\"day\":\"2026-11-20\","
        listOf(
            v2Wire.replace(anchor, "$anchor\"ruleId\":\"$rule\","),
            v2Wire.replace(anchor, "$anchor\"ruleId\":null,"),
            v1Wire.replace(anchor, "$anchor\"ruleId\":\"$rule\","),
            v1Wire.replace(anchor, "$anchor\"ruleId\":null,"),
            SkeletonSchema.serialize(matched()).replace("\"ruleId\":\"$rule\",", ""),
        ).forEach { bad ->
            rejects { SkeletonSchema.deserialize(bad) }
            rejects { CensusSkeletonSchema.deserialize(bad) }
        }
        rejects { screen(SkeletonSchema.SCHEMA_ID_V2, ruleId = rule) }
        rejects { screen(SkeletonSchema.SCHEMA_ID, ruleId = rule, node = UiSkeletonNodeDto()) }
        rejects { matched(ruleId = null) }
        listOf("doordash.screen.Bad", "uber.screen.offer", "doordash." + "a".repeat(88), "doordash", "doordash.", "").forEach { bad ->
            rejects { matched(bad) }
            val wire = SkeletonSchema.serialize(matched()).replace("\"ruleId\":\"$rule\"", "\"ruleId\":\"$bad\"")
            rejects { CensusSkeletonSchema.deserialize(wire) }
        }
        val longest = "doordash." + "a".repeat(87)
        assertEquals(WireTokenGrammars.MAX_RULE_ID_LENGTH, longest.length)
        assertEquals(longest, (CensusSkeletonSchema.deserialize(SkeletonSchema.serialize(matched(longest))) as UiSkeletonDto).ruleId)
        assertThrows(IllegalStateException::class.java) { matched().toV1() }
        assertEquals("bad_rule_id", SkeletonRejectionReason.BAD_RULE_ID.wire)
        assertEquals(SkeletonRejectionReason.BAD_RULE_ID, SkeletonRejectionReason.fromWire("bad_rule_id"))
    }

    @Test
    fun `C4 the one rule-id owner holds the length, the grammar and the platform prefix`() {
        assertTrue(WireTokenGrammars.isRuleIdFor("doordash", rule))
        assertTrue(WireTokenGrammars.isRuleIdFor("uber", "uber.screen.offer"))
        assertFalse(WireTokenGrammars.isRuleIdFor("doordash", "uber.screen.offer"))
        assertFalse(WireTokenGrammars.isRuleIdFor("door", rule))
        assertFalse(WireTokenGrammars.isRuleIdFor("_unknown", "_unknown.screen.offer"))
        assertFalse(WireTokenGrammars.isRuleIdFor("doordash", "doordash." + "a".repeat(88)))
        assertTrue(WireTokenGrammars.isRuleIdFor("doordash", "doordash." + "a".repeat(87)))
    }

    @Test
    fun `C5 the matched id follows the version ladder and leaves it alone`() {
        assertEquals(
            listOf("uinode.skeleton.v1", "notification.skeleton.v1", "uinode.skeleton.v2", "notification.skeleton.v2", "uinode.matched.v1"),
            CensusSkeletonSchema.SUPPORTED_SCHEMA_IDS.take(5),
        )
        assertNull(SkeletonVersion.ofScreenSchema(SkeletonSchema.SCHEMA_ID_MATCHED))
        assertEquals(SkeletonVersion.V2, SkeletonVersion.newestScreen(CensusSkeletonSchema.SUPPORTED_SCHEMA_IDS))
        assertNull(SkeletonVersion.newestScreen(listOf(SkeletonSchema.SCHEMA_ID_MATCHED)))
    }

    @Test
    fun `C6 every matched vector gets its contract verdict`() {
        val rows = conformance("matched-vectors.jsonl").map { Json.parseToJsonElement(it).jsonObject }
        val names = mutableSetOf<String>()
        val servers = mutableSetOf<String>()
        for (row in rows) {
            val name = row.getValue("name").jsonPrimitive.content
            assertTrue("duplicate vector $name", names.add(name))
            val item = row.getValue("item").jsonObject
            val contract = row.getValue("contract").jsonPrimitive.content
            val server = row.getValue("server").jsonPrimitive.content
            servers += server
            assertEquals(name, server == "accepted", contract == "accept")
            if (server != "accepted") assertTrue(name, SkeletonRejectionReason.fromWire(server) != null)
            when (contract) {
                "accept" -> {
                    val decoded = CensusSkeletonSchema.deserialize(item.toString()) as UiSkeletonDto
                    assertTrue(name, decoded.isMatched)
                    assertEquals(name, item.getValue("ruleId").jsonPrimitive.content, decoded.ruleId)
                    assertEquals(name, item.toString(), CensusSkeletonSchema.serialize(decoded))
                    // The server recomputes it; a wrong one would hide the verdict under fingerprint_mismatch.
                    assertEquals(name, decoded.fingerprint, CensusFingerprint.of(decoded.root))
                }
                "refuse" -> assertThrows(name, IllegalArgumentException::class.java) { CensusSkeletonSchema.deserialize(item.toString()) }
                else -> throw AssertionError("$name: unknown contract verdict $contract")
            }
        }
        assertEquals(
            setOf("accepted", "unknown_field", "bad_item", "bad_rule_id", "missing_hash", "plaintext_field"),
            servers,
        )
        // One layout throughout, so each refusal is the vector's own fault and never a fingerprint mismatch.
        assertEquals(1, rows.map { it.getValue("item").jsonObject.getValue("fingerprint").jsonPrimitive.content }.toSet().size)
    }

    /** Included-build and repository-root invocations both resolve the same checked-in file. */
    private fun conformance(name: String): List<String> {
        val file = sequenceOf(File("conformance/$name"), File("census-contract/conformance/$name")).first { it.isFile }
        val text = if (name.endsWith(".gz")) GZIPInputStream(file.inputStream()).bufferedReader().use { it.readText() } else file.readText()
        return text.lines().filter { it.isNotBlank() }
    }
}
