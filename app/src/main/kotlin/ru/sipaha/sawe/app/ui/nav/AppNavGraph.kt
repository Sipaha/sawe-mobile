package ru.sipaha.sawe.app.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.sipaha.sawe.app.ui.qr.QrPairingScreen
import ru.sipaha.sawe.app.ui.servers.ServersListScreen
import ru.sipaha.sawe.app.ui.settings.CrashLogsScreen
import ru.sipaha.sawe.app.ui.settings.SettingsScreen
import ru.sipaha.sawe.app.ui.solutions.SessionDetailScreen
import ru.sipaha.sawe.app.ui.solutions.SolutionProjectsScreen
import ru.sipaha.sawe.app.ui.workspace.WorkspaceScreen
import ru.sipaha.sawe.app.vm.MainViewModel
import ru.sipaha.sawe.app.vm.UiState

/**
 * Routes:
 *   pairing                                              — QR + manual URL entry (R-5a/b).
 *   connecting                                           — handshake spinner.
 *   servers                                              — paired-server picker (R-6c-multi, ≥2 servers).
 *   workspace                                            — unified open-solutions list (F1).
 *   workspace/sessions/{sessionId}                       — chat surface (R-5d).
 *   workspace/solutions/{solutionId}/projects            — solution projects list.
 *
 * The nav graph is the source of truth for navigation. Pairing success is
 * detected via [MainViewModel.state]: when it flips to [UiState.Connected]
 * we push `workspace` onto the back stack (popping any prior pairing entry).
 * When it flips back to Disconnected we pop everything except `pairing`.
 *
 * **R-6c-multi:** [initialRoute] picks the cold-start landing destination
 * based on the paired-server count:
 *   - `null` (recreated activity) → start at `pairing` and rely on the
 *     surviving VM state to push us forward.
 *   - `"pairing"` (first launch / no servers paired) → identical to the
 *     R-6b unpaired path.
 *   - `"workspace"` (single server) → R-6b auto-resume preserved.
 *   - `"servers"` (multi-server) → user picks which one to connect to.
 *
 * R-6a adds a persistent banner above the NavHost surfaced from
 * [MainViewModel.connectionBanner]. It's hidden in Connected/Disconnected
 * and surfaces a one-line "reconnecting…" or "re-pair required" state.
 */
