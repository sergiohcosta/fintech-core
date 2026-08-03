package com.fintech.mobile.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fintech.mobile.api.TransactionsApi
import com.fintech.mobile.api.model.TransactionRequestDTO
import com.fintech.mobile.core.network.ApiResult
import com.fintech.mobile.core.network.apiCall
import com.fintech.mobile.data.local.PendingTransactionDao
import com.fintech.mobile.data.local.PendingTransactionEntity
import com.google.gson.Gson
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

// Drena a fila offline (outbox) criada na Tarefa 6: cada item PENDING é reenviado pela
// mesma API usada no lançamento manual (Tarefa 9). Falha de validação/HTTP marca o item
// como FAILED em vez de tentar de novo pra sempre — reenviar um payload já rejeitado
// pelo backend só reproduziria o mesmo erro. Só NetworkError pede retry do WorkManager,
// que já tem backoff embutido — falha de conectividade é a única transitória de verdade.
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val transactionsApi: TransactionsApi,
    private val pendingDao: PendingTransactionDao,
    private val gson: Gson
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val pending = pendingDao.getByStatus(PendingTransactionEntity.STATUS_PENDING)
        for (item in pending) {
            val dto = gson.fromJson(item.payloadJson, TransactionRequestDTO::class.java)
            when (val result = apiCall(gson) { transactionsApi.createTransaction(dto) }) {
                is ApiResult.Success -> pendingDao.delete(item.localId)
                is ApiResult.ValidationError ->
                    pendingDao.updateStatus(item.localId, PendingTransactionEntity.STATUS_FAILED, result.message)
                is ApiResult.HttpError ->
                    pendingDao.updateStatus(item.localId, PendingTransactionEntity.STATUS_FAILED, result.message)
                is ApiResult.NetworkError -> return Result.retry()
            }
        }
        return Result.success()
    }
}
