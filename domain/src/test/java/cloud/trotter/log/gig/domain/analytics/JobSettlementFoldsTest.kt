package cloud.trotter.log.gig.domain.analytics

import cloud.trotter.log.gig.domain.model.event.AppEvent
import cloud.trotter.log.gig.domain.model.event.AppEventCodec
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.EventMetadata
import cloud.trotter.log.gig.domain.model.event.SequencedAppEvent
import cloud.trotter.log.gig.domain.model.event.payload.AppEventPayload
import cloud.trotter.log.gig.domain.model.event.payload.JobReceiptSettledPayload
import cloud.trotter.log.gig.domain.model.event.payload.JobSettlementKind
import cloud.trotter.log.gig.domain.model.event.payload.JobSettlementLine
import cloud.trotter.log.gig.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1601: the `JOB_RECEIPT_SETTLED` fold judges a shift that EXISTS at its log position; every malformed shape skips. */
class JobSettlementFoldsTest {
    private val live = SessionFoldContext("S1", Platform.DoorDash, 100L, 200L, endedAt = null)
    private val malformed = "JOB_RECEIPT_SETTLED: malformed settlement"

    private fun ev(payload: AppEventPayload?, envelope: String? = "S1") = SequencedAppEvent(
        sequenceId = 42,
        event = AppEvent(AppEventType.JOB_RECEIPT_SETTLED, 999_999L, envelope, payload),
        metadata = EventMetadata(odometer = 999.0),
    )

    private fun settled(
        lines: List<JobSettlementLine> = listOf(JobSettlementLine("t1", 878), JobSettlementLine("t2", 877)),
        receiptCents: Long = 1755,
        card: Int = 2,
        revision: Int = 1,
        kind: JobSettlementKind = JobSettlementKind.SETTLE,
        commandId: String = "settle:J1",
        evidenceId: String = "receipt:t2:4000",
        evidenceRevision: Int = 1,
    ) = JobReceiptSettledPayload(
        commandId = commandId, sessionId = "S1", jobId = "J1", revision = revision, kind = kind,
        policyVersion = JobReceiptSettledPayload.JOB_SETTLEMENT_POLICY_VERSION, evidenceId = evidenceId,
        evidenceRevision = evidenceRevision, receiptCents = receiptCents, cardDropoffCount = card, lines = lines,
    )

    private fun fold(payload: AppEventPayload?, context: SessionFoldContext? = live, envelope: String? = "S1") =
        RecordFolds.foldEvent(ev(payload, envelope), context, null)

    @Test fun `the event is bookkeeping`() = assertTrue(AppEventType.JOB_RECEIPT_SETTLED in RecordFolds.BOOKKEEPING_EVENTS)

    @Test fun `a valid settlement folds on a LIVE shift, with no context`() { // non-vacuous
        val outcome = fold(settled())
        assertNull(outcome.skip)
        assertNull(outcome.context)
        assertEquals(
            JobSettlementFold(
                commandId = "settle:J1", sessionId = "S1", jobId = "J1", revision = 1, kind = JobSettlementKind.SETTLE,
                evidenceId = "receipt:t2:4000", evidenceRevision = 1, receiptCents = 1755,
                shares = listOf("t1" to 878L, "t2" to 877L), settledAt = 999_999L, sequenceId = 42,
            ),
            outcome.jobSettlement,
        )
        // An ended shift folds it too: the shift must exist, not have ended.
        assertNull(fold(settled(), live.copy(endedAt = 300L)).skip)
    }

    @Test fun `a withdrawal folds with no lines`() {
        val outcome = fold(settled(lines = emptyList(), kind = JobSettlementKind.WITHDRAW, revision = 2))
        assertEquals(JobSettlementKind.WITHDRAW, outcome.jobSettlement?.kind)
        assertEquals(malformed, fold(settled(kind = JobSettlementKind.WITHDRAW, revision = 2)).skip)
    }

    @Test fun `an unknown shift, or one that does not match the envelope, is a counted skip`() {
        val unknown = "JOB_RECEIPT_SETTLED: target shift unknown at this log position"
        assertEquals(unknown, fold(settled(), context = null).skip)
        assertEquals(unknown, fold(settled(), envelope = "other").skip)
        assertEquals("JOB_RECEIPT_SETTLED: missing/malformed payload", fold(null).skip)
    }

    @Test fun `every malformed shape is a counted skip`() {
        val shapes = listOf(
            settled(commandId = ""),
            settled(commandId = "settle:J2"),
            settled(evidenceId = ""),
            settled(revision = 0),
            settled(evidenceRevision = 0),
            settled(receiptCents = 0, lines = listOf(JobSettlementLine("t1", 0), JobSettlementLine("t2", 0))),
            settled(card = 1, lines = listOf(JobSettlementLine("t1", 1755))),
            settled(lines = listOf(JobSettlementLine("t1", 1755))),
            settled(lines = listOf(JobSettlementLine("t1", 878), JobSettlementLine("t1", 877))),
            settled(lines = listOf(JobSettlementLine("", 878), JobSettlementLine("t2", 877))),
            settled(lines = listOf(JobSettlementLine("t1", 1800), JobSettlementLine("t2", -45))),
            settled(lines = listOf(JobSettlementLine("t1", 878), JobSettlementLine("t2", 878))),
            settled(lines = listOf(JobSettlementLine("t1", 878, tipCents = 300, basePayCents = 578), JobSettlementLine("t2", 877))),
            settled(lines = listOf(JobSettlementLine("t1", 878, 300, 500), JobSettlementLine("t2", 877, 400, 477))),
        )
        shapes.forEachIndexed { i, p ->
            val outcome = fold(p)
            assertEquals("shape $i", malformed, outcome.skip)
            assertNull("shape $i", outcome.jobSettlement)
        }
        // A coherent tip/base split on every line folds.
        val split = settled(lines = listOf(JobSettlementLine("t1", 878, 300, 578), JobSettlementLine("t2", 877, 400, 477)))
        assertNull(fold(split).skip)
    }

    @Test fun `the payload round-trips through the codec`() {
        val p = settled(lines = listOf(JobSettlementLine("t1", 878, 300, 578), JobSettlementLine("t2", 877)))
        assertEquals(p, AppEventCodec.decodePayload(AppEventType.JOB_RECEIPT_SETTLED, AppEventCodec.encodePayload(p)))
        val w = settled(lines = emptyList(), kind = JobSettlementKind.WITHDRAW, revision = 3)
        assertEquals(w, AppEventCodec.decodePayload(AppEventType.JOB_RECEIPT_SETTLED, AppEventCodec.encodePayload(w)))
    }
}
