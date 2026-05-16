package ru.sipaha.spkremote.app.ui.solutions

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.sipaha.spkremote.app.vm.MainViewModel
import ru.sipaha.spkremote.app.vm.UiData
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
    onBack: () -> Unit,
) {
    val solutionsState by viewModel.solutions.collectAsState()
    val sessionsState by viewModel.sessions.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val solution: SolutionSummary? = (solutionsState as? UiData.Loaded)
        ?.value
        ?.firstOrNull { it.id == solutionId }

    DisposableEffect(solutionId) {
        viewModel.clearSessions()
        viewModel.refreshSessions(solutionId)
        viewModel.startObservingSessions(solutionId)
        onDispose { viewModel.stopObservingSessions() }
    }

    LaunchedEffect(sessionsState) {
        val current = sessionsState
        if (current is UiData.Error) {
            snackbarHostState.showSnackbar(current.message)
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
                onClick = {
                    scope.launch {
                        snackbarHostState.showSnackbar("New session: coming in R-5d")
                    }
                },
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
                    Text(
                        text = "$memberLabel at ${solution.root}",
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
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(s.value, key = { it.id }) { session ->
                                SessionRow(
                                    session = session,
                                    onClick = { onOpenSession(session) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: SessionSummary, onClick: () -> Unit) {
    val display = parseDisplayState(session.state)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
}

@Composable
private fun StatePill(state: DisplayState, raw: String) {
    val (label, fg, bg) = when (state) {
        DisplayState.Idle -> Triple(
            "Idle",
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.surfaceVariant,
        )
        DisplayState.Running -> Triple(
            "Running",
            MaterialTheme.colorScheme.onPrimary,
            MaterialTheme.colorScheme.primary,
        )
        DisplayState.AwaitingInput -> Triple(
            "Awaiting input",
            MaterialTheme.colorScheme.onTertiaryContainer,
            MaterialTheme.colorScheme.tertiaryContainer,
        )
        DisplayState.Errored -> Triple(
            "Errored",
            MaterialTheme.colorScheme.onErrorContainer,
            MaterialTheme.colorScheme.errorContainer,
        )
        DisplayState.Unknown -> Triple(
            raw.take(20),
            MaterialTheme.colorScheme.onSurface,
            MaterialTheme.colorScheme.surface,
        )
    }
    Surface(
        color = bg,
        contentColor = fg,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 0.dp,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
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

