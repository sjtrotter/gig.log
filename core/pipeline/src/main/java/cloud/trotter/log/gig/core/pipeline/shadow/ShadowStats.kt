package cloud.trotter.log.gig.core.pipeline.shadow

import cloud.trotter.log.gig.core.pipeline.rules.CandidateOutcome
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * The shadow worker's counters (#1630), rendered as one part of the periodic `PipelineStats` summary. Counts and
 * timings only, never a rule id or frame text, so the shareable INFO line stays PII-free by construction.
 *
 * `medianMs` is over the last [WINDOW] evaluated frames; `maxMs` over the process.
 */
class ShadowStats {
    private val frames = AtomicLong()
    private val dropped = AtomicLong()
    private val skipped = AtomicLong()
    private val errors = AtomicLong()
    private val matched = AtomicLong()
    private val noMatch = AtomicLong()
    private val budget = AtomicLong()
    private val failed = AtomicLong()

    private val lock = Any()
    private val recentNanos = LongArray(WINDOW)
    private var recentCount = 0
    private var nextSlot = 0
    private var maxNanos = 0L

    /** The worker's queue was full: the frame was dropped unevaluated. */
    fun onDropped() {
        dropped.incrementAndGet()
    }

    /** The frame carried a sensitive marker in its tree or window title: no candidate saw it. */
    fun onSkipped() {
        skipped.incrementAndGet()
    }

    /** The worker failed outside the candidate engine; the frame recorded nothing. */
    fun onError() {
        errors.incrementAndGet()
    }

    /** One evaluated frame: each candidate's [outcomes] and the frame's evaluation time. */
    fun onFrame(outcomes: List<CandidateOutcome>, nanos: Long) {
        frames.incrementAndGet()
        for (outcome in outcomes) {
            when (outcome) {
                is CandidateOutcome.Matched -> matched
                CandidateOutcome.NoMatch -> noMatch
                is CandidateOutcome.Unevaluated -> when (outcome.reason) {
                    CandidateOutcome.Reason.BUDGET -> budget
                    CandidateOutcome.Reason.FAILED -> failed
                }
            }.incrementAndGet()
        }
        synchronized(lock) {
            recentNanos[nextSlot] = nanos
            nextSlot = (nextSlot + 1) % WINDOW
            if (recentCount < WINDOW) recentCount++
            if (nanos > maxNanos) maxNanos = nanos
        }
    }

    fun droppedCount(): Long = dropped.get()
    fun skippedCount(): Long = skipped.get()
    fun frameCount(): Long = frames.get()

    /** `" shadow{frames=…,dropped=…,…}"`, or empty while the worker has seen nothing. */
    fun suffix(): String {
        if (frames.get() == 0L && dropped.get() == 0L && skipped.get() == 0L && errors.get() == 0L) return ""
        val (median, max) = synchronized(lock) {
            val sorted = recentNanos.copyOf(recentCount).also { it.sort() }
            (if (sorted.isEmpty()) 0L else sorted[sorted.size / 2]) to maxNanos
        }
        val errorPart = errors.get().let { if (it == 0L) "" else ",errors=$it" }
        return " shadow{frames=${frames.get()},dropped=${dropped.get()},skipped=${skipped.get()}," +
            "matched=${matched.get()},noMatch=${noMatch.get()},budget=${budget.get()},failed=${failed.get()}," +
            "medianMs=${ms(median)},maxMs=${ms(max)}$errorPart}"
    }

    private fun ms(nanos: Long): String = String.format(Locale.ROOT, "%.2f", nanos / 1_000_000.0)

    private companion object {
        const val WINDOW = 256
    }
}
