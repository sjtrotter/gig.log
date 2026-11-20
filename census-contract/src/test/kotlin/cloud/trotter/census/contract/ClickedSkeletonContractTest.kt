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

import cloud.trotter.census.contract.contributor.ContributorSlots
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/** `uinode.clicked.v1` (#1634, census#87): a v2 body of the tapped window plus a required `tap`, under its own id. */
class ClickedSkeletonContractTest {

    private val generation = "a1b2c3d4e5f60718"
    private val rule = "doordash.screen.offer"

    private fun node(
        cls: String,
        id: String,
        clickable: Boolean = false,
        action: Boolean = false,
        children: List<UiSkeletonNodeDto> = emptyList(),
    ) = UiSkeletonNodeDto(
        className = "android.widget.$cls", id = "com.example.app:id/$id",
        isClickable = clickable, isEnabled = clickable || action, hasClickAction = action, children = children,
    )

    /**
     * root/0 takes no click; root/1 is clickable with a plain child; root/2 advertises the click action only, and holds
     * an icon (root/2/0) and a clickable box (root/2/1) with a plain child; root/3..root/10 are plain, so root/10 exists.
     */
    private val root = node(
        "ScrollView", "sheet",
        children = listOf(
            node("TextView", "title"),
            node("Button", "accept", clickable = true, children = listOf(node("TextView", "accept_label"))),
            node(
                "LinearLayout", "row", action = true,
                children = listOf(
                    node("ImageView", "icon"),
                    node("CheckBox", "check", clickable = true, children = listOf(node("TextView", "check_label"))),
                ),
            ),
        ) + List(7) { node("TextView", "line") } + node("TextView", "footer"),
    )

    private fun screen(schemaId: String, ruleId: String? = null, tap: TapRef? = null) = UiSkeletonDto(
        schemaId = schemaId, hashDomain = 1, filterRev = 1, fingerprint = requireNotNull(CensusFingerprint.of(root)),
        platform = "doordash", engineVersion = 1, day = "2026-11-20", ruleId = ruleId, tap = tap, root = root,
    )

    private fun tap(owner: String, tapped: String? = null) = TapRef(ownerPath = owner, tapPath = tapped, generation = generation)

    private fun clicked(tap: TapRef? = tap("root/1", "root/1/0")) = screen(SkeletonSchema.SCHEMA_ID_CLICKED, tap = tap)