@Composable
fun AppNav(viewModel: MainViewModel, initialRoute: String? = null) {
    val navController = rememberNavController()
    val uiState by viewModel.state.collectAsState()

    val startDestination = initialRoute ?: "pairing"

    // R-6d: persist the resolved nav route on every back-stack change so
    // a cold-start can land the user back on the deepest screen they
    // were on. The route string includes substituted arguments (e.g.
    // `workspace/sessions/xyz`) — we rebuild that from the entry's
    // NavArguments because Compose-Navigation only exposes the route
    // *template* on `currentDestination`. See [resolvedRoute] below.
    //
    // `saveCurrentRoute` hops onto IO inside the ViewModel so the
    // SharedPreferences write doesn't stutter recomposition.
    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { entry ->
            val resolved = resolvedRoute(entry) ?: return@collect
            viewModel.saveCurrentRoute(resolved)
        }
    }

    // One-shot deep-link restore after a successful pairing replay. The
    // route is restored at most once per VM instance — the `restored`
    // flag prevents a `popBackStack()` later in the session from re-
    // triggering navigation back to the saved deep route.
    var restored by remember { mutableStateOf(false) }

    LaunchedEffect(uiState) {
        when (uiState) {
            is UiState.Connected -> {
                val current = navController.currentDestination?.route
                if (current == null || current == "pairing" || current == "connecting") {
                    val savedRoute = if (!restored) viewModel.loadSavedRoute() else null
                    val target = savedRoute?.takeIf { it.startsWith("workspace") } ?: "workspace"
                    restored = true
                    navController.navigate(target) {
                        // Pop to the earliest entry in the back stack —
                        // `pairing` for cold-start landed on pairing,
                        // `servers` for the multi-server landing. Either
                        // way the connecting/spinner entries get
                        // discarded so back-press doesn't bounce there.
                        popUpTo(startDestination) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            }
            is UiState.Connecting -> {
                val current = navController.currentDestination?.route
                // Don't push `connecting` over `servers` — the per-row
                // pill on the Servers screen surfaces the connecting
                // state inline. Pushing the spinner would hide the
                // useful picker the user just landed on.
                if (current != "connecting" && current != "workspace" && current != "servers") {
                    navController.navigate("connecting") {
                        popUpTo(startDestination) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            }
            is UiState.Disconnected -> {
                // Pop to either `servers` (if multi-server) or `pairing`.
                val target = if (viewModel.pairedServers.value.size >= 2) "servers" else "pairing"
                navController.popBackStack(route = target, inclusive = false)
            }
            is UiState.IncompatibleServer -> {
                // Intentionally no nav side-effect — the full-screen gate
                // below overrides the NavHost while this state holds.
                // We don't want to push or pop the back-stack because
                // there's no in-app remediation: the user must update
                // the app outside it, and reopening should land them
                // back here cleanly.
            }
        }
    }

    val gate = uiState
    if (gate is UiState.IncompatibleServer) {
        IncompatibleServerScreen(state = gate)
        return
    }

    // Connection-status feedback now lives INSIDE each screen, just under
    // its header (see [ConnectionStatusBanner] in `ui/common`). The previous
    // global strip above the NavHost shifted the whole page down on every
    // transient drop and flickered on each retry. Screens that need it
    // (`workspace`, `workspace/sessions/{id}`) render it themselves; the
    // pairing / connecting / servers / settings screens don't surface it
    // because they either ARE the disconnected UX (pairing) or have their
    // own per-row status (servers list).
    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = startDestination) {
            composable("pairing") {
                val s = uiState
                val initialUrl = (s as? UiState.Disconnected)?.lastUrl.orEmpty()
                val error = (s as? UiState.Disconnected)?.error
                QrPairingScreen(
                    initialUrl = initialUrl,
                    error = error,
                    onPair = { rawUrl -> viewModel.addServer(rawUrl) },
                )
            }
            composable("connecting") {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
            composable("servers") {
                ServersListScreen(
                    viewModel = viewModel,
                    onOpenServer = {
                        navController.navigate("workspace") {
                            popUpTo("servers") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onAddNew = {
                        navController.navigate("pairing") {
                            // Don't pop `servers` — the user is adding
                            // *another* server, not replacing.
                            launchSingleTop = true
                        }
                    },
                    onOpenSettings = { navController.navigate("settings") },
                )
            }
            composable("workspace") {
                WorkspaceScreen(
                    viewModel = viewModel,
                    onOpenSession = { sessionId ->
                        navController.navigate("workspace/sessions/$sessionId")
                    },
                    onOpenProjects = { solutionId ->
                        navController.navigate("workspace/solutions/$solutionId/projects")
                    },
                    onOpenSettings = { navController.navigate("settings") },
                )
            }
            composable("settings") {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    // Forget Server: clear THIS server (R-6c-multi) then
                    // route to either `servers` (others survive) or
                    // `pairing` (last server gone).
                    onForget = {
                        viewModel.forgetPairing()
                        val remainingCount = viewModel.pairedServers.value.size
                        val target = if (remainingCount >= 1) "servers" else "pairing"
                        navController.navigate(target) {
                            popUpTo(target) { inclusive = remainingCount == 0 }
                            launchSingleTop = true
                        }
                    },
                    onSwitchServer = {
                        navController.navigate("servers") {
                            popUpTo("workspace") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onOpenCrashLogs = { navController.navigate("crash-logs") },
                )
            }
            composable("crash-logs") {
                CrashLogsScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = "workspace/solutions/{solutionId}/projects",
                arguments = listOf(navArgument("solutionId") { type = NavType.StringType }),
            ) { entry ->
                val solutionId = entry.arguments?.getString("solutionId").orEmpty().toLongOrNull() ?: 0L
                SolutionProjectsScreen(
                    viewModel = viewModel,
                    solutionId = solutionId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "workspace/sessions/{sessionId}",
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
            ) { entry ->
                val sessionId = entry.arguments?.getString("sessionId").orEmpty()
                SessionDetailScreen(
                    viewModel = viewModel,
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() },
                    // F-phone: chip-row taps navigate sibling-wise. The
                    // unified workspace dropped the per-solution route
                    // segment, so sibling navigation just swaps the
                    // sessionId. `launchSingleTop` prevents a re-entry
                    // on the same id from stacking duplicates.
                    onOpenSibling = { siblingId ->
                        navController.navigate("workspace/sessions/$siblingId") {
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    }
}

/**
 * Build the *resolved* route string for [entry] — substituting any
 * `{argName}` placeholders in the destination route template with the
 * actual argument values from the back-stack entry.
 *
 * Compose-Navigation only exposes the template on `destination.route`,
 * not the resolved form (e.g. it gives us `solutions/{solutionId}` even
 * though the user is on `solutions/abc`). This helper closes that gap
 * so [ru.sipaha.sawe.app.data.NavStateRepository.saveRoute] can
 * persist a route that `navController.navigate(...)` will accept verbatim
 * on the next cold start.
 *
 * Returns `null` when the entry has no route (synthetic back-stack
 * entries, e.g. the root graph node).
 */
private fun resolvedRoute(entry: NavBackStackEntry): String? {
    val template = entry.destination.route ?: return null
    val args = entry.arguments ?: return template
    return Regex("""\{([^}]+)\}""").replace(template) { match ->
        val key = match.groupValues[1]
        args.getString(key) ?: match.value
    }
}

/**
 * Terminal "update the app" screen shown when the paired desktop
 * advertises a chat-wire schema this client doesn't understand
 * ([UiState.IncompatibleServer]). Rendered above the NavHost — every
 * session-detail / solutions surface is hidden while this holds so
 * the user can't drive an undecodable wire.
 *
 * Wired from [AppNav]'s top-level `if (uiState is IncompatibleServer)`
 * short-circuit; no nav callbacks because there's no in-app
 * remediation (the user updates the app via the store / sideload).
 */
@Composable
private fun IncompatibleServerScreen(state: UiState.IncompatibleServer) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Update required",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = state.message,
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "Server wire schema: ${state.serverWireSchemaVersion} • " +
                    "app supports: ${state.supportedWireSchemaVersion}",
                modifier = Modifier.padding(top = 24.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

