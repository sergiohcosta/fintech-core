package com.fintech.mobile.data.repository

import com.fintech.mobile.api.AccountsApi
import com.fintech.mobile.api.model.AccountResponse
import com.fintech.mobile.core.network.ApiResult
import com.fintech.mobile.core.network.apiCall
import com.google.gson.Gson
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepository @Inject constructor(
    private val accountsApi: AccountsApi,
    private val gson: Gson
) {
    // Cache em memória do último GET bem-sucedido nesta sessão do processo — sem ele, abrir
    // o formulário de novo lançamento offline (achado em QA manual, Tarefa 14) deixa o
    // dropdown de conta sempre vazio, mesmo que o dispositivo já tivesse a lista minutos
    // antes. @Singleton garante uma única instância do repository no grafo Hilt, condição
    // necessária para o cache sobreviver entre aberturas da tela.
    @Volatile
    private var cachedAccounts: List<AccountResponse>? = null

    suspend fun listAccounts(): ApiResult<List<AccountResponse>> {
        val result = apiCall(gson) { accountsApi.listAccounts() }
        return when (result) {
            is ApiResult.Success -> {
                cachedAccounts = result.value
                result
            }
            else -> cachedAccounts?.let { ApiResult.Success(it) } ?: result
        }
    }
}
