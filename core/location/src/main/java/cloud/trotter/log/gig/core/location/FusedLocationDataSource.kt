package cloud.trotter.log.gig.core.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import cloud.trotter.log.gig.domain.model.location.Coordinates
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The specific implementation that talks to Google's FusedLocationProviderClient.
 * It handles the "Hardware Kill Switch" via the awaitClose block.
 */
@Singleton
class FusedLocationDataSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : LocationDataSource {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission") // Permission is checked by the UI/Service before starting
    override val locationUpdates: Flow<Coordinates> = callbackFlow {
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { trySend(it.toCoordinates()) }
            }
        }

        // High accuracy, updates every 2-5 seconds
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L).apply {
            setMinUpdateIntervalMillis(2000L)
            // Optional: Don't wake up for micro-movements (saves battery)
            setMinUpdateDistanceMeters(5f)
        }.build()

        Timber.i("⚡ Starting GPS updates...")
        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { e ->
                Timber.e(e, "Failed to start GPS")
                close(e)
            }

        // --- THE KILL SWITCH ---
        // This runs automatically when the collector (OdometerRepository) stops listening.
        awaitClose {
            Timber.i("🛑 Stopping GPS updates (Hardware Off)")
            fusedClient.removeLocationUpdates(callback)
        }
    }

    /**
     * #1057/#918 — carry the fix's QUALITY, not just its position. `accuracy`/`time` were dropped on
     * the floor here, which is why [cloud.trotter.log.gig.domain.location.OdometerFixPolicy] had
     * nothing to judge an implausible fix with. `hasAccuracy()` is honoured rather than reading the
     * always-present `accuracy` field, so "no estimate" stays null instead of masquerading as 0 m.
     *
     * `elapsedRealtimeNanos` rides along as the ORDERING key (round 2): `time` is the settable
     * wall clock, and Android's own guidance is not to compare fixes with it — an NTP step-back
     * mid-drive used to stall the odometer for ~110 s. It is always present on a real `Location`.
     */
    private fun Location.toCoordinates(): Coordinates = Coordinates(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
        timestampMs = time,
        monotonicMs = elapsedRealtimeNanos / 1_000_000L,
    )
}
