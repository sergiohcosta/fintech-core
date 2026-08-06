package com.fintech.mobile.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingTransactionDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: PendingTransactionDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.pendingTransactionDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `insert then observeAll returns the pending item`() = runTest {
        dao.insert(PendingTransactionEntity(payloadJson = "{}", createdAt = 1L))

        val all = dao.observeAll().first()

        assertEquals(1, all.size)
        assertEquals(PendingTransactionEntity.STATUS_PENDING, all[0].status)
    }

    @Test
    fun `getByStatus only returns items with the matching status`() = runTest {
        val failedId = dao.insert(
            PendingTransactionEntity(payloadJson = "{}", createdAt = 1L, status = PendingTransactionEntity.STATUS_FAILED)
        )
        dao.insert(PendingTransactionEntity(payloadJson = "{}", createdAt = 2L))

        val pending = dao.getByStatus(PendingTransactionEntity.STATUS_PENDING)

        assertEquals(1, pending.size)
        assertTrue(pending.none { it.localId == failedId })
    }

    @Test
    fun `updateStatus marks the item as failed with a message`() = runTest {
        val id = dao.insert(PendingTransactionEntity(payloadJson = "{}", createdAt = 1L))

        dao.updateStatus(id, PendingTransactionEntity.STATUS_FAILED, "valor inválido")

        val all = dao.observeAll().first()
        assertEquals(PendingTransactionEntity.STATUS_FAILED, all[0].status)
        assertEquals("valor inválido", all[0].errorMessage)
    }

    @Test
    fun `delete removes the item`() = runTest {
        val id = dao.insert(PendingTransactionEntity(payloadJson = "{}", createdAt = 1L))

        dao.delete(id)

        assertTrue(dao.observeAll().first().isEmpty())
    }

    @Test
    fun `deleteAll removes every pending item`() = runTest {
        dao.insert(PendingTransactionEntity(payloadJson = "{}", createdAt = 1L))
        dao.insert(PendingTransactionEntity(payloadJson = "{}", createdAt = 2L))

        dao.deleteAll()

        assertTrue(dao.observeAll().first().isEmpty())
    }
}
