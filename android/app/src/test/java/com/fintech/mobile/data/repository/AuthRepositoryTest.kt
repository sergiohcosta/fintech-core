package com.fintech.mobile.data.repository

import com.fintech.mobile.api.AuthApi
import com.fintech.mobile.api.model.LoginDTO
import com.fintech.mobile.api.model.LoginResponseDTO
import com.fintech.mobile.core.network.ApiResult
import com.fintech.mobile.session.TokenProvider
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test
import retrofit2.Response
import kotlin.test.assertIs

class AuthRepositoryTest {

    private val gson = Gson()

    @Test
    fun `saves the token and returns Success when login succeeds`() = runTest {
        val authApi = mockk<AuthApi>()
        coEvery { authApi.login(LoginDTO(email = "carlos@costa.com", password = "costa123")) } returns
            Response.success(LoginResponseDTO(token = "jwt-token-123"))
        val tokenProvider = mockk<TokenProvider>(relaxUnitFun = true)

        val repository = AuthRepository(authApi, tokenProvider, gson)
        val result = repository.login("carlos@costa.com", "costa123")

        assertIs<ApiResult.Success<Unit>>(result)
        verify { tokenProvider.saveToken("jwt-token-123") }
    }

    @Test
    fun `does not save a token when the backend returns a blank token`() = runTest {
        val authApi = mockk<AuthApi>()
        coEvery { authApi.login(any()) } returns Response.success(LoginResponseDTO(token = null))
        val tokenProvider = mockk<TokenProvider>(relaxUnitFun = true)

        val repository = AuthRepository(authApi, tokenProvider, gson)
        val result = repository.login("carlos@costa.com", "costa123")

        assertIs<ApiResult.HttpError>(result)
        verify(exactly = 0) { tokenProvider.saveToken(any()) }
    }
}
