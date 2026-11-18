package cloud.trotter.log.gig.core.data.analytics

import androidx.room.Room
import cloud.trotter.log.gig.core.data.event.AppEventRepo
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.database.GigLogDatabase
import cloud.trotter.log.gig.core.database.analytics.AnalyticsProjectionStateEntity
import cloud.trotter.log.gig.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.log.gig.core.database.event.AppEventEntity
import cloud.trotter.log.gig.domain.analytics.AnalyticsEventFixtures.acceptedOffer
import cloud.trotter.log.gig.domain.analytics.AnalyticsEventFixtures.evaluation
import cloud.trotter.log.gig.domain.analytics.PayBasis
import cloud.trotter.log.gig.domain.analytics.checks.ShiftCheck
import cloud.trotter.log.gig.domain.analytics.receipts.MatchOrigin
import cloud.trotter.log.gig.domain.analytics.receipts.ReceiptStatus
import cloud.trotter.log.gig.domain.analytics.receipts.ShiftReceipts
import cloud.trotter.log.gig.domain.evaluation.UserEconomy
import cloud.trotter.log.gig.domain.model.event.AppEventCodec
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.AppEventPayload
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryAdjustmentPayload
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryPayload
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryReceiptRepricePayload
import cloud.trotter.log.gig.domain.model.event.payload.JobReceiptSettledPayload
import cloud.trotter.log.gig.domain.model.event.payload.JobSettlementKind
import cloud.trotter.log.gig.domain.model.event.payload.JobSettlementLine
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchLine
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchRole
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchUndonePayload
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchedPayload
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptObservedPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionEndSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionStartPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionStartSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionStopPayload
import cloud.trotter.log.gig.domain.model.pay.ParsedPay
import cloud.trotter.log.gig.domain.model.pay.ParsedPayItem
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.ReceiptScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #1601: the projector applies a whole-job settlement revision all or nothing: initial, replacement, withdraw, and the
 * dasher's undo, the same in one batch, in many and on a rebuild. Store names are invented.
 */
@RunWith(RobolectricTestRunner::class)
class JobSettlementProjectorTest {

    private class Harness {
        val db: GigLogDatabase = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), GigLogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        private val prefs: AppPreferencesRepository = mock { on { userEconomy } doReturn flowOf(UserEconomy()) }
        val projector = AnalyticsProjector(db, AppEventRepo(db, db.appEventDao(), db.effectsFiredDao()), db.appEventDao(), db.analyticsDao(), prefs)
        private var odometer = 100.0

        suspend fun insert(type: AppEventType, sid: String?, at: Long, payload: AppEventPayload?, odo: Double? = null): Long =
            db.appEventDao().insert(
                AppEventEntity(
                    aggregateId = sid,
                    eventType = type,
                    eventPayload = payload?.let(AppEventCodec::encodePayload) ?: "{}",
                    occurredAt = at,
                    metadata = odo?.let { """{"odometer":$it}""" },
                ),
            )

        suspend fun start(at: Long = 1_000) = insert(
            AppEventType.DASH_START, "S1", at, SessionStartPayload("S1", Platform.DoorDash.name, at, SessionStartSource.INTERACTION, "x"),
            odo = odometer,
        )

        suspend fun accept(at: Long = 1_100) = insert(
            AppEventType.OFFER_ACCEPTED, "S1", at,
            acceptedOffer(at, evaluation(net = 9.0, dist = 3.0, opCpm = 0.25, payAmount = 16.0, dollarsPerMile = 3.0), hash = "hY"),
        )

        /** An unproven drop of job J1 carrying an OFFER_PAY estimate of $8.00. */
        suspend fun drop(at: Long, taskId: String, jobId: String = "J1"): Long {
            odometer += 2.0
            return insert(
                AppEventType.DELIVERY_COMPLETED, "S1", at,
                DeliveryPayload(
                    jobId = jobId, taskId = taskId, storeName = "Alpha Burger", customerHash = "c-$taskId", phaseStartedAt = at - 500,
                    arrivedAt = at - 100, completedAt = at, offerPayShare = 8.0, jobOfferHashes = listOf("hY"),
                    receiptScope = ReceiptScope.UNPROVEN, receiptEvidenceId = EVIDENCE,
                ),
                odo = odometer,
            )
        }

