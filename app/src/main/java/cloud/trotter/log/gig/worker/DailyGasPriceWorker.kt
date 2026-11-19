package cloud.trotter.log.gig.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import cloud.trotter.log.gig.core.data.fuel.FuelPriceRepository
import cloud.trotter.log.gig.core.data.fuel.FuelRefreshOutcome
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.domain.model.vehicle.FuelType
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * The daily fuel feed refresh, through WorkManager. The feed channel checks "Auto gas price" before every request, so
 * with it off nothing is sent; the fuel client's follow writes any new price. An electric vehicle needs no gas price.
 */
@HiltWorker
class DailyGasPriceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val fuelPriceRepository: FuelPriceRepository,
    private val appPreferencesRepository: AppPreferencesRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (appPreferencesRepository.fuelType.first() == FuelType.ELECTRICITY) return Result.success()
        return resultOf(fuelPriceRepository.refresh())
    }

    companion object {
        /** A failure worth retrying asks WorkManager for a retry; every other outcome waits for the next day. */
        fun resultOf(outcome: FuelRefreshOutcome): ListenableWorker.Result = when (outcome) {
            is FuelRefreshOutcome.Failed -> if (outcome.retry) {
                Timber.tag("FuelPrice").w("daily fuel refresh failed; WorkManager retries later")
                ListenableWorker.Result.retry()
            } else {
                ListenableWorker.Result.success()
            }
            FuelRefreshOutcome.Updated, FuelRefreshOutcome.Unchanged, FuelRefreshOutcome.Off -> ListenableWorker.Result.success()
        }
    }
}
