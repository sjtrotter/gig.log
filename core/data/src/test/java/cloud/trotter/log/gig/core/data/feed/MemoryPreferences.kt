package cloud.trotter.log.gig.core.data.feed

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A preferences store in memory. [holdWrites] makes every write wait until [releaseWrites]; [holdReads] makes the first
 * read wait until [releaseReads]: the store "held back" while a test checks what the feed does meanwhile.
 */
class MemoryPreferences(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    @Volatile var failWrites = false
    private var writeGate: CompletableDeferred<Unit>? = null
    private var readGate: CompletableDeferred<Unit>? = null

    fun holdWrites() { writeGate = CompletableDeferred() }
    fun releaseWrites() { writeGate?.complete(Unit) }
    fun holdReads() { readGate = CompletableDeferred() }
    fun releaseReads() { readGate?.complete(Unit) }

    val current: Preferences get() = state.value

    override val data: Flow<Preferences> = flow {
        readGate?.await()
        emit(state.first())
    }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        writeGate?.await()
        if (failWrites) throw java.io.IOException("store refused the write")
        return transform(state.value).also { state.value = it }
    }
}
