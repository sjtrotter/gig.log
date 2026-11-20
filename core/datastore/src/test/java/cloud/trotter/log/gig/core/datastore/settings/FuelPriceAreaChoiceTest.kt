package cloud.trotter.log.gig.core.datastore.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.log.gig.domain.fuel.FuelArea
import cloud.trotter.log.gig.domain.fuel.FuelAreaChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #1687: the fuel price area setting is Automatic or Picked. An install from before Automatic keeps a hand-picked
 * area and turns the old U.S. default into Automatic; picking by hand turns Automatic off, and the detected area is
 * the only location-derived value stored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FuelPriceAreaChoiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.store(fileName: String): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
        produceFile = { File(tmp.root, fileName) },
    )

    /** A store as a build from before Automatic left it: the area code with no automatic flag. */
    private suspend fun DataStore<Preferences>.legacyArea(code: String) {
        edit { it[stringPreferencesKey("fuel_price_area")] = code }
    }

    @Test
    fun `nothing stored is Automatic with nothing detected, priced as the U_S_`() = runTest {
        val source = AppPreferencesDataSource(store("empty.preferences_pb"))

        assertEquals(FuelAreaChoice.Automatic(null), source.fuelPriceAreaChoice.first())
        assertEquals(FuelArea.US, source.fuelPriceArea.first())
    }

    @Test
    fun `a legacy hand-picked area survives the update as Picked`() = runTest {
        val ds = store("legacy-texas.preferences_pb")
        ds.legacyArea("STX")
        val source = AppPreferencesDataSource(ds)

        assertEquals(FuelAreaChoice.Picked(FuelArea.TEXAS), source.fuelPriceAreaChoice.first())
        assertEquals(FuelArea.TEXAS, source.fuelPriceArea.first())
    }

    @Test
    fun `the legacy U_S_ default becomes Automatic`() = runTest {
        val ds = store("legacy-us.preferences_pb")
        ds.legacyArea("NUS")
        val source = AppPreferencesDataSource(ds)

        assertEquals(FuelAreaChoice.Automatic(null), source.fuelPriceAreaChoice.first())
    }

    @Test
    fun `picking by hand turns Automatic off, and Automatic again reads the detected area`() = runTest {
        val source = AppPreferencesDataSource(store("pick.preferences_pb"))
        source.setDetectedFuelArea(FuelArea.TEXAS)
        assertEquals(FuelArea.TEXAS, source.fuelPriceArea.first())

        source.setFuelPriceArea(FuelArea.OHIO)
        assertEquals(FuelAreaChoice.Picked(FuelArea.OHIO), source.fuelPriceAreaChoice.first())
        assertEquals(FuelArea.OHIO, source.fuelPriceArea.first())

        source.setFuelPriceAreaAutomatic()
        assertEquals(FuelAreaChoice.Automatic(FuelArea.TEXAS), source.fuelPriceAreaChoice.first())
        assertEquals(FuelArea.TEXAS, source.fuelPriceArea.first())
    }

    @Test
    fun `a detected area never overrides a picked one`() = runTest {
        val source = AppPreferencesDataSource(store("picked-stays.preferences_pb"))
        source.setFuelPriceArea(FuelArea.OHIO)

        source.setDetectedFuelArea(FuelArea.HOUSTON)

        assertEquals(FuelAreaChoice.Picked(FuelArea.OHIO), source.fuelPriceAreaChoice.first())
        assertEquals(FuelArea.OHIO, source.fuelPriceArea.first())
    }

    @Test
    fun `a Costs reset keeps Automatic and the detected area`() = runTest {
        val source = AppPreferencesDataSource(store("reset.preferences_pb"))
        source.setDetectedFuelArea(FuelArea.HOUSTON)

        source.resetSharedEconomyDefaults()

        assertEquals(FuelAreaChoice.Automatic(FuelArea.HOUSTON), source.fuelPriceAreaChoice.first())
    }
}
