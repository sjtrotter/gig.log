package cloud.trotter.log.gig.domain.analytics.receipts

import cloud.trotter.log.gig.domain.analytics.DeliveryRecord
import cloud.trotter.log.gig.domain.analytics.PayBasis
import cloud.trotter.log.gig.domain.analytics.SessionRecord
import cloud.trotter.log.gig.domain.analytics.checks.ShiftCheck
import cloud.trotter.log.gig.domain.analytics.checks.ShiftChecks
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchRole
import cloud.trotter.log.gig.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1601: [ReceiptMatch] and [ShiftReceipts] are origin-aware, and [ReceiptMatch.settlement] is the strict entry point. */
class JobSettlementMatchTest {

    private fun row(
        seq: Long,
        jobId: String = "J1",
        pay: Double? = null,
        basis: String = PayBasis.NONE,
        adjustedAt: Long? = null,
        assigned: Boolean = false,
    ) = DeliveryRecord(
        eventSequenceId = seq, sessionId = "S", platform = Platform.DoorDash, jobId = jobId, taskId = "t$seq",
        storeName = "Store A", completedAt = seq * 100, realizedPay = pay, payBasis = basis, netProfit = null,
        realizedMiles = 2.0, realizedMinutes = 10.0, tip = null, basePay = null, sessionAssigned = assigned,
        driverAdjustedAt = adjustedAt, jobOfferHashes = listOf("hY"),
    )

    private fun receipt(subjectSeq: Long, id: String = "receipt:t$subjectSeq:1", cents: Long = 1755) =
        SavedReceipt(id, "J1", "t$subjectSeq", 1, cents, null, null, proven = false)

    private fun app(seq: Long, cents: Long, evidence: String = "receipt:t2:1") =
        ActiveMatchRow("settle:J1", evidence, seq, ReceiptMatchRole.COVERED, cents, matchedAt = 70, origin = MatchOrigin.APP)

    private fun dasher(seq: Long, cents: Long = 500, evidence: String = "receipt:other", role: ReceiptMatchRole = ReceiptMatchRole.COVERED) =
        ActiveMatchRow("M1", evidence, seq, role, cents.takeIf { role == ReceiptMatchRole.COVERED }, matchedAt = 50)

    private val shares = listOf("t1" to 878L, "t2" to 877L)
    private val y1 = row(1)
    private val y2 = row(2, pay = 9.0, basis = PayBasis.OFFER_PAY)

    @Test fun `settlement - an open one-offer job is written whole, rows in share order`() { // non-vacuous
        val plan = ReceiptMatch.settlement("J1", "receipt:t2:1", shares, listOf(y1, y2), emptyList(), listOf(receipt(2)))
        assertEquals(SettlementPlan.Write(listOf(y1 to 878L, y2 to 877L)), plan)
    }

    @Test fun `settlement - refused whole on a missing row, an extra row, an edited, hand-added, moved or named row, or a funded job`() {
        fun refused(rows: List<DeliveryRecord>, active: List<ActiveMatchRow> = emptyList(), receipts: List<SavedReceipt> = listOf(receipt(2))) =
            assertTrue(ReceiptMatch.settlement("J1", "receipt:t2:1", shares, rows, active, receipts) is SettlementPlan.Refuse)
        refused(listOf(y2))
        refused(listOf(y1, y2, row(3)))
        refused(listOf(y1, y2.copy(driverAdjustedAt = 9)))
        refused(listOf(y1, y2.copy(payBasis = PayBasis.MANUAL)))
        refused(listOf(y1, y2.copy(payBasis = PayBasis.USER_CORRECTED)))
        refused(listOf(y1, y2.copy(sessionAssigned = true)))
        refused(listOf(y1, y2), listOf(dasher(1)))
        refused(listOf(y1, y2), listOf(dasher(1, role = ReceiptMatchRole.CLEARED)))
        // A job another receipt's active match pays (its row names a delivery outside the job but its receipt is the job's).
        refused(listOf(y1, y2, row(9, jobId = "J9")), listOf(dasher(9, evidence = "receipt:t1:1")), listOf(receipt(2), receipt(1)))
    }

