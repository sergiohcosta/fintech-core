package com.fintech.mobile.data.repository

import com.fintech.mobile.api.AccountsApi
import com.fintech.mobile.api.model.AccountResponse
import com.fintech.mobile.api.model.AccountType
import com.fintech.mobile.core.network.ApiResult
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AccountRepositoryTest {

    private val sampleAccount = AccountResponse(
        id = UUID.randomUUID(),
        name = "Cartão Nubank",
        type = AccountType.CREDIT_CARD,
        countInLiquidBalance = false,
        countInNetWorth = true,
        active = true,
        balance = 150.0
    )

    @Test
    fun `returns the accounts from the API`() = runTest {
        val api = mockk<AccountsApi>()
        coEvery { api.listAccounts() } returns Response.success(listOf(sampleAccount))

        val result = AccountRepository(api, Gson()).listAccounts()

        assertIs<ApiResult.Success<List<AccountResponse>>>(result)
        assertEquals(listOf(sampleAccount), result.value)
    }

    @Test
    fun `falls back to the last successful list when a later call fails offline`() = runTest {
        val api = mockk<AccountsApi>()
        coEvery { api.listAccounts() } returns Response.success(listOf(sampleAccount))
        val repository = AccountRepository(api, Gson())
        repository.listAccounts()

        coEvery { api.listAccounts() } throws IOException("sem conexão")
        val result = repository.listAccounts()

        assertIs<ApiResult.Success<List<AccountResponse>>>(result)
        assertEquals(listOf(sampleAccount), result.value)
    }

    @Test
    fun `returns the network error when there is no cache yet`() = runTest {
        val api = mockk<AccountsApi>()
        coEvery { api.listAccounts() } throws IOException("sem conexão")

        val result = AccountRepository(api, Gson()).listAccounts()

        assertIs<ApiResult.NetworkError>(result)
    }
}
