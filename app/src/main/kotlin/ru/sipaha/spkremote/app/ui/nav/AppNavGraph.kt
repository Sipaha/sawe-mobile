package ru.sipaha.spkremote.app.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.sipaha.spkremote.app.ui.qr.QrPairingScreen
import ru.sipaha.spkremote.app.ui.solutions.SessionDetailStubScreen
import ru.sipaha.spkremote.app.ui.solutions.SolutionDetailScreen
import ru.sipaha.spkremote.app.ui.solutions.SolutionsListScreen
import ru.sipaha.spkremote.app.vm.MainViewModel
import ru.sipaha.spkremote.app.vm.UiState

/**
 * Routes:
 *   pairing                                              — QR + manual URL entry (R-5a/b).
 *   connecting                                           — handshake spinner.
 *   solutions                                            — list of solutions on the paired editor.
 *   solutions/{solutionId}                               — sessions in one solution.
 *   solutions/{solutionId}/sessions/{sessionId}          — chat stub (R-5d will replace).
 *
 * The nav graph is the source of truth for navigation. Pairing success is
 * detected via [MainViewModel.state]: when it flips to [UiState.Connected]
 * we push `solutions` onto the back stack (popping any prior pairing entry).
 * When it flips back to Disconnected we pop everything except `pairing`.
 */
@Composable
fun AppNav(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val uiState by viewModel.state.collectAsState()

    LaunchedEffect(uiState) {
        when (uiState) {
            is UiState.Connected -> {
                // Only navigate if we're not already past the pairing leg.
                val current = navController.currentDestination?.route
                if (current == null || current == "pairing" || current == "connecting") {
                    navController.navigate("solutions") {
                        popUpTo("pairing") { inclusive = false }
                        launchSingleTop = true
                    }
                }
            }
            is UiState.Connecting -> {
                val current = navController.currentDestination?.route
                if (current != "connecting" && current != "solutions") {
                    navController.navigate("connecting") {
                        popUpTo("pairing") { inclusive = false }
                        launchSingleTop = true
                    }
                }
            }
            is UiState.Disconnected -> {
                navController.popBackStack(route = "pairing", inclusive = false)
            }
        }
    }

    NavHost(navController = navController, startDestination = "pairing") {
        composable("pairing") {
            val s = uiState
            val initialUrl = (s as? UiState.Disconnected)?.lastUrl.orEmpty()
            val error = (s as? UiState.Disconnected)?.error
            QrPairingScreen(
                initialUrl = initialUrl,
                error = error,
                onPair = viewModel::connect,
            )
        }
        composable("connecting") {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        }
        composable("solutions") {
            SolutionsListScreen(
                viewModel = viewModel,
                onOpenSolution = { sol ->
                    navController.navigate("solutions/${sol.id}")
                },
            )
        }
        composable(
            route = "solutions/{solutionId}",
            arguments = listOf(navArgument("solutionId") { type = NavType.StringType }),
        ) { entry ->
            val solutionId = entry.arguments?.getString("solutionId").orEmpty()
            SolutionDetailScreen(
                viewModel = viewModel,
                solutionId = solutionId,
                onOpenSession = { session ->
                    navController.navigate("solutions/$solutionId/sessions/${session.id}")
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "solutions/{solutionId}/sessions/{sessionId}",
            arguments = listOf(
                navArgument("solutionId") { type = NavType.StringType },
                navArgument("sessionId") { type = NavType.StringType },
            ),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId").orEmpty()
            SessionDetailStubScreen(
                sessionId = sessionId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
