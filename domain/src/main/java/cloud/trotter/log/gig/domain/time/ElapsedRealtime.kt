package cloud.trotter.log.gig.domain.time

/**
 * Milliseconds since boot, counting deep sleep (`SystemClock.elapsedRealtime()` in the app; tests pass their own).
 * Never set by the dasher or the network, so an interval measured with it survives a wall-clock correction (#640).
 */
fun interface ElapsedRealtime {
    fun millis(): Long
}
