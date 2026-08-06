package com.fintech.mobile.data.repository

import com.fintech.mobile.api.TransactionsApi
import com.fintech.mobile.api.model.TransactionRequestDTO
import com.fintech.mobile.api.model.TransactionResponseDTO
import com.fintech.mobile.core.network.ApiResult
import com.fintech.mobile.core.network.apiCall
import com.fintech.mobile.data.local.PendingTransactionDao
import com.fintech.mobile.data.local.PendingTransactionEntity
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

// Ponto central de decisão: salva direto na API quando há rede, cai para o outbox (Room)
// quando a falha é de conectividade (NetworkError), e nunca enfileira erro de validação —
// reenviar um payload que o backend já rejeitou só reproduziria o mesmo erro no próximo sync.
class TransactionRepository @Inject constructor(
    private val transactionsApi: TransactionsApi,
    private val pendingDao: PendingTransactionDao,
    private val gson: Gson
) {
    suspend fun create(dto: TransactionRequestDTO): CreateTransactionResult {
        return when (val result = apiCall(gson) { transactionsApi.createTransaction(dto) }) {
            is ApiResult.Success -> CreateTransactionResult.Saved
            is ApiResult.NetworkError -> {
                pendingDao.insert(
                    PendingTransactionEntity(
                        payloadJson = gson.toJson(dto),
                        createdAt = System.currentTimeMillis()
                    )
                )
                CreateTransactionResult.Queued
            }
            is ApiResult.ValidationError -> CreateTransactionResult.Failed(result.message, result.fieldErrors)
            is ApiResult.HttpError -> CreateTransactionResult.Failed(result.message)
        }
    }

    suspend fun listRemote(): ApiResult<List<TransactionResponseDTO>> =
        apiCall(gson) { transactionsApi.listTransactions() }

    fun observePending(): Flow<List<PendingTransactionEntity>> = pendingDao.observeAll()

    suspend fun discardPending(localId: Long) = pendingDao.delete(localId)

    // Troca de ambiente: itens pendentes foram criados apontando (indiretamente, via
    // apiCall/SyncWorker) para o backend do ambiente anterior. Decisão do produto: não
    // preservar entre ambientes — reenviar ao ambiente novo poderia gravar dado no tenant
    // errado, e não há hoje um jeito de marcar "de qual ambiente veio" no payload.
    suspend fun discardAllPending() = pendingDao.deleteAll()
}
