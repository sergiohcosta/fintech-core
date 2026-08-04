package com.fintech.mobile.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintech.mobile.core.network.ApiResult
import com.fintech.mobile.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _uiState.value = LoginUiState.Error("Informe e-mail e senha")
            return
        }
        viewModelScope.launch {
            _uiState.value = LoginUiState.Loading
            _uiState.value = when (val result = authRepository.login(email.trim(), password)) {
                is ApiResult.Success -> LoginUiState.Success
                is ApiResult.ValidationError -> LoginUiState.Error(result.message)
                is ApiResult.NetworkError -> LoginUiState.Error("Sem conexão. Tente novamente.")
                is ApiResult.HttpError -> LoginUiState.Error(mapHttpError(result.code, result.message))
            }
        }
    }

    private fun mapHttpError(code: Int, fallbackMessage: String): String = when (code) {
        401 -> "E-mail ou senha inválidos"
        429 -> "Muitas tentativas. Aguarde um minuto e tente novamente."
        else -> fallbackMessage
    }
}
