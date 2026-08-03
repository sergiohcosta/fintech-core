package com.fintech.mobile.ui.newtransaction

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTransactionScreen(
    onSaved: (SubmitBanner) -> Unit,
    viewModel: NewTransactionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // uiState.banner é setado no instante em que a criação termina (SAVED/QUEUED). Repassamos
    // o valor pra quem monta o NavHost em vez de decidir a UI de sucesso aqui — esta tela
    // desaparece da composição (popBackStack) antes de qualquer Snackbar/mensagem própria ter
    // chance de aparecer, então quem exibe o feedback é a tela de destino (ver AppNavHost).
    LaunchedEffect(uiState.banner) {
        uiState.banner?.let { onSaved(it) }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        OutlinedTextField(
            value = uiState.description,
            onValueChange = viewModel::onDescriptionChange,
            label = { Text("Descrição") },
            isError = uiState.fieldErrors.containsKey("description"),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = uiState.amountText,
            onValueChange = viewModel::onAmountChange,
            label = { Text("Valor") },
            isError = uiState.fieldErrors.containsKey("amount"),
            modifier = Modifier.fillMaxWidth()
        )

        var accountMenuExpanded by remember { mutableStateOf(false) }
        val selectedAccountName = uiState.accounts.find { it.id == uiState.selectedAccountId }?.name ?: ""
        ExposedDropdownMenuBox(expanded = accountMenuExpanded, onExpandedChange = { accountMenuExpanded = it }) {
            OutlinedTextField(
                value = selectedAccountName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Conta") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accountMenuExpanded) },
                isError = uiState.fieldErrors.containsKey("accountId"),
                // menuAnchor() é obrigatório no Material3 atual: sem ele o ExposedDropdownMenuBox
                // não sabe onde ancorar o popup e o menu nunca abre ao tocar no campo (bug real
                // encontrado em QA manual, Tarefa 14 — nenhum teste unitário cobre abertura real
                // de popup Compose).
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(
                expanded = accountMenuExpanded,
                onDismissRequest = { accountMenuExpanded = false }
            ) {
                uiState.accounts.forEach { account ->
                    DropdownMenuItem(
                        text = { Text(account.name) },
                        onClick = {
                            viewModel.onAccountChange(account.id)
                            accountMenuExpanded = false
                        }
                    )
                }
            }
        }

        if (uiState.showInstallments) {
            OutlinedTextField(
                value = uiState.totalInstallmentsText,
                onValueChange = viewModel::onTotalInstallmentsChange,
                label = { Text("Número de parcelas") },
                isError = uiState.fieldErrors.containsKey("totalInstallments"),
                modifier = Modifier.fillMaxWidth()
            )
        }

        uiState.submitError?.let { Text(it) }

        Button(onClick = viewModel::submit, enabled = !uiState.isSubmitting) {
            Text(if (uiState.isSubmitting) "Salvando..." else "Salvar")
        }
    }
}
