package cloud.trotter.log.gig.core.data.feed

import cloud.trotter.feed.contract.Feed
import cloud.trotter.feed.contract.FeedLimits
import cloud.trotter.feed.contract.FeedMark
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * What a committed generation's `meta.json` holds: the envelope (the exact manifest string and its signature, so
 * start-up re-verifies it with the same verifier), the manifest digest and sequence, the high-water with the digest
 * that set it, the consumer's carry (fuel anchors, constants' retained years) and, for gig.log's source only, the
 * envelope's ETag. The high-water and the carry live here and nowhere else.
 */
internal class FeedMeta(
    val envelope: ByteArray,
    val digest: String,
    val sequence: Long,
    val highWater: FeedMark,
    val carry: JsonObject?,
    val etag: String?,
)

/** The pointer pair in `CURRENT`: the live generation and the one before it. */
internal data class FeedPointers(val current: String, val previous: String?)

/**
 * The cache of one (source, feed) under `noBackupFilesDir/feeds/<source>/<feed>/` (design-r3 § App client):
 *
 * - `stage-<sequence>-<random>/` is a generation being written. It is never committed state, and start-up deletes
 *   every one.
 * - `gen-<sequence>/` is a committed generation: each payload named by its sha256, and `meta.json`. It exists only
 *   after its `meta.json` was fsynced and its stage directory renamed to it, so its name alone is high-water evidence.
 * - `CURRENT` holds the pointer pair (current, previous), replaced through an fsynced temp file and an atomic rename,
 *   so a crash leaves the old pair or the new one, never half of one.
 *
 * Each created directory and each rename is made durable through [sync]. A sync failure before `CURRENT` moves aborts
 * the commit; after it, [commit] reports the commit as not proven durable and the commit stands. The caller serializes
 * every call for one (source, feed).
 */
