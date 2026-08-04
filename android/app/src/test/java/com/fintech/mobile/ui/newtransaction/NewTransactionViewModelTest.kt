package com.fintech.mobile.ui.newtransaction

import com.fintech.mobile.api.model.AccountResponse
import com.fintech.mobile.api.model.AccountType
import com.fintech.mobile.core.network.ApiResult
import com.fintech.mobile.data.repository.AccountRepository
import com.fintech.mobile.data.repository.CategoryRepository
import com.fintech.mobile.data.repository.CreateTransactionResult
import com.fintech.mobile.data.repository.TransactionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NewTransactionViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val accountId = UUID.randomUUID()

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private fun buildViewModel(
        accounts: List<AccountResponse> = emptyList(),
        transactionRepository: TransactionRepository = mockk()
    ): NewTransactionViewModel {
        val accountRepository = mockk<AccountRepository>()
        coEvery { accountRepository.listAccounts() } returns ApiResult.Success(accounts)
        val categoryRepository = mockk<CategoryRepository>()
        coEvery { categoryRepository.listCategories() } returns ApiResult.Success(emptyList())

        val viewModel = NewTransactionViewModel(accountRepository, categoryRepository, transactionRepository)
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `shows the installments field only when the selected account is a credit card`() {
        val creditCard = AccountResponse(
            id = accountId, name = "Cartão", type = AccountType.CREDIT_CARD,
            countInLiquidBalance = false, countInNetWorth = true, active = true, balance = 0.0
        )
        val viewModel = buildViewModel(accounts = listOf(creditCard))

        viewModel.onAccountChange(accountId)

        assertTrue(viewModel.uiState.value.showInstallments)
    }

    @Test
    fun `submitting an invalid form sets field errors without calling the repository`() {
        val transactionRepository = mockk<TransactionRepository>()
        val viewModel = buildViewModel(transactionRepository = transactionRepository)

        viewModel.submit()

        assertTrue(viewModel.uiState.value.fieldErrors.isNotEmpty())
    }

    @Test
    fun `successful submit sets the Saved banner`() = runTest {
        val checking = AccountResponse(
            id = accountId, name = "Conta corrente", type = AccountType.CHECKING,
            countInLiquidBalance = true, countInNetWorth = true, active = true, balance = 0.0
        )
        val transactionRepository = mockk<TransactionRepository>()
        coEvery { transactionRepository.create(any()) } returns CreateTransactionResult.Saved
        val viewModel = buildViewModel(accounts = listOf(checking), transactionRepository = transactionRepository)

        viewModel.onDescriptionChange("Mercado")
        viewModel.onAmountChange("150,00")
        viewModel.onAccountChange(accountId)
        viewModel.submit()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(SubmitBanner.SAVED, viewModel.uiState.value.banner)
    }

    @Test
    fun `double-tapping submit before it finishes only creates one transaction`() = runTest {
        val checking = AccountResponse(
            id = accountId, name = "Conta corrente", type = AccountType.CHECKING,
            countInLiquidBalance = true, countInNetWorth = true, active = true, balance = 0.0
        )
        val transactionRepository = mockk<TransactionRepository>()
        coEvery { transactionRepository.create(any()) } returns CreateTransactionResult.Saved
        val viewModel = buildViewModel(accounts = listOf(checking), transactionRepository = transactionRepository)

        viewModel.onDescriptionChange("Mercado")
        viewModel.onAmountChange("150,00")
        viewModel.onAccountChange(accountId)

        // Primeiro submit() marca isSubmitting=true de forma síncrona (antes do viewModelScope.launch
        // rodar); um segundo toque imediato, antes do dispatcher avançar, deve ser bloqueado pela guarda.
        viewModel.submit()
        viewModel.submit()
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { transactionRepository.create(any()) }
        assertEquals(SubmitBanner.SAVED, viewModel.uiState.value.banner)
    }
}
