package com.fintech.mobile.ui.login

import com.fintech.mobile.api.AuthApi
import com.fintech.mobile.api.model.LoginResponseDTO
import com.fintech.mobile.data.repository.AuthRepository
import com.fintech.mobile.session.TokenProvider
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `blank email shows a validation error without calling the API`() {
        val authApi = mockk<AuthApi>(relaxed = true)
        val viewModel = viewModelWith(authApi)

        viewModel.login("", "costa123")

        assertIs<LoginUiState.Error>(viewModel.uiState.value)
    }

    @Test
    fun `successful login moves to Success state`() = runTest {
        val authApi = mockk<AuthApi>()
        coEvery { authApi.login(any()) } returns Response.success(LoginResponseDTO(token = "jwt-abc"))
        val viewModel = viewModelWith(authApi)

        viewModel.login("carlos@costa.com", "costa123")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(LoginUiState.Success, viewModel.uiState.value)
    }

    @Test
    fun `401 from the backend shows an invalid credentials message`() = runTest {
        val authApi = mockk<AuthApi>()
        val body = "{}".toResponseBody("application/json".toMediaType())
        coEvery { authApi.login(any()) } returns Response.error(401, body)
        val viewModel = viewModelWith(authApi)

        viewModel.login("carlos@costa.com", "senha-errada")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertIs<LoginUiState.Error>(state)
        assertEquals("E-mail ou senha inválidos", state.message)
    }

    private fun viewModelWith(authApi: AuthApi): LoginViewModel {
        val tokenProvider = mockk<TokenProvider>(relaxUnitFun = true)
        val repository = AuthRepository(authApi, tokenProvider, Gson())
        return LoginViewModel(repository)
    }
}