internal class FeedStore(
    root: File,
    sourceId: String,
    feed: Feed,
    private val sync: DirectorySync,
    private val crashPoints: CrashPoints = CrashPoints { },
) {
    init {
        require(SOURCE_ID.matches(sourceId)) { "not a source id" }
    }

    val dir = File(File(root, sourceId), feed.wire)

    fun pointers(): FeedPointers? {
        val bytes = readBounded(File(dir, CURRENT), POINTER_MAX_BYTES) ?: return null
        return try {
            val json = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            val current = json.text("current")?.takeIf { GENERATION.matches(it) } ?: return null
            FeedPointers(current, json.text("previous")?.takeIf { GENERATION.matches(it) })
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Every committed generation on disk, newest first. */
    fun generations(): List<String> = dir.listFiles().orEmpty()
        .filter { it.isDirectory && GENERATION.matches(it.name) }
        .map { it.name }
        .sortedByDescending { sequenceOf(it) }

    /**
     * The high-water: the largest sequence any evidence on disk names. That is every `gen-` directory (its name counts
     * even when its `meta.json` cannot be read), every readable `meta.json` and the pointer pair, so recovery never
     * lowers it. The digest is the one a readable `meta.json` records for that sequence, else empty, so an
     * equal-sequence candidate is then refused rather than taken as the same.
     */
    fun highWater(): FeedMark? {
        val marks = ArrayList<FeedMark>()
        for (generation in generations()) {
            marks += FeedMark(sequenceOf(generation), "")
            readMeta(generation)?.let { meta ->
                marks += meta.highWater
                if (meta.sequence == sequenceOf(generation)) marks += FeedMark(meta.sequence, meta.digest)
            }
        }
        pointers()?.let { pair -> listOfNotNull(pair.current, pair.previous).forEach { marks += FeedMark(sequenceOf(it), "") } }
        val top = marks.maxOfOrNull { it.sequence } ?: return null
        return marks.firstOrNull { it.sequence == top && it.digest.isNotEmpty() } ?: FeedMark(top, "")
    }

    fun readMeta(generation: String): FeedMeta? {
        val bytes = readBounded(File(File(dir, generation), META), META_MAX_BYTES) ?: return null
        return try {
            val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            val highWater = root["high_water"]?.jsonObject ?: return null
            FeedMeta(
                envelope = root.text("envelope")?.toByteArray(Charsets.UTF_8) ?: return null,
                digest = root.text("digest") ?: return null,
                sequence = root.number("sequence") ?: return null,
                highWater = FeedMark(highWater.number("sequence") ?: return null, highWater.text("digest") ?: return null),
                carry = root["carry"] as? JsonObject,
                etag = root.text("etag"),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** A payload file of a committed generation by its sha256, at most [size] bytes; the caller verifies it. */
    fun readFile(generation: String, sha256: String, size: Int): ByteArray? =
        readBounded(File(File(dir, generation), sha256), size)

    /** Deletes every stage directory and temp file: none of them is committed state. */
    fun deleteStages() {
        for (file in dir.listFiles().orEmpty()) {
            if (file.name.startsWith(STAGE_PREFIX) || file.name.endsWith(TMP)) file.deleteRecursively()
        }
    }

    /** Writes [files] (sha256 to bytes) into a new stage directory, each fsynced. Returns the stage's name. */
    @Throws(IOException::class)
    fun stage(sequence: Long, files: Map<String, ByteArray>): String {
        makeDirectories(dir)
        val name = "$STAGE_PREFIX$sequence-${UUID.randomUUID()}"
        val stage = File(dir, name)
        if (!stage.mkdir()) throw IOException("mkdir")
        sync.sync(dir, published = false)
        for ((sha256, bytes) in files) writeDurably(File(stage, sha256), bytes)
        sync.sync(stage, published = false)
        return name
    }

    /**
     * Commits [stage] as `gen-<sequence>`: its `meta.json`, the rename to its generation name, then the pointer pair
     * (the new generation, [previous]). Returns false when the pointer moved but its directory sync failed; the commit
     * stands. Throws, with the pointer pair unchanged, when anything before the pointer's rename fails.
     */
    @Throws(IOException::class)
    fun commit(stage: String, meta: FeedMeta, previous: String?): Boolean {
        val stageDir = File(dir, stage)
        writeDurably(File(stageDir, META), encode(meta))
        sync.sync(stageDir, published = false)
        val genDir = File(dir, generationName(meta.sequence))
        try {
            Files.move(stageDir.toPath(), genDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
            sync.sync(dir, published = false)
        } catch (e: IOException) {
            genDir.deleteRecursively()
            throw e
        }
        crashPoints.reached(CrashPoint.RENAMED)
        val pair = JsonObject(
            mapOf("current" to JsonPrimitive(genDir.name), "previous" to (previous?.let(::JsonPrimitive) ?: JsonNull)),
        )
        val tmp = File(dir, CURRENT + TMP)
        writeDurably(tmp, pair.toString().toByteArray(Charsets.UTF_8))
        Files.move(tmp.toPath(), File(dir, CURRENT).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return try {
            sync.sync(dir, published = true)
            true
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Removes every committed generation that is not in [keep] and not the newest on disk. The caller reads the
     * high-water first; the generation that sets it is never removed.
     */
    fun prune(keep: Set<String>) {
        val all = generations()
        for (generation in all.drop(1)) {
            if (generation !in keep) File(dir, generation).deleteRecursively()
        }
    }

    fun discard(stage: String) {
        File(dir, stage).deleteRecursively()
    }

    /** Creates [target] and each missing ancestor, syncing each new entry into its parent. */
    private fun makeDirectories(target: File) {
        val missing = generateSequence(target) { it.parentFile }.takeWhile { !it.exists() }.toList().asReversed()
        for (directory in missing) {
            if (!directory.mkdir() && !directory.isDirectory) throw IOException("mkdir")
            sync.sync(directory.parentFile ?: throw IOException("no parent"), published = false)
        }
    }

    private fun encode(meta: FeedMeta): ByteArray {
        val fields = linkedMapOf(
            "meta_version" to JsonPrimitive(META_VERSION),
            "envelope" to JsonPrimitive(meta.envelope.toString(Charsets.UTF_8)),
            "digest" to JsonPrimitive(meta.digest),
            "sequence" to JsonPrimitive(meta.sequence),
            "high_water" to JsonObject(
                mapOf("sequence" to JsonPrimitive(meta.highWater.sequence), "digest" to JsonPrimitive(meta.highWater.digest)),
            ),
        )
        meta.carry?.let { fields["carry"] = it }
        meta.etag?.let { fields["etag"] = JsonPrimitive(it) }
        return JsonObject(fields).toString().toByteArray(Charsets.UTF_8)
    }

    private fun writeDurably(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
    }

    private fun readBounded(file: File, cap: Int): ByteArray? = try {
        if (!file.isFile) null else file.inputStream().use { FeedLimits.readBounded(it, cap) }
    } catch (_: IOException) {
        null
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    companion object {
        const val CURRENT = "CURRENT"
        private const val META = "meta.json"
        private const val TMP = ".tmp"
        private const val STAGE_PREFIX = "stage-"
        private const val META_VERSION = 1
        private const val POINTER_MAX_BYTES = 256

        /** The envelope (at most 32 KB, escaped at most 6x as a JSON string) plus the carry. */
        private const val META_MAX_BYTES = 512_000
        private val GENERATION = Regex("gen-([1-9][0-9]{0,18})")
        private val SOURCE_ID = Regex("[a-z0-9.:]{1,64}")

        fun generationName(sequence: Long): String = "gen-$sequence"

        fun sequenceOf(generation: String): Long = GENERATION.matchEntire(generation)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }
}
