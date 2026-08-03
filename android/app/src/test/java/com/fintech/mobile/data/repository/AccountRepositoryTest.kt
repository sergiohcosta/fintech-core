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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AccountRepositoryTest {

    @Test
    fun `returns the accounts from the API`() = runTest {
        val account = AccountResponse(
            id = UUID.randomUUID(),
            name = "Cartão Nubank",
            type = AccountType.CREDIT_CARD,
            countInLiquidBalance = false,
            countInNetWorth = true,
            active = true,
            balance = 150.0
        )
        val api = mockk<AccountsApi>()
        coEvery { api.listAccounts() } returns Response.success(listOf(account))

        val result = AccountRepository(api, Gson()).listAccounts()

        assertIs<ApiResult.Success<List<AccountResponse>>>(result)
        assertEquals(listOf(account), result.value)
    }
}
