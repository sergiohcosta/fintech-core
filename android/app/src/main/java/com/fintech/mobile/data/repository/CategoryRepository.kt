package com.fintech.mobile.data.repository

import com.fintech.mobile.api.CategoriesApi
import com.fintech.mobile.api.model.CategoryResponseDTO
import com.fintech.mobile.core.network.ApiResult
import com.fintech.mobile.core.network.apiCall
import com.google.gson.Gson
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepository @Inject constructor(
    private val categoriesApi: CategoriesApi,
    private val gson: Gson
) {
    // Mesmo racional do cache de AccountRepository: sem isso, o formulário de novo lançamento
    // offline nunca tem categoria pra oferecer, mesmo com carga bem-sucedida minutos antes.
    @Volatile
    private var cachedCategories: List<CategoryResponseDTO>? = null

    suspend fun listCategories(): ApiResult<List<CategoryResponseDTO>> {
        val result = apiCall(gson) { categoriesApi.listCategories(includeArchived = false) }
        return when (result) {
            is ApiResult.Success -> {
                cachedCategories = result.value
                result
            }
            else -> cachedCategories?.let { ApiResult.Success(it) } ?: result
        }
    }
}
