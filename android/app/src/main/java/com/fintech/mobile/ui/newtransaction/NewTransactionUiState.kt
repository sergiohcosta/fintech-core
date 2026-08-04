package com.fintech.mobile.ui.newtransaction

import com.fintech.mobile.api.model.AccountResponse
import com.fintech.mobile.api.model.AccountType
import com.fintech.mobile.api.model.CategoryResponseDTO
import com.fintech.mobile.api.model.TransactionType
import java.time.LocalDate
import java.util.UUID

enum class SubmitBanner { SAVED, QUEUED }

data class NewTransactionUiState(
    val description: String = "",
    val amountText: String = "",
    val date: LocalDate = LocalDate.now(),
    val type: TransactionType = TransactionType.EXPENSE,
    val accounts: List<AccountResponse> = emptyList(),
    val selectedAccountId: UUID? = null,
    val categories: List<CategoryResponseDTO> = emptyList(),
    val selectedCategoryId: UUID? = null,
    val totalInstallmentsText: String = "",
    val isSubmitting: Boolean = false,
    val fieldErrors: Map<String, String> = emptyMap(),
    val submitError: String? = null,
    val banner: SubmitBanner? = null
) {
    val selectedAccountType: AccountType?
        get() = accounts.find { it.id == selectedAccountId }?.type

    val showInstallments: Boolean
        get() = selectedAccountType == AccountType.CREDIT_CARD
}
