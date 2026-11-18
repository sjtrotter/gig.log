package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.JobReceiptSettledPayload
import cloud.trotter.log.gig.domain.model.event.payload.JobSettlementKind
import cloud.trotter.log.gig.domain.model.event.payload.JobSettlementLine
import cloud.trotter.log.gig.domain.model.pay.ParsedPay
import cloud.trotter.log.gig.domain.model.pay.ParsedPayItem
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.state.AcceptedOfferEconomics
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.Job
import cloud.trotter.log.gig.domain.state.JobReceiptRead
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.PendingOffer
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.PoolEntryVia
import cloud.trotter.log.gig.domain.state.PooledOffer
import cloud.trotter.log.gig.domain.state.Session
import cloud.trotter.log.gig.domain.state.StoreKeys
import cloud.trotter.log.gig.domain.state.Task
import cloud.trotter.log.gig.domain.state.TaskPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1601 — whole-job settlement through the real stepper and `EffectMap` ([ReceiptFold]: a fake `effects_fired`). The
 * fixture is a one-offer stack whose card showed N stops, drop 1..N-1 delivered, drop N active and arrived, with the
 * receipt next. Invented stores only (`Alpha Burger`, `Beta Tacos`, `Gamma Pizza`).
 */
class JobSettlementSessionTest {

    private val hints = listOf("Alpha Burger (Loop 410)", "Beta Tacos (Main)")
    private fun store(i: Int) = if (i % 2 == 1) "Alpha Burger #55" else "Beta Tacos #2"

    private fun drop(i: Int, completedAt: Long?) = Task(
        taskId = "t$i", jobId = "J1", phase = TaskPhase.DROPOFF, storeName = store(i), customerNameHash = "cust-$i",
        startedAt = 2_000L, arrivedAt = 3_000L, completedAt = completedAt,
    )

    private fun stack(
        n: Int = 2,
        card: Int? = n,
        priorReads: List<JobReceiptRead> = emptyList(),
        pool: List<PooledOffer> = emptyList(),
        lastActive: Boolean = true,
    ): PlatformRegion {
        val drops = (1..n).map { i -> drop(i, if (i < n || !lastActive) 3_000L + i * 100 else null) }
        val job = Job(
            jobId = "J1", offerStoreHint = hints, parentOfferHash = "o1",
            acceptedOffers = listOf(
                AcceptedOfferEconomics(offerHash = "o1", payAmount = 16.0, storeHints = hints, cardDropoffCount = card, acceptedAt = 100L),
            ),
            tasks = drops, startedAt = 200L,
            receiptReads = priorReads, ownAcceptSeq = 1, dropoffActivatedSeq = drops.associate { it.taskId to 0L },
        )
        return PlatformRegion(
            platform = Platform.DoorDash, mode = Mode.Online, session = Session("S1", startedAt = 100L),
            activeJob = job,
            activeTask = drops.last().takeIf { lastActive },
            recentTasks = if (lastActive) drops.dropLast(1) else drops,
            lastActedFlow = Flow.TaskDropoffArrived,
            acceptResolveSeq = 1,
            offerPool = pool,
        )
    }

    private fun collapsed(total: Double, declared: Boolean = true) =
        ParsedFields.PostTaskFields(totalPay = total, parsedPay = null, isExpanded = false, receiptTotalIsOffer = declared)

    private fun expanded(pay: ParsedPay) =
        ParsedFields.PostTaskFields(totalPay = pay.total, parsedPay = pay, isExpanded = true, receiptTotalIsOffer = true)

    /** $17.55 itemized by store: Alpha $3.00, Beta $6.55, base $8.00. */
    private val itemized = ParsedPay(
        appPayComponents = listOf(ParsedPayItem("Base Pay", 8.00)),
        customerTips = listOf(ParsedPayItem("Alpha Burger", 3.00), ParsedPayItem("Beta Tacos", 6.55)),
    )

    private fun ReceiptFold.settlements(): List<JobReceiptSettledPayload> =
        events.filter { it.type == AppEventType.JOB_RECEIPT_SETTLED }.map { it.payload as JobReceiptSettledPayload }

    private fun ReceiptFold.settlementKeys(): List<String?> = keys.filter { it.first == AppEventType.JOB_RECEIPT_SETTLED }.map { it.second }

    private fun lines(vararg shares: Pair<String, Long>) = shares.map { (t, c) -> JobSettlementLine(t, c) }

