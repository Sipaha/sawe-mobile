package ru.sipaha.spkremote.app.ui.solutions

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.sipaha.spkremote.app.vm.MainViewModel
import ru.sipaha.spkremote.app.vm.UiData
import ru.sipaha.spkremote.core.ConnectionState
import ru.sipaha.spkremote.core.DisplayState
import ru.sipaha.spkremote.core.SessionSummary
import ru.sipaha.spkremote.core.SolutionSummary
import ru.sipaha.spkremote.core.parseDisplayState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolutionDetailScreen(
    viewModel: MainViewModel,
    solutionId: String,
    onOpenSession: (SessionSummary) -> Unit,
    onOpenSessionById: (sessionId: String) -> Unit,
    onBack: () -> Unit,
) {
    val solutionsState by viewModel.solutions.collectAsState()
    val sessionsState by viewModel.sessions.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showNewSessionDialog by rememberSaveable { mutableStateOf(false) }

    val solution: SolutionSummary? = (solutionsState as? UiData.Loaded)
        ?.value
        ?.firstOrNull { it.id == solutionId }

    DisposableEffect(solutionId) {
        viewModel.clearSessions()
        viewModel.refreshSessions(solutionId)
        viewModel.startObservingSessions(solutionId)
        onDispose { viewModel.stopObservingSessions() }
    }

    // Re-fetch when the connection actually lands. The DisposableEffect
    // above runs at mount time, but on cold start the async connect
    // inside switchToServer hasn't finished yet, so that first call
    // hits the offline-cache fallback and returns without a wire fetch.
    // Without this observer the sessions list would stay frozen at the
    // cached (or Loading) state until the user manually pulled.
    val connectionState by viewModel.rawConnectionState.collectAsState()
    // Track the Disconnected→Connected edge explicitly. Without this,
    // every re-emission of `Connected` (config-change recomposition,
    // rotation, etc.) re-triggers a wire refetch — wasted RPC bandwidth
    // and a brief Loading flicker. MUST be rememberSaveable: a plain
    // `remember` resets to false when the activity is recreated on
    // rotation, so the edge re-fires on every rotation — the exact
    // wasted-refetch + flicker this flag exists to prevent.
    val wasConnected = rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(connectionState) {
        val nowConnected = connectionState is ConnectionState.Connected
        if (nowConnected && !wasConnected.value) {
            viewModel.refreshSessions(solutionId)
        }
        wasConnected.value = nowConnected
    }

    LaunchedEffect(sessionsState) {
        val current = sessionsState
        if (current is UiData.Error) {
            snackbarHostState.showSnackbar(current.message)
        }
    }

    // Surface create_session failures (also fed sendMessage / cancelTurn —
    // the existing chat surface owns the channel, but on this screen the
    // dialog needs the same visibility for its own RPCs).
    LaunchedEffect(Unit) {
        viewModel.sendError.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(solution?.name ?: solutionId) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewSessionDialog = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New session") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (solution != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val memberLabel = "${solution.memberCount} " +
                        if (solution.memberCount == 1) "member" else "members"
                    // Drop the `at <solution.root>` server path — meaningless
                    // on the phone; the member count is the useful summary.
                    Text(
                        text = memberLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when (val s = sessionsState) {
                    is UiData.Loading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    is UiData.Error -> EmptyMessage(
                        title = "Couldn't load sessions",
                        body = s.message,
                    )

                    is UiData.Loaded -> if (s.value.isEmpty()) {
                        EmptyMessage(
                            title = "No sessions yet",
                            body = "Start a Claude session for this solution from SPK Editor.",
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            // Clear the gesture nav bar + the FAB so the
                            // last session row stays tappable.
                            contentPadding = PaddingValues(
                                bottom = 88.dp +
                                    WindowInsets.navigationBars.asPaddingValues()
                                        .calculateBottomPadding(),
                            ),
                        ) {
                            items(s.value, key = { it.id }) { session ->
                                SessionRow(
                                    session = session,
                                    onClick = { onOpenSession(session) },
                                    onDelete = { viewModel.closeSession(session.id) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }

        if (showNewSessionDialog) {
            NewSessionDialog(
                viewModel = viewModel,
                solutionId = solutionId,
                onDismiss = { showNewSessionDialog = false },
                onCreated = { sessionId ->
                    showNewSessionDialog = false
                    onOpenSessionById(sessionId)
                },
            )
        }
    }
}

@Composable
private fun SessionRow(
    session: SessionSummary,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val display = parseDisplayState(session.state)
    // Per-row confirmation toggle. Local-state — when the row is removed
    // from the list (post-delete refresh) the state is discarded along
    // with it, so there's no need to reset on success.
    var confirmDelete by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = session.title.ifBlank { "(untitled session)" },
                style = MaterialTheme.typography.titleMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatePill(state = display, raw = session.state)
                Text(
                    text = relativeTime(session.lastActivityAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        IconButton(onClick = { confirmDelete = true }) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete session",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    // Confirm via dialog rather than an inline button swap — the inline
    // "Delete? Yes/Cancel" reflowed the row + its divider. Dialog keeps
    // the row geometry stable and matches the server-removal pattern.
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete session?") },
            text = { Text("Close \"${session.title.ifBlank { "this session" }}\" and discard its conversation.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EmptyMessage(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * Format `last_activity_at` (epoch millis) into a relative string like
 * "5m ago". `DateUtils` returns "0 minutes ago" for very recent events,
 * which is fine for v0 — we can tighten later if it bothers users.
 */
private fun relativeTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    val now = System.currentTimeMillis()
    return DateUtils.getRelativeTimeSpanString(
        epochMillis,
        now,
        DateUtils.SECOND_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}

