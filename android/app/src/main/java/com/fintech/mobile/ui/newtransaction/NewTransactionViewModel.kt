package com.fintech.mobile.ui.newtransaction

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintech.mobile.api.model.TransactionRequestDTO
import com.fintech.mobile.core.network.ApiResult
import com.fintech.mobile.data.repository.AccountRepository
import com.fintech.mobile.data.repository.CategoryRepository
import com.fintech.mobile.data.repository.CreateTransactionResult
import com.fintech.mobile.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class NewTransactionViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val transactionRepository: TransactionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(NewTransactionUiState())
    val uiState: StateFlow<NewTransactionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            when (val accounts = accountRepository.listAccounts()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(accounts = accounts.value)
                else -> Unit
            }
        }
        viewModelScope.launch {
            when (val categories = categoryRepository.listCategories()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(categories = categories.value)
                else -> Unit
            }
        }
    }

    fun onDescriptionChange(value: String) { _uiState.value = _uiState.value.copy(description = value) }
    fun onAmountChange(value: String) { _uiState.value = _uiState.value.copy(amountText = value) }
    fun onDateChange(value: LocalDate) { _uiState.value = _uiState.value.copy(date = value) }
    fun onAccountChange(value: UUID) { _uiState.value = _uiState.value.copy(selectedAccountId = value) }
    fun onCategoryChange(value: UUID?) { _uiState.value = _uiState.value.copy(selectedCategoryId = value) }
    fun onTotalInstallmentsChange(value: String) { _uiState.value = _uiState.value.copy(totalInstallmentsText = value) }

    fun submit() {
        val state = _uiState.value
        // Guarda de reentrância: duplo-toque não deve disparar uma segunda chamada de criação
        // (lançamento duplicado é um bug sério num app financeiro).
        if (state.isSubmitting) return
        when (
            val validation = NewTransactionFormValidator.validate(
                description = state.description,
                amountText = state.amountText,
                accountId = state.selectedAccountId,
                totalInstallmentsText = state.totalInstallmentsText,
                requiresInstallments = state.showInstallments
            )
        ) {
            is FormValidationResult.Invalid -> {
                _uiState.value = state.copy(fieldErrors = validation.fieldErrors)
            }
            is FormValidationResult.Valid -> {
                _uiState.value = state.copy(isSubmitting = true, fieldErrors = emptyMap(), submitError = null)
                viewModelScope.launch {
                    val dto = TransactionRequestDTO(
                        description = validation.form.description,
                        amount = validation.form.amount,
                        date = state.date,
                        type = state.type,
                        accountId = validation.form.accountId,
                        categoryId = state.selectedCategoryId,
                        totalInstallments = validation.form.totalInstallments
                    )
                    when (val result = transactionRepository.create(dto)) {
                        is CreateTransactionResult.Saved ->
                            _uiState.value = _uiState.value.copy(isSubmitting = false, banner = SubmitBanner.SAVED)
                        is CreateTransactionResult.Queued ->
                            _uiState.value = _uiState.value.copy(isSubmitting = false, banner = SubmitBanner.QUEUED)
                        is CreateTransactionResult.Failed ->
                            _uiState.value = _uiState.value.copy(
                                isSubmitting = false,
                                submitError = result.message,
                                fieldErrors = result.fieldErrors
                            )
                    }
                }
            }
        }
    }
}
