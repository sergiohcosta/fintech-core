package com.fintech.mobile.di

import android.content.Context
import androidx.room.Room
import com.fintech.mobile.data.local.AppDatabase
import com.fintech.mobile.data.local.PendingTransactionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "fintech-mobile.db").build()

    @Provides
    @Singleton
    fun providePendingTransactionDao(database: AppDatabase): PendingTransactionDao =
        database.pendingTransactionDao()
}
