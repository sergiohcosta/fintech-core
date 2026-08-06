package com.fintech.mobile.ui.transactionlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintech.mobile.core.network.ApiResult
import com.fintech.mobile.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TransactionListViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransactionListUiState())
    val uiState: StateFlow<TransactionListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            transactionRepository.observePending().collect { pending ->
                _uiState.value = _uiState.value.copy(pending = pending)
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            _uiState.value = when (val result = transactionRepository.listRemote()) {
                is ApiResult.Success -> _uiState.value.copy(isLoading = false, transactions = result.value)
                is ApiResult.NetworkError -> _uiState.value.copy(isLoading = false, error = "Sem conexão. Tente novamente.")
                else -> _uiState.value.copy(isLoading = false, error = "Não foi possível carregar as transações.")
            }
        }
    }

    fun discardPending(localId: Long) {
        viewModelScope.launch { transactionRepository.discardPending(localId) }
    }
}
