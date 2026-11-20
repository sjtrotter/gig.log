package cloud.trotter.log.gig.core.data.census

import cloud.trotter.census.contract.ClickedIdentity
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.sha256OrNull
import cloud.trotter.log.gig.core.network.census.CensusApi
import cloud.trotter.log.gig.domain.census.CensusUploadStats
import cloud.trotter.log.gig.domain.census.ClickEvidence
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #1634 (ADR-0011 §19, D7): click evidence on the phone, kept apart from the FIFO spool so that neither the order of the
 * taps nor the time of a send can be read from what goes out.
 *
 * - **Candidates** (`census/clicks.json`): per open UTC day, one candidate per selection key
 *   ([ClickedIdentity.selectionKey], a hash of `day|fingerprint|layout|ownerPath`). Of the bodies offered for one key,
 *   the one with the smallest sha256 of its canonical bytes is kept, and a day keeps the
 *   [ClickEvidence.CANDIDATES_PER_DAY] smallest keys, so the kept set does not depend on arrival order.
 * - **Day closure** ([closeEndedDays], by the census worker): a day is finalized only after its UTC day has ended. Its
 *   [ClickEvidence.SENT_PER_DAY] smallest keys, ordered by key, are written as one immutable frozen batch
 *   (`census/clicks-frozen/<day>.<batchId>.json`) and the day is marked closed; a later candidate for a closed day is
 *   dropped and nothing ever replenishes a day's ten.
 * - **Sending**: a frozen batch is resent unchanged until the server answers it; any final answer deletes it
 *   ([resolve]). A 413 splits it in key order into two frozen batches ([split]), each with its own batch id.
 * - **Expiry**: candidates, closed-day markers and frozen batches older than [ClickEvidence.KEPT_DAYS] days by
 *   observation day are deleted on every admission, read and worker run.
 * - **Deletion**: every change runs under the skeleton spool's lock with its clear boundary
 *   ([CensusSpool.underClearLock]); the spool's [CensusSpool.clear] deletes all of this under the same lock and
 *   boundary bump ([deleteAll]). An admission whose boundary is older than the spool's is dropped, so evidence read
 *   before a census deletion or identity reset is never written behind it.
 *
 * Nothing is cached in memory: the files are the state, read under the lock. Never uploaded or backed up as files (the
 * whole `census/` directory is excluded); only the frozen batches' items leave the phone.
 */
