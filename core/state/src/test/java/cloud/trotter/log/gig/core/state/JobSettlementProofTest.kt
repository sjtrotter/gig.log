package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.model.offer.OfferQuoteBasis
import cloud.trotter.log.gig.domain.model.offer.OfferScope
import cloud.trotter.log.gig.domain.model.pay.ParsedPay
import cloud.trotter.log.gig.domain.model.pay.ParsedPayItem
import cloud.trotter.log.gig.domain.state.AcceptedOfferEconomics
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.Job
import cloud.trotter.log.gig.domain.state.JobReceiptRead
import cloud.trotter.log.gig.domain.state.JobSettlementGap
import cloud.trotter.log.gig.domain.state.PendingOffer
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.PoolEntryVia
import cloud.trotter.log.gig.domain.state.PooledOffer
import cloud.trotter.log.gig.domain.state.StoreKeys
import cloud.trotter.log.gig.domain.state.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1601 — [jobSettlementAt], one test per clause: each clause's failing case and the passing one. A **clean fixture**
 * is a one-offer job whose card showed two stops (`Alpha Burger (Loop 410)`, `Beta Tacos (Main)`), two delivered drops at
 * `Alpha Burger #55` and `Beta Tacos #2` (t2 completed last), and one $17.55 read on t2 whose rule declared the total the
 * offer's pay, read with no presentation since t2 became active. Invented stores only.
 */
class JobSettlementProofTest {

    private val hints = listOf("Alpha Burger (Loop 410)", "Beta Tacos (Main)")

    private fun offer(count: Int? = 2, basis: OfferQuoteBasis = OfferQuoteBasis.TOTAL, hash: String = "o1") =
        AcceptedOfferEconomics(offerHash = hash, payAmount = 16.0, storeHints = hints, cardDropoffCount = count, acceptedAt = 100L, quoteBasis = basis)

    private fun drop(id: String, store: String?, completedAt: Long?, arrivedAt: Long? = 3_000L, unassigned: Long? = null) = Task(
        taskId = id, jobId = "J1", phase = cloud.trotter.log.gig.domain.state.TaskPhase.DROPOFF, storeName = store,
        customerNameHash = "cust-$id", startedAt = 2_000L, arrivedAt = arrivedAt, completedAt = completedAt, unassignedAt = unassigned,
    )

    private fun read(
        id: String = "receipt:t2:4000",
        subject: String = "t2",
        cents: Long = 1755,
        pay: ParsedPay? = null,
        declared: Boolean = true,
        seq: Long = 0,
        at: Long = 4_000L,
        acceptSeq: Long = 1,
    ) = JobReceiptRead(id, 1, subject, cents, pay, declared, seq, at, firstSeenAt = at, acceptSeqAtFirstSeen = acceptSeq)

    private val t1 = drop("t1", "Alpha Burger #55", completedAt = 3_500L)
    private val t2 = drop("t2", "Beta Tacos #2", completedAt = null)

    private fun job(
        offers: List<AcceptedOfferEconomics> = listOf(offer()),
        reads: List<JobReceiptRead>? = listOf(read()),
        tasks: List<Task> = listOf(t1, t2),
        ownAccept: Long? = 1,
        activated: Map<String, Long> = mapOf("t1" to 0, "t2" to 0),
    ) = Job(
        jobId = "J1", offerStoreHint = hints, parentOfferHash = "o1", acceptedOffers = offers, tasks = tasks, startedAt = 200L,
        receiptReads = reads, ownAcceptSeq = ownAccept, dropoffActivatedSeq = activated,
    )

    private fun prove(
        job: Job = job(),
        copies: List<Task> = job.tasks,
        minted: Set<String> = setOf("t1", "t2"),
        pool: List<PooledOffer> = emptyList(),
    ) = jobSettlementAt(job, copies, minted, pool)

    private fun assertGap(gap: JobSettlementGap, proof: JobSettlementProof) =
        assertEquals(JobSettlementProof.Refused(gap), proof)

    private fun pooled(
        presentedAt: Long,
        stores: Set<String>,
        demoted: Boolean = false,
        scope: OfferScope = OfferScope.STANDALONE,
    ) = PooledOffer(
        offer = PendingOffer(
            offerHash = "x$presentedAt",
            offerFields = ParsedFields.OfferFields(parsedOffer = PF.parsedOffer("x$presentedAt", scope = scope)),
            presentedAt = presentedAt,
            returnFlow = Flow.Idle,
        ),
        leftAt = presentedAt + 10,
        via = PoolEntryVia.FRAME,
        sessionId = "S1",
        candidateStoreKeys = stores,
        demoted = demoted,
    )

