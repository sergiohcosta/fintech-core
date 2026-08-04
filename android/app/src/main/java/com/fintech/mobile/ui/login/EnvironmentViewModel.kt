package com.fintech.mobile.ui.login

import androidx.lifecycle.ViewModel
import com.fintech.mobile.core.network.Environment
import com.fintech.mobile.core.network.NetworkRoute
import com.fintech.mobile.data.EnvironmentPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class EnvironmentViewModel @Inject constructor(
    private val environmentPreferences: EnvironmentPreferences
) : ViewModel() {

    val environment: StateFlow<Environment> = environmentPreferences.environment
    val route: StateFlow<NetworkRoute> = environmentPreferences.route
    val customLocalUrl: StateFlow<String?> = environmentPreferences.customLocalUrl

    fun onEnvironmentChange(value: Environment) = environmentPreferences.setEnvironment(value)
    fun onRouteChange(value: NetworkRoute) = environmentPreferences.setRoute(value)
    fun onCustomLocalUrlChange(value: String) = environmentPreferences.setCustomLocalUrl(value)
}
