package com.fintech.mobile.ui.login

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fintech.mobile.core.network.Environment
import com.fintech.mobile.core.network.NetworkRoute

private fun environmentLabel(environment: Environment): String = when (environment) {
    Environment.LOCAL -> "Local"
    Environment.DEV -> "Dev"
    Environment.HMG -> "Hmg"
    Environment.PROD -> "Prod"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnvironmentSelector(viewModel: EnvironmentViewModel = hiltViewModel()) {
    val environment by viewModel.environment.collectAsState()
    val route by viewModel.route.collectAsState()
    val customLocalUrl by viewModel.customLocalUrl.collectAsState()

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        var environmentMenuExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = environmentMenuExpanded,
            onExpandedChange = { environmentMenuExpanded = it }
        ) {
            OutlinedTextField(
                value = environmentLabel(environment),
                onValueChange = {},
                readOnly = true,
                label = { Text("Ambiente") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = environmentMenuExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(
                expanded = environmentMenuExpanded,
                onDismissRequest = { environmentMenuExpanded = false }
            ) {
                Environment.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(environmentLabel(option)) },
                        onClick = {
                            viewModel.onEnvironmentChange(option)
                            environmentMenuExpanded = false
                        }
                    )
                }
            }
        }

        if (environment == Environment.LOCAL) {
            OutlinedTextField(
                value = customLocalUrl ?: "",
                onValueChange = viewModel::onCustomLocalUrlChange,
                label = { Text("URL local (opcional — padrão: emulador)") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        } else {
            Row(modifier = Modifier.padding(top = 8.dp)) {
                FilterChip(
                    selected = route == NetworkRoute.LAN,
                    onClick = { viewModel.onRouteChange(NetworkRoute.LAN) },
                    label = { Text("LAN") }
                )
                FilterChip(
                    selected = route == NetworkRoute.TAILSCALE,
                    onClick = { viewModel.onRouteChange(NetworkRoute.TAILSCALE) },
                    label = { Text("Tailscale") },
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}
