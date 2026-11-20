package cloud.trotter.log.gig.core.data.fuel

import cloud.trotter.log.gig.core.data.location.OdometerRepository
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.domain.di.IoDispatcher
import cloud.trotter.log.gig.domain.fuel.FuelArea
import cloud.trotter.log.gig.domain.fuel.FuelAreaChoice
import cloud.trotter.log.gig.domain.fuel.FuelAreaLocator
import cloud.trotter.log.gig.domain.model.location.Coordinates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The automatic fuel price area (#1687): while "Auto gas price" is on and the area is on Automatic, the GPS fixes the
 * odometer already accepts during a shift ([OdometerRepository.acceptedFixes]) pick the area on the phone
 * ([FuelAreaLocator], against outlines bundled in the app). It makes no location request of its own.
 *
 * Only the resulting area code is stored ([AppPreferencesRepository.setDetectedFuelArea]); the fix is dropped after the
 * lookup, never stored or logged, and nothing about it is sent. A fix outside every state keeps the area it had. The
 * lookup runs on the first fix after collection (re)starts, then at most once per [FuelAreaThrottle.INTERVAL_MS].
 *
 * Logging (tag `FuelArea`): DEBUG names the new area's code; INFO says only that it changed, since a state is the
 * dasher's location and INFO reaches the exported bug report. No coordinates at any level. If the bundled outlines
 * cannot be read, one WARN and the detector stops for the life of the process; the area stays as it was.
 */
@Singleton
class FuelAreaDetector internal constructor(
    private val preferences: AppPreferencesRepository,
    private val odometer: OdometerRepository,
    private val io: CoroutineDispatcher,
    private val locate: (latitude: Double, longitude: Double) -> FuelArea?,
) {
    @Inject constructor(
        preferences: AppPreferencesRepository,
        odometer: OdometerRepository,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(preferences, odometer, io, { latitude, longitude -> FuelAreaLocator.locate(latitude, longitude) })

    private val started = AtomicBoolean(false)

    /** Starts detecting, once per process. Called at app start. */
    fun start(scope: CoroutineScope) {
        if (started.compareAndSet(false, true)) scope.launch { detect() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun detect() {
        val on = combine(preferences.isGasPriceAuto, preferences.fuelPriceAreaChoice) { auto, choice ->
            auto && choice is FuelAreaChoice.Automatic
        }.distinctUntilChanged()
        try {
            on.flatMapLatest { if (it) dueFixes() else emptyFlow() }.collect { fix -> lookUp(fix) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: OutlinesUnavailable) {
            // Logged where it happened; the area stays as it was for the rest of the process.
        } catch (t: Throwable) {
            Timber.tag(TAG).w("fuel area detection stopped (%s)", t.javaClass.simpleName)
        }
    }

    /** The accepted fixes due for a lookup: the first after (re)subscribing, then one per interval. */
    private fun dueFixes(): Flow<Coordinates> = flow {
        var last: Long? = null
        odometer.acceptedFixes.collect { fix ->
            val at = FuelAreaThrottle.clockOf(fix)
            if (FuelAreaThrottle.due(last, at)) {
                last = at
                emit(fix)
            }
        }
    }

    private suspend fun lookUp(fix: Coordinates) {
        val area = try {
            withContext(io) { locate(fix.latitude, fix.longitude) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w("state outlines unavailable (%s); fuel area detection off", e.javaClass.simpleName)
            throw OutlinesUnavailable()
        }
        if (area == null) return
        val choice = preferences.fuelPriceAreaChoice.first()
        if (choice !is FuelAreaChoice.Automatic || choice.detected == area) return
        try {
            preferences.setDetectedFuelArea(area)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w("fuel area not saved (%s)", e.javaClass.simpleName)
            return
        }
        Timber.tag(TAG).d("fuel area detected: %s", area.code)
        Timber.tag(TAG).i("fuel price area changed (automatic)")
    }

    private class OutlinesUnavailable : RuntimeException()

    private companion object {
        const val TAG = "FuelArea"
    }
}

/** When a fix is due for an area lookup. Pure. */
internal object FuelAreaThrottle {
    /** At most one lookup per this many ms of fix time. */
    const val INTERVAL_MS = 10 * 60_000L

    /**
     * A fix's time for the throttle: the monotonic clock when the fix has it (it cannot be stepped by a clock
     * correction, [Coordinates.monotonicMs]), else the wall clock, else null.
     */
    fun clockOf(fix: Coordinates): Long? = fix.monotonicMs ?: fix.timestampMs

    /**
     * True for the first fix ([lastMs] null), a fix with no clock, a fix [INTERVAL_MS] or more after the last lookup,
     * and a fix whose clock went backwards (the wall clock was stepped).
     */
    fun due(lastMs: Long?, nowMs: Long?): Boolean =
        lastMs == null || nowMs == null || nowMs < lastMs || nowMs - lastMs >= INTERVAL_MS
}
