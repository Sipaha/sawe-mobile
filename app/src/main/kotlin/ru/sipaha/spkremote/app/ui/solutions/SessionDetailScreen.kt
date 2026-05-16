package ru.sipaha.spkremote.app.ui.solutions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.sipaha.spkremote.app.vm.MainViewModel
import ru.sipaha.spkremote.app.vm.UiData
import ru.sipaha.spkremote.core.DisplayState
import ru.sipaha.spkremote.core.EntryRole
import ru.sipaha.spkremote.core.EntrySummary
import ru.sipaha.spkremote.core.GetSessionResult
import ru.sipaha.spkremote.core.parseDisplayState
import ru.sipaha.spkremote.core.parseEntryRole

/**
 * Chat surface for one solution-agent session (R-5d).
 *
 * **Server-side limitation**: the wire-side `EntrySummary` is just
 * `{ role, preview }` — preview is markdown truncated to ~200 chars.
 * No image content, no tool args/results, no full markdown. Future
 * server-side enrichment can drop in additional fields without breaking
 * existing clients (kotlinx.serialization uses `ignoreUnknownKeys`).
 *
 * **Streaming pattern**: `agent_session_message_appended` is id-only,
 * so the ViewModel re-polls `get_session` on every relevant frame and
 * the chat list diffs by index. Same trick R-5c used for sessions.
 *
 * **Optimistic user bubble**: outgoing text is appended locally before
 * the server roundtrips, then deduped against the server-echoed user
 * entries by exact `(role, preview)` match. Imperfect for very long
 * messages (truncation may differ) — accepted as ship constraint.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    viewModel: MainViewModel,
    sessionId: String,
    onBack: () -> Unit,
) {
    val sessionState by viewModel.session.collectAsState()
    val optimistic by viewModel.optimisticEntries.collectAsState()
    val cancelInFlight by viewModel.cancelInFlight.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    DisposableEffect(sessionId) {
        viewModel.openSession(sessionId)
        onDispose { viewModel.closeSession() }
    }

    LaunchedEffect(Unit) {
        viewModel.sendError.collect { msg -> snackbarHostState.showSnackbar(msg) }
    }

    val displayTitle: String = (sessionState as? UiData.Loaded)?.value?.title?.ifBlank { "Session" }
        ?: "Session"
    val displayState: DisplayState = (sessionState as? UiData.Loaded)?.value
        ?.let { parseDisplayState(it.state) } ?: DisplayState.Unknown
    val rawState: String = (sessionState as? UiData.Loaded)?.value?.state ?: ""

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = displayTitle, style = MaterialTheme.typography.titleMedium)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (sessionState is UiData.Loaded) {
                        StatePill(state = displayState, raw = rawState)
                        Spacer(Modifier.padding(end = 8.dp))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            ComposeBar(
                enabled = sessionState is UiData.Loaded,
                state = displayState,
                cancelInFlight = cancelInFlight,
                onSend = viewModel::sendMessage,
                onCancel = viewModel::cancelTurn,
                rawState = rawState,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val s = sessionState) {
                is UiData.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                is UiData.Error -> EmptyChatMessage(
                    title = "Couldn't load session",
                    body = s.message,
                )

                is UiData.Loaded -> ChatList(
                    server = s.value,
                    optimistic = optimistic,
                )
            }
        }
    }
}

@Composable
private fun ChatList(
    server: GetSessionResult,
    optimistic: List<EntrySummary>,
) {
    val combined: List<EntrySummary> = server.entries + optimistic
    val lazyState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll behaviour: with reverseLayout = true, item 0 is the
    // newest entry visually pinned to the bottom. So "user is at bottom"
    // = first visible item index 0 AND scroll offset 0.
    val atBottom by remember {
        derivedStateOf {
            lazyState.firstVisibleItemIndex == 0 && lazyState.firstVisibleItemScrollOffset == 0
        }
    }
    // When the entries grow and the user is already pinned to the bottom,
    // animate to keep them there. `combined.size` as the key — both
    // server-side growth and a new optimistic bubble bump it.
    LaunchedEffect(combined.size) {
        if (combined.isNotEmpty() && atBottom) {
            lazyState.animateScrollToItem(0)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (combined.isEmpty()) {
            EmptyChatMessage(
                title = "No messages yet",
                body = "Send a message to start the conversation.",
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = lazyState,
                reverseLayout = true,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // reverseLayout flips list order — pass the reversed list
                // so newest-at-the-bottom maps to item 0. itemsIndexed
                // keeps the stable original index for any future need
                // (e.g. jump-to-message), even though we don't expose it.
                itemsIndexed(combined.asReversed()) { _, entry ->
                    ChatBubble(entry = entry)
                }
            }
        }

        // "Jump to bottom" pill: only when the user has scrolled away
        // from the newest entry. Reset-button-style FAB feels right for
        // a chat UI but a small Surface pill works in a pinch.
        AnimatedVisibility(
            visible = !atBottom && combined.isNotEmpty(),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            FloatingActionButton(
                onClick = { scope.launch { lazyState.animateScrollToItem(0) } },
                modifier = Modifier.heightIn(min = 40.dp),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Jump to bottom")
            }
        }
    }
}

@Composable
private fun ChatBubble(entry: EntrySummary) {
    val role = parseEntryRole(entry.role)
    when (role) {
        EntryRole.User -> UserBubble(text = entry.preview)
        EntryRole.Assistant -> AssistantBubble(text = entry.preview)
        EntryRole.ToolCall -> CenteredAnnotatedBubble(
            text = entry.preview,
            icon = Icons.Filled.Build,
            bg = MaterialTheme.colorScheme.tertiaryContainer,
            fg = MaterialTheme.colorScheme.onTertiaryContainer,
            label = "tool",
        )
        EntryRole.Plan -> CenteredAnnotatedBubble(
            text = entry.preview,
            icon = Icons.AutoMirrored.Filled.List,
            bg = MaterialTheme.colorScheme.secondaryContainer,
            fg = MaterialTheme.colorScheme.onSecondaryContainer,
            label = "plan",
        )
        EntryRole.Unknown -> CenteredAnnotatedBubble(
            text = entry.preview,
            icon = Icons.Filled.Build,
            bg = MaterialTheme.colorScheme.surfaceVariant,
            fg = MaterialTheme.colorScheme.onSurfaceVariant,
            label = entry.role,
        )
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun AssistantBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun CenteredAnnotatedBubble(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    bg: Color,
    fg: Color,
    label: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = bg,
            contentColor = fg,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.heightIn(min = 16.dp, max = 16.dp),
                )
                Column {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
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
            raw.take(20).ifBlank { "?" },
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
private fun ComposeBar(
    enabled: Boolean,
    state: DisplayState,
    cancelInFlight: Boolean,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    rawState: String,
) {
    // Persist draft across config changes — emptied on successful send.
    var draft by rememberSaveable { mutableStateOf("") }
    val isRunning = state == DisplayState.Running
    val sendEnabled = enabled && !isRunning && draft.isNotBlank()
    val showCancel = isRunning

    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            // Tool-approval banner when the agent is blocked on user input
            // that has to happen on the desktop. The compose row stays
            // enabled — queuing a follow-up message is harmless.
            if (state == DisplayState.AwaitingInput) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Tool requires approval — open SPK Editor on your computer.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            if (state == DisplayState.Errored) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        // Trim the leading "Errored" prefix from the raw
                        // Rust Debug string; if there's no payload, show
                        // a clean fallback so this banner doesn't go blank.
                        text = "Session errored: ${rawState.removePrefix("Errored").ifBlank { "see logs" }}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Send a message") },
                    // `weight(1f)` here is the RowScope extension — it
                    // grows the text field to fill the remaining width
                    // alongside the trailing send/cancel icon button.
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                        .heightIn(max = 200.dp),
                    enabled = enabled,
                    maxLines = 6,
                    colors = TextFieldDefaults.colors(),
                )
                // Right-hand action — Cancel when running, Send otherwise.
                if (showCancel) {
                    FilledIconButton(
                        onClick = onCancel,
                        enabled = !cancelInFlight,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        if (cancelInFlight) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(4.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        } else {
                            Icon(Icons.Filled.Clear, contentDescription = "Cancel turn")
                        }
                    }
                } else {
                    FilledIconButton(
                        onClick = {
                            val toSend = draft.trim()
                            if (toSend.isNotEmpty()) {
                                onSend(toSend)
                                draft = ""
                            }
                        },
                        enabled = sendEnabled,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyChatMessage(title: String, body: String) {
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
