package cloud.trotter.log.gig.core.data.census

import android.content.Context
import cloud.trotter.log.gig.domain.census.MatchedSkeletons
import cloud.trotter.log.gig.domain.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #1573: the daily limits on matched skeletons, [MatchedSkeletons.PER_RULE_PER_DAY] for one rule and
 * [MatchedSkeletons.PER_DAY] in all, over every platform, by the item's own UTC `day`. The counts live in
 * `filesDir/census/matched.json` (`{day: {ruleId: count}}`), which never leaves the phone and is outside every backup
 * (the whole `census/` directory is); only the two latest days are kept, and a skeleton for an older day than those is
 * not kept. A census data deletion does not reset it, as with the daily sample ([CensusDailySample]).
 */
@Singleton
class CensusMatchedCap internal constructor(
    private val file: File,
    private val io: CoroutineDispatcher,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(File(context.filesDir, "census/matched.json"), io)

    private val mutex = Mutex()
    /** day → rule id → matched skeletons kept. */
    private var days: Map<String, Map<String, Int>>? = null

    /** True when a matched skeleton of [ruleId] on [day] is within both limits; it is then counted. */
    suspend fun admit(day: String, ruleId: String): Boolean = withContext(io) {
        mutex.withLock {
            val current = days ?: load()
            if (current.size >= KEPT_DAYS && day !in current && day < current.keys.min()) return@withLock false
            val counts = current[day].orEmpty()
            if ((counts[ruleId] ?: 0) >= MatchedSkeletons.PER_RULE_PER_DAY) return@withLock false
            if (counts.values.sum() >= MatchedSkeletons.PER_DAY) return@withLock false
            val next = (current + (day to counts + (ruleId to (counts[ruleId] ?: 0) + 1))).toSortedMap()
            while (next.size > KEPT_DAYS) next.remove(next.firstKey())
            save(next)
            days = next
            true
        }
    }

    private fun load(): Map<String, Map<String, Int>> = runCatching {
        if (!file.isFile) emptyMap() else Json.decodeFromString<Map<String, Map<String, Int>>>(file.readText(Charsets.UTF_8))
    }.getOrDefault(emptyMap())

    private fun save(value: Map<String, Map<String, Int>>) {
        file.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Census matched cap unavailable") }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            tmp.writeText(Json.encodeToString(value), Charsets.UTF_8)
            if (!tmp.renameTo(file)) throw IOException("Census matched cap commit failed")
        } finally {
            tmp.delete()
        }
    }

    private companion object {
        const val KEPT_DAYS = 2
    }
}
