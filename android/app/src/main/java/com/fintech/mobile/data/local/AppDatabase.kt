package com.fintech.mobile.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [PendingTransactionEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingTransactionDao(): PendingTransactionDao
}
