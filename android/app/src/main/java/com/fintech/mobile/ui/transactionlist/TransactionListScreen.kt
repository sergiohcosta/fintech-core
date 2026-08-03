package com.fintech.mobile.ui.transactionlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.fintech.mobile.ui.newtransaction.SubmitBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionListScreen(
    onAddTransaction: () -> Unit,
    pendingBanner: SubmitBanner? = null,
    onBannerConsumed: () -> Unit = {},
    viewModel: TransactionListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Resultado de navegação do formulário (AppNavHost via SavedStateHandle): dispara
    // refresh() — sem isso a lista só atualizava ao tocar manualmente no ícone de atualizar,
    // já que o ViewModel sobrevive na back stack e refresh() só rodava no init{} — e mostra
    // a distinção SAVED (foi direto pro backend) vs. QUEUED (ficou na fila offline), critério
    // de aceite da Tarefa 14 que o LaunchedEffect antigo nunca deixava o usuário ver.
    LaunchedEffect(pendingBanner) {
        val banner = pendingBanner ?: return@LaunchedEffect
        viewModel.refresh()
        val message = when (banner) {
            SubmitBanner.SAVED -> "Transação salva"
            SubmitBanner.QUEUED -> "Sem conexão — salva localmente, será enviada depois"
        }
        snackbarHostState.showSnackbar(message)
        onBannerConsumed()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transações") },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Atualizar")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddTransaction) {
                Icon(Icons.Filled.Add, contentDescription = "Novo lançamento")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (uiState.isLoading && uiState.transactions.isEmpty()) {
            CircularProgressIndicator(modifier = Modifier.padding(padding))
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(uiState.pending) { pending ->
                ListItem(
                    headlineContent = { Text("Pendente de envio") },
                    supportingContent = { pending.errorMessage?.let { Text(it) } },
                    trailingContent = {
                        IconButton(onClick = { viewModel.discardPending(pending.localId) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Descartar pendente")
                        }
                    }
                )
            }
            items(uiState.transactions) { transaction ->
                ListItem(
                    headlineContent = { Text(transaction.description) },
                    supportingContent = { Text("${transaction.date} · ${transaction.type}") },
                    trailingContent = { Text(transaction.amount.toString()) }
                )
            }
            uiState.error?.let { error ->
                item {
                    Row(horizontalArrangement = Arrangement.Center) { Text(error) }
                }
            }
        }
    }
}
