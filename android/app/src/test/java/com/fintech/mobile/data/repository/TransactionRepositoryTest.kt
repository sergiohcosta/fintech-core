package com.fintech.mobile.data.repository

import com.fintech.mobile.api.TransactionsApi
import com.fintech.mobile.api.model.TransactionRequestDTO
import com.fintech.mobile.api.model.TransactionResponseDTO
import com.fintech.mobile.api.model.TransactionType
import com.fintech.mobile.data.local.PendingTransactionDao
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TransactionRepositoryTest {

    private val gson = Gson()
    private val sampleDto = TransactionRequestDTO(
        description = "Mercado",
        amount = 150.0,
        date = LocalDate.of(2026, 7, 31),
        type = TransactionType.EXPENSE,
        accountId = UUID.randomUUID()
    )

    @Test
    fun `queues the payload when the create call fails with a network error`() = runTest {
        val api = mockk<TransactionsApi>()
        coEvery { api.createTransaction(sampleDto) } throws IOException("sem conexão")
        val dao = mockk<PendingTransactionDao>()
        coEvery { dao.insert(any()) } returns 1L

        val result = TransactionRepository(api, dao, gson).create(sampleDto)

        assertEquals(CreateTransactionResult.Queued, result)
        coVerify { dao.insert(match { it.payloadJson == gson.toJson(sampleDto) }) }
    }

    @Test
    fun `does not queue when the backend rejects with a validation error`() = runTest {
        val api = mockk<TransactionsApi>()
        val body = """{"message":"Erro de Validação","details":{"amount":"deve ser maior que zero"}}"""
            .toResponseBody("application/json".toMediaType())
        coEvery { api.createTransaction(sampleDto) } returns Response.error(400, body)
        val dao = mockk<PendingTransactionDao>()

        val result = TransactionRepository(api, dao, gson).create(sampleDto)

        assertIs<CreateTransactionResult.Failed>(result)
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun `returns Saved when the API accepts the transaction`() = runTest {
        val api = mockk<TransactionsApi>()
        coEvery { api.createTransaction(sampleDto) } returns Response.success(emptyList<TransactionResponseDTO>())
        val dao = mockk<PendingTransactionDao>()

        val result = TransactionRepository(api, dao, gson).create(sampleDto)

        assertEquals(CreateTransactionResult.Saved, result)
    }
}