        suspend fun observed(at: Long, total: Double, revision: Int = 1, parsed: ParsedPay? = null) = insert(
            AppEventType.RECEIPT_OBSERVED, "S1", at,
            ReceiptObservedPayload(
                evidenceId = EVIDENCE, revision = revision, platform = Platform.DoorDash.wire, jobId = "J1",
                subjectTaskId = "t2", firstSeenAt = 1_400, observedAt = at, totalPay = total, parsedPay = parsed,
                isExpanded = parsed != null, scope = ReceiptScope.UNPROVEN,
            ),
        )

        suspend fun settle(
            at: Long,
            revision: Int,
            vararg shares: Pair<String, Long>,
            evidenceRevision: Int = revision,
            kind: JobSettlementKind = JobSettlementKind.SETTLE,
        ) = insert(
            AppEventType.JOB_RECEIPT_SETTLED, "S1", at,
            JobReceiptSettledPayload(
                commandId = CMD, sessionId = "S1", jobId = "J1", revision = revision, kind = kind,
                policyVersion = 1, evidenceId = EVIDENCE, evidenceRevision = evidenceRevision,
                receiptCents = shares.sumOf { it.second }.takeIf { it > 0 } ?: 1755, cardDropoffCount = 2,
                lines = shares.map { (t, c) -> JobSettlementLine(t, c) },
            ),
        )

        suspend fun withdraw(at: Long, revision: Int) = settle(at, revision, evidenceRevision = 1, kind = JobSettlementKind.WITHDRAW)

        suspend fun stop(at: Long = 9_000, total: Double = 17.55) = insert(
            AppEventType.DASH_STOP, "S1", at,
            SessionStopPayload("S1", endedAt = at, source = SessionEndSource.SUMMARY_SCREEN, totalEarnings = total),
            odo = odometer,
        )

        suspend fun undo(at: Long, cmd: String = CMD) = insert(AppEventType.RECEIPT_MATCH_UNDONE, "S1", at, ReceiptMatchUndonePayload("S1", cmd))

        suspend fun payEdit(at: Long, target: Long, pay: Double) = insert(
            AppEventType.DELIVERY_ADJUSTMENT, "S1", at, DeliveryAdjustmentPayload(targetEventSequenceId = target, sessionId = "S1", newPay = pay),
        )

        suspend fun row(seq: Long): DeliveryRecordEntity = db.analyticsDao().deliveryRecord(seq)!!
        suspend fun allRows() = db.analyticsDao().deliveriesBetween(0, Long.MAX_VALUE)
        suspend fun deliveries() = db.analyticsDao().deliveriesForSession("S1").first()

