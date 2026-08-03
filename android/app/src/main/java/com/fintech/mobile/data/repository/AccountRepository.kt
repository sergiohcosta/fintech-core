package com.fintech.mobile.data.repository

import com.fintech.mobile.api.AccountsApi
import com.fintech.mobile.api.model.AccountResponse
import com.fintech.mobile.core.network.ApiResult
import com.fintech.mobile.core.network.apiCall
import com.google.gson.Gson
import javax.inject.Inject

class AccountRepository @Inject constructor(
    private val accountsApi: AccountsApi,
    private val gson: Gson
) {
    suspend fun listAccounts(): ApiResult<List<AccountResponse>> =
        apiCall(gson) { accountsApi.listAccounts() }
}
