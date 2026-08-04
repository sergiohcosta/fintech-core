package com.fintech.mobile.ui.transactionlist

import com.fintech.mobile.api.model.TransactionResponseDTO
import com.fintech.mobile.api.model.TransactionStatus
import com.fintech.mobile.api.model.TransactionType
import com.fintech.mobile.core.network.ApiResult
import com.fintech.mobile.data.local.PendingTransactionEntity
import com.fintech.mobile.data.repository.TransactionRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionListViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `loads remote transactions and pending items on init`() {
        val remote = TransactionResponseDTO(
            id = UUID.randomUUID(), description = "Mercado", amount = 150.0,
            date = LocalDate.of(2026, 7, 31), type = TransactionType.EXPENSE, status = TransactionStatus.PENDING
        )
        val pending = PendingTransactionEntity(localId = 1, payloadJson = "{}", createdAt = 1L)

        val repository = mockk<TransactionRepository>()
        every { repository.observePending() } returns MutableStateFlow(listOf(pending))
        coEvery { repository.listRemote() } returns ApiResult.Success(listOf(remote))

        val viewModel = TransactionListViewModel(repository)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(remote), viewModel.uiState.value.transactions)
        assertEquals(listOf(pending), viewModel.uiState.value.pending)
    }

    @Test
    fun `shows an error message when the refresh fails with a network error`() {
        val repository = mockk<TransactionRepository>()
        every { repository.observePending() } returns MutableStateFlow(emptyList())
        coEvery { repository.listRemote() } returns ApiResult.NetworkError(RuntimeException("sem conexão"))

        val viewModel = TransactionListViewModel(repository)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Sem conexão. Tente novamente.", viewModel.uiState.value.error)
    }
}