        fun matchRowDump(): List<String> = db.openHelper.readableDatabase
            .query("SELECT * FROM receipt_match_rows ORDER BY commandId, deliveryEventSequenceId").use { c ->
                buildList { while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { c.getString(it) ?: "null" }) }
            }

        suspend fun shiftReceipts(): ShiftReceipts {
            val read = ReceiptMatchRepository(db.receiptDao(), db.receiptMatchDao()).read("S1").first()
            return ShiftReceipts.of(read.receipts, deliveries().map { it.toDomain() }, read.active, read.conflicted)
        }

        suspend fun checks(): List<ShiftCheck> = ShiftChecksRepository(
            AnalyticsRepository(db.analyticsDao(), db.appEventDao(), db.hudShiftDao()),
            ShiftTimelineRepository(db.analyticsDao()),
            ReceiptMatchRepository(db.receiptDao(), db.receiptMatchDao()),
        ).checks("S1").first()

        suspend fun foldOneByOne(): Int {
            var skips = 0
            while (true) skips += (projector.processBatch(limit = 1) ?: break).adjustmentSkips
            return skips
        }
    }

    private val harnesses = mutableListOf<Harness>()
    private fun harness() = Harness().also { harnesses += it }

    @After
    fun tearDown() = harnesses.forEach { it.db.close() }

    private class Stack(val t1: Long, val t2: Long)

    /** A live one-offer two-drop stack: t1 and t2 on $8.00 estimates, then a $17.55 receipt read on t2. */
    private suspend fun Harness.stack(): Stack {
        start()
        accept()
        val t1 = drop(1_300, "t1")
        val t2 = drop(1_400, "t2")
        observed(1_400, 17.55)
        return Stack(t1, t2)
    }

    private fun cents(pay: Double?) = pay?.let { Math.round(it * 100) }

    @Test
    fun `an initial settlement conserves cents, writes origin APP and never driverAdjustedAt, on a live shift`() = runBlocking { // non-vacuous
        val h = harness()
        val s = h.stack()
        h.projector.catchUp()
        val before = h.row(s.t1)
        h.settle(1_500, 1, "t1" to 878L, "t2" to 877L)
        assertEquals(0, h.projector.catchUp().adjustmentSkips)

        val t1 = h.row(s.t1)
        val t2 = h.row(s.t2)
        assertEquals(878L, cents(t1.realizedPay))
        assertEquals(877L, cents(t2.realizedPay))
        assertEquals(PayBasis.DROP_SHARE, t1.payBasis)
        assertNull(t1.driverAdjustedAt)
        assertNull(t2.driverAdjustedAt)
        assertEquals(before.copy(realizedPay = t1.realizedPay, payBasis = t1.payBasis, netProfit = t1.netProfit), t1)

        val receipts = h.shiftReceipts()
        val paying = receipts.items.single()
        assertEquals(ReceiptStatus.MATCHED, paying.status)
        assertEquals(MatchOrigin.APP, paying.origin)
        h.stop()
        h.projector.catchUp()
        assertTrue(h.checks().none { it is ShiftCheck.ReceiptNotMatched })
    }

    @Test
    fun `refused whole - a missing row, an extra row, an edited row, a named row, a funded job, an evidence revision mismatch`() = runBlocking {
        // The second drop is logged AFTER the settlement: not sequenced before it.
        harness().apply {
            start(); accept(); val t1 = drop(1_300, "t1"); observed(1_400, 17.55)
            settle(1_500, 1, "t1" to 878L, "t2" to 877L); drop(1_600, "t2")
            assertEquals(1, projector.catchUp().adjustmentSkips)
            assertEquals(PayBasis.OFFER_PAY, row(t1).payBasis)
            assertTrue(matchRowDump().isEmpty())
        }
        // A third drop of the job.
        harness().apply {
            stack(); drop(1_450, "t3"); settle(1_500, 1, "t1" to 878L, "t2" to 877L)
            assertEquals(1, projector.catchUp().adjustmentSkips)
            assertTrue(deliveries().all { it.payBasis == PayBasis.OFFER_PAY })
        }
        // The dasher's own pay edit on one row.
        harness().apply {
            val s = stack(); payEdit(1_450, s.t2, 9.50); settle(1_500, 1, "t1" to 878L, "t2" to 877L)
            assertEquals(1, projector.catchUp().adjustmentSkips)
            assertEquals(PayBasis.OFFER_PAY, row(s.t1).payBasis)
            assertEquals(950L, cents(row(s.t2).realizedPay))
        }
        // The receipt revised (r2) before the settlement that names r1.
        harness().apply {
            stack(); observed(1_450, 17.55, revision = 2, parsed = ParsedPay(listOf(ParsedPayItem("Base Pay", 8.0)), listOf(ParsedPayItem("Alpha Burger", 9.55))))
            settle(1_500, 1, "t1" to 878L, "t2" to 877L, evidenceRevision = 1)
            assertEquals(1, projector.catchUp().adjustmentSkips)
            assertTrue(deliveries().all { it.payBasis == PayBasis.OFFER_PAY })
        }
        // An active dasher match already names a row (a CLEARED one), and with it the job is funded.
        harness().apply {
            val s = stack(); stop(1_450)
            insert(
                AppEventType.RECEIPT_MATCHED, "S1", 1_460,
                ReceiptMatchedPayload(
                    "M1", "S1", EVIDENCE, 1755,
                    listOf(
                        ReceiptMatchLine(s.t2, ReceiptMatchRole.COVERED, 800, PayBasis.OFFER_PAY, 1755),
                        ReceiptMatchLine(s.t1, ReceiptMatchRole.CLEARED, 800, PayBasis.OFFER_PAY, null),
                    ),
                ),
            )
            settle(1_500, 1, "t1" to 878L, "t2" to 877L)
            assertEquals(1, projector.catchUp().adjustmentSkips)
            assertEquals(PayBasis.RECEIPT_MATCHED, row(s.t2).payBasis)
            assertEquals(PayBasis.NONE, row(s.t1).payBasis)
        }
        Unit
    }

    @Test
    fun `a replacement applies while every row holds the active revision, and is refused once one does not`() = runBlocking {
        val h = harness()
        val s = h.stack()
        h.settle(1_500, 1, "t1" to 878L, "t2" to 877L)
        h.observed(1_600, 17.55, revision = 2)
        h.settle(1_600, 2, "t1" to 700L, "t2" to 1055L)
        assertEquals(0, h.projector.catchUp().adjustmentSkips)
        assertEquals(listOf(700L, 1055L), listOf(cents(h.row(s.t1).realizedPay), cents(h.row(s.t2).realizedPay)))
        // The original priors (the $8.00 estimates) are kept through the replacement.
        val rows = h.db.receiptMatchDao().rowsForCommand(CMD)
        assertEquals(listOf(8.0, 8.0), rows.map { it.priorRealizedPay })
        assertEquals(listOf(PayBasis.OFFER_PAY, PayBasis.OFFER_PAY), rows.map { it.priorPayBasis })
        assertEquals(listOf(2, 2), rows.map { it.settlementRevision })

        h.payEdit(1_700, s.t1, 7.25)
        h.observed(1_800, 18.55, revision = 3)
        h.settle(1_800, 3, "t1" to 928L, "t2" to 927L)
        assertEquals(1, h.projector.catchUp().adjustmentSkips)
        assertEquals(725L, cents(h.row(s.t1).realizedPay))
        assertEquals(1055L, cents(h.row(s.t2).realizedPay))
    }

    @Test
    fun `a withdrawal restores the ORIGINAL priors while intact, and marks CONFLICT when a row was edited`() = runBlocking {
        val intact = harness()
        val a = intact.stack()
        val estimate = intact.run { projector.catchUp(); row(a.t1) }
        intact.settle(1_500, 1, "t1" to 878L, "t2" to 877L)
        intact.observed(1_600, 17.55, revision = 2)
        intact.settle(1_600, 2, "t1" to 700L, "t2" to 1055L)
        intact.withdraw(1_700, 3)
        assertEquals(0, intact.projector.catchUp().adjustmentSkips)
        assertEquals(estimate, intact.row(a.t1))
        assertEquals(PayBasis.OFFER_PAY, intact.row(a.t2).payBasis)
        assertTrue(intact.db.receiptMatchDao().activeRowsInSession("S1").isEmpty())

        val edited = harness()
        val b = edited.stack()
        edited.settle(1_500, 1, "t1" to 878L, "t2" to 877L)
        edited.payEdit(1_600, b.t1, 7.25)
        edited.withdraw(1_700, 2)
        assertEquals(0, edited.projector.catchUp().adjustmentSkips)
        assertEquals(725L, cents(edited.row(b.t1).realizedPay))
        assertEquals(877L, cents(edited.row(b.t2).realizedPay))
        assertEquals(PayBasis.DROP_SHARE, edited.row(b.t2).payBasis)
        val paying = edited.shiftReceipts().items.single()
        assertEquals(ReceiptStatus.OPEN, paying.status)
        assertTrue(paying.changedAfterSettle)
        edited.stop()
        edited.projector.catchUp()
        assertEquals(1, edited.checks().count { it is ShiftCheck.ReceiptNotMatched && it.changedAfterSettle })
    }

    @Test
    fun `undo restores the original priors, a later revision is refused, and a manual match then works`() = runBlocking {
        val h = harness()
        val s = h.stack()
        val estimate = h.run { projector.catchUp(); row(s.t1) }
        h.settle(1_500, 1, "t1" to 878L, "t2" to 877L)
        h.observed(1_600, 17.55, revision = 2)
        h.settle(1_600, 2, "t1" to 700L, "t2" to 1055L)
        h.stop()
        h.undo(9_100)
        assertEquals(0, h.projector.catchUp().adjustmentSkips)
        assertEquals(estimate, h.row(s.t1))
        assertEquals(PayBasis.OFFER_PAY, h.row(s.t2).payBasis)

        h.observed(9_200, 17.55, revision = 3)
        h.settle(9_200, 3, "t1" to 878L, "t2" to 877L)
        assertEquals("an undone settlement takes no revision", 1, h.projector.catchUp().adjustmentSkips)
        assertEquals(PayBasis.OFFER_PAY, h.row(s.t2).payBasis)

        h.insert(
            AppEventType.RECEIPT_MATCHED, "S1", 9_300,
            ReceiptMatchedPayload(
                "M1", "S1", EVIDENCE, 1755,
                listOf(
                    ReceiptMatchLine(s.t1, ReceiptMatchRole.COVERED, 800, PayBasis.OFFER_PAY, 878),
                    ReceiptMatchLine(s.t2, ReceiptMatchRole.COVERED, 800, PayBasis.OFFER_PAY, 877),
                ),
            ),
        )
        assertEquals(0, h.projector.catchUp().adjustmentSkips)
        assertEquals(PayBasis.RECEIPT_MATCHED, h.row(s.t1).payBasis)
    }

    @Test
    fun `a machine re-price never overwrites a settled row`() = runBlocking {
        val h = harness()
        val s = h.stack()
        h.settle(1_500, 1, "t1" to 878L, "t2" to 877L)
        h.projector.catchUp()
        val settled = h.row(s.t2)
        h.insert(
            AppEventType.DELIVERY_RECEIPT_REPRICE, "S1", 1_600,
            DeliveryReceiptRepricePayload(
                jobId = "J1", taskId = "t2", totalPay = 17.55,
                parsedPay = ParsedPay(listOf(ParsedPayItem("Base Pay", 8.0)), listOf(ParsedPayItem("Alpha Burger", 9.55))),
                dropRealizedPay = 17.55,
            ),
        )
        assertEquals(1, h.projector.catchUp().adjustmentSkips)
        assertEquals(settled, h.row(s.t2))
    }

    /** Settle, replace, withdraw on one shift; settle and undo on another job of the same shift. */
    private suspend fun Harness.mixedLog() {
        val s = stack()
        settle(1_500, 1, "t1" to 878L, "t2" to 877L)
        observed(1_600, 17.55, revision = 2)
        settle(1_600, 2, "t1" to 700L, "t2" to 1055L)
        payEdit(1_650, s.t1, 7.0)
        observed(1_700, 18.55, revision = 3)
        settle(1_700, 3, "t1" to 928L, "t2" to 927L) // refused: t1 edited
        withdraw(1_800, 4) // CONFLICT
        stop()
    }

    @Test
    fun `one event per batch, one batch and a version rebuild fold the same rows`() = runBlocking { // non-vacuous
        val incremental = harness().apply { mixedLog() }
        val skips = incremental.foldOneByOne()
        val single = harness().apply { mixedLog() }
        assertEquals(skips, single.projector.catchUp().adjustmentSkips)
        assertEquals(1, skips)
        assertEquals(single.allRows(), incremental.allRows())
        assertEquals(single.matchRowDump(), incremental.matchRowDump())
        assertTrue(single.matchRowDump().all { it.endsWith("|APP|2|CONFLICT") })

        incremental.db.analyticsDao().setWatermark(AnalyticsProjectionStateEntity(watermarkSequenceId = 99, projectorVersion = 1))
        incremental.projector.catchUp()
        assertEquals(single.allRows(), incremental.allRows())
        assertEquals(single.matchRowDump(), incremental.matchRowDump())
    }

    private companion object {
        const val EVIDENCE = "receipt:t2:1400"
        const val CMD = "settle:J1"
    }
}
