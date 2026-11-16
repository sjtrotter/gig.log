package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.core.pipeline.rules.RuleIdentifiers.FileRef
import cloud.trotter.log.gig.core.pipeline.rules.RuleIdentifiers.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** #1630: committed vectors for the candidate digest and the generation id. */
class RuleIdentifiersTest {

    @Test
    fun `candidate digest is sha256 of the JCS form, shadow key included`() {
        val candidate = Json.parseToJsonElement(
            """{ "id": "doordash.screen.idle_map", "priority": 10, "shadow": true,
                 "require": { "exists": { "hasText": "Dash Now" } } }""",
        ).jsonObject
        assertEquals(
            """{"id":"doordash.screen.idle_map","priority":10,"require":{"exists":{"hasText":"Dash Now"}},"shadow":true}""",
            Jcs.encode(candidate),
        )
        val digest = RuleIdentifiers.candidateDigest(candidate)
        assertEquals("c8d49dda15fb783f4c635a3f0483191d5ebba0fecb29b685a1ae06c6b9545f2d", digest)
        assertEquals("c8d49dda15fb783f", RuleIdentifiers.wire(digest!!))
    }

    @Test
    fun `key order and whitespace do not move the digest`() {
        val a = Json.parseToJsonElement("""{"shadow":true,"priority":10,"id":"doordash.screen.x"}""").jsonObject
        val b = Json.parseToJsonElement("""{ "id": "doordash.screen.x",  "priority": 10, "shadow": true }""").jsonObject
        assertEquals(RuleIdentifiers.candidateDigest(a), RuleIdentifiers.candidateDigest(b))
    }

    private val a = "63c5cdfc617fb8fe93888e68674717590957a25b5e08219b1a61c3f031f88ee6"
    private val b = "ee8869b8de3c8de510951bc7ef5eed482198e21ed4a71cc9213274898cac21c1"

    @Test
    fun `generation id vectors, and a role swap gives another id`() {
        val bundledARemoteB = RuleIdentifiers.generationId(listOf(FileRef(Role.BUNDLED, "a", a), FileRef(Role.REMOTE, "b", b)))
        val bundledBRemoteA = RuleIdentifiers.generationId(listOf(FileRef(Role.BUNDLED, "b", b), FileRef(Role.REMOTE, "a", a)))
        assertEquals("9398b9775b3d2ea37e693eca55c42ddc3cf86a269396e664871a663b4967d9cf", bundledARemoteB)
        assertEquals("d87b13922b87ac4bf75498f59b19aa0a91c662b4572602ecf60f6f2b5540a370", bundledBRemoteA)
        assertNotEquals(bundledARemoteB, bundledBRemoteA)
    }

    @Test
    fun `assembly order is part of the generation id`() {
        val ab = RuleIdentifiers.generationId(listOf(FileRef(Role.BUNDLED, "a", a), FileRef(Role.BUNDLED, "b", b)))
        val ba = RuleIdentifiers.generationId(listOf(FileRef(Role.BUNDLED, "b", b), FileRef(Role.BUNDLED, "a", a)))
        assertNotEquals(ab, ba)
    }
}
