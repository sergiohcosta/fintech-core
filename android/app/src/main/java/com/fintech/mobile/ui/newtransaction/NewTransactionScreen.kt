package com.fintech.mobile.ui.newtransaction

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
    onSaved: () -> Unit,
    viewModel: NewTransactionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.banner) {
        if (uiState.banner != null) onSaved()
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
                modifier = Modifier.fillMaxWidth()
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
