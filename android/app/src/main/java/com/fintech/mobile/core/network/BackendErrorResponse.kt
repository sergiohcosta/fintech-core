package com.fintech.mobile.core.network

// Espelha o formato de GlobalExceptionHandler.buildErrorResponse (backend):
// {timestamp, status, error, message, details?}. Só os campos usados pelo app são mapeados.
data class BackendErrorResponse(
    val message: String? = null,
    val details: Map<String, String>? = null
)
