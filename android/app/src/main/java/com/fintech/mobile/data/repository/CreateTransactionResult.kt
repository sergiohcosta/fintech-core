package com.fintech.mobile.data.repository

sealed class CreateTransactionResult {
    data object Saved : CreateTransactionResult()
    data object Queued : CreateTransactionResult()
    data class Failed(val message: String, val fieldErrors: Map<String, String> = emptyMap()) : CreateTransactionResult()
}
