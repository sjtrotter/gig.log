package cloud.trotter.log.gig.domain.model.event.payload

import kotlinx.serialization.Serializable

/** What one `JOB_RECEIPT_SETTLED` revision does (#1601). */
@Serializable
enum class JobSettlementKind {
    /** Place the receipt on the job's drops: the first revision, or a replacement of the active one. */
    SETTLE,

    /** The job's receipt reads now disagree: take the active settlement back (A2). */
    WITHDRAW,
}

/** One drop's share of a whole-job settlement (#1601), in cents. Tip and base only when the split states them. */
@Serializable
data class JobSettlementLine(
    val taskId: String,
    val cents: Long,
    val tipCents: Long? = null,
    val basePayCents: Long? = null,
)

/**
 * `JOB_RECEIPT_SETTLED` (#1601): the state machine proved that the receipt [evidenceId] (at [evidenceRevision]) paid
 * the whole one-offer job [jobId], whose card showed [cardDropoffCount] stops, and placed it on the job's drops.
 * [commandId] is the settlement's stable identity, `settle:<jobId>`; [revision] is the stepper's decision revision,
 * so a replacement or a withdrawal is a new revision of the same settlement. A machine event, never the dasher's.
 * PII: the app's own ids and cents only.
 */
@Serializable
data class JobReceiptSettledPayload(
    val commandId: String,
    val sessionId: String,
    val jobId: String,
    val revision: Int,
    val kind: JobSettlementKind = JobSettlementKind.SETTLE,
    /** [JOB_SETTLEMENT_POLICY_VERSION] when the stepper decided. */
    val policyVersion: Int,
    val evidenceId: String,
    val evidenceRevision: Int,
    val receiptCents: Long,
    val cardDropoffCount: Int,
    /** SETTLE: one line per drop, summing to [receiptCents]. WITHDRAW: empty. */
    val lines: List<JobSettlementLine>,
) : AppEventPayload {
    companion object {
        /** The proof's clause set; bump when a clause changes meaning. */
        const val JOB_SETTLEMENT_POLICY_VERSION = 1

        /** The settlement's stable identity, shared by every revision and by `RECEIPT_MATCH_UNDONE`. */
        fun commandIdFor(jobId: String): String = "settle:$jobId"
    }
}
