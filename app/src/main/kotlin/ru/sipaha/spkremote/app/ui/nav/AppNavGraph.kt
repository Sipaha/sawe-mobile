package ru.sipaha.spkremote.app.ui.nav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.sipaha.spkremote.app.ui.qr.QrPairingScreen
import ru.sipaha.spkremote.app.ui.servers.ServersListScreen
import ru.sipaha.spkremote.app.ui.settings.CrashLogsScreen
import ru.sipaha.spkremote.app.ui.settings.SettingsScreen
import ru.sipaha.spkremote.app.ui.solutions.SessionDetailScreen
import ru.sipaha.spkremote.app.ui.solutions.SolutionProjectsScreen
import ru.sipaha.spkremote.app.ui.workspace.WorkspaceScreen
import ru.sipaha.spkremote.app.vm.ConnectionBanner
import ru.sipaha.spkremote.app.vm.MainViewModel
import ru.sipaha.spkremote.app.vm.UiState

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
    val banner by viewModel.connectionBanner.collectAsState()

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

    Column(modifier = Modifier.fillMaxSize()) {
        ConnectionStateBanner(
            banner = banner,
            onRePair = {
                navController.navigate("pairing") { launchSingleTop = true }
            },
        )
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
                    // G1 deleted the legacy SolutionsListScreen /
                    // SolutionDetailScreen that owned the "new solution"
                    // and "new session" dialogs. The workspace-owned
                    // versions are F-phase / beyond — until then these
                    // callbacks are no-ops so the build stays green;
                    // the FAB still surfaces the "Open closed solution…"
                    // picker, which is the only creation-adjacent flow
                    // wired end-to-end on the workspace today.
                    onCreateNewSolution = {},
                    onCreateNewSessionFor = {},
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
                val solutionId = entry.arguments?.getString("solutionId").orEmpty()
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
 * so [ru.sipaha.spkremote.app.data.NavStateRepository.saveRoute] can
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
 * Surfaced strip above the nav stack when the underlying socket is in
 * trouble. We deliberately do NOT block input on the screen below — the
 * user can still navigate, read existing transcripts, and even queue new
 * messages (which [RemoteClient.queueCall] will flush on reconnect).
 *
 * Color choice:
 *   - [ConnectionBanner.Reconnecting] → `tertiaryContainer` (yellow-ish,
 *     "transient, will heal itself").
 *   - [ConnectionBanner.FailedTerminal] → `errorContainer` (red, "user
 *     action required — re-pair").
 */
@Composable
private fun ConnectionStateBanner(banner: ConnectionBanner, onRePair: () -> Unit) {
    when (banner) {
        ConnectionBanner.Hidden -> return
        is ConnectionBanner.Reconnecting -> {
            // Live countdown. The previous version computed `next try in
            // Xs` ONCE per emission, so it sat frozen at e.g. "30s" for the
            // whole backoff and read as a hung connection. Capture a
            // deadline when this attempt began and tick it down so the
            // number actually moves.
            val deadline = remember(banner.attempt, banner.nextRetryMs) {
                System.currentTimeMillis() + banner.nextRetryMs
            }
            var remainingMs by remember(deadline) {
                mutableStateOf((deadline - System.currentTimeMillis()).coerceAtLeast(0L))
            }
            LaunchedEffect(deadline) {
                while (remainingMs > 0) {
                    delay(500)
                    remainingMs = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
                }
            }
            val seconds = remainingMs / 1000
            val tail = if (seconds > 0) "next try in ${seconds}s…" else "retrying…"
            val reason = banner.reason
            val text = if (reason != null) {
                "$reason · Reconnecting (attempt ${banner.attempt}, $tail)"
            } else {
                "Reconnecting (attempt ${banner.attempt}, $tail)"
            }
            BannerSurface(MaterialTheme.colorScheme.tertiaryContainer, text, onClick = null)
        }
        is ConnectionBanner.FailedTerminal -> {
            // Terminal failure was previously a dead-end static line. Make
            // it tappable so the user has a one-tap recovery into pairing
            // instead of being stuck.
            BannerSurface(
                background = MaterialTheme.colorScheme.errorContainer,
                text = "${banner.reason} · Tap to re-pair.",
                onClick = onRePair,
            )
        }
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

@Composable
private fun BannerSurface(background: Color, text: String, onClick: (() -> Unit)?) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = background,
    ) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                // The banner is the top-most element (above the NavHost), so
                // it must clear the system status bar itself — otherwise the
                // text draws under the clock / battery icons. Horizontal too
                // for the landscape camera cutout.
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
