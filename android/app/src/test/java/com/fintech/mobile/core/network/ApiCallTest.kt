package com.fintech.mobile.core.network

import com.google.gson.Gson
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ApiCallTest {

    private val gson = Gson()

    @Test
    fun `maps a successful response to Success`() = runTest {
        val result = apiCall(gson) { Response.success("ok") }
        assertEquals(ApiResult.Success("ok"), result)
    }

    @Test
    fun `maps 400 with body to ValidationError with field details`() = runTest {
        val body = """{"message":"Dados inválidos","details":{"amount":"deve ser maior que zero"}}"""
            .toResponseBody("application/json".toMediaType())

        val result = apiCall<Unit>(gson) { Response.error(400, body) }

        assertIs<ApiResult.ValidationError>(result)
        assertEquals("Dados inválidos", result.message)
        assertEquals(mapOf("amount" to "deve ser maior que zero"), result.fieldErrors)
    }

    @Test
    fun `maps a non-400 error response to HttpError with the status code`() = runTest {
        val body = """{"message":"Fatura não encontrada"}"""
            .toResponseBody("application/json".toMediaType())

        val result = apiCall<Unit>(gson) { Response.error(404, body) }

        assertIs<ApiResult.HttpError>(result)
        assertEquals(404, result.code)
        assertEquals("Fatura não encontrada", result.message)
    }

    @Test
    fun `maps IOException to NetworkError`() = runTest {
        val exception = IOException("sem conexão")

        val result = apiCall<Unit>(gson) { throw exception }

        assertIs<ApiResult.NetworkError>(result)
        assertEquals(exception, result.cause)
    }
}
