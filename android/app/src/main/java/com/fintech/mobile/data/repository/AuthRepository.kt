package com.fintech.mobile.data.repository

import com.fintech.mobile.api.AuthApi
import com.fintech.mobile.api.model.LoginDTO
import com.fintech.mobile.core.network.ApiResult
import com.fintech.mobile.core.network.apiCall
import com.fintech.mobile.session.TokenProvider
import com.google.gson.Gson
import javax.inject.Inject

class AuthRepository @Inject constructor(
    private val authApi: AuthApi,
    private val tokenProvider: TokenProvider,
    private val gson: Gson
) {
    suspend fun login(email: String, password: String): ApiResult<Unit> {
        return when (val result = apiCall(gson) { authApi.login(LoginDTO(email = email, password = password)) }) {
            is ApiResult.Success -> {
                val token = result.value.token
                if (token.isNullOrBlank()) {
                    ApiResult.HttpError(code = 200, message = "Resposta de login sem token")
                } else {
                    tokenProvider.saveToken(token)
                    ApiResult.Success(Unit)
                }
            }
            is ApiResult.ValidationError -> result
            is ApiResult.HttpError -> result
            is ApiResult.NetworkError -> result
        }
    }
}