@Singleton
class CensusClickCandidates(
    private val spool: CensusSpool,
    private val stats: CensusUploadStats,
    private val clock: Clock,
) {
    @Inject constructor(spool: CensusSpool, stats: CensusUploadStats) : this(spool, stats, Clock.systemUTC())

    /** What [admit] did with a clicked skeleton. */
    enum class Admission { KEPT, NOT_KEPT, LATE, STALE, INVALID }

    /** One frozen batch, read back from its file. Items are the frozen bytes, in key order. */
    class Batch internal constructor(
        internal val file: String,
        val day: String,
        val batchId: String,
        val platforms: List<String>,
        val items: List<String>,
        internal val keys: List<String>,
        internal val identities: List<String>,
    ) {
        override fun toString(): String = "Batch([redacted])"
    }

    @Serializable
    private data class Candidate(val identity: String, val bodySha: String, val platform: String, val body: String)

    @Serializable
    private data class Candidates(
        /** day → selection key → candidate. */
        val days: Map<String, Map<String, Candidate>> = emptyMap(),
        /** Days whose batch was frozen. */
        val closed: List<String> = emptyList(),
    )

    @Serializable
    private data class Frozen(
        val day: String,
        val batchId: String,
        val keys: List<String>,
        val identities: List<String>,
        val platforms: List<String>,
        val items: List<String>,
    )

    /**
     * Keeps [item] (a `uinode.clicked.v1` skeleton serialized as [json], its canonical bytes) as a candidate of its day
     * if it belongs in the day's kept set. [boundary] is the clear boundary read before its window was read.
     */
    suspend fun admit(item: UiSkeletonDto, json: String, platformWire: String, boundary: Long): Admission =
        spool.underClearLock<Admission> { current ->
            val root = spool.censusRoot
            val today = today()
            sweepFrozen(root, today)
            if (boundary != current) return@underClearLock Admission.STALE.also { stats.clickedStale.incrementAndGet() }
            val identity = if (item.isClicked) ClickedIdentity.of(item) else null
            val key = if (item.isClicked) ClickedIdentity.selectionKey(item) else null
            val bodySha = sha256OrNull(json)
            if (identity == null || key == null || bodySha == null) return@underClearLock Admission.INVALID
            val state = sweep(load(root), today)
            val day = item.day
            if (day in state.closed || day in frozenDays(root) || day < cutoff(today)) {
                save(root, state)
                return@underClearLock Admission.LATE.also { stats.clickedLate.incrementAndGet() }
            }
            val kept = state.days[day].orEmpty()
            val existing = kept[key]
            val next: Map<String, Candidate>? = when {
                existing != null -> if (bodySha < existing.bodySha) kept + (key to Candidate(identity, bodySha, platformWire, json)) else null
                kept.size < ClickEvidence.CANDIDATES_PER_DAY -> kept + (key to Candidate(identity, bodySha, platformWire, json))
                else -> {
                    val largest = kept.keys.max()
                    if (key < largest) kept - largest + (key to Candidate(identity, bodySha, platformWire, json)) else null
                }
            }
            save(root, if (next == null) state else state.copy(days = state.days + (day to next)))
            if (next == null) {
                stats.clickedNotKept.incrementAndGet()
                Admission.NOT_KEPT
            } else {
                stats.clickedKept.incrementAndGet()
                Admission.KEPT
            }
        }

    /**
     * Freezes every day whose UTC day has ended: its smallest keys, in key order, become its one frozen batch and the day
     * is closed. The frozen file is written before the candidates file, and its name and bytes depend only on the
     * candidates, so a crash between the two re-freezes the same batch. Returns how many days were frozen.
     */
    suspend fun closeEndedDays(): Int = spool.underClearLock {
        val root = spool.censusRoot
        val today = today()
        sweepFrozen(root, today)
        var state = sweep(load(root), today)
        var frozen = 0
        // A day with a frozen batch already on disk (a crash after its freeze, before the close was saved) is only closed.
        val alreadyFrozen = frozenDays(root)
        for (day in state.days.keys.sorted()) {
            if (day >= today.toString()) continue
            val chosen = state.days.getValue(day).entries.sortedBy { it.key }.take(ClickEvidence.SENT_PER_DAY)
            if (chosen.isNotEmpty() && day !in alreadyFrozen) {
                write(root, day, chosen.map { it.key }, chosen.map { it.value.identity }, chosen.map { it.value.platform },
                    chosen.map { it.value.body })
                frozen++
                stats.clickedFrozen.incrementAndGet()
            }
            state = state.copy(days = state.days - day, closed = (state.closed + day).distinct().sorted())
        }
        save(root, state)
        frozen
    }

    /** The frozen batches waiting to be sent, by day and then by their first key. */
    suspend fun frozen(): List<Batch> = spool.underClearLock {
        val root = spool.censusRoot
        sweepFrozen(root, today())
        frozenFiles(root).mapNotNull { read(root, it) }.sortedWith(compareBy<Batch>({ it.day }, { it.keys.first() }, { it.file }))
    }

    /** The server gave [batch] a final answer: its file is deleted and its day stays closed. */
    suspend fun resolve(batch: Batch) {
        spool.underClearLock { removeFrozen(spool.censusRoot, batch.file) }
    }

    /**
     * A 413: [batch] becomes two frozen batches, its first half and its second half in key order, each with its own batch
     * id; the halves are written before the original is deleted. Null when [batch] has a single item; empty when its file
     * is gone (a clear ran since it was read), so nothing is written back.
     */
    suspend fun split(batch: Batch): List<Batch>? = if (batch.items.size < 2) null else spool.underClearLock {
        val root = spool.censusRoot
        if (!File(frozenDir(root), batch.file).isFile) return@underClearLock emptyList<Batch>()
        val middle = batch.items.size / 2
        val halves = listOf(0 until middle, middle until batch.items.size).map { range ->
            write(root, batch.day, batch.keys.slice(range), batch.identities.slice(range), batch.platforms.slice(range),
                batch.items.slice(range))
        }
        removeFrozen(root, batch.file)
        halves
    }

    /**
     * The items of [batch] from a delivery app the census choices still cover ([coveredWires]); a batch with others is
     * rewritten without them under a new batch id. Null when none is left (the file is deleted).
     */
    suspend fun restrict(batch: Batch, coveredWires: Set<String>): Batch? {
        if (batch.platforms.all { it in coveredWires }) return batch
        return spool.underClearLock<Batch?> {
            val root = spool.censusRoot
            if (!File(frozenDir(root), batch.file).isFile) return@underClearLock null
            val kept = batch.platforms.indices.filter { batch.platforms[it] in coveredWires }
            val next = if (kept.isEmpty()) null else write(root, batch.day, kept.map { batch.keys[it] },
                kept.map { batch.identities[it] }, kept.map { batch.platforms[it] }, kept.map { batch.items[it] })
            if (next?.file != batch.file) removeFrozen(root, batch.file)
            next
        }
    }

    // ── Files (called under the spool lock) ───────────────────────────────

    private fun today(): LocalDate = LocalDate.now(clock.withZone(ZoneOffset.UTC))

    private fun cutoff(today: LocalDate): String = today.minusDays(ClickEvidence.KEPT_DAYS).toString()

    /** Drops candidate days and closed markers older than the kept days. */
    private fun sweep(state: Candidates, today: LocalDate): Candidates {
        val cutoff = cutoff(today)
        return state.copy(days = state.days.filterKeys { it >= cutoff }, closed = state.closed.filter { it >= cutoff })
    }

    /** Deletes frozen batches older than the kept days. */
    private fun sweepFrozen(root: File, today: LocalDate) {
        val cutoff = cutoff(today)
        for (name in frozenFiles(root)) {
            val day = FROZEN_NAME.matchEntire(name)?.groupValues?.get(1) ?: continue
            if (day < cutoff) {
                val file = File(frozenDir(root), name)
                val items = read(root, name)?.items?.size ?: 1
                if (file.exists() && !file.delete()) throw IOException("Census click batch expiry failed")
                stats.stale.addAndGet(items.toLong())
            }
        }
    }

    private fun load(root: File): Candidates {
        val file = File(root, CANDIDATES_FILE)
        if (!file.isFile) return Candidates()
        // A corrupt file costs the open candidates, never the census: it is replaced on the next write.
        return runCatching { JSON.decodeFromString(Candidates.serializer(), file.readText(Charsets.UTF_8)) }.getOrDefault(Candidates())
    }

    private fun save(root: File, state: Candidates) {
        val file = File(root, CANDIDATES_FILE)
        if (state.days.isEmpty() && state.closed.isEmpty()) {
            if (file.exists() && !file.delete()) throw IOException("Census click candidates removal failed")
            return
        }
        commit(file, JSON.encodeToString(Candidates.serializer(), state))
    }

    private fun write(
        root: File,
        day: String,
        keys: List<String>,
        identities: List<String>,
        platforms: List<String>,
        items: List<String>,
    ): Batch {
        val batchId = CensusApi.clickedBatchId(identities)
        val name = "$day.$batchId.json"
        val dir = frozenDir(root)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Census click batches unavailable")
        commit(File(dir, name), JSON.encodeToString(Frozen.serializer(), Frozen(day, batchId, keys, identities, platforms, items)))
        return Batch(name, day, batchId, platforms, items, keys, identities)
    }

    private fun read(root: File, name: String): Batch? {
        val match = FROZEN_NAME.matchEntire(name) ?: return null
        val frozen = runCatching {
            JSON.decodeFromString(Frozen.serializer(), File(frozenDir(root), name).readText(Charsets.UTF_8))
        }.getOrNull()
        val sizes = frozen?.let { listOf(it.keys.size, it.identities.size, it.platforms.size, it.items.size) }
        if (frozen == null || frozen.day != match.groupValues[1] || frozen.batchId != match.groupValues[2] ||
            frozen.items.isEmpty() || sizes!!.distinct().size != 1
        ) {
            // A file that does not read back as written is dropped, never sent half-understood.
            File(frozenDir(root), name).delete()
            return null
        }
        return Batch(name, frozen.day, frozen.batchId, frozen.platforms, frozen.items, frozen.keys, frozen.identities)
    }

    private fun removeFrozen(root: File, name: String) {
        val file = File(frozenDir(root), name)
        if (file.exists() && !file.delete()) throw IOException("Census click batch removal failed")
    }

    private fun frozenDays(root: File): Set<String> =
        frozenFiles(root).mapNotNullTo(HashSet()) { FROZEN_NAME.matchEntire(it)?.groupValues?.get(1) }

    private fun frozenFiles(root: File): List<String> =
        frozenDir(root).listFiles().orEmpty().filter { it.isFile && FROZEN_NAME.matches(it.name) }.map { it.name }.sorted()

    private fun commit(file: File, text: String) {
        file.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Census click evidence unavailable") }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(tmp).use {
                it.write(text.toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            if (!tmp.renameTo(file)) throw IOException("Census click evidence commit failed")
        } finally {
            tmp.delete()
        }
    }

    companion object {
        private const val CANDIDATES_FILE = "clicks.json"
        private const val FROZEN_DIR = "clicks-frozen"
        private val FROZEN_NAME = Regex("([0-9]{4}-[0-9]{2}-[0-9]{2})\\.(c-[0-9a-f]{32})\\.json")
        private val JSON = Json { ignoreUnknownKeys = false }

        private fun frozenDir(root: File) = File(root, FROZEN_DIR)

        /**
         * Deletes every click evidence file under [root] (the candidates with their closed days, and every frozen batch).
         * Called by [CensusSpool.clear] under its lock, after its boundary bump. Throws when a file stays.
         */
        internal fun deleteAll(root: File) {
            listOf(File(root, CANDIDATES_FILE), File(root, "$CANDIDATES_FILE.tmp")).forEach {
                if (it.exists() && !it.delete()) throw IOException("Census click candidates clear failed")
            }
            frozenDir(root).listFiles().orEmpty().forEach {
                if (it.isFile && !it.delete()) throw IOException("Census click batches clear failed")
            }
        }
    }
}
