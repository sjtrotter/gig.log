package cloud.trotter.log.gig.core.pipeline.census

import cloud.trotter.log.gig.core.pipeline.ParseRotTracker
import cloud.trotter.log.gig.core.pipeline.PipelineStats
import cloud.trotter.log.gig.core.pipeline.RotVisitConfirmations
import cloud.trotter.log.gig.domain.capture.CensusRecord
import cloud.trotter.log.gig.domain.capture.CensusSink
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #1573: holds a matched skeleton until the tracker confirms its visit, so a pre-render frame (a screen drawn before
 * its values land, which reads as "the field moved") never spends the rule's one sample of the day.
 *
 * At most one record per (platform, rule): the latest rot frame of the visit, replacing the one before. On a
 * confirmation of that rule's visit that started at `start` ([onConfirmed]) the record is offered to the sink only when
 * the rule is still in an open incident and the frame is that visit's (`start <= held < start + PRE_RENDER_WINDOW_MS`);
 * a record older than the confirmed visit is dropped, and a newer one waits for its own visit. A visit the tracker
 * discards (a healthy parse inside the window) sends no confirmation, so its record waits until the next visit drops
 * it. There is no time-based expiry: a confirmation comes on the platform's next admitted frame, which can be many
 * seconds later; at most [MAX_ENTRIES] records are held (the oldest is evicted), and a record whose rule left the open
 * incident set is dropped on the next [hold] or [onConfirmed] of its platform.
 *
 * Memory only: a restart loses what is held and nothing is sent for it. Each record carries the census clear boundary
 * read when its frame was admitted, so a census deletion while it is held drops it at the spool.
 */
@Singleton
class MatchedSkeletonHold @Inject constructor(
    private val sink: CensusSink,
    private val stats: PipelineStats,
) : RotVisitConfirmations {

    private class Held(val timestamp: Long, val record: CensusRecord, val boundary: Long)

    private val lock = Any()

    /** Insertion order is age: a replaced record is re-inserted, so the first entry is the oldest. */
    private val held = LinkedHashMap<Pair<String, String>, Held>()

    /**
     * Hold [record], built from the frame at [timestamp] that carries rot of [ruleId] on [platformWire]. [boundary] is
     * the clear boundary read at the frame's admission; [openRuleIds] are the platform's open parse-rot rules now.
     */
    fun hold(platformWire: String, ruleId: String, timestamp: Long, record: CensusRecord, boundary: Long, openRuleIds: Set<String>) {
        synchronized(lock) {
            dropClosed(platformWire, openRuleIds)
            if (ruleId !in openRuleIds) return
            val key = platformWire to ruleId
            if (held.remove(key) == null && held.size >= MAX_ENTRIES) {
                held.remove(held.keys.first())
                stats.onMatchedDropped()
            }
            held[key] = Held(timestamp, record, boundary)
        }
        stats.onMatchedHeld()
    }

    override fun onConfirmed(platformWire: String, ruleId: String, start: Long, openRuleIds: Set<String>) {
        val release = synchronized(lock) {
            dropClosed(platformWire, openRuleIds)
            val key = platformWire to ruleId
            val entry = held[key] ?: return
            when {
                entry.timestamp < start -> {
                    held.remove(key)
                    stats.onMatchedDropped()
                    null
                }
                entry.timestamp < start + ParseRotTracker.PRE_RENDER_WINDOW_MS -> {
                    held.remove(key)
                    entry
                }
                // A frame of a later visit: it waits for that visit's confirmation.
                else -> null
            }
        } ?: return
        if (sink.offer(release.record, release.boundary)) stats.onMatchedReleased() else stats.onCensusSinkRefused()
    }

    /** Held records (tests). */
    internal fun size(): Int = synchronized(lock) { held.size }

    /** Called under [lock]: drops the platform's records whose rule left the open incident set. */
    private fun dropClosed(platformWire: String, openRuleIds: Set<String>) {
        val closed = held.keys.filter { (wire, rule) -> wire == platformWire && rule !in openRuleIds }
        closed.forEach { held.remove(it) }
        repeat(closed.size) { stats.onMatchedDropped() }
    }

    companion object {
        /** The most records held at once, over every platform. */
        const val MAX_ENTRIES = 16
    }
}
