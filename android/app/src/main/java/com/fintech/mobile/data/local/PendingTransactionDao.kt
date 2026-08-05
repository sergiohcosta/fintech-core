package com.fintech.mobile.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingTransactionDao {

    @Insert
    suspend fun insert(entity: PendingTransactionEntity): Long

    @Query("SELECT * FROM pending_transactions ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<PendingTransactionEntity>>

    @Query("SELECT * FROM pending_transactions WHERE status = :status ORDER BY createdAt ASC")
    suspend fun getByStatus(status: String = PendingTransactionEntity.STATUS_PENDING): List<PendingTransactionEntity>

    @Query("UPDATE pending_transactions SET status = :status, errorMessage = :errorMessage WHERE localId = :localId")
    suspend fun updateStatus(localId: Long, status: String, errorMessage: String?)

    @Query("DELETE FROM pending_transactions WHERE localId = :localId")
    suspend fun delete(localId: Long)

    @Query("DELETE FROM pending_transactions")
    suspend fun deleteAll()
}