    @Test fun `holdsWritten and lock read the origin - a settled row is DROP_SHARE with no driverAdjustedAt`() {
        val settled = row(1, pay = 8.78, basis = PayBasis.DROP_SHARE)
        assertTrue(ReceiptMatch.holdsWritten(app(1, 878), settled))
        assertFalse(ReceiptMatch.holdsWritten(app(1, 878), settled.copy(driverAdjustedAt = 70)))
        assertFalse(ReceiptMatch.holdsWritten(app(1, 878), settled.copy(payBasis = PayBasis.RECEIPT_MATCHED)))
        assertFalse(ReceiptMatch.holdsWritten(app(1, 878), settled.copy(realizedPay = 9.00)))
        assertEquals(MatchLock.SettledFrom("receipt:t2:1"), ReceiptMatch.lock(settled, listOf(app(1, 878))))
        // A sibling of a settled job, never matched itself, reads "Settled from" too (the funded job).
        val open = row(5)
        val lock = ReceiptMatch.lockFor("receipt:t5:1", open, listOf(settled, open), listOf(app(1, 878)), listOf(receipt(2), receipt(5)))
        assertEquals(MatchLock.SettledFrom("receipt:t2:1"), lock)
    }

    @Test fun `a settled paying receipt is MATCHED by the app, the job's other reads SETTLED, no check`() {
        val rows = listOf(row(1, pay = 8.78, basis = PayBasis.DROP_SHARE), row(2, pay = 8.77, basis = PayBasis.DROP_SHARE))
        val paying = receipt(2, "receipt:t2:1")
        val other = receipt(1, "receipt:t1:1")
        val out = ShiftReceipts.of(listOf(paying, other), rows, listOf(app(1, 878), app(2, 877)))
        val byId = out.items.associateBy { it.receipt.evidenceId }
        assertEquals(ReceiptStatus.MATCHED, byId.getValue("receipt:t2:1").status)
        assertEquals(MatchOrigin.APP, byId.getValue("receipt:t2:1").origin)
        assertTrue(byId.getValue("receipt:t2:1").undoable)
        assertEquals(ReceiptStatus.SETTLED, byId.getValue("receipt:t1:1").status)
        assertTrue(out.open.isEmpty())
        assertEquals(mapOf(1L to "settle:J1", 2L to "settle:J1"), out.undoByDelivery)
    }

    @Test fun `a settlement in CONFLICT is not MATCHED and does not fund its job - its paying receipt asks, with its own copy`() {
        val rows = listOf(row(1, pay = 12.0, basis = PayBasis.USER_CORRECTED, adjustedAt = 90), row(2, pay = 8.77, basis = PayBasis.DROP_SHARE))
        val paying = receipt(2, "receipt:t2:1")
        val out = ShiftReceipts.of(listOf(paying), rows, emptyList(), conflicted = setOf("receipt:t2:1"))
        val item = out.items.single()
        assertEquals(ReceiptStatus.OPEN, item.status)
        assertTrue(item.changedAfterSettle)
        val session = SessionRecord(
            sessionId = "S", platform = Platform.DoorDash, startedAt = 0, endedAt = 1_000, reportedEarnings = null,
            reportedDurationMillis = null, miles = null, deliveries = 0, jobsCompleted = 0,
            offersReceived = 0, offersAccepted = 0, offersDeclined = 0, offersTimeout = 0,
        )
        val checks = ShiftChecks.of(session, emptyList(), null, out).filterIsInstance<ShiftCheck.ReceiptNotMatched>()
        assertEquals(listOf(ShiftCheck.ReceiptNotMatched("receipt:t2:1", 1755, 1, changedAfterSettle = true)), checks)
    }
}