    /** The receipt on the last drop, then the idle map: the job closes on the receipt's exit. */
    private fun closeOnExit(fold: ReceiptFold, fields: ParsedFields.PostTaskFields = collapsed(17.55)) =
        fold.step(RS.postTask(fields, 4_000L)).step(RS.screen(Flow.Idle, 4_500L))

    /** The collapsed receipt stays up; its 8 s retire grace commits on a later receipt frame, closing the job there. */
    private fun closeOnReceipt(fold: ReceiptFold) =
        fold.step(RS.postTask(collapsed(17.55), 4_000L)).step(RS.postTask(collapsed(17.55), 12_500L))

    @Test fun `a clean two-drop stack settles once at the close, after its completions and its receipt`() { // non-vacuous
        val fold = closeOnExit(ReceiptFold(stack()))
        val settled = fold.settlements().single()
        assertEquals(JobSettlementKind.SETTLE, settled.kind)
        assertEquals("settle:J1", settled.commandId)
        assertEquals("S1", settled.sessionId)
        assertEquals(1, settled.revision)
        assertEquals(1755L, settled.receiptCents)
        assertEquals(2, settled.cardDropoffCount)
        assertEquals(lines("t1" to 878L, "t2" to 877L), settled.lines)
        assertEquals(listOf("log:JOB_RECEIPT_SETTLED:J1:r1"), fold.settlementKeys())
        // Ordering (D4): within the close step, every DELIVERY_COMPLETED is emitted before the settlement.
        val closeStep = fold.effects.filter { it.first == 4_500L }.map { it.second }.filterIsInstance<AppEffect.LogEvent>().map { it.event.type }
        val at = closeStep.indexOf(AppEventType.JOB_RECEIPT_SETTLED)
        assertTrue(closeStep.subList(0, at).count { it == AppEventType.DELIVERY_COMPLETED } >= 1)
        assertTrue(closeStep.subList(at, closeStep.size).none { it == AppEventType.DELIVERY_COMPLETED || it == AppEventType.RECEIPT_OBSERVED })
        // Re-rendering the same receipt afterwards decides nothing.
        fold.step(RS.postTask(collapsed(17.55), 5_000L))
        assertEquals(1, fold.settlements().size)
    }

    @Test fun `a clean three-drop stack settles once and conserves cents`() {
        val fold = closeOnExit(ReceiptFold(stack(n = 3)), collapsed(20.00))
        val settled = fold.settlements().single()
        assertEquals(lines("t1" to 667L, "t2" to 667L, "t3" to 666L), settled.lines)
        assertEquals(2000L, settled.lines.sumOf { it.cents })
    }

    @Test fun `collapsed at the close, then a quiet expansion of the same receipt - revision 2 with the itemization`() {
        val fold = closeOnReceipt(ReceiptFold(stack()))
        assertEquals(lines("t1" to 878L, "t2" to 877L), fold.settlements().single().lines)
        assertNull("the close was on the receipt", fold.region.activeJob)
        fold.step(RS.postTask(expanded(itemized), 13_000L))
        val r2 = fold.settlements().last()
        assertEquals(2, r2.revision)
        assertEquals(2, r2.evidenceRevision)
        assertEquals(lines("t1" to 700L, "t2" to 1055L), r2.lines)
        // Ordering (D4): the receipt revision is emitted before the settlement revision that names it.
        val step = fold.effects.filter { it.first == 13_000L }.map { it.second }.filterIsInstance<AppEffect.LogEvent>().map { it.event.type }
        assertTrue(step.indexOf(AppEventType.RECEIPT_OBSERVED) < step.indexOf(AppEventType.JOB_RECEIPT_SETTLED))
    }

    @Test fun `X then Y then X keeps three effect keys, by decision revision`() {
        val fold = closeOnReceipt(ReceiptFold(stack()))
        fold.step(RS.postTask(collapsed(18.55), 13_000L)).step(RS.postTask(collapsed(17.55), 13_500L))
        assertEquals(listOf(1755L, 1855L, 1755L), fold.settlements().map { it.receiptCents })
        assertEquals(
            listOf("log:JOB_RECEIPT_SETTLED:J1:r1", "log:JOB_RECEIPT_SETTLED:J1:r2", "log:JOB_RECEIPT_SETTLED:J1:r3"),
            fold.settlementKeys(),
        )
    }