    @Test fun `a clean two-drop stack is proven, an even split of the collapsed total in taskId order`() { // non-vacuous
        val proof = prove() as JobSettlementProof.Proven
        assertEquals("receipt:t2:4000", proof.paying.evidenceId)
        assertEquals(listOf("t1" to 878L, "t2" to 877L), proof.shares)
        assertEquals(2, proof.cardCount)
    }

    @Test fun `an itemization that sums to its own total splits by store, else the bare total splits evenly`() {
        val pay = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 8.00)),
            customerTips = listOf(ParsedPayItem("Alpha Burger", 3.00), ParsedPayItem("Beta Tacos", 6.55)),
        )
        val itemized = prove(job(reads = listOf(read(pay = pay)))) as JobSettlementProof.Proven
        assertEquals(listOf("t1" to 700L, "t2" to 1055L), itemized.shares)
        assertEquals(1755L, itemized.shares.sumOf { it.second })
        // A stale itemization (items $16.55) under a $17.55 revision: never used.
        val stale = ParsedPay(listOf(ParsedPayItem("Base Pay", 8.00)), listOf(ParsedPayItem("Alpha Burger", 2.00), ParsedPayItem("Beta Tacos", 6.55)))
        val even = prove(job(reads = listOf(read(pay = stale)))) as JobSettlementProof.Proven
        assertEquals(listOf("t1" to 878L, "t2" to 877L), even.shares)
    }

    @Test fun `a three-drop stack conserves cents`() {
        val t3 = drop("t3", "Alpha Burger #55", completedAt = null)
        val two = t2.copy(completedAt = 3_800L)
        val j = job(offers = listOf(offer(count = 3)), reads = listOf(read(subject = "t3", cents = 2000)), tasks = listOf(t1, two, t3),
            activated = mapOf("t1" to 0, "t2" to 0, "t3" to 0))
        val proof = prove(j, minted = setOf("t1", "t2", "t3")) as JobSettlementProof.Proven
        assertEquals(listOf("t1" to 667L, "t2" to 667L, "t3" to 666L), proof.shares)
    }

    @Test fun `clause 1 - reads not tracked from the mint`() = assertGap(JobSettlementGap.READS_UNTRACKED, prove(job(reads = null)))

    @Test fun `clause 2 - two offers, or an incremental quote`() {
        assertGap(JobSettlementGap.OFFER_COUNT, prove(job(offers = listOf(offer(), offer(hash = "o2")))))
        assertGap(JobSettlementGap.OFFER_COUNT, prove(job(offers = listOf(offer(basis = OfferQuoteBasis.INCREMENTAL)))))
    }

    @Test fun `clause 3 - a missing card count, and N = 1 never enters this path`() {
        assertGap(JobSettlementGap.CARD_COUNT_MISSING, prove(job(offers = listOf(offer(count = null)))))
        assertGap(JobSettlementGap.CARD_COUNT_ONE, prove(job(offers = listOf(offer(count = 1)))))
    }

    @Test fun `clause 4 - an unassigned drop, a drop count off the card, a drop without a minted completion`() {
        val unassigned = t1.copy(unassignedAt = 3_200L)
        assertGap(JobSettlementGap.UNASSIGNED, prove(job(), copies = listOf(unassigned, t1, t2)))
        assertGap(JobSettlementGap.DROP_COUNT, prove(job(offers = listOf(offer(count = 3)))))
        assertGap(JobSettlementGap.DROP_NOT_MINTED, prove(minted = setOf("t2")))
    }

    @Test fun `clause 5 - a drop with neither arrival nor the completion steps`() {
        val unwitnessed = t1.copy(arrivedAt = null)
        assertGap(JobSettlementGap.NO_DELIVERY_WITNESS, prove(job(tasks = listOf(unwitnessed, t2))))
        val steps = unwitnessed.copy(completionStepsAt = 3_400L)
        assertTrue(prove(job(tasks = listOf(steps, t2))) is JobSettlementProof.Proven)
    }

    @Test fun `clause 6 - a drop with no store, or a store the offer did not quote`() {
        assertGap(JobSettlementGap.STORE_MISSING, prove(job(tasks = listOf(t1.copy(storeName = null), t2))))
        assertGap(JobSettlementGap.STORE_MISMATCH, prove(job(tasks = listOf(t1.copy(storeName = "Gamma Pizza #9"), t2))))
    }

    @Test fun `clause 7 - a platform whose rules do not declare the total offer-scoped never settles`() =
        assertGap(JobSettlementGap.SCOPE_UNDECLARED, prove(job(reads = listOf(read(declared = false)))))

    @Test fun `clause 8 - no read names the drop that completed last`() {
        assertGap(JobSettlementGap.NO_TERMINAL_READ, prove(job(reads = listOf(read(subject = "t1")))))
        assertGap(JobSettlementGap.NO_TERMINAL_READ, prove(job(reads = emptyList())))
    }

    @Test fun `clause 9 - a part receipt then a full receipt disagree and refuse, never pick`() {
        val part = read(id = "receipt:t1:3500", subject = "t1", cents = 900, at = 3_500L)
        assertGap(JobSettlementGap.READS_DISAGREE, prove(job(reads = listOf(part, read()))))
        val repeat = read(id = "receipt:t1:3500", subject = "t1", cents = 1755, at = 3_500L)
        assertTrue(prove(job(reads = listOf(repeat, read()))) is JobSettlementProof.Proven)
    }

    @Test fun `clause 10a - an accept resolved between the job's own accept and the paying read`() {
        assertGap(JobSettlementGap.OTHER_ACCEPT, prove(job(reads = listOf(read(acceptSeq = 2)))))
        assertGap(JobSettlementGap.OTHER_ACCEPT, prove(job(ownAccept = null)))
        // The read's first-seen snapshot holds across its revisions; an accept after the read (a T2 close) cannot own it.
        assertTrue(prove(job(reads = listOf(read(acceptSeq = 1).copy(revision = 3)))) is JobSettlementProof.Proven)
    }

    @Test fun `clause 10b - a non-demoted pool entry presented between the accept and the paying read, any store`() {
        assertGap(JobSettlementGap.POOLED_OFFER, prove(pool = listOf(pooled(presentedAt = 3_200L, stores = setOf("store z")))))
        // A demoted entry (a decline inferred from the confirm sheet) is not a rival.
        assertTrue(prove(pool = listOf(pooled(3_200L, setOf("store z"), demoted = true))) is JobSettlementProof.Proven)
        // Presented after the read was first seen (4 000): it cannot own the read, whatever its store.
        val chain = StoreKeys.normalizedChain("Alpha Burger #55")
        assertTrue(prove(pool = listOf(pooled(4_500L, setOf(chain)))) is JobSettlementProof.Proven)
    }

    @Test fun `an offer pooled BEFORE the accept at a store of the job's drops is a rival`() {
        val chain = StoreKeys.normalizedChain("Alpha Burger #55")
        assertGap(JobSettlementGap.RIVAL_POOLED, prove(pool = listOf(pooled(presentedAt = 50L, stores = setOf(chain)))))
        assertTrue(prove(pool = listOf(pooled(50L, setOf(chain), demoted = true))) is JobSettlementProof.Proven)
        assertTrue(prove(pool = listOf(pooled(50L, setOf("store z")))) is JobSettlementProof.Proven)
    }

    @Test fun `an ADD_ON entry with no candidate store counts only when presented before the paying read`() {
        assertGap(JobSettlementGap.POOLED_OFFER, prove(pool = listOf(pooled(3_900L, emptySet(), scope = OfferScope.ADD_ON))))
        assertTrue(prove(pool = listOf(pooled(6_000L, emptySet(), scope = OfferScope.ADD_ON))) is JobSettlementProof.Proven)
    }

    @Test fun `clause 11 - a presentation between the terminal drop's activation and the paying read`() {
        assertGap(JobSettlementGap.LATE_FRAME, prove(job(reads = listOf(read(seq = 1)))))
        assertGap(JobSettlementGap.LATE_FRAME, prove(job(activated = mapOf("t1" to 0))))
        // A presentation before the terminal drop activated is not inside the window.
        assertTrue(prove(job(reads = listOf(read(seq = 3)), activated = mapOf("t1" to 0, "t2" to 3))) is JobSettlementProof.Proven)
    }
}
