package cloud.trotter.log.gig.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import cloud.trotter.log.gig.core.data.fuel.FuelPriceRepository
import cloud.trotter.log.gig.core.data.fuel.FuelRefreshOutcome
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.domain.model.vehicle.FuelType
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The daily fuel refresh: a failure worth retrying asks WorkManager to retry, and an electric vehicle sends nothing. */
@RunWith(RobolectricTestRunner::class)
class DailyGasPriceWorkerTest {
    private val fuelPrices: FuelPriceRepository = mock()
    private val preferences: AppPreferencesRepository = mock()

    private fun worker(): DailyGasPriceWorker = TestListenableWorkerBuilder<DailyGasPriceWorker>(RuntimeEnvironment.getApplication())
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                DailyGasPriceWorker(appContext, workerParameters, fuelPrices, preferences)
        }).build()

    @Test fun `only a retryable failure asks for a retry`() {
        assertEquals(ListenableWorker.Result.retry(), DailyGasPriceWorker.resultOf(FuelRefreshOutcome.Failed(retry = true)))
        for (outcome in listOf(FuelRefreshOutcome.Failed(retry = false), FuelRefreshOutcome.Updated,
            FuelRefreshOutcome.Unchanged, FuelRefreshOutcome.Off)) {
            assertEquals("$outcome", ListenableWorker.Result.success(), DailyGasPriceWorker.resultOf(outcome))
        }
    }

    @Test fun `a gas vehicle refreshes and maps the outcome`(): Unit = runBlocking {
        whenever(preferences.fuelType).thenReturn(flowOf(FuelType.REGULAR))
        whenever(fuelPrices.refresh()).thenReturn(FuelRefreshOutcome.Failed(retry = true))

        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
    }

    @Test fun `an electric vehicle succeeds with no refresh`(): Unit = runBlocking {
        whenever(preferences.fuelType).thenReturn(flowOf(FuelType.ELECTRICITY))

        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        verify(fuelPrices, never()).refresh()
    }
}
