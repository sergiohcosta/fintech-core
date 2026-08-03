package com.fintech.mobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fintech.mobile.ui.login.LoginScreen
import com.fintech.mobile.ui.newtransaction.NewTransactionScreen
import com.fintech.mobile.ui.transactionlist.TransactionListScreen

object Routes {
    const val LOGIN = "login"
    const val TRANSACTION_LIST = "transaction_list"
    const val NEW_TRANSACTION = "new_transaction"
}

@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    val sessionViewModel: SessionViewModel = hiltViewModel()
    val isLoggedIn by sessionViewModel.isLoggedIn.collectAsState()

    // 401 em qualquer chamada limpa a sessão (AuthInterceptor); esse efeito reage
    // e devolve o usuário para o Login mesmo que o NavHost já tenha sido composto.
    LaunchedEffect(isLoggedIn) {
        if (!isLoggedIn && navController.currentDestination?.route != Routes.LOGIN) {
            navController.navigate(Routes.LOGIN) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    // Snapshot ÚNICO no valor síncrono inicial do StateFlow (já reflete
    // sessionManager.currentToken() != null). `startDestination` é chave de
    // `remember` interno do NavHost do Compose Navigation: se ficasse reativo
    // (lendo `isLoggedIn` direto), toda mudança de login/logout recriaria um
    // NOVO NavGraph do zero, resetando a back stack — competindo com a
    // navegação explícita já feita via `navigate()` abaixo. Congelando aqui,
    // login/logout passam a depender só desses `navigate()` explícitos.
    val startDestination = remember { if (isLoggedIn) Routes.TRANSACTION_LIST else Routes.LOGIN }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Routes.LOGIN) {
            LoginScreen(onLoginSuccess = {
                navController.navigate(Routes.TRANSACTION_LIST) {
                    popUpTo(Routes.LOGIN) { inclusive = true }
                }
            })
        }
        composable(Routes.TRANSACTION_LIST) {
            TransactionListScreen(onAddTransaction = { navController.navigate(Routes.NEW_TRANSACTION) })
        }
        composable(Routes.NEW_TRANSACTION) {
            NewTransactionScreen(onSaved = { navController.popBackStack() })
        }
    }
}
