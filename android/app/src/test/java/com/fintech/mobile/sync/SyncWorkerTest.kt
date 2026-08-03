package com.fintech.mobile.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.fintech.mobile.api.TransactionsApi
import com.fintech.mobile.api.infrastructure.Serializer
import com.fintech.mobile.api.model.TransactionRequestDTO
import com.fintech.mobile.api.model.TransactionResponseDTO
import com.fintech.mobile.api.model.TransactionType
import com.fintech.mobile.data.local.PendingTransactionDao
import com.fintech.mobile.data.local.PendingTransactionEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

// Usa Serializer.gson (com TypeAdapters de LocalDate) em vez de Gson() puro — o mesmo
// problema já resolvido na Tarefa 9: Gson() puro serializa java.time.* por reflexão e
// quebra em JDK 17+ (InaccessibleObjectException, módulo java.base fechado).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncWorkerTest {

    private val gson = Serializer.gson
    private val sampleDto = TransactionRequestDTO(
        description = "Mercado",
        amount = 150.0,
        date = LocalDate.of(2026, 7, 31),
        type = TransactionType.EXPENSE,
        accountId = UUID.randomUUID()
    )

    private fun buildWorker(api: TransactionsApi, dao: PendingTransactionDao): SyncWorker {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return TestListenableWorkerBuilder<SyncWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ) = SyncWorker(appContext, workerParameters, api, dao, gson)
            })
            .build()
    }

    @Test
    fun `removes a pending item after a successful sync`() = runTest {
        val pending = PendingTransactionEntity(localId = 1, payloadJson = gson.toJson(sampleDto), createdAt = 1L)
        val api = mockk<TransactionsApi>()
        coEvery { api.createTransaction(sampleDto) } returns Response.success(emptyList<TransactionResponseDTO>())
        val dao = mockk<PendingTransactionDao>()
        coEvery { dao.getByStatus(PendingTransactionEntity.STATUS_PENDING) } returns listOf(pending)
        coEvery { dao.delete(1L) } returns Unit

        val result = buildWorker(api, dao).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { dao.delete(1L) }
    }

    @Test
    fun `marks the item as failed on validation error and keeps the worker successful`() = runTest {
        val pending = PendingTransactionEntity(localId = 1, payloadJson = gson.toJson(sampleDto), createdAt = 1L)
        val api = mockk<TransactionsApi>()
        val body = """{"message":"valor inválido"}""".toResponseBody("application/json".toMediaType())
        coEvery { api.createTransaction(sampleDto) } returns Response.error(400, body)
        val dao = mockk<PendingTransactionDao>()
        coEvery { dao.getByStatus(PendingTransactionEntity.STATUS_PENDING) } returns listOf(pending)
        coEvery { dao.updateStatus(1L, PendingTransactionEntity.STATUS_FAILED, "valor inválido") } returns Unit

        val result = buildWorker(api, dao).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { dao.updateStatus(1L, PendingTransactionEntity.STATUS_FAILED, "valor inválido") }
    }

    @Test
    fun `requests a retry when a network error happens`() = runTest {
        val pending = PendingTransactionEntity(localId = 1, payloadJson = gson.toJson(sampleDto), createdAt = 1L)
        val api = mockk<TransactionsApi>()
        coEvery { api.createTransaction(sampleDto) } throws IOException("sem conexão")
        val dao = mockk<PendingTransactionDao>()
        coEvery { dao.getByStatus(PendingTransactionEntity.STATUS_PENDING) } returns listOf(pending)

        val result = buildWorker(api, dao).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }
}
