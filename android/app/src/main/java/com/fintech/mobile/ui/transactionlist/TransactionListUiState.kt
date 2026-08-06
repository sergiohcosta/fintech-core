package com.fintech.mobile.ui.transactionlist

import com.fintech.mobile.api.model.TransactionResponseDTO
import com.fintech.mobile.data.local.PendingTransactionEntity

data class TransactionListUiState(
    val isLoading: Boolean = true,
    val transactions: List<TransactionResponseDTO> = emptyList(),
    val pending: List<PendingTransactionEntity> = emptyList(),
    val error: String? = null
)
