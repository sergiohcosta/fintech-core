package com.fintech.mobile.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_transactions")
data class PendingTransactionEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val payloadJson: String,
    val createdAt: Long,
    val status: String = STATUS_PENDING,
    val errorMessage: String? = null
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_FAILED = "FAILED"
    }
}