    @Test fun `a noisy expansion - an offer presented in between - decides nothing, and the settlement stands`() {
        val fold = closeOnReceipt(ReceiptFold(stack()))
        fold.step(RS.offerScreen(13_000L, "x1", store = "Gamma Pizza")).step(RS.postTask(expanded(itemized), 13_500L))
        assertEquals(1, fold.settlements().size)
        assertEquals(1, fold.settlements().single().revision)
    }

    @Test fun `a disagreeing second record refuses at the close`() {
        val part = JobReceiptRead("receipt:t1:3100", 1, "t1", 900, null, totalIsOffer = true, possibleWorkSeq = 0, observedAt = 3_100L)
        val fold = closeOnExit(ReceiptFold(stack(priorReads = listOf(part))))
        assertTrue(fold.settlements().isEmpty())
    }

    @Test fun `records that disagree after a settlement withdraw it, once, and it never comes back`() {
        val first = JobReceiptRead("receipt:t1:3100", 1, "t1", 1755, null, totalIsOffer = true, possibleWorkSeq = 0, observedAt = 3_100L)
        val fold = closeOnReceipt(ReceiptFold(stack(priorReads = listOf(first))))
        assertEquals(JobSettlementKind.SETTLE, fold.settlements().single().kind)
        fold.step(RS.postTask(collapsed(18.55), 13_000L))
        val withdrawn = fold.settlements().last()
        assertEquals(JobSettlementKind.WITHDRAW, withdrawn.kind)
        assertEquals(2, withdrawn.revision)
        assertTrue(withdrawn.lines.isEmpty())
        fold.step(RS.postTask(collapsed(17.55), 13_500L))
        assertEquals(2, fold.settlements().size)
    }

    @Test fun `N = 1 never settles here, nor a platform whose rules do not declare the total`() {
        assertTrue(closeOnExit(ReceiptFold(stack(n = 1, card = 1))).settlements().isEmpty())
        assertTrue(closeOnExit(ReceiptFold(stack()), collapsed(17.55, declared = false)).settlements().isEmpty())
        assertTrue(closeOnExit(ReceiptFold(stack(card = null))).settlements().isEmpty())
    }

    @Test fun `the 11-16 shape - an offer presented mid-stack that ended in the pool - is refused`() {
        val fold = ReceiptFold(stack())
            .step(RS.offerScreen(3_600L, "x1", store = "Gamma Pizza"))
            .step(RS.screen(Flow.TaskDropoffArrived, 3_700L))
        assertEquals(1, fold.region.offerPool.size)
        closeOnExit(fold, expanded(itemized))
        assertTrue(fold.settlements().isEmpty())
    }

    @Test fun `a teardown decides on the pool BEFORE the shift-end flush (A5)`() {
        val read = JobReceiptRead("receipt:t2:3300", 1, "t2", 1755, null, totalIsOffer = true, possibleWorkSeq = 0, observedAt = 3_300L)
        fun end(pool: List<PooledOffer>) = ReceiptFold(stack(priorReads = listOf(read), pool = pool, lastActive = false)).step(
            Observation.UiInput(
                timestamp = 6_000L, action = Observation.UiInput.ACTION_END_SHIFT, targetPlatform = Platform.DoorDash,
                sessionId = "S1",
            ),
        )
        val clean = end(emptyList())
        assertEquals(lines("t1" to 878L, "t2" to 877L), clean.settlements().single().lines)
        assertEquals("S1", clean.settlements().single().sessionId)
        // An offer pooled before the accept at a store of the job's drops is a rival; the flush would have hidden it.
        val rival = PooledOffer(
            offer = PendingOffer(
                offerHash = "x0", offerFields = ParsedFields.OfferFields(parsedOffer = PF.parsedOffer("x0")),
                presentedAt = 50L, returnFlow = Flow.Idle,
            ),
            leftAt = 60L, via = PoolEntryVia.FRAME, sessionId = "S1",
            candidateStoreKeys = setOf(StoreKeys.normalizedChain("Alpha Burger #55")),
        )
        assertTrue(end(listOf(rival)).settlements().isEmpty())
        assertNull(end(listOf(rival)).region.closedJobSettlement)
    }

    @Test fun `the marker is cleared at the shift end and when another job becomes active`() {
        val fold = closeOnExit(ReceiptFold(stack()))
        assertEquals("J1", fold.region.closedJobSettlement?.job?.jobId)
        fold.step(PF.dropoff(5_000L, customer = "cust-9"))
        assertNull(fold.region.closedJobSettlement)
    }
}
