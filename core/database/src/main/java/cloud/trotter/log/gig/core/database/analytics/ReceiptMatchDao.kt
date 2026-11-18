package cloud.trotter.log.gig.core.database.analytics

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** The receipt match rows (#1599): written by the projector's match apply, read by the shift page and the checks. */
@Dao
interface ReceiptMatchDao {
    /** ABORT: a command id is applied once; the apply checks [commandRowCount] first. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(rows: List<ReceiptMatchRowEntity>)

    @Query("SELECT COUNT(*) FROM receipt_match_rows WHERE commandId = :commandId")
    suspend fun commandRowCount(commandId: String): Int

    @Query(
        """SELECT * FROM receipt_match_rows WHERE sessionId = :sessionId AND undoneAt IS NULL
           ORDER BY matchEventSequenceId, deliveryEventSequenceId""",
    )
    suspend fun activeRowsInSession(sessionId: String): List<ReceiptMatchRowEntity>

    @Query(
        """SELECT * FROM receipt_match_rows WHERE sessionId = :sessionId AND undoneAt IS NULL
           ORDER BY matchEventSequenceId, deliveryEventSequenceId""",
    )
    fun activeRowsInSessionFlow(sessionId: String): Flow<List<ReceiptMatchRowEntity>>

    @Query(
        """SELECT * FROM receipt_match_rows WHERE commandId = :commandId AND undoneAt IS NULL
           ORDER BY deliveryEventSequenceId""",
    )
    suspend fun activeRowsForCommand(commandId: String): List<ReceiptMatchRowEntity>

    @Query(
        """UPDATE receipt_match_rows SET undoneAt = :undoneAt, undoEventSequenceId = :undoSeq
           WHERE commandId = :commandId AND undoneAt IS NULL""",
    )
    suspend fun markUndone(commandId: String, undoneAt: Long, undoSeq: Long): Int

    /** #1601: every row of [commandId], active or not (a settlement's lifecycle reads them all). */
    @Query("SELECT * FROM receipt_match_rows WHERE commandId = :commandId ORDER BY deliveryEventSequenceId")
    suspend fun rowsForCommand(commandId: String): List<ReceiptMatchRowEntity>

    /** #1601: a replacement revision rewrites a settlement's rows in place. */
    @Update
    suspend fun updateAll(rows: List<ReceiptMatchRowEntity>)

    /** #1601: a settlement's withdrawal ends its active rows with [reason] (`WITHDRAWN` or `CONFLICT`). */
    @Query(
        """UPDATE receipt_match_rows SET undoneAt = :endedAt, undoEventSequenceId = :endSeq, endReason = :reason
           WHERE commandId = :commandId AND undoneAt IS NULL""",
    )
    suspend fun markEnded(commandId: String, endedAt: Long, endSeq: Long, reason: String): Int

    /** #1601: active rows of any match naming [deliveryEventSequenceId]; the machine re-price refuses such a row. */
    @Query("SELECT COUNT(*) FROM receipt_match_rows WHERE deliveryEventSequenceId = :deliveryEventSequenceId AND undoneAt IS NULL")
    suspend fun activeRowCountForDelivery(deliveryEventSequenceId: Long): Int

    /** #1601: the paying receipts of the shift's settlements that ended in CONFLICT. */
    @Query("SELECT DISTINCT evidenceId FROM receipt_match_rows WHERE sessionId = :sessionId AND endReason = 'CONFLICT'")
    fun conflictedEvidenceInSessionFlow(sessionId: String): Flow<List<String>>

    @Query("DELETE FROM receipt_match_rows")
    suspend fun deleteAll()
}
