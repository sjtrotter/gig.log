package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.pipeline.RuleEngineConstants
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import timber.log.Timber

/**
 * Builds the [ShadowSet] of a live generation from its bundled `<platform>_shadow` files (#1630).
 *
 * Nothing here can fail the live generation: the assembler builds the live generation first, without these files,
 * and this runs after it. A shadow file that fails a file-level check (size, JSON, keys, the exact
 * [RuleEngineConstants.SHADOW_FORMAT_VERSION], a platform that matches its name and has live screen rules) adds no
 * candidates. A candidate that does not compile, has no digest, or shares its logical id with another candidate is
 * dropped alone, with a DEBUG line and a local count. Two candidates whose digests share the wire prefix reject the
 * whole set. No candidates at all: no set.
 */
internal object ShadowSetCompiler {

    const val SHADOW_SUFFIX = "_shadow"

    private const val TAG = "Rules"
    private val FILE_KEYS = setOf("format_version", "platform_id", "screens")

    fun isShadowFile(file: RuleFile): Boolean = file.logicalName.endsWith(SHADOW_SUFFIX)

    /**
     * The shadow set of [files] for the live generation [generationId], whose screen rules cover [livePlatforms];
     * null when nothing survives. [digest] is a test seam for the candidate digest.
     */
    fun build(
        files: List<RuleFile.Bundled>,
        generationId: String?,
        livePlatforms: Set<Platform>,
        digest: (JsonObject) -> String? = RuleIdentifiers::candidateDigest,
    ): ShadowSet? {
        if (files.isEmpty()) return null
        if (generationId == null) {
            Timber.tag(TAG).w("Shadow rules not loaded: the live generation has no id")
            return null
        }
        return try {
            assemble(files, generationId, livePlatforms, digest)
        } catch (e: Exception) {
            Timber.tag(TAG).w("Shadow rules not loaded: %s", e.javaClass.simpleName)
            null
        } catch (_: StackOverflowError) {
            Timber.tag(TAG).w("Shadow rules not loaded: too deep")
            null
        }
    }

    private class Raw(val id: String?, val obj: JsonObject?, val platform: Platform, val platformId: String)

    private fun assemble(
        files: List<RuleFile.Bundled>,
        generationId: String,
        livePlatforms: Set<Platform>,
        digest: (JsonObject) -> String?,
    ): ShadowSet? {
        val raw = files.flatMap { readFile(it, livePlatforms).orEmpty() }
        var dropped = 0
        fun drop(id: String?, reason: String) {
            dropped++
            Timber.tag(TAG).d("Shadow candidate '%s' dropped: %s", id ?: "<unreadable-id>", reason)
        }
        val idCounts = raw.mapNotNull { it.id }.groupingBy { it }.eachCount()
        val candidates = raw.mapNotNull { r ->
            when {
                r.id == null || r.obj == null -> null.also { drop(r.id, "not a shadow rule object") }
                idCounts.getValue(r.id) > 1 -> null.also { drop(r.id, "another candidate has its id") }
                else -> compile(r, digest) ?: null.also { drop(r.id, "did not compile or hash") }
            }
        }
        val prefixes = candidates.map { RuleIdentifiers.wire(it.digest) }
        if (prefixes.toSet().size != prefixes.size) {
            Timber.tag(TAG).w("Shadow rules not loaded: two candidates share a digest prefix")
            return null
        }
        if (candidates.isEmpty()) return null
        Timber.tag(TAG).i("Shadow rules loaded: %d candidate(s), %d dropped", candidates.size, dropped)
        return ShadowSet(generationId, candidates, dropped)
    }

    /** The candidate objects of [file], or null when the file fails a file-level check. */
    private fun readFile(file: RuleFile.Bundled, livePlatforms: Set<Platform>): List<Raw>? {
        fun refuse(reason: String): List<Raw>? {
            Timber.tag(TAG).w("Shadow file '%s' not loaded: %s", file.source, reason)
            return null
        }
        if (file.json.length > MAX_RULE_FILE_BYTES) return refuse("over the size limit")
        val root = RuleCompiler.parseBoundedJson(file.json) as? JsonObject ?: return refuse("not an object")
        if (!FILE_KEYS.containsAll(root.keys)) return refuse("unknown top-level key")
        val version = (root["format_version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        if (version != RuleEngineConstants.SHADOW_FORMAT_VERSION) return refuse("format_version is not the shadow version")
        val platformId = (root["platform_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() } ?: return refuse("missing platform_id")
        val platform = Platform.fromWire(platformId.substringBefore('.'))?.takeUnless { it == Platform.Unknown }
            ?: return refuse("platform_id names no known platform")
        if (file.logicalName != platform.wire + SHADOW_SUFFIX) return refuse("platform_id is not the file's platform")
        if (platform !in livePlatforms) return refuse("the live generation has no screen rules for its platform")
        val screens = root["screens"] as? JsonArray ?: return refuse("no screens array")
        if (screens.size > RuleCompiler.MAX_RULES_PER_FILE) return refuse("too many rules")
        return screens.map { element ->
            val obj = element as? JsonObject
            val marked = (obj?.get("shadow") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true
            val id = (obj?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
            Raw(id, obj?.takeIf { marked }, platform, platformId)
        }
    }

    private fun compile(raw: Raw, digest: (JsonObject) -> String?): ShadowCandidate? {
        val obj = raw.obj ?: return null
        val hex = digest(obj) ?: return null
        val rule = try {
            RuleCompiler.compileRules<UiNode>(
                JsonArray(listOf(JsonObject(obj - "shadow"))), RuleContext.SCREEN, raw.platformId,
            ).singleOrNull()
        } catch (_: Exception) {
            null
        } ?: return null
        return ShadowCandidate(rule, raw.platform, hex)
    }
}
