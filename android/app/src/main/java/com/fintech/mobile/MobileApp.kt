package com.fintech.mobile

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.fintech.mobile.sync.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class MobileApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        scheduleSync()
    }

    // Fecha o ciclo de sincronização: periódico (15 min, mínimo permitido pelo WorkManager)
    // garante drenagem contínua da fila offline, e o disparo único ao abrir o app cobre o
    // caso comum de "escrevi offline, abri o app já com rede de volta" sem esperar o próximo
    // ciclo periódico. NetworkType.CONNECTED em ambos — sincronizar sem rede é desperdício
    // de bateria e cai em NetworkError de qualquer forma (o SyncWorker já pede retry nesse caso).
    private fun scheduleSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workManager = WorkManager.getInstance(this)

        // KEEP: não reagenda o periódico já existente a cada onCreate (evita resetar a janela).
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_SYNC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
        )

        // REPLACE: cada novo start do app substitui o disparo único anterior (se ainda pendente).
        workManager.enqueueUniqueWork(
            STARTUP_SYNC_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()
        )
    }

    private companion object {
        const val PERIODIC_SYNC_NAME = "sync-pending-transactions-periodic"
        const val STARTUP_SYNC_NAME = "sync-pending-transactions-startup"
    }
}
