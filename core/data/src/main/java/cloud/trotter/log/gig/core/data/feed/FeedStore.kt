package cloud.trotter.log.gig.core.data.feed

import cloud.trotter.feed.contract.Feed
import cloud.trotter.feed.contract.FeedLimits
import cloud.trotter.feed.contract.FeedMark
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

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

/** A pointer file's state: absent, unreadable, or naming one generation. */
internal sealed interface FeedPointer {
    data object Absent : FeedPointer
    data object Garbled : FeedPointer
    data class Names(val generation: String, val sequence: Long) : FeedPointer
}

/**
 * The cache of one (source, feed) under `noBackupFilesDir/feeds/<source>/<feed>/` (design-r3 § App client):
 *
 * - `gen-<sequence>/` holds one generation: each payload file named by its sha256, and `meta.json` once committed.
 * - `CURRENT` names the live generation and `PREVIOUS` the one before it. Each is replaced by an atomic rename after an
 *   fsync, so a crash leaves either the old pointer or the new one, never half of one.
 *
 * Commit order: `meta.json`, then `PREVIOUS`, then `CURRENT`. The rename of `CURRENT` is the commit; a crash before it
 * leaves the old generation live and the staged directory is removed at the next start. Only the current and the
 * previous generation are kept. The caller serializes every call for one (source, feed).
 */
internal class FeedStore(root: File, sourceId: String, feed: Feed) {
    init {
        require(SOURCE_ID.matches(sourceId)) { "not a source id" }
    }

    val dir = File(File(root, sourceId), feed.wire)

    fun pointer(name: String): FeedPointer {
        val file = File(dir, name)
        if (!file.exists()) return FeedPointer.Absent
        val text = readBounded(file, POINTER_MAX_BYTES)?.toString(Charsets.UTF_8) ?: return FeedPointer.Garbled
        val sequence = GENERATION.matchEntire(text)?.groupValues?.get(1)?.toLongOrNull() ?: return FeedPointer.Garbled
        return FeedPointer.Names(text, sequence)
    }

    /** Every generation directory on disk, committed or not. */
    fun generations(): List<String> = dir.listFiles().orEmpty().filter { it.isDirectory && GENERATION.matches(it.name) }.map { it.name }

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

    /** A payload file of [generation] by its sha256, at most [size] bytes; the caller verifies it. */
    fun readFile(generation: String, sha256: String, size: Int): ByteArray? =
        readBounded(File(File(dir, generation), sha256), size)

    /** Writes [files] (sha256 to bytes) into a fresh `gen-<sequence>/`, each fsynced. Returns the generation's name. */
    @Throws(IOException::class)
    fun stage(sequence: Long, files: Map<String, ByteArray>): String {
        val name = generationName(sequence)
        val gen = File(dir, name)
        if (gen.exists() && !gen.deleteRecursively()) throw IOException("stale stage")
        if (!gen.mkdirs()) throw IOException("mkdir")
        for ((sha256, bytes) in files) writeDurably(File(gen, sha256), bytes)
        syncDirectory(gen)
        return name
    }

    /**
     * Commits [generation]: its `meta.json`, then `PREVIOUS` (the generation live until now, or none), then the atomic
     * rename of `CURRENT`. Prunes every other generation afterwards.
     */
    @Throws(IOException::class)
    fun commit(generation: String, meta: FeedMeta, previous: String?) {
        replaceDurably(File(File(dir, generation), META), encode(meta))
        val pointerPrevious = File(dir, PREVIOUS)
        if (previous != null && previous != generation) replaceDurably(pointerPrevious, previous.toByteArray(Charsets.UTF_8))
        else if (pointerPrevious.exists() && !pointerPrevious.delete()) throw IOException("previous pointer")
        replaceDurably(File(dir, CURRENT), generation.toByteArray(Charsets.UTF_8))
        prune(setOfNotNull(generation, previous))
    }

    /** Removes every generation directory and temp file not in [keep]. A failure leaves clutter, never a wrong pointer. */
    fun prune(keep: Set<String>) {
        for (file in dir.listFiles().orEmpty()) {
            val stale = (file.isDirectory && GENERATION.matches(file.name) && file.name !in keep) || file.name.endsWith(TMP)
            if (stale) file.deleteRecursively()
        }
    }

    fun discard(generation: String) {
        File(dir, generation).deleteRecursively()
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

    private fun replaceDurably(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + TMP)
        writeDurably(tmp, bytes)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(target.parentFile!!)
    }

    private fun writeDurably(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
    }

    /** Makes a rename durable. Best effort: not every filesystem lets a directory be opened for a sync. */
    private fun syncDirectory(directory: File) {
        try {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
        } catch (_: UnsupportedOperationException) {
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
        const val PREVIOUS = "PREVIOUS"
        private const val META = "meta.json"
        private const val TMP = ".tmp"
        private const val META_VERSION = 1
        private const val POINTER_MAX_BYTES = 64

        /** The envelope (at most 32 KB, escaped at most 6x as a JSON string) plus the carry. */
        private const val META_MAX_BYTES = 512_000
        private val GENERATION = Regex("gen-([1-9][0-9]{0,18})")
        private val SOURCE_ID = Regex("[a-z0-9.:]{1,64}")

        fun generationName(sequence: Long): String = "gen-$sequence"
    }
}
