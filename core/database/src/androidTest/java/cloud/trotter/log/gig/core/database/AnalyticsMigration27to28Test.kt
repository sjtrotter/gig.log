package cloud.trotter.log.gig.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** #1601: v27 → v28 adds origin/settlementRevision/endReason to `receipt_match_rows`; an existing match is the dasher's. */
@RunWith(AndroidJUnit4::class)
class AnalyticsMigration27to28Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), GigLogDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun anExistingMatchRowBecomesTheDashersAndStaysActive() {
        val name = "migration-27-to-28"
        helper.createDatabase(name, 27).use { db ->
            db.execSQL("""INSERT INTO receipt_match_rows
                (commandId, deliveryEventSequenceId, sessionId, evidenceId, role, matchEventSequenceId, matchedAt,
                 priorRealizedPay, priorPayBasis, priorNetProfit, priorTip, priorBasePay, priorDriverAdjustedAt,
                 newPayCents, undoneAt, undoEventSequenceId)
                VALUES ('cmd-1', 7, 'session', 'receipt:t1:900', 'COVERED', 9, 1000,
                 4.5, 'OFFER_PAY', 1.25, NULL, NULL, NULL, 1025, NULL, NULL)""")
        }
        helper.runMigrationsAndValidate(name, 28, true).use { db ->
            db.query("SELECT origin, settlementRevision, endReason, newPayCents, undoneAt FROM receipt_match_rows WHERE commandId = 'cmd-1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("DASHER", c.getString(0))
                assertTrue(c.isNull(1))
                assertTrue(c.isNull(2))
                assertEquals(1025L, c.getLong(3))
                assertTrue(c.isNull(4))
            }
        }
    }
}
