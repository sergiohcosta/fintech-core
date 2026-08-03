package com.fintech.mobile.data.repository

import com.fintech.mobile.api.CategoriesApi
import com.fintech.mobile.api.model.CategoryResponseDTO
import com.fintech.mobile.core.network.ApiResult
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import retrofit2.Response
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CategoryRepositoryTest {

    @Test
    fun `returns the categories from the API`() = runTest {
        val category = CategoryResponseDTO(
            id = UUID.randomUUID(),
            name = "Mercado",
            icon = "cart",
            color = "#00FF00",
            archived = false,
            children = emptyList()
        )
        val api = mockk<CategoriesApi>()
        coEvery { api.listCategories(any()) } returns Response.success(listOf(category))

        val result = CategoryRepository(api, Gson()).listCategories()

        assertIs<ApiResult.Success<List<CategoryResponseDTO>>>(result)
        assertEquals(listOf(category), result.value)
    }
}