    private fun rejects(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    @Test
    fun `K1 v1 and v2 items encode as before, with no tap key, across the whole golden corpus`() {
        val v2 = screen(SkeletonSchema.SCHEMA_ID_V2)
        assertFalse(SkeletonSchema.serialize(v2).contains("\"tap\""))
        var screens = 0
        conformance("skeletons.jsonl.gz").forEach { line ->
            val skeleton = Json.parseToJsonElement(line).jsonObject["skeleton"] as? JsonObject ?: return@forEach
            val decoded = CensusSkeletonSchema.deserialize(skeleton.toString())
            assertEquals(skeleton.toString(), CensusSkeletonSchema.serialize(decoded))
            assertFalse("tap" in skeleton)
            if (decoded is UiSkeletonDto) {
                screens++
                assertNull(decoded.tap)
                assertFalse(decoded.isClicked)
                assertNull(ClickedIdentity.of(decoded))
            }
        }
        assertTrue("the golden carries screens", screens > 0)
    }

    @Test
    fun `K2 a clicked item decodes with its tap and re-encodes byte for byte`() {
        val item = clicked()
        val wire = SkeletonSchema.serialize(item)
        assertTrue(
            wire,
            wire.contains(""""day":"2026-11-20","tap":{"ownerPath":"root/1","tapPath":"root/1/0","generation":"$generation"},"root":"""),
        )
        val decoded = CensusSkeletonSchema.deserialize(wire) as UiSkeletonDto
        assertEquals(item, decoded)
        assertEquals(wire, CensusSkeletonSchema.serialize(decoded))
        assertTrue(decoded.isClicked)
        assertFalse(decoded.isMatched)
        assertNull(decoded.ruleId)
        assertEquals(SkeletonVersion.V2, decoded.version)
        assertEquals(SkeletonKind.SCREEN, decoded.kind)
        val ownerOnly = clicked(tap("root/1"))
        assertTrue(SkeletonSchema.serialize(ownerOnly).contains(""""tap":{"ownerPath":"root/1","generation":"$generation"}"""))
    }

    @Test
    fun `K3 the fingerprint ignores the schema and the tap, and the item size counts the tap`() {
        val v2 = screen(SkeletonSchema.SCHEMA_ID_V2)
        val item = clicked()
        assertEquals(CensusFingerprint.of(v2), CensusFingerprint.of(item))
        assertEquals(CensusFingerprint.of(root), CensusFingerprint.of(item))
        assertEquals(CensusFingerprint.of(item), CensusFingerprint.of(clicked(tap("root/2", "root/2/0"))))
        val anchor = "\"day\":\"2026-11-20\","
        val fragment = """"tap":{"ownerPath":"root/1","tapPath":"root/1/0","generation":"$generation"},"""
        val expected = SkeletonSchema.serialize(v2)
            .replace(SkeletonSchema.SCHEMA_ID_V2, SkeletonSchema.SCHEMA_ID_CLICKED)
            .replace(anchor, anchor + fragment)
        val measured = SkeletonSchema.measure(item)
        assertEquals(expected, measured.json)
        assertEquals(expected.toByteArray(Charsets.UTF_8).size, measured.bytes)
        assertEquals(measured, CensusSkeletonSchema.measure(item))
    }

    @Test
    fun `K4 a tap rides only the clicked schema, never with a rule id, and never projects down`() {
        val v2Wire = SkeletonSchema.serialize(screen(SkeletonSchema.SCHEMA_ID_V2))
        val v1Wire = SkeletonSchema.serialize(screen(SkeletonSchema.SCHEMA_ID_V2).toV1())
        val matchedWire = SkeletonSchema.serialize(screen(SkeletonSchema.SCHEMA_ID_MATCHED, ruleId = rule))
        val clickedWire = SkeletonSchema.serialize(clicked())
        val tapJson = """{"ownerPath":"root/1","generation":"$generation"}"""
        val anchor = "\"day\":\"2026-11-20\","
        listOf(
            v2Wire.replace(anchor, "$anchor\"tap\":$tapJson,"),
            v2Wire.replace(anchor, "$anchor\"tap\":null,"),
            v1Wire.replace(anchor, "$anchor\"tap\":null,"),
            v1Wire.replace(anchor, "$anchor\"tap\":$tapJson,"),
            matchedWire.replace("\"root\":", "\"tap\":null,\"root\":"),
            matchedWire.replace("\"root\":", "\"tap\":$tapJson,\"root\":"),
            clickedWire.replace(anchor, "$anchor\"ruleId\":null,"),
            clickedWire.replace(anchor, "$anchor\"ruleId\":\"$rule\","),
            clickedWire.replace("\"tapPath\":\"root/1/0\",", "\"tapPath\":null,"),
            clickedWire.replace("\"ownerPath\":\"root/1\",", "\"ownerPath\":null,"),
            clickedWire.replace("\"generation\":\"$generation\"", "\"generation\":null"),
            clickedWire.replace(Regex("\"tap\":\\{[^}]*\\},"), ""),
            clickedWire.replace(Regex("\"tap\":\\{[^}]*\\},"), "\"tap\":null,"),
        ).forEach { bad ->
            rejects { SkeletonSchema.deserialize(bad) }
            rejects { CensusSkeletonSchema.deserialize(bad) }
        }
        rejects { screen(SkeletonSchema.SCHEMA_ID_V2, tap = tap("root/1")) }
        rejects { screen(SkeletonSchema.SCHEMA_ID_MATCHED, ruleId = rule, tap = tap("root/1")) }
        rejects { screen(SkeletonSchema.SCHEMA_ID_CLICKED, ruleId = rule, tap = tap("root/1")) }
        rejects { clicked(tap = null) }
        assertThrows(IllegalStateException::class.java) { clicked().toV1() }
        assertEquals("bad_tap_ref", SkeletonRejectionReason.BAD_TAP_REF.wire)
        assertEquals(SkeletonRejectionReason.BAD_TAP_REF, SkeletonRejectionReason.fromWire("bad_tap_ref"))
    }

    @Test
    fun `K5 a node path is canonical, at most 64 steps of 0 to 9999 with no leading zero`() {
        assertEquals(emptyList<Int>(), NodePath.parse("root"))
        assertEquals(listOf(0, 10, 9_999), NodePath.parse("root/0/10/9999"))
        val deepest = "root" + "/1".repeat(NodePath.MAX_STEPS)
        assertEquals(List(NodePath.MAX_STEPS) { 1 }, NodePath.parse(deepest))
        listOf(
            "", "Root", "root/", "/root", "root/01", "root/00", "root/-1", "root/10000", "root/1/text", "root/1 ",
            "root//1", "root/+1", "node/1", deepest + "/1", "root/" + "1".repeat(4_000),
        ).forEach { assertNull(it, NodePath.parse(it)) }
        assertEquals("root", NodePath.of(emptyList()))
        assertEquals("root/0/10/9999", NodePath.of(listOf(0, 10, 9_999)))
        assertEquals(deepest, NodePath.of(List(NodePath.MAX_STEPS) { 1 }))
        assertNull(NodePath.of(List(NodePath.MAX_STEPS + 1) { 1 }))
        assertNull(NodePath.of(listOf(10_000)))
        assertNull(NodePath.of(listOf(-1)))
    }

    @Test
    fun `K6 a tap names the nearest clickable owner of its own tree`() {
        // Holds: an owner alone, an owner and its plain child, an action-only owner, a nested owner.
        listOf(tap("root/1"), tap("root/1", "root/1/0"), tap("root/2", "root/2/0"), tap("root/2/1", "root/2/1/0"))
            .forEach { assertTrue(it.toString(), clicked(it).tap!!.holdsIn(root)) }
        listOf(
            tap("root/0"), // the owner takes no click
            tap("root", "root/0"), // the root takes no click
            tap("root/7/0"), // off the tree: root/7 has no children
            tap("root/11"), // off the tree: root has 11 children
            tap("root/1", "root/1/1"), // the tapped node is off the tree
            tap("root/1", "root/10"), // `root/1` is a string prefix of `root/10`, not an ancestor
            tap("root/1", "root/1"), // the tapped node is the owner
            tap("root/2/1", "root/2"), // the tapped node is above the owner
            tap("root/2", "root/2/1"), // the tapped node takes clicks itself
            tap("root/2", "root/2/1/0"), // root/2/1 takes clicks between them, so it is the nearest owner
        ).forEach { bad ->
            assertFalse(bad.toString(), bad.holdsIn(root))
            rejects { clicked(bad) }
        }
        listOf("root/01", "root/1/", "root/1/text", "Root/1").forEach { bad ->
            rejects { TapRef(ownerPath = bad, generation = generation) }
            rejects { TapRef(ownerPath = "root/1", tapPath = bad, generation = generation) }
        }
        listOf("A1B2C3D4E5F60718", generation.take(15), generation + "0", generation.repeat(4), "").forEach { bad ->
            rejects { TapRef(ownerPath = "root/1", generation = bad) }
        }
        assertTrue(WireTokenGrammars.shortDigest.matches(generation))
    }

    @Test
    fun `K7 the clicked identity is day, fingerprint, layout and owner path, read the same by phone and server`() {
        val item = clicked()
        val layout = requireNotNull(ContributorSlots.layout(item))
        val identity = "2026-11-20|${item.fingerprint}|$layout|root/1"
        assertEquals(identity, ClickedIdentity.of(item))
        assertEquals(sha256OrNull(identity), ClickedIdentity.selectionKey(item))
        assertEquals(64, ClickedIdentity.selectionKey(item)!!.length)
        // The tapped node and the generation are not identity; the owner is.
        assertEquals(identity, ClickedIdentity.of(clicked(tap("root/1"))))
        assertEquals(identity, ClickedIdentity.of(clicked(TapRef("root/1", generation = "0000000000000000"))))
        assertNotEquals(identity, ClickedIdentity.of(clicked(tap("root/2", "root/2/0"))))
        assertEquals(identity.replace("2026-11-20", "2026-11-21"), ClickedIdentity.of(clicked().copy(day = "2026-11-21")))
        assertNull(ClickedIdentity.of(screen(SkeletonSchema.SCHEMA_ID_V2)))
        assertNull(ClickedIdentity.of(screen(SkeletonSchema.SCHEMA_ID_MATCHED, ruleId = rule)))
        assertNull(ClickedIdentity.selectionKey(screen(SkeletonSchema.SCHEMA_ID_V2)))
    }

    @Test
    fun `K8 the clicked id is supported last and leaves the version ladder alone`() {
        assertEquals(
            listOf(
                "uinode.skeleton.v1", "notification.skeleton.v1", "uinode.skeleton.v2", "notification.skeleton.v2",
                "uinode.matched.v1", "uinode.clicked.v1",
            ),
            CensusSkeletonSchema.SUPPORTED_SCHEMA_IDS,
        )
        assertNull(SkeletonVersion.ofScreenSchema(SkeletonSchema.SCHEMA_ID_CLICKED))
        assertEquals(SkeletonVersion.V2, SkeletonVersion.newestScreen(CensusSkeletonSchema.SUPPORTED_SCHEMA_IDS))
        assertNull(SkeletonVersion.newestScreen(listOf(SkeletonSchema.SCHEMA_ID_CLICKED)))
    }

    @Test
    fun `K9 every clicked vector gets its contract verdict`() {
        val rows = conformance("clicked-vectors.jsonl").map { Json.parseToJsonElement(it).jsonObject }
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
                    assertTrue(name, decoded.isClicked)
                    assertEquals(name, item.getValue("tap").jsonObject.getValue("ownerPath").jsonPrimitive.content, decoded.tap?.ownerPath)
                    assertEquals(name, item.toString(), CensusSkeletonSchema.serialize(decoded))
                    // The server recomputes it; a wrong one would hide the verdict under fingerprint_mismatch.
                    assertEquals(name, decoded.fingerprint, CensusFingerprint.of(decoded.root))
                }
                "refuse" -> assertThrows(name, IllegalArgumentException::class.java) { CensusSkeletonSchema.deserialize(item.toString()) }
                else -> throw AssertionError("$name: unknown contract verdict $contract")
            }
        }
        assertEquals(setOf("accepted", "unknown_field", "bad_item", "bad_tap_ref", "missing_hash"), servers)
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
