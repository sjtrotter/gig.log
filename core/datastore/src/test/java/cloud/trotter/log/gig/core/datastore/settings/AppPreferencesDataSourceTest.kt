package cloud.trotter.log.gig.core.datastore.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.log.gig.domain.evaluation.EconomyField
import cloud.trotter.log.gig.domain.fuel.FuelArea
import cloud.trotter.log.gig.domain.fuel.FuelPriceStamp
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AppPreferencesDataSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newSource(dispatcher: TestDispatcher, fileName: String): AppPreferencesDataSource {
        val ds = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatcher + Job()),
            produceFile = { File(tmp.root, fileName) },
        )
        return AppPreferencesDataSource(ds)
    }

    @Test
    fun `vehicle lookup defaults false and round trips`() = runTest {
        val source = newSource(StandardTestDispatcher(testScheduler), "vehicle-lookup.preferences_pb")
        assertEquals(false, source.vehicleLookupAllowed.first())
        source.setVehicleLookupAllowed(true)
        assertEquals(true, source.vehicleLookupAllowed.first())
        source.setVehicleLookupAllowed(false)
        assertEquals(false, source.vehicleLookupAllowed.first())
    }

    @Test
    fun `gas price auto refresh defaults to false`() = runTest {
        val source = newSource(StandardTestDispatcher(testScheduler), "gas-default.preferences_pb")
        assertEquals(false, source.isGasPriceAuto.first())
    }

    // #722 — the bubble's mode-adaptive gas quick-edit write paths.

    @Test
    fun `updateGasPriceManual writes the price and disables auto in one atomic edit`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = newSource(dispatcher, "prefs3.preferences_pb")

        source.updateGasPriceManual(3.29f)
        advanceUntilIdle()

        assertEquals(3.29f, source.gasPrice.first())
        assertEquals(false, source.isGasPriceAuto.first())
    }

    @Test
    fun `updateGasPriceAuto writes the price and re-enables auto in one atomic edit`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = newSource(dispatcher, "prefs4.preferences_pb")

        // Start manual (as the stepper would leave it), then "Resume auto" should flip both back.
        source.updateGasPriceManual(3.29f)
        advanceUntilIdle()

        source.updateGasPriceAuto(3.61f, st(1_000L))
        advanceUntilIdle()

        assertEquals(3.61f, source.gasPrice.first())
        assertEquals(true, source.isGasPriceAuto.first())
    }

    // The feed stamp travels with a feed price and is left alone by a typed one.

    /** A stamp whose issuedAt differs from its period, so a test reading the wrong half fails. */
    private fun st(period: Long) = FuelPriceStamp(period, period + 1)

    @Test
    fun `the fuel price area defaults to the U_S_, round trips, and survives a Costs reset`() = runTest {
        val source = newSource(StandardTestDispatcher(testScheduler), "fuel-area.preferences_pb")
        assertEquals(FuelArea.US, source.fuelPriceArea.first())
        source.setFuelPriceArea(FuelArea.HOUSTON)
        assertEquals(FuelArea.HOUSTON, source.fuelPriceArea.first())

        source.resetSharedEconomyDefaults()

        assertEquals(FuelArea.HOUSTON, source.fuelPriceArea.first())
    }

    @Test
    fun `a Costs reset removes the price, the auto flag and both halves of the stamp`() = runTest {
        val source = newSource(StandardTestDispatcher(testScheduler), "gas-reset.preferences_pb")
        source.updateGasPriceAuto(3.61f, st(1_000L))

        source.resetSharedEconomyDefaults()

        assertNull(source.gasPrice.first())
        assertEquals(false, source.isGasPriceAuto.first())
        assertNull(source.gasPriceStamp.first())
    }

    @Test
    fun `updateGasPrice writes the price, period and issuedAt in one edit`() = runTest {
        val source = newSource(StandardTestDispatcher(testScheduler), "gas-stamp.preferences_pb")
        assertNull(source.gasPriceStamp.first())
        source.setGasPriceAuto(true)

        source.updateGasPrice(3.29f, st(1_700_000_000_000L))

        assertEquals(3.29f, source.gasPrice.first())
        assertEquals(st(1_700_000_000_000L), source.gasPriceStamp.first())
    }

    @Test
    fun `updateGasPriceAuto writes the stamp with the price and auto`() = runTest {
        val source = newSource(StandardTestDispatcher(testScheduler), "gas-auto-stamp.preferences_pb")
        source.updateGasPriceManual(4.00f)

        source.updateGasPriceAuto(3.61f, st(1_700_000_000_000L))

        assertEquals(3.61f, source.gasPrice.first())
        assertEquals(true, source.isGasPriceAuto.first())
        assertEquals(st(1_700_000_000_000L), source.gasPriceStamp.first())
    }

    @Test
    fun `updateGasPriceManual leaves the stamp alone`() = runTest {
        val source = newSource(StandardTestDispatcher(testScheduler), "gas-manual-stamp.preferences_pb")
        source.updateGasPriceAuto(3.61f, st(1_700_000_000_000L))

        source.updateGasPriceManual(4.00f)

        assertEquals(4.00f, source.gasPrice.first())
        assertEquals(false, source.isGasPriceAuto.first())
        assertEquals(st(1_700_000_000_000L), source.gasPriceStamp.first())
    }

    @Test
    fun `turning auto back on over a typed price drops the stamp, and the wizard save sets or removes it`() = runTest {
        val source = newSource(StandardTestDispatcher(testScheduler), "gas-auto-on-stamp.preferences_pb")
        source.updateGasPriceAuto(3.61f, st(1_000L))
        source.setGasPriceAuto(true)
        assertEquals(st(1_000L), source.gasPriceStamp.first())

        source.updateGasPriceManual(4.00f)
        source.setGasPriceAuto(true)
        assertNull(source.gasPriceStamp.first())

        source.updateEconomySettings("", "", "", "", 30f, true, 3.40f, stamp = st(2_000L))
        assertEquals(st(2_000L), source.gasPriceStamp.first())
        source.updateEconomySettings("", "", "", "", 30f, true, 3.90f, stamp = null)
        assertNull(source.gasPriceStamp.first())

        source.updateGasPrice(3.50f, st(3_000L))
        source.resetSharedEconomyDefaults()
        assertNull(source.gasPriceStamp.first())
    }

    @Test
    fun `a wizard save of a price typed over an old lookup removes the stamp even with auto on throughout`() = runTest {
        // Stored auto on + stamp; in the wizard the dasher typed (auto off in the VM), turned auto back on, the
        // lookup failed, and saved: storage never saw auto off, so only the save itself can drop the stamp.
        val source = newSource(StandardTestDispatcher(testScheduler), "gas-wizard-typed.preferences_pb")
        source.updateGasPriceAuto(3.29f, st(1_000L))

        source.updateEconomySettings("", "", "", "", 30f, true, 3.75f, stamp = null)

        assertEquals(3.75f, source.gasPrice.first())
        assertEquals(true, source.isGasPriceAuto.first())
        assertNull(source.gasPriceStamp.first())
    }

    @Test
    fun `a lookup that returns after a typed price leaves the typed price and no new stamp`() = runTest {
        val source = newSource(StandardTestDispatcher(testScheduler), "gas-late-lookup.preferences_pb")
        source.updateGasPriceAuto(3.29f, st(1_000L))
        source.updateGasPriceManual(4.00f)

        assertEquals(false, source.updateGasPrice(3.31f, st(2_000L)))

        assertEquals(4.00f, source.gasPrice.first())
        assertEquals(false, source.isGasPriceAuto.first())
        assertEquals(st(1_000L), source.gasPriceStamp.first())
    }

    @Test
    fun `a manual price undo restores the price's stamp and refuses when the stamp changed`() = runTest {
        val source = newSource(StandardTestDispatcher(testScheduler), "gas-undo-stamp.preferences_pb")
        source.updateGasPriceAuto(3.10f, st(500L))
        source.updateGasPrice(3.29f, st(1_000L))
        val previous = source.updateGasPriceManual(4.00f)
        assertEquals(StoredPreference(1_000L, false), previous.period)
        assertEquals(StoredPreference(1_001L, false), previous.issuedAt)
        val expected = StoredGasPrice(StoredPreference(4.00f, false), StoredPreference(false, false), StoredPreference(1_000L, false), StoredPreference(1_001L, false))
        source.updateGasPrice(3.31f, st(2_000L)) // a late lookup: skipped, auto is off

        assertTrue(source.restoreGasPriceManual(previous, expected))
        assertEquals(3.29f, source.gasPrice.first())
        assertEquals(true, source.isGasPriceAuto.first())
        assertEquals(st(1_000L), source.gasPriceStamp.first())

        val next = source.updateGasPriceManual(4.00f)
        // Same price and auto, different stamp: the wizard saved this typed price, dropping the stamp.
        source.updateEconomySettings("", "", "", "", 30f, false, 4.00f, stamp = null)
        assertEquals(false, source.restoreGasPriceManual(next, expected))
        assertEquals(4.00f, source.gasPrice.first())
        assertNull(source.gasPriceStamp.first())
    }

    @Test fun `time overrides set and clear independently and reject invalid inputs`() = runTest {
        val source = newSource(StandardTestDispatcher(testScheduler), "time.preferences_pb")
        source.setTimeConstantOverride(EconomyField.AVG_MIN_PER_MILE, 2.5)
        assertEquals(setOf(EconomyField.AVG_MIN_PER_MILE.name), source.userSetEconomyFields.first())
        assertNull(source.basePickupMin.first())
        source.setTimeConstantOverride(EconomyField.BASE_PICKUP_MIN, 0.0)
        source.setTimeConstantOverride(EconomyField.AVG_MIN_PER_MILE, null)
        assertNull(source.avgMinPerMile.first())
        assertEquals(0.0, source.basePickupMin.first()!!, 0.0)
        assertEquals(setOf(EconomyField.BASE_PICKUP_MIN.name), source.userSetEconomyFields.first())
        source.setTimeConstantOverride(EconomyField.BASE_PICKUP_MIN, null)
        assertTrue(source.userSetEconomyFields.first().isEmpty())
        for ((field, value) in listOf(EconomyField.AVG_MIN_PER_MILE to 0.0,
            EconomyField.AVG_MIN_PER_MILE to Double.NaN, EconomyField.BASE_PICKUP_MIN to -1.0,
            EconomyField.BASE_PICKUP_MIN to Double.POSITIVE_INFINITY, EconomyField.TIRE_COST to 3.0)) {
            assertTrue(runCatching { source.setTimeConstantOverride(field, value) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun `manual price undo restores 350 and auto on together`() = runTest {
        val source = newSource(StandardTestDispatcher(testScheduler), "gas-undo.preferences_pb")
        source.updateGasPriceAuto(3.50f, st(1_000L))
        val previous = source.updateGasPriceManual(3.75f)
        assertEquals(false, source.isGasPriceAuto.first())
        assertTrue(source.restoreGasPriceManual(previous,
            StoredGasPrice(StoredPreference(3.75f, false), StoredPreference(false, false), StoredPreference(1_000L, false), StoredPreference(1_001L, false))))
        assertEquals(3.50f, source.gasPrice.first())
        assertEquals(true, source.isGasPriceAuto.first())
    }

    @Test fun `shared restores check value marker and submission validity inside edit`() = runTest {
        val source = newSource(StandardTestDispatcher(testScheduler), "cas.preferences_pb")
        val previous = source.updatePhoneTotal(100.0)
        val expected = StoredPreference(100.0, true)
        assertEquals(false, source.restorePhoneTotal(previous, expected) { false })
        assertEquals(100.0, source.phonePlanTotal.first())
        // Same value with a different marker must also fail CAS.
        assertTrue(source.restorePhoneTotal(StoredPreference(100.0, false), expected))
        assertEquals(false, source.restorePhoneTotal(previous, expected))
        assertEquals(100.0, source.phonePlanTotal.first())
        assertTrue(source.userSetEconomyFields.first().isEmpty())
        source.updatePhoneTotal(110.0)
        assertEquals(false, source.restorePhoneTotal(previous, expected))
        assertEquals(110.0, source.phonePlanTotal.first())
        val lines = source.updatePhoneLines(3)
        source.updatePhoneLines(4)
        assertEquals(false, source.restorePhoneLines(lines, StoredPreference(3, true)))
        val share = source.updatePhoneShare(40.0)
        source.updatePhoneShare(50.0)
        assertEquals(false, source.restorePhoneShare(share, StoredPreference(40.0, true)))
        val tariff = source.updateElectricityPrice(.25)
        source.updateElectricityPrice(.30)
        assertEquals(false, source.restoreElectricityPrice(tariff, StoredPreference(.25, true)))
        val time = source.setTimeConstantOverride(EconomyField.AVG_MIN_PER_MILE, 4.0)
        source.setTimeConstantOverride(EconomyField.AVG_MIN_PER_MILE, null)
        assertEquals(false, source.restoreTimeConstantOverride(EconomyField.AVG_MIN_PER_MILE, time, StoredPreference(4.0, true)))
    }

    @Test fun `capture survives failure after datastore transform without recapturing`() = runTest {
        val delegate = PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { File(tmp.root, "capture.preferences_pb") })
        var failOnce = true
        val source = AppPreferencesDataSource(object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = delegate.data
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences) =
                delegate.updateData {
                    val updated = transform(it)
                    if (failOnce) { failOnce = false; throw java.io.IOException("failed disk write") }
                    updated
                }
        })
        val capture = SaveCapture<StoredPreference<Double>>()
        assertTrue(runCatching { source.updatePhoneTotal(100.0, capture) }.exceptionOrNull() is java.io.IOException)
        assertNull(source.phonePlanTotal.first())
        // An intervening stored state exposes accidental recapture on retry.
        source.updatePhoneTotal(90.0)
        val previous = source.updatePhoneTotal(100.0, capture)
        assertEquals(StoredPreference<Double>(null, false), previous)
        assertTrue(source.restorePhoneTotal(previous, StoredPreference(100.0, true)))
        assertNull(source.phonePlanTotal.first())
        assertTrue(source.userSetEconomyFields.first().isEmpty())
    }

}
