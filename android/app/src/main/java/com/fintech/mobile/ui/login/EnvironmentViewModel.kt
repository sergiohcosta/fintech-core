package com.fintech.mobile.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fintech.mobile.core.network.Environment
import com.fintech.mobile.core.network.NetworkRoute
import com.fintech.mobile.data.EnvironmentPreferences
import com.fintech.mobile.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EnvironmentViewModel @Inject constructor(
    private val environmentPreferences: EnvironmentPreferences,
    private val transactionRepository: TransactionRepository
) : ViewModel() {

    val environment: StateFlow<Environment> = environmentPreferences.environment
    val route: StateFlow<NetworkRoute> = environmentPreferences.route
    val customLocalUrl: StateFlow<String?> = environmentPreferences.customLocalUrl

    // Fila offline (Room) não sabe de qual ambiente veio o item pendente — trocar de
    // ambiente e sincronizar depois enviaria o payload para o backend errado. Decisão do
    // produto: descartar a fila ao trocar (não preservar entre ambientes). Reselecionar o
    // mesmo ambiente não deve limpar nada.
    fun onEnvironmentChange(value: Environment) {
        if (value != environmentPreferences.environment.value) {
            viewModelScope.launch { transactionRepository.discardAllPending() }
        }
        environmentPreferences.setEnvironment(value)
    }

    fun onRouteChange(value: NetworkRoute) = environmentPreferences.setRoute(value)
    fun onCustomLocalUrlChange(value: String) = environmentPreferences.setCustomLocalUrl(value)
}
