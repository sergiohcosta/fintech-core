package com.fintech.mobile.data.repository

import com.fintech.mobile.api.CategoriesApi
import com.fintech.mobile.api.model.CategoryResponseDTO
import com.fintech.mobile.core.network.ApiResult
import com.fintech.mobile.core.network.apiCall
import com.google.gson.Gson
import javax.inject.Inject

class CategoryRepository @Inject constructor(
    private val categoriesApi: CategoriesApi,
    private val gson: Gson
) {
    suspend fun listCategories(): ApiResult<List<CategoryResponseDTO>> =
        apiCall(gson) { categoriesApi.listCategories(includeArchived = false) }
}
