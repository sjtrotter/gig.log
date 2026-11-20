package cloud.trotter.log.gig.core.data.fuel

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.log.gig.core.data.feed.FuelFeedSwitch
import cloud.trotter.log.gig.core.data.feed.MemoryPreferences
import cloud.trotter.log.gig.core.data.location.OdometerRepository
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.data.vehicle.GarageRepository
import cloud.trotter.log.gig.core.datastore.settings.AppPreferencesDataSource
import cloud.trotter.log.gig.core.datastore.vehicle.VehicleDataSource
import cloud.trotter.log.gig.domain.fuel.FuelArea
import cloud.trotter.log.gig.domain.fuel.FuelAreaChoice
import cloud.trotter.log.gig.domain.fuel.FuelAreaLocator
import cloud.trotter.log.gig.domain.model.location.Coordinates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import timber.log.Timber
import java.io.File

/**
 * #1687: the odometer's accepted fixes pick the fuel price area on the phone, only while "Auto gas price" is on and the
 * area is on Automatic, at most once per ten minutes of fix time; a fix outside every state keeps the area.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FuelAreaDetectorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fixes = MutableSharedFlow<Coordinates>(extraBufferCapacity = 8)
    private val odometer: OdometerRepository = mock { on { acceptedFixes } doReturn fixes }

    private class Recorder : Timber.Tree() {
        val lines = mutableListOf<Triple<Int, String?, String>>()
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            lines += Triple(priority, tag, message)
        }
    }

    private val recorder = Recorder()

    @Before fun plant() = Timber.plant(recorder)

    /** The detector runs on the test scheduler, not `backgroundScope`, which `advanceUntilIdle` does not drain. */
    private val detectorJob = Job()

    @After fun uproot() {
        detectorJob.cancel()
        Timber.uproot(recorder)
    }

    private class Fixture(
        val preferences: AppPreferencesRepository,
        val detector: FuelAreaDetector,
        val scope: CoroutineScope,
    ) {
        fun start() = detector.start(scope)
    }

    private fun TestScope.fixture(
        locate: (Double, Double) -> FuelArea? = { lat, lon -> FuelAreaLocator.locate(lat, lon) },
    ): Fixture {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = AppPreferencesDataSource(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(dispatcher + Job()),
                produceFile = { File(tmp.root, "prefs.preferences_pb") },
            ),
        )
        val preferences = AppPreferencesRepository(
            store, GarageRepository(VehicleDataSource(MemoryPreferences())), FuelFeedSwitch(store, backgroundScope),
        )
        return Fixture(
            preferences, FuelAreaDetector(preferences, odometer, dispatcher, locate), CoroutineScope(dispatcher + detectorJob),
        )
    }

    /** A fix at [minute] minutes of fix time on both clocks. */
    private fun fix(latitude: Double, longitude: Double, minute: Long) =
        Coordinates(latitude, longitude, accuracyMeters = 8.0, timestampMs = minute * 60_000L, monotonicMs = minute * 60_000L)

    private suspend fun TestScope.drive(fix: Coordinates) {
        fixes.emit(fix)
        advanceUntilIdle()
    }

    private val houston = 29.7604 to -95.3698
    private val austin = 30.2672 to -97.7431

    private suspend fun Fixture.choice() = preferences.fuelPriceAreaChoice.first()

    @Test fun `on Automatic a fix picks the area, a second within ten minutes waits, and one after does not`() = runTest {
        val f = fixture()
        f.preferences.setGasPriceAuto(true)
        f.start()
        advanceUntilIdle()

        drive(fix(houston.first, houston.second, minute = 0))
        assertEquals(FuelAreaChoice.Automatic(FuelArea.HOUSTON), f.choice())

        drive(fix(austin.first, austin.second, minute = 2))
        assertEquals("throttled: no lookup", FuelAreaChoice.Automatic(FuelArea.HOUSTON), f.choice())

        drive(fix(austin.first, austin.second, minute = 13))
        assertEquals(FuelAreaChoice.Automatic(FuelArea.TEXAS), f.choice())
        assertEquals(FuelArea.TEXAS, f.preferences.fuelPriceArea.first())
    }

    @Test fun `a fix outside every state keeps the area`() = runTest {
        val f = fixture()
        f.preferences.setGasPriceAuto(true)
        f.start()
        advanceUntilIdle()

        drive(fix(houston.first, houston.second, minute = 0))
        drive(fix(26.0, -90.0, minute = 11))

        assertEquals(FuelAreaChoice.Automatic(FuelArea.HOUSTON), f.choice())
    }

    @Test fun `a picked area is never touched by a fix`() = runTest {
        val f = fixture()
        f.preferences.setGasPriceAuto(true)
        f.preferences.setFuelPriceArea(FuelArea.OHIO)
        f.start()
        advanceUntilIdle()

        drive(fix(houston.first, houston.second, minute = 0))
        assertEquals(FuelAreaChoice.Picked(FuelArea.OHIO), f.choice())

        // Nothing was detected meanwhile: back on Automatic, the U.S. applies until the next fix.
        f.preferences.setFuelPriceAreaAutomatic()
        advanceUntilIdle()
        assertEquals(FuelAreaChoice.Automatic(null), f.choice())
    }

    @Test fun `with auto gas price off no fix is looked up`() = runTest {
        var lookups = 0
        val f = fixture { lat, lon -> lookups++; FuelAreaLocator.locate(lat, lon) }
        f.start()
        advanceUntilIdle()

        drive(fix(houston.first, houston.second, minute = 0))

        assertEquals(0, lookups)
        assertEquals(FuelAreaChoice.Automatic(null), f.choice())
    }

    @Test fun `turning Automatic back on looks up the next fix at once`() = runTest {
        val f = fixture()
        f.preferences.setGasPriceAuto(true)
        f.start()
        advanceUntilIdle()
        drive(fix(houston.first, houston.second, minute = 0))

        f.preferences.setFuelPriceArea(FuelArea.OHIO)
        advanceUntilIdle()
        f.preferences.setFuelPriceAreaAutomatic()
        advanceUntilIdle()
        drive(fix(austin.first, austin.second, minute = 1))

        assertEquals(FuelAreaChoice.Automatic(FuelArea.TEXAS), f.choice())
    }

    @Test fun `unreadable outlines log one WARN with no coordinates and stop detection`() = runTest {
        var lookups = 0
        val f = fixture { _, _ -> lookups++; throw IllegalArgumentException("Bad outline magic") }
        f.preferences.setGasPriceAuto(true)
        f.start()
        advanceUntilIdle()

        drive(fix(houston.first, houston.second, minute = 0))
        drive(fix(austin.first, austin.second, minute = 20))

        assertEquals("stopped after the first failure", 1, lookups)
        assertEquals(FuelAreaChoice.Automatic(null), f.choice())
        val warns = recorder.lines.filter { it.first == android.util.Log.WARN && it.second == "FuelArea" }
        assertEquals(1, warns.size)
    }

    @Test fun `no log line carries a coordinate, and INFO names no area`() = runTest {
        val f = fixture()
        f.preferences.setGasPriceAuto(true)
        f.start()
        advanceUntilIdle()

        drive(fix(houston.first, houston.second, minute = 0))

        val mine = recorder.lines.filter { it.second == "FuelArea" }
        assertTrue(mine.any { it.first == android.util.Log.DEBUG && it.third.contains(FuelArea.HOUSTON.code) })
        assertEquals(listOf("fuel price area changed (automatic)"), mine.filter { it.first == android.util.Log.INFO }.map { it.third })
        for ((_, _, message) in mine) {
            assertTrue(message, !message.contains("29.7") && !message.contains("95.3"))
        }
    }
}
