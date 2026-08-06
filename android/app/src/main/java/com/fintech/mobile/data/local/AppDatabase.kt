package com.fintech.mobile.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

// exportSchema = true: sem o schema versionado em disco (android/app/schemas/, commitado),
// não há como escrever uma Migration segura no futuro — Room precisa comparar o schema
// antigo exportado contra o novo para validar o path de migração. Corrigir isso DEPOIS que
// já existir uma versão 2 em produção seria tarde demais (o histórico da v1 já teria sido
// perdido). O diretório de saída é configurado via KSP arg em build.gradle.kts.
@Database(entities = [PendingTransactionEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingTransactionDao(): PendingTransactionDao
}
