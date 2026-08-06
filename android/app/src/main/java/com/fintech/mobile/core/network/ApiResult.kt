package com.fintech.mobile.core.network

sealed class ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>()
    data class ValidationError(val message: String, val fieldErrors: Map<String, String>) : ApiResult<Nothing>()
    data class HttpError(val code: Int, val message: String) : ApiResult<Nothing>()
    data class NetworkError(val cause: Throwable) : ApiResult<Nothing>()
}
