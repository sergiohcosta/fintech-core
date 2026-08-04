package com.fintech.mobile.data

import android.content.Context
import com.fintech.mobile.core.network.Environment
import com.fintech.mobile.core.network.EnvironmentUrlResolver
import com.fintech.mobile.core.network.NetworkRoute
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

// SharedPreferences simples (não criptografado) — ao contrário do JWT no SessionManager,
// ambiente/rota/URL local não são dado sensível. Mesmo padrão de StateFlow-em-memória
// espelhando o disco usado pelo SessionManager, para leitura síncrona pelo
// EnvironmentInterceptor (que roda fora do main thread, sem acesso a coroutines).
@Singleton
class EnvironmentPreferences @Inject constructor(
    @ApplicationContext context: Context
) : EnvironmentUrlProvider {

    private val prefs = context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    private val _environment = MutableStateFlow(readEnum(KEY_ENVIRONMENT, Environment.LOCAL))
    val environment: StateFlow<Environment> = _environment.asStateFlow()

    private val _route = MutableStateFlow(readEnum(KEY_ROUTE, NetworkRoute.LAN))
    val route: StateFlow<NetworkRoute> = _route.asStateFlow()

    private val _customLocalUrl = MutableStateFlow(prefs.getString(KEY_CUSTOM_LOCAL_URL, null))
    val customLocalUrl: StateFlow<String?> = _customLocalUrl.asStateFlow()

    fun setEnvironment(value: Environment) {
        prefs.edit().putString(KEY_ENVIRONMENT, value.name).apply()
        _environment.value = value
    }

    fun setRoute(value: NetworkRoute) {
        prefs.edit().putString(KEY_ROUTE, value.name).apply()
        _route.value = value
    }

    fun setCustomLocalUrl(value: String?) {
        prefs.edit().putString(KEY_CUSTOM_LOCAL_URL, value).apply()
        _customLocalUrl.value = value
    }

    override fun currentBaseUrl(): String =
        EnvironmentUrlResolver.resolveBaseUrl(_environment.value, _route.value, _customLocalUrl.value)

    private inline fun <reified T : Enum<T>> readEnum(key: String, default: T): T =
        prefs.getString(key, null)?.let { stored ->
            runCatching { enumValueOf<T>(stored) }.getOrNull()
        } ?: default

    private companion object {
        const val PREFS_FILE_NAME = "fintech_environment"
        const val KEY_ENVIRONMENT = "environment"
        const val KEY_ROUTE = "route"
        const val KEY_CUSTOM_LOCAL_URL = "custom_local_url"
    }
}
