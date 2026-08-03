package com.fintech.mobile.core.network

import com.google.gson.Gson
import retrofit2.Response
import java.io.IOException

// Todo método gerado pelo openapi-generator (kotlin/jvm-retrofit2) retorna Response<T> —
// não lança HttpException em status não-2xx. Só IOException (sem conexão/timeout) é
// exceção de verdade aqui; erro HTTP é um valor (response.isSuccessful == false).
suspend fun <T> apiCall(gson: Gson, block: suspend () -> Response<T>): ApiResult<T> {
    return try {
        val response = block()
        if (response.isSuccessful) {
            val body = response.body()
            if (body != null) {
                ApiResult.Success(body)
            } else {
                ApiResult.HttpError(code = response.code(), message = "Resposta vazia")
            }
        } else {
            val parsed = parseErrorBody(gson, response)
            if (response.code() == 400) {
                ApiResult.ValidationError(
                    message = parsed?.message ?: "Dados inválidos",
                    fieldErrors = parsed?.details ?: emptyMap()
                )
            } else {
                ApiResult.HttpError(
                    code = response.code(),
                    message = parsed?.message ?: response.message().ifBlank { "Erro ${response.code()}" }
                )
            }
        }
    } catch (e: IOException) {
        ApiResult.NetworkError(e)
    }
}

private fun parseErrorBody(gson: Gson, response: Response<*>): BackendErrorResponse? {
    val raw = response.errorBody()?.string() ?: return null
    return runCatching { gson.fromJson(raw, BackendErrorResponse::class.java) }.getOrNull()
}
