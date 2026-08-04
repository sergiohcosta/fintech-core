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
import java.io.IOException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CategoryRepositoryTest {

    private val sampleCategory = CategoryResponseDTO(
        id = UUID.randomUUID(),
        name = "Mercado",
        icon = "cart",
        color = "#00FF00",
        archived = false,
        children = emptyList()
    )

    @Test
    fun `returns the categories from the API`() = runTest {
        val api = mockk<CategoriesApi>()
        coEvery { api.listCategories(any()) } returns Response.success(listOf(sampleCategory))

        val result = CategoryRepository(api, Gson()).listCategories()

        assertIs<ApiResult.Success<List<CategoryResponseDTO>>>(result)
        assertEquals(listOf(sampleCategory), result.value)
    }

    @Test
    fun `falls back to the last successful list when a later call fails offline`() = runTest {
        val api = mockk<CategoriesApi>()
        coEvery { api.listCategories(any()) } returns Response.success(listOf(sampleCategory))
        val repository = CategoryRepository(api, Gson())
        repository.listCategories()

        coEvery { api.listCategories(any()) } throws IOException("sem conexão")
        val result = repository.listCategories()

        assertIs<ApiResult.Success<List<CategoryResponseDTO>>>(result)
        assertEquals(listOf(sampleCategory), result.value)
    }

    @Test
    fun `returns the network error when there is no cache yet`() = runTest {
        val api = mockk<CategoriesApi>()
        coEvery { api.listCategories(any()) } throws IOException("sem conexão")

        val result = CategoryRepository(api, Gson()).listCategories()

        assertIs<ApiResult.NetworkError>(result)
    }
}
