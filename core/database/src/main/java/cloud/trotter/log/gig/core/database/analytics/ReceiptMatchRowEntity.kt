package cloud.trotter.log.gig.core.database.analytics

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * One delivery row a receipt match (#1599) wrote, with what it found there, so its undo restores it exactly.
 * Folded from `RECEIPT_MATCHED`; `undoneAt` stamped by `RECEIPT_MATCH_UNDONE`. A rebuildable projection: wiped and
 * refolded with the other read-model tables. App ids and money only; not in the CSV export.
 *
 * #1601 (Room v28): a whole-job settlement (`JOB_RECEIPT_SETTLED`) writes its rows here too, [origin] `APP`, command
 * id `settle:<jobId>`. Its prior columns are the ORIGINAL priors its first revision found, never rewritten; a
 * replacement revision updates [newPayCents] and [settlementRevision] in place. `undoneAt` set means inactive, for any
 * reason: an undo ([endReason] null), a withdrawal (`WITHDRAWN`) or a withdrawal that found an edited row (`CONFLICT`).
 */
@Entity(
    tableName = "receipt_match_rows",
    primaryKeys = ["commandId", "deliveryEventSequenceId"],
    indices = [Index("sessionId"), Index("evidenceId"), Index("deliveryEventSequenceId")],
)
data class ReceiptMatchRowEntity(
    val commandId: String,
    val deliveryEventSequenceId: Long,
    val sessionId: String,
    val evidenceId: String,
    /** `ReceiptMatchRole.name`. */
    val role: String,
    val matchEventSequenceId: Long,
    val matchedAt: Long,
    val priorRealizedPay: Double?,
    val priorPayBasis: String,
    val priorNetProfit: Double?,
    val priorTip: Double?,
    val priorBasePay: Double?,
    val priorDriverAdjustedAt: Long?,
    /** COVERED: the share written; CLEARED: null. */
    val newPayCents: Long?,
    val undoneAt: Long? = null,
    val undoEventSequenceId: Long? = null,
    /** #1601: `MatchOrigin.name` — `DASHER` (a Confirm) or `APP` (a whole-job settlement). */
    @ColumnInfo(defaultValue = "DASHER")
    val origin: String = "DASHER",
    /** #1601: the settlement revision these rows hold; null on a dasher's match. */
    val settlementRevision: Int? = null,
    /** #1601: why a settlement's rows went inactive: `WITHDRAWN` or `CONFLICT`; null on an undo and while active. */
    val endReason: String? = null,
)
