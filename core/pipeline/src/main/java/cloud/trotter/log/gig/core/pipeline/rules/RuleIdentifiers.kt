package cloud.trotter.log.gig.core.pipeline.rules

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The identifiers shadow reports carry (#1630), each a full sha256 hex kept on the phone, of which the wire carries
 * the first [WIRE_HEX_LENGTH] characters.
 */
internal object RuleIdentifiers {

    const val WIRE_HEX_LENGTH = 16

    /** One file of a generation, as the generation id names it. */
    data class FileRef(val role: Role, val name: String, val sha256: String)

    enum class Role(val wire: String) { BUNDLED("bundled"), REMOTE("remote") }

    /**
     * A shadow candidate's digest: sha256 of the JCS form of [rule], the candidate object exactly as the canonicalizer
     * emitted it (its `shadow` key included). Null when the object cannot be encoded or hashed.
     */
    fun candidateDigest(rule: JsonObject): String? =
        runCatching { Jcs.encode(rule) }.getOrNull()?.let(::sha256OrNull)

    /**
     * A live generation's id: sha256 of the JCS form of the array, in assembly order, of
     * `{"role", "name", "sha256"}` per file. The order is part of the id, so the same files in other roles give
     * another id. Null when hashing fails.
     */
    fun generationId(files: List<FileRef>): String? {
        val array = JsonArray(
            files.map {
                JsonObject(
                    mapOf(
                        "role" to JsonPrimitive(it.role.wire),
                        "name" to JsonPrimitive(it.name),
                        "sha256" to JsonPrimitive(it.sha256),
                    ),
                )
            },
        )
        return runCatching { Jcs.encode(array) }.getOrNull()?.let(::sha256OrNull)
    }

    fun wire(hex: String): String = hex.take(WIRE_HEX_LENGTH)
}
