package cloud.trotter.log.gig.domain.census

import cloud.trotter.log.gig.domain.pipeline.VisitTiming

/**
 * The shadow collector's visits (#1630, design F1): one state per (platform, generation, candidate digest), `Idle`,
 * `Open` or `Spent`, plus the start time of that candidate's last visit.
 *
 * - Idle + Matched opens a visit when the last start is unset or at least [VisitTiming.PRE_RENDER_WINDOW_MS] earlier,
 *   else goes Spent. Open + Matched folds the frame in. Open + NoMatch closes the visit and goes Idle. Spent + Matched
 *   stays; Spent + NoMatch goes Idle. An Unevaluated outcome changes nothing.
 * - An Open visit that is [MAX_VISIT_MS] old, or started on an earlier UTC day, closes at the candidate's next definite
 *   outcome and goes Spent, so a stay across midnight is one visit, counted on the day it started.
 * - A frame under another generation of the platform closes every Open visit of the old one and drops its states; a
 *   candidate missing from a frame's results (no longer active, or a boundary record with none) has its Open visit
 *   closed and its state dropped the same way.
 *
 * A closed visit credits the row it started on ([HealthKey] of its first frame), once, through
 * [HealthLedger.creditVisit]. The state lives in memory: process death loses an Open visit and the last start times,
 * so a restart between two starts under 3 s apart counts two visits. State built against one ledger generation is
 * dropped when [step] sees another, so a closure computed before a census reset never reaches the cleared ledger.
 *
 * Not thread-safe: the owner serializes [step] and [clear] (`PersistentHealthSink` holds its mutex).
 */
class ShadowVisits {

    private sealed interface State {
        data object Idle : State
        data class Open(val start: Long, val startKey: HealthKey, val fold: VisitFold) : State
        data object Spent : State
    }

    private class Track(var state: State = State.Idle, var lastStart: Long? = null)

    private var ledgerGeneration: Long? = null
    private val generations = HashMap<String, String>()
    private val tracks = HashMap<String, HashMap<String, Track>>()

    /** [ledger] after one shadow [frame] keyed [key] (the frame's own row). */
    fun step(ledger: HealthLedger, key: HealthKey, frame: ShadowFrameRecord): HealthLedger {
        if (ledgerGeneration != ledger.generation) {
            clear()
            ledgerGeneration = ledger.generation
        }
        var out = ledger
        val platform = key.platform
        val previous = generations[platform]
        if (previous != frame.generationId) {
            if (previous != null) {
                tracks.remove(platform)?.forEach { (digest, track) ->
                    val open = track.state as? State.Open ?: return@forEach
                    out = out.creditVisit(open.startKey, ShadowTally.entryKey(previous, digest), open.fold)
                }
            }
            generations[platform] = frame.generationId
        }
        out = out.shadowFrame(key, frame.generationId, frame.results)
        val platformTracks = tracks.getOrPut(platform) { HashMap() }
        val active = frame.results.mapTo(HashSet()) { it.digest }
        val gone = platformTracks.keys.filter { it !in active }
        for (digest in gone) {
            val open = platformTracks.remove(digest)?.state as? State.Open ?: continue
            out = out.creditVisit(open.startKey, ShadowTally.entryKey(frame.generationId, digest), open.fold)
        }
        for (result in frame.results) {
            if (result.outcome == ShadowOutcome.Unevaluated) continue
            val track = platformTracks.getOrPut(result.digest) { Track() }
            out = advance(out, track, ShadowTally.entryKey(frame.generationId, result.digest), result.outcome, key, frame)
        }
        return out
    }

    /** Drops every pending visit and last start time (census deletion and identity reset). */
    fun clear() {
        ledgerGeneration = null
        generations.clear()
        tracks.clear()
    }

    private fun advance(
        ledger: HealthLedger,
        track: Track,
        entry: String,
        outcome: ShadowOutcome,
        key: HealthKey,
        frame: ShadowFrameRecord,
    ): HealthLedger {
        var out = ledger
        var state = track.state
        val now = frame.timestampMillis
        if (state is State.Open && (now - state.start >= MAX_VISIT_MS || key.day != state.startKey.day)) {
            out = out.creditVisit(state.startKey, entry, state.fold)
            state = State.Spent
        }
        track.state = when (outcome) {
            is ShadowOutcome.Matched -> when (state) {
                State.Idle -> {
                    val last = track.lastStart
                    if (last == null || now - last >= VisitTiming.PRE_RENDER_WINDOW_MS) {
                        track.lastStart = now
                        State.Open(now, key, VisitFold().plus(frame.liveWon, outcome))
                    } else {
                        State.Spent
                    }
                }
                is State.Open -> state.copy(fold = state.fold.plus(frame.liveWon, outcome))
                State.Spent -> State.Spent
            }
            ShadowOutcome.NoMatch -> {
                if (state is State.Open) out = out.creditVisit(state.startKey, entry, state.fold)
                State.Idle
            }
            ShadowOutcome.Unevaluated -> state
        }
        return out
    }

    companion object {
        /** An Open visit this old closes at the candidate's next definite outcome. */
        const val MAX_VISIT_MS = 60_000L
    }
}
