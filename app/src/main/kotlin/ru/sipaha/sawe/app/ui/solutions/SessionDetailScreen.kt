package ru.sipaha.sawe.app.ui.solutions

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.createBitmap
import java.util.Locale
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.sipaha.sawe.app.vm.DeferredUpload
import ru.sipaha.sawe.app.vm.MainViewModel
import ru.sipaha.sawe.app.vm.OpenSessionVM
import ru.sipaha.sawe.app.vm.WorkspaceUiState
import ru.sipaha.sawe.app.vm.PendingUploadProgress
import ru.sipaha.sawe.app.vm.PickedAttachment
import ru.sipaha.sawe.app.vm.UiData
import ru.sipaha.sawe.app.vm.UploadManager
import ru.sipaha.sawe.core.ConnectionState
import ru.sipaha.sawe.core.ContentBlockDto
import ru.sipaha.sawe.core.ContextFill
import ru.sipaha.sawe.core.DisplayState
import ru.sipaha.sawe.app.ui.common.ConnectionStatusBanner
import ru.sipaha.sawe.core.EntryImage
import ru.sipaha.sawe.core.EntryRoleDto
import ru.sipaha.sawe.core.SystemLevelDto
import ru.sipaha.sawe.core.EntrySummary
import ru.sipaha.sawe.core.GetSessionResult
import kotlinx.coroutines.flow.StateFlow
import ru.sipaha.sawe.core.PlanSummary
import ru.sipaha.sawe.core.SessionStateDto
import ru.sipaha.sawe.core.SessionSummary
import ru.sipaha.sawe.core.StreamDto
import ru.sipaha.sawe.core.StreamIdDto
import ru.sipaha.sawe.core.SupervisorStateDto
import ru.sipaha.sawe.core.ToolCallStatusDto
import ru.sipaha.sawe.core.ToolCallSummary
import ru.sipaha.sawe.core.displayState
import ru.sipaha.sawe.core.erroredMessage
import ru.sipaha.sawe.core.startedAtMs
import ru.sipaha.sawe.core.stripInjectedMeta
import ru.sipaha.sawe.core.stripRoleHeading

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
    onOpenSibling: (sessionId: String) -> Unit = {},
) {
    val sessionState by viewModel.session.collectAsState()
    val optimistic by viewModel.optimisticEntries.collectAsState()
    val pendingUploads by viewModel.pendingUploadProgress.collectAsState()
    val serverQueuedBundles by viewModel.serverQueuedBundles.collectAsState()
    val streams by viewModel.streams.collectAsState()
    val selectedStream by viewModel.selectedStream.collectAsState()
    val cancelInFlight by viewModel.cancelInFlight.collectAsState()
    val isLoadingOlder by viewModel.isLoadingOlder.collectAsState()
    val childrenMap by viewModel.sessionChildren.collectAsState()
    val sessionsList by viewModel.sessions.collectAsState()
    val workspaceState by viewModel.workspaceState.collectAsState()
    val connectionState by viewModel.rawConnectionState.collectAsState()
    val lastConnectedMs by viewModel.lastConnectedMs.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val supervisorState by viewModel.supervisorState.collectAsState()

    DisposableEffect(sessionId) {
        viewModel.openSession(sessionId)
        onDispose { viewModel.closeSession() }
    }

    // Connection-aware error gate (Feature A). A send error that fires while
    // we're already disconnected is most likely a transient blip the
    // queue-replay / reconnect will heal within a second or two — surfacing it
    // immediately would flash a scary snackbar that contradicts the connection
    // banner. So: if Connected at the time of the error → genuine failure,
    // show it now. Otherwise wait up to a ~4s grace window for the connection
    // to come back; if it recovers, drop the error (the banner already informs
    // the user); if it doesn't, the outage is real and we surface it.
    LaunchedEffect(Unit) {
        viewModel.sendError.collect { msg ->
            if (viewModel.rawConnectionState.value is ConnectionState.Connected) {
                snackbarHostState.showSnackbar(msg)
            } else {
                // Handle each error concurrently so the collector isn't blocked
                // for the grace window (and overlapping errors each get one).
                scope.launch {
                    val recovered = withTimeoutOrNull(4_000L) {
                        viewModel.rawConnectionState.first { it is ConnectionState.Connected }
                        true
                    }
                    if (recovered != true) {
                        snackbarHostState.showSnackbar(msg)
                    }
                }
            }
        }
    }

    // Reset context produces a new session id server-side; hop the open
    // chat surface onto it so DisposableEffect's restart doesn't reopen
    // the closed source session on next composition.
    LaunchedEffect(Unit) {
        viewModel.resetSwitch.collect { newSessionId -> onOpenSibling(newSessionId) }
    }

    // R-6d: bounce-to-input recovery. If a previous queueCall expired
    // (TTL hit / process kill while offline / terminal RPC failure), the
    // ViewModel stashed the text on DraftRepository's bounced channel.
    // Reading it here clears the slot, so the snackbar appears exactly
    // once per expiry event.
    //
    // The bounce text takes precedence over the regular draft because
    // it's the more recent intent — if the user was typing AND a
    // background queued send expired, the typed text is in the draft
    // slot and the failed-send text is in the bounce slot. Surfacing
    // the bounce gets the lost content back into view; once the user
    // edits/sends/cancels it, the typed draft can be recovered by hand
    // from anywhere they pasted it. (Alternative: append the bounce
    // to the typed draft separated by `\n\n`. Kept simpler for v1.)
    //
    // We use a `LaunchedEffect`-driven async load (via
    // `MainViewModel.loadDraftSeed`) instead of a `remember { ... }`
    // synchronous read because the SharedPreferences-backed read happens
    // off the composition / main thread. The empty initial state during
    // the brief load window keeps the field functional (user can start
    // typing immediately — once the seed lands, only the empty initial
    // value is replaced).
    var seedText by remember(sessionId) { mutableStateOf("") }
    var seedLoaded by remember(sessionId) { mutableStateOf(false) }
    LaunchedEffect(sessionId) {
        val (text, bounce) = viewModel.loadDraftSeed(sessionId)
        seedText = text
        seedLoaded = true
        if (bounce) {
            snackbarHostState.showSnackbar(
                "Couldn't send earlier — added back to your message for retry.",
            )
        }
    }

    val loadedSession: GetSessionResult? = (sessionState as? UiData.Loaded)?.value
    val sessionsCache: List<SessionSummary> = (sessionsList as? UiData.Loaded)?.value.orEmpty()
    // Cross-reference the open session to pull its `total_tokens` /
    // `max_tokens` — `GetSessionResult` doesn't carry those today.
    //
    // The live source is the workspace mirror: each open session's
    // `OpenSessionVM` carries the tokens and is kept current by
    // `workspace.session_metrics_changed`. We read it FIRST because the
    // unified-workspace navigation goes straight from the mirror to this
    // screen WITHOUT a per-solution `refreshSessions`, so the list-side
    // `SessionListStore.sessions` cache (`sessionsCache`) is usually empty
    // here — relying on it alone is what silently blanked the
    // ContextFillMeter after the unified-workspace refactor. Fall back to
    // the list cache for any session the mirror doesn't hold.
    val mirrorSession: OpenSessionVM? = (workspaceState as? WorkspaceUiState.Loaded)
        ?.snapshot
        ?.solutions
        ?.firstNotNullOfOrNull { sol -> sol.sessions.firstOrNull { it.id == sessionId } }
    val activeSummary: SessionSummary? = sessionsCache.firstOrNull { it.id == sessionId }
    // Title fallback chain: prefer the always-current session-list sources
    // (mirror > list summary) over the loaded GetSessionResult, because
    // the cache-first open path synthesises a blank title until the first
    // delta or full `get_session` lands. A blank loaded title must NOT
    // shadow the real title that the list already knows.
    val displayTitle: String = mirrorSession?.title?.ifBlank { null }
        ?: activeSummary?.title?.ifBlank { null }
        ?: loadedSession?.title?.ifBlank { null }
        ?: "Session"
    val sessionStateDto: SessionStateDto? = (sessionState as? UiData.Loaded)?.value?.state
    val displayState: DisplayState = sessionStateDto?.displayState() ?: DisplayState.Unknown

    // F-phone chip row inputs.
    //
    // `parentId` is read from the loaded session (`GetSessionResult` now
    // carries `parent_session_id`). The parent's *title* — the label we
    // want to render on the chip — isn't in that payload, so we cross-
    // reference `_sessions` (the list-of-sessions cache for the active
    // solution). When the user deep-linked into a child whose parent
    // isn't in that cache, fall back to a short hash of the id.
    val parentId: String? = loadedSession?.parentSessionId
    val parentTitle: String? = parentId?.let { id ->
        sessionsCache.firstOrNull { it.id == id }?.title?.ifBlank { null }
            ?: id.take(8)
    }
    // Immediate children of the current session, sorted by created_at ASC
    // so the oldest sub-agent appears first (matches the spec). Empty list
    // when the server hasn't reported any (or the child fetch is in flight).
    val children: List<SessionSummary> = childrenMap[sessionId]
        ?.sortedBy { it.createdAt }
        .orEmpty()
    val showChipRow = parentId != null || children.isNotEmpty()

    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showResetConfirm by rememberSaveable { mutableStateOf(false) }
    var showCompactConfirm by rememberSaveable { mutableStateOf(false) }
    var showSupervisorSheet by rememberSaveable { mutableStateOf(false) }
    val activeTotalTokens: Long? = mirrorSession?.totalTokens ?: activeSummary?.totalTokens
    val activeMaxTokens: Long? = mirrorSession?.maxTokens ?: activeSummary?.maxTokens
    // `state_started_at_ms` now lives inside the structured
    // [SessionStateDto.Running] variant (post-DTO migration). The list-side
    // cache is still authoritative for the chat header anchor — the
    // active session's `SessionSummary` is freshest re: state transitions.
    val activeStateStartedAtMs: Long? =
        mirrorSession?.state?.startedAtMs() ?: activeSummary?.state?.startedAtMs()
    // "Last activity" anchor for the status bar: the newest chat entry's
    // wall-clock timestamp, filtered to a real value (> 0). This mirrors
    // `combined.lastOrNull()?.createdMs` from [ChatList] — optimistic and
    // synthetic queue rows carry no real `createdMs`, so the chronologically
    // newest *real-time* entry is the last server entry. Null → the status
    // bar shows nothing extra (looks exactly as before).
    val lastActivityMs: Long? = loadedSession?.entries?.lastOrNull()?.createdMs?.takeIf { it > 0 }
    Scaffold(
        // Android 15+ (targetSdk 35+) forces edge-to-edge; Scaffold's
        // default contentWindowInsets = systemBars would then double-
        // consume against the topBar / bottomBar's own inset handling.
        // Zero it here and apply insets explicitly on each slot so the
        // chat surface reaches edge-to-edge without overlap.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SlimTopBar(
                title = displayTitle,
                onBack = onBack,
                onTitleClick = { if (sessionState is UiData.Loaded) showRenameDialog = true },
                actions = {
                    if (sessionState is UiData.Loaded) {
                        // Overflow menu — Reset / Compact context. The
                        // anchor's `Box` wrapping is what lets DropdownMenu
                        // compute its caret position; placing the menu
                        // inside the IconButton's lambda would re-anchor
                        // on every recomposition and flicker.
                        Box {
                            IconButton(
                                onClick = { showOverflowMenu = true },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.MoreVert,
                                    contentDescription = "Session actions",
                                )
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Reset context") },
                                    onClick = {
                                        showOverflowMenu = false
                                        showResetConfirm = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Compact context") },
                                    onClick = {
                                        showOverflowMenu = false
                                        showCompactConfirm = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Supervisor") },
                                    onClick = {
                                        showOverflowMenu = false
                                        viewModel.loadSupervisorState(sessionId)
                                        showSupervisorSheet = true
                                    },
                                )
                            }
                        }
                    }
                },
                // Status indicators (context meter, state pill, elapsed,
                // last-activity) used to live here as a second header row.
                // They now sit in a thin strip at the BOTTOM, just above the
                // compose row — mirroring the desktop status row and freeing
                // the top bar for the title alone.
                statusRow = null,
            )
        },
        // No `snackbarHost` slot — Scaffold renders that slot pinned to
        // the BOTTOM, where it sat over the chat tail and got clipped by
        // the compose row / status panel. We render the host as a
        // top-aligned overlay inside the content Box instead (below the
        // top bar), so errors read clearly above the conversation.
        bottomBar = {
            // Bottom + horizontal safe insets: lifts the compose row above
            // the IME and the system nav bar (bottom, or a side bar in
            // landscape) AND clears the landscape camera cutout. safeDrawing
            // already unions ime / navigationBars / displayCutout, so a
            // single `.only(Bottom + Horizontal)` covers all of them without
            // the double-count an `ime + navigationBars` sum would risk.
            Column(
                modifier = Modifier
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                        ),
                    ),
            ) {
                // Thin status strip, mirroring the desktop status row: context
                // meter + state pill + elapsed + last-activity, sitting just
                // above the compose row instead of in the top bar. Only when a
                // session is loaded (matches the old header-row gate).
                if (sessionState is UiData.Loaded) {
                    androidx.compose.material3.HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ContextFillMeter(
                            totalTokens = activeTotalTokens,
                            maxTokens = activeMaxTokens,
                        )
                        // `raw` only matters for [DisplayState.Unknown]; the
                        // structured DTO carries no payload there, so an empty
                        // string is fine — the pill renders "?".
                        StatePill(state = displayState, raw = "")
                        RunningElapsed(
                            displayState = displayState,
                            stateStartedAtMs = activeStateStartedAtMs,
                        )
                        LastActivityLabel(lastActivityMs = lastActivityMs)
                    }
                }
                if (showChipRow) {
                    SubAgentChipRow(
                        parentId = parentId,
                        parentTitle = parentTitle,
                        children = children,
                        onChipTap = onOpenSibling,
                    )
                }
                ComposeBar(
                    enabled = sessionState is UiData.Loaded,
                    state = displayState,
                    cancelInFlight = cancelInFlight,
                    onSend = { text, blocks ->
                        // Mixed sends (text + attachment handles) route
                        // through sendMessageBlocks; pure-text stays on
                        // sendMessage so the optimistic-bubble dedupe
                        // continues to fire by content equality where
                        // the legacy text-only payload has no _meta
                        // seam to carry a client_send_id.
                        if (blocks.isEmpty()) {
                            viewModel.sendMessage(text)
                        } else {
                            val finalBlocks = if (text.isNotBlank()) {
                                listOf<ContentBlockDto>(ContentBlockDto.Text(text)) + blocks
                            } else {
                                blocks
                            }
                            viewModel.sendMessageBlocks(finalBlocks)
                        }
                        // On send the regular draft is invalidated. We
                        // optimistically clear here so a quick re-open
                        // doesn't repopulate the field even before the
                        // server echo lands. Bounce-on-failure is
                        // handled separately by handleExpiredMessage.
                        viewModel.clearDraft(sessionId)
                    },
                    onSendDeferred = { text, uploads ->
                        // User pressed Send while ≥1 upload is still in
                        // flight. The store places an optimistic bubble
                        // with an "Uploading X/Y" badge and dispatches
                        // the real send once every upload reaches Done.
                        val textBlock = if (text.isNotBlank()) ContentBlockDto.Text(text) else null
                        viewModel.sendMessageBlocksDeferred(textBlock, uploads)
                        viewModel.clearDraft(sessionId)
                    },
                    onAttachmentError = { reason ->
                        scope.launch { snackbarHostState.showSnackbar(reason) }
                    },
                    onCancel = viewModel::cancelTurn,
                    sessionStateDto = sessionStateDto,
                    sessionId = sessionId,
                    initialDraft = seedText,
                    seedLoaded = seedLoaded,
                    onDraftChanged = { text -> viewModel.saveDraft(sessionId, text) },
                    onDraftFlush = { text -> viewModel.flushDraft(sessionId, text) },
                    initialAttachments = { viewModel.pickedAttachments(sessionId) },
                    onAttachmentsChanged = { viewModel.setPickedAttachments(sessionId, it) },
                    onStartUpload = { uri, sid, mime, name, size ->
                        viewModel.startAttachmentUpload(uri, sid, mime, name, size)
                    },
                    onCancelUpload = viewModel::cancelAttachmentUpload,
                    onForgetUpload = viewModel::forgetAttachmentUpload,
                    awaitUploadTerminal = viewModel::awaitAttachmentUploadTerminal,
                    hasOptimisticSends = optimistic.isNotEmpty() ||
                        serverQueuedBundles.isNotEmpty(),
                    onForceFlush = viewModel::forceFlushQueue,
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Horizontal safe insets: in landscape the camera cutout
                // (left) and the side 3-button nav bar would otherwise sit
                // over the chat content — messages slid under the cutout.
                // The Scaffold zeroes contentWindowInsets and handles
                // vertical insets per-slot, so we only need horizontal here.
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                ),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Connection-status strip (Feature B): shown only when NOT
                // Connected, so a healthy chat looks exactly as before.
                ConnectionStatusBanner(
                    state = connectionState,
                    lastConnectedMs = lastConnectedMs,
                )
                // Per-source stream tabs (wire schema v3) — one pill per
                // stream (Main first). Hidden when only Main exists, so a
                // plain conversation looks exactly as before.
                SubagentTabStrip(
                    streams = streams,
                    selected = selectedStream,
                    onSelect = viewModel::selectStream,
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                            pendingUploads = pendingUploads,
                            serverQueuedBundles = serverQueuedBundles,
                            selectedStream = selectedStream,
                            sessionDisplayState = displayState,
                            isLoadingOlder = isLoadingOlder,
                            onRequestOlder = { viewModel.loadOlder(sessionId) },
                            onAuthorizeToolCall = viewModel::authorizeToolCall,
                        )
                    }
                }
            }

            // Top-aligned error host. Sits just under the top bar (the
            // content Box is already inset by `padding`), so a send /
            // attachment error reads above the conversation instead of
            // being clipped at the bottom behind the compose row.
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(8.dp),
            )
        }
    }

    if (showRenameDialog) {
        RenameSessionDialog(
            initialTitle = displayTitle,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newTitle ->
                viewModel.renameSession(sessionId, newTitle)
                showRenameDialog = false
            },
        )
    }

    if (showResetConfirm) {
        ConfirmActionDialog(
            title = "Reset context",
            body = "Reset will start a fresh session and discard the conversation. Continue?",
            confirmLabel = "Reset",
            onDismiss = { showResetConfirm = false },
            onConfirm = {
                showResetConfirm = false
                viewModel.resetContextOnActiveSession()
            },
        )
    }

    if (showCompactConfirm) {
        ConfirmActionDialog(
            title = "Compact context",
            body = "Compact will summarise the current context into a fresh session. Continue?",
            confirmLabel = "Compact",
            onDismiss = { showCompactConfirm = false },
            onConfirm = {
                showCompactConfirm = false
                viewModel.compactContextOnActiveSession()
            },
        )
    }

    if (showSupervisorSheet) {
        SupervisorSettingsSheet(
            state = supervisorState,
            onToggleEnabled = { enabled -> viewModel.setSupervisorEnabled(sessionId, enabled) },
            onSavePrompt = { prompt -> viewModel.setSupervisorPrompt(sessionId, prompt) },
            onDismiss = { showSupervisorSheet = false },
        )
    }

}

@Composable
private fun ConfirmActionDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RenameSessionDialog(
    initialTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initialTitle) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename session") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Session name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onConfirm(text) },
                enabled = text.trim().isNotEmpty() && text.trim() != initialTitle,
            ) { Text("Rename") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ChatList(
    server: GetSessionResult,
    optimistic: List<EntrySummary>,
    pendingUploads: Map<Long, PendingUploadProgress>,
    serverQueuedBundles: List<ru.sipaha.sawe.core.QueuedBundleSummary>,
    selectedStream: StreamIdDto,
    sessionDisplayState: DisplayState,
    isLoadingOlder: Boolean,
    onRequestOlder: () -> Unit,
    onAuthorizeToolCall: (toolCallId: String, optionId: String) -> Unit = { _, _ -> },
) {
    // Per-source-streams cutover: the server already scoped `server.entries`
    // to the selected stream (stream-local indices), so we render them
    // directly — no client-side subagent filter. Optimistic bubbles and
    // queued bundles are Main-thread only (sends target Main), so hide them
    // on any other stream — otherwise an in-flight local send would flash a
    // wrong-thread bubble on a teammate/shell tab.
    @Suppress("NAME_SHADOWING")
    val optimistic = if (selectedStream == StreamIdDto.Main) optimistic else emptyList()
    @Suppress("NAME_SHADOWING")
    val serverQueuedBundles = remember(serverQueuedBundles, selectedStream) {
        if (selectedStream == StreamIdDto.Main) serverQueuedBundles else emptyList()
    }
    // Server-side `pending_messages` flattened into synthetic
    // EntrySummary rows so they slot into the same LazyColumn pass as
    // server entries. The server is the source of truth for queued
    // state — it merges sends from every client into shared bundles and
    // rewrites a bundle's preview in place on a desktop edit — so we
    // render ALL bundles here. `SessionDetailStore.onSessionQueueChanged`
    // drops any local optimistic whose csid landed in a bundle, so there
    // is exactly one bubble per bundle (no local-vs-synthetic dupes).
    //
    // `clientSendId = csids.first` keys the bubble in the LazyColumn so a
    // re-broadcast of the same bundle (e.g. a desktop edit changing only
    // the preview) UPDATES the existing bubble instead of remounting it.
    val syntheticQueueEntries: List<EntrySummary> = remember(serverQueuedBundles) {
        serverQueuedBundles.map { bundle ->
            // The bundle ships `image_count` but no image bytes, and the
            // `[image #N]` placeholders in `preview` get stripped by the
            // user-bubble renderer (it re-adds clickable links from
            // `entry.images`, which a queued bundle doesn't carry). Append
            // a plain attachment note so the Queued bubble still shows that
            // images are attached — they're not downloaded to the phone
            // until the bundle flushes, so a non-clickable count is honest.
            val attachmentNote = if (bundle.imageCount > 0) {
                val noun = if (bundle.imageCount == 1) "image" else "images"
                "\n\n📎 ${bundle.imageCount} $noun"
            } else {
                ""
            }
            EntrySummary(
                role = EntryRoleDto.User,
                preview = bundle.preview + attachmentNote,
                clientSendId = bundle.csids.firstOrNull(),
            )
        }
    }
    val serverQueueIdentitySet = remember(syntheticQueueEntries) {
        java.util.IdentityHashMap<EntrySummary, Unit>().also { map ->
            for (e in syntheticQueueEntries) map[e] = Unit
        }
    }
    // Set of every csid the server has already echoed — either as a fully
    // appended server entry or as a still-queued bundle. Used to hide an
    // optimistic bubble the instant its echo surfaces, so the
    // optimistic→echo swap is driven by a SINGLE observable change (the echo
    // appearing) instead of two independent StateFlow emits (optimistic pop
    // + server append). That removes the one-frame gap that caused the
    // send-flicker, and guarantees an optimistic entry and its echo never
    // coexist in `combined` (which would collide on the `csid:N` LazyColumn
    // key and crash).
    val echoedCsids: Set<Long> = remember(server.entries, serverQueuedBundles) {
        buildSet {
            for (e in server.entries) {
                e.clientSendId?.let { add(it) }
                addAll(e.clientSendIds)
            }
            for (bundle in serverQueuedBundles) {
                addAll(bundle.csids)
            }
        }
    }
    val visibleOptimisticEntries = remember(optimistic, echoedCsids) {
        ru.sipaha.sawe.core.visibleOptimistic(optimistic, echoedCsids)
    }
    // Defense-in-depth: collapse any same-`index` server slots before they
    // reach the LazyColumn key derivation. The store's merge paths are meant
    // to keep indices unique, but the position-vs-index dual addressing there
    // has crashed this list with "Key idx:N already used" more than once; this
    // guard turns a residual duplicate into a dropped row instead of an app
    // kill. Should essentially never fire now that the placeholder carries its
    // server index — the warn is the tripwire that says it did.
    val renderServerEntries: List<EntrySummary> = remember(server.entries) {
        val deduped = ru.sipaha.sawe.core.dedupeEntriesByIndex(server.entries)
        if (deduped.size != server.entries.size) {
            android.util.Log.w(
                "SessionDetailScreen",
                "dropped ${server.entries.size - deduped.size} duplicate-index chat " +
                    "entr${if (server.entries.size - deduped.size == 1) "y" else "ies"} " +
                    "before render to avoid a LazyColumn key crash (root cause should be " +
                    "fixed upstream in SessionEntryMerge/SessionDetailStore)",
            )
        }
        deduped
    }
    // Queued follow-ups (`syntheticQueueEntries`) are deliberately NOT part of
    // the scrolling conversation: they render in the non-scrolling footer below
    // the list (mirrors the desktop pending-section). Keeping them in the
    // LazyColumn made the agent's streaming appends shove the queued bubble
    // around — it flickered in and out and "flew" on every tool/message event.
    val combined: List<EntrySummary> = renderServerEntries + visibleOptimisticEntries
    // Identity of every optimistic entry is referential — they are the
    // same objects the store published — so we can use `===` to flip
    // the per-bubble status icon without touching server entries.
    val optimisticIdentitySet = remember(optimistic) {
        java.util.IdentityHashMap<EntrySummary, Unit>().also { map ->
            for (e in optimistic) map[e] = Unit
        }
    }
    val lazyState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Identity of the newest entry (= `combined.last()`, the last list item).
    // Keying the auto-scroll on this — not just on `combined.size` — catches
    // the optimistic→server-echo swap, where a pending bubble is popped AND
    // its confirmed server entry is appended in the same frame: size is
    // unchanged, but the newest slot's identity flips (`csid:N` → `idx:M`).
    val newestEntryKey: String? = combined.lastOrNull()?.let { entry ->
        entry.clientSendId?.let { "csid:$it" }
            ?: if (entry.index >= 0) "idx:${entry.index}"
            else "role:${entry.role}#${entry.preview.hashCode()}"
    }
    // Length of the newest entry's body. Content-only streaming updates keep
    // the same key, so the auto-scroll keys on this too — a growing reply
    // changes its length, which (while pinned) re-pins to the new bottom. When
    // the user has scrolled up to read, the follow effect is gated off, so the
    // growth (which happens BELOW the viewport in natural layout) never moves
    // their read position.
    val newestContentLen: Int = combined.lastOrNull()
        ?.let { (it.markdown ?: it.preview).length } ?: 0

    // Follow-the-newest flag. true = pinned to the bottom, so auto-scroll keeps
    // the newest content in view as it streams; false = the user dragged up to
    // read and must be left alone. A LATCHED flag (not a live position sample)
    // so an in-flight programmatic scroll mid-stream doesn't read as "user
    // scrolled away".
    val stickyBottom = remember { mutableStateOf(true) }
    LaunchedEffect(lazyState) {
        // Unstick the instant the user starts dragging — they've taken control.
        launch {
            lazyState.interactionSource.interactions.collect { i ->
                if (i is androidx.compose.foundation.interaction.DragInteraction.Start) {
                    stickyBottom.value = false
                }
            }
        }
        // Re-arm only when the viewport SETTLES at the bottom (scroll finished,
        // nothing more below) — the user scrolled back down or tapped jump-to-
        // bottom. Gating on `!isScrollInProgress` avoids a drag-start race.
        launch {
            androidx.compose.runtime.snapshotFlow {
                !lazyState.isScrollInProgress && !lazyState.canScrollForward
            }.collect { settledAtBottom ->
                if (settledAtBottom) stickyBottom.value = true
            }
        }
    }
    // Keep the newest content in view while following. Fires on new entries
    // (`combined.size`), a new newest entry (`newestEntryKey`), the thinking
    // row, and — for a streaming reply — the newest body GROWING
    // (`newestContentLen`). In natural top-anchored layout the growth is at the
    // BOTTOM of the last item, so when the user has scrolled up to read
    // (stickyBottom == false) NOTHING scrolls — the read position is stable
    // with no drift and no per-frame compensation. Only the follower pinned at
    // the bottom is moved. This replaces the reverseLayout + drift-compensation
    // design, which jittered the read position on every token.
    LaunchedEffect(combined.size, newestEntryKey, newestContentLen) {
        if (combined.isEmpty() || !stickyBottom.value) return@LaunchedEffect
        val info = lazyState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        if (last != null && last.index == info.totalItemsCount - 1) {
            // Newest item already on screen — smoothly consume just the
            // below-fold overshoot. For a growing streaming reply this nudges
            // down by the new tokens' height with no jump-to-top flicker.
            val overshoot = last.offset + last.size - info.viewportEndOffset
            if (overshoot > 0) lazyState.scrollBy(overshoot.toFloat())
        } else {
            // Newest item off-screen (a new entry landed below the fold). Reach
            // it with a DOWN-ONLY scroll: a large `scrollBy` clamps to the
            // bottom. Deliberately NOT `scrollToItem(MAX)` — that top-anchors
            // the last item, so a streaming reply taller than the viewport
            // briefly shows its TOP (a visible flick UP) before settling back
            // to the floor. `scrollBy` can only move down, so the pin is
            // flicker-free. The user-driven jump-to-bottom FAB keeps the
            // animated `scrollToChatBottom` for its one-shot ride.
            lazyState.scrollBy(1_000_000f)
        }
    }

    // R-6e: are there older entries we haven't loaded yet?
    //   - The oldest currently-loaded entry has index N (or -1 sentinel
    //     from a pre-R-6e server, which means "we got the whole thing
    //     in one shot and pagination doesn't apply").
    //   - If N > 0, there are N older entries on the server we can fetch.
    //   - If N <= 0, we've reached the start of history.
    val oldestLoadedIndex: Int = server.entries.firstOrNull()?.index ?: -1
    val hasOlder: Boolean = oldestLoadedIndex > 0

    // Auto-trigger when the user scrolls towards the top of loaded history.
    // Natural top-anchored layout: the history-edge row is item 0 and the
    // OLDEST entries follow it, so "near top of history" = firstVisibleItemIndex
    // within a small window of 0. Prepending older entries keeps the visible
    // item anchored by its stable key, so the viewport doesn't jump.
    // `distinctUntilChanged()` keeps the collector quiet between identical
    // values (recompositions that didn't shift the viewport).
    LaunchedEffect(lazyState, hasOlder) {
        if (!hasOlder) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { lazyState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { firstVisible ->
                // Trigger one screen ahead of the literal top so the fetch has
                // a head start and loading feels near-instant.
                if (firstVisible <= 5 && !isLoadingOlder) {
                    onRequestOlder()
                }
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
      Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        if (combined.isEmpty()) {
            EmptyChatMessage(
                title = "No messages yet",
                body = "Send a message to start the conversation.",
            )
        } else {
            // C3: weave date-separator rows into the timeline. The pure
            // helper produces a CHRONOLOGICAL list (separator BEFORE its
            // day's messages) — exactly the order a natural top-anchored
            // LazyColumn wants: oldest at the top, newest (`combined.last()`)
            // as the last item flush at the bottom, each day's separator
            // ABOVE that day's messages.
            val timeline = remember(combined) {
                ru.sipaha.sawe.core.withDateSeparators(
                    combined,
                    java.time.ZoneId.systemDefault(),
                )
            }
            val today = remember(combined) { java.time.LocalDate.now() }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = lazyState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // R-6e: history-edge affordance at the TOP (oldest end) of the
                // list — item 0. Two states:
                //   - hasOlder && isLoadingOlder: spinner row.
                //   - hasOlder && !isLoadingOlder: "Load older" tappable. The
                //     scroll trigger usually fires this automatically, but the
                //     explicit tap recovers if the trigger missed (e.g. a fling
                //     that stopped just shy of the trigger window).
                item("history-edge") {
                    HistoryEdgeRow(
                        isLoadingOlder = isLoadingOlder,
                        hasOlder = hasOlder,
                        onTap = onRequestOlder,
                    )
                }
                // itemsIndexed keeps the stable original index for any future
                // need (e.g. jump-to-message), even though we don't expose it.
                //
                // STABLE KEYS: without `key = ...` LazyColumn falls back
                // on positional identity, which means every
                // `agent_session_message_appended` notification (and
                // there can be ~5/s now that EntryUpdated is throttled
                // through to the wire) rebinds and re-composes every
                // bubble on screen — visible as a scroll jump and a
                // brief "square" placeholder before the real content
                // re-renders. Identity preference:
                //   1. `clientSendId` for user entries — preserved
                //      across the optimistic-bubble → server-echo
                //      handoff so the row keeps its slot identity.
                //   2. `index` for any server-known entry (post-R-6e).
                //   3. A role+preview hash as last-resort fallback for
                //      optimistic entries without csid (shouldn't
                //      happen — see [[csid-required-on-every-optimistic-entry]]
                //      — but defensive).
                itemsIndexed(
                    items = timeline,
                    key = { position, item ->
                        when (item) {
                            is ru.sipaha.sawe.core.ChatItem.DateSeparator ->
                                "date:${item.epochDay}"
                            is ru.sipaha.sawe.core.ChatItem.Message -> {
                                val entry = item.entry
                                when {
                                    // Synthetic server-queue bubble: namespace its key
                                    // so it can't collide with the REAL flushed user
                                    // entry that carries the same csid during the
                                    // queue-drain → message-appended handoff (a bare
                                    // `csid:N` collision would crash the LazyColumn).
                                    entry in serverQueueIdentitySet ->
                                        "queued:${entry.clientSendId ?: "pos$position"}"
                                    entry.clientSendId != null -> "csid:${entry.clientSendId}"
                                    entry.index >= 0 -> "idx:${entry.index}"
                                    // Un-indexed entries (index == -1, e.g. optimistic
                                    // bubbles or legacy entries that carry no server
                                    // index). Two such tool-call
                                    // placeholders with the same truncated preview used
                                    // to collide on `role#previewHash` and crash the
                                    // LazyColumn ("Key … already used"). The itemsIndexed
                                    // position is unique within the list, so key on it.
                                    else -> "pos$position:${entry.role}"
                                }
                            }
                        }
                    },
                ) { _, item ->
                    when (item) {
                        is ru.sipaha.sawe.core.ChatItem.DateSeparator ->
                            DateSeparatorRow(label = formatDateSeparator(item.epochDay, today))
                        is ru.sipaha.sawe.core.ChatItem.Message -> {
                            val entry = item.entry
                            val status = userBubbleStatusFor(
                                entry = entry,
                                isOptimistic = entry in optimisticIdentitySet,
                                isServerQueued = entry in serverQueueIdentitySet,
                                pendingUploads = pendingUploads,
                                sessionDisplayState = sessionDisplayState,
                            )
                            ChatBubble(
                                entry = entry,
                                userStatus = status,
                                onAuthorizeToolCall = onAuthorizeToolCall,
                            )
                        }
                    }
                }
                // The "Thinking…" sentinel and queued follow-ups used to live
                // here as trailing list items; they now render in the
                // non-scrolling footer below so streaming appends can't shove
                // them around (see the footer after this Box).
            }
        }

        // "Jump to bottom" pill: shown while the user is NOT following the
        // newest entry (they scrolled up to read). Keying on the follow flag
        // rather than a live `canScrollForward` sample avoids a flicker each
        // streaming token, where new content briefly sits below the fold
        // before the follow effect consumes it.
        JumpToBottomFab(
            visible = !stickyBottom.value && combined.isNotEmpty(),
            onClick = { scope.launch { lazyState.scrollToChatBottom(animate = true) } },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
      }
      // Non-scrolling footer (mirrors the desktop pending-section + status-row
      // split): the live "Thinking…" indicator and any queued follow-ups sit
      // OUT of the LazyColumn, so the agent's streaming tool/message appends can
      // never push them around — which is what made the queued bubble flicker
      // and "fly" while the user did nothing. Capped + self-scrolling so a long
      // queue can't eat the whole screen.
      if (syntheticQueueEntries.isNotEmpty()) {
          androidx.compose.material3.HorizontalDivider(
              color = MaterialTheme.colorScheme.outlineVariant,
          )
          Column(
              modifier = Modifier
                  .fillMaxWidth()
                  .heightIn(max = 240.dp)
                  .verticalScroll(rememberScrollState())
                  .padding(horizontal = 12.dp, vertical = 6.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
              for (entry in syntheticQueueEntries) {
                  val status = userBubbleStatusFor(
                      entry = entry,
                      isOptimistic = false,
                      isServerQueued = true,
                      pendingUploads = pendingUploads,
                      sessionDisplayState = sessionDisplayState,
                  )
                  ChatBubble(
                      entry = entry,
                      userStatus = status,
                      onAuthorizeToolCall = onAuthorizeToolCall,
                  )
              }
          }
      }
    }
}

/**
 * "Jump to latest" pill, extracted to its own composable so the
 * [AnimatedVisibility] call resolves unambiguously to the top-level overload.
 * Inline in `ChatList` the surrounding `Column` brought `ColumnScope`'s
 * `AnimatedVisibility` into scope alongside the top-level one (there is no
 * `BoxScope` variant), making the call ambiguous. The caller supplies a
 * `BoxScope`-aligned [modifier] so the pill still anchors bottom-end.
 */
@Composable
private fun JumpToBottomFab(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        FloatingActionButton(
            onClick = onClick,
            modifier = Modifier.heightIn(min = 40.dp),
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Jump to bottom")
        }
    }
}

/**
 * Scroll the chat to the very bottom (newest content), revealing the END of
 * the last item even when it is taller than the viewport — a long streaming
 * reply whose latest tokens sit below the fold. Instant by default so
 * back-to-back streaming updates don't queue animations; the jump-to-bottom
 * FAB passes [animate] = true for a smooth ride.
 */
private suspend fun LazyListState.scrollToChatBottom(animate: Boolean = false) {
    if (animate) {
        val count = layoutInfo.totalItemsCount
        if (count == 0) return
        animateScrollToItem(count - 1)
    } else {
        // Int.MAX_VALUE is coerced to the last item at measure time, so this
        // also lands a freshly-opened chat at the newest entry before the
        // first layout has reported a count.
        scrollToItem(Int.MAX_VALUE)
    }
    // `scrollToItem` lands the item's TOP at the viewport top; if the last item
    // is taller than the viewport its bottom is still below the fold. Nudge by
    // the overshoot so the newest tokens are actually visible.
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return
    if (last.index != info.totalItemsCount - 1) return
    val overshoot = last.offset + last.size - info.viewportEndOffset
    if (overshoot > 0) {
        if (animate) animateScrollBy(overshoot.toFloat()) else scrollBy(overshoot.toFloat())
    }
}

/**
 * Per-user-bubble status badge.
 *
 *   - [None]: historic message (not sent during this session) — no badge.
 *   - [Uploading]: send queued but waiting for attachment uploads; the
 *     badge shows progress as `done/total`. Set by the pending-send
 *     coroutine in [SessionDetailStore.sendMessageBlocksDeferred].
 *   - [Sending]: optimistic bubble fired and the queueCall is in flight
 *     (or queued offline). Server hasn't echoed yet.
 *   - [Delivered]: server echoed the user entry back. Derived directly
 *     from `entry.clientSendId != null && !isOptimistic` — every recent
 *     client stamps a csid on outbound user messages, so the presence
 *     of the field on a server entry IS proof of delivery. No
 *     client-side set to maintain, no rehydrate path; persists naturally
 *     across app restarts because the csid lives on the persisted
 *     server entry itself.
 */
internal sealed class UserBubbleStatus {
    object None : UserBubbleStatus()
    data class Uploading(
        val sentBytes: Long,
        val totalBytes: Long,
        val paused: Boolean,
    ) : UserBubbleStatus()
    /**
     * Send is waiting for the agent's current turn to finish (or for
     * the user to press the force-flush button). Shown with an
     * hourglass icon to differentiate from [Sending] (which is on-the-
     * wire and waiting for the server echo).
     */
    object Queued : UserBubbleStatus()
    object Sending : UserBubbleStatus()
    object Delivered : UserBubbleStatus()
}

private fun userBubbleStatusFor(
    entry: EntrySummary,
    isOptimistic: Boolean,
    isServerQueued: Boolean,
    pendingUploads: Map<Long, PendingUploadProgress>,
    sessionDisplayState: DisplayState,
): UserBubbleStatus {
    if (entry.role != EntryRoleDto.User) return UserBubbleStatus.None
    // Server-broadcast queue bundle: ALWAYS Queued regardless of
    // local optimistic state or session display state. The server
    // is the source of truth — if the bundle is still in
    // `pending_messages`, the agent hasn't flushed it yet.
    if (isServerQueued) return UserBubbleStatus.Queued
    val csid = entry.clientSendId
    if (isOptimistic) {
        if (csid != null) {
            val pending = pendingUploads[csid]
            if (pending != null && pending.totalBytes > 0) {
                return UserBubbleStatus.Uploading(
                    sentBytes = pending.sentBytes,
                    totalBytes = pending.totalBytes,
                    paused = pending.status == PendingUploadProgress.Status.Paused,
                )
            }
        }
        // The mobile fires `send_message_blocks` immediately; the
        // server queues it server-side (into `pending_messages`) when
        // the agent is mid-turn. From the bubble's perspective that
        // looks identical to "wire send in flight, server hasn't
        // echoed", so we use the live session state to differentiate:
        // Running / AwaitingInput → the entry is sitting in the
        // server-side queue and will flush when the turn ends, render
        // as Queued (hourglass) so the user can tell from a glance
        // which presses are pending agent-busy vs awaiting wire ack.
        // `Stopping` also counts as busy: the agent is mid-cancel but the
        // server-side `pending_messages` queue is still gated, so a fresh
        // send placed during the transition has to wait for the next Idle
        // window before flushing — same Queued affordance as Running.
        val busy = sessionDisplayState == DisplayState.Running ||
            sessionDisplayState == DisplayState.AwaitingInput ||
            sessionDisplayState == DisplayState.Stopping
        return if (busy) UserBubbleStatus.Queued else UserBubbleStatus.Sending
    }
    // Server-echoed user entry — if it carries a client_send_id (every
    // recent client stamps one) the server obviously received it. The
    // check stays put across app restarts because the csid lives on the
    // persisted server entry itself; no client-side set to rehydrate.
    if (csid != null) return UserBubbleStatus.Delivered
    return UserBubbleStatus.None
}

/** Pretty-print bytes as "N.N MB" / "NN KB" / "NNN B" for the bubble badge. */
private fun formatBytes(b: Long): String = when {
    b >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", b / (1024.0 * 1024.0))
    b >= 1024L -> "${b / 1024L} KB"
    else -> "$b B"
}

@Composable
private fun ChatBubble(
    entry: EntrySummary,
    userStatus: UserBubbleStatus = UserBubbleStatus.None,
    onAuthorizeToolCall: (toolCallId: String, optionId: String) -> Unit = { _, _ -> },
) {
    val role = entry.role
    // C3: per-message HH:MM, revealed ONLY on a SHORT tap. The always-on
    // "last message time" now lives in the status bar (LastActivityLabel),
    // so no bubble shows its time unprompted. The footer line sits OUTSIDE
    // the colored Surface so the tap toggle never competes with the inner
    // SelectionContainer long-press (text selection) or the user-bubble
    // image-link taps.
    var revealed by rememberSaveable(entry.index, entry.clientSendId) { mutableStateOf(false) }
    val timeText = entry.createdMs?.takeIf { it > 0 }?.let { formatHm(it) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.fillMaxWidth().then(
                if (timeText != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { revealed = !revealed }
                } else {
                    Modifier
                },
            ),
        ) {
            when (role) {
                EntryRoleDto.User ->
                    // A supervisor nudge is delivered as a user-role entry (so
                    // the agent acts on it) but is NOT the human's message —
                    // the `observer_nudge` flag tags it. Render it as the same
                    // Observer plaque the System/Observer branch below uses, so
                    // the reader sees at a glance it's the watcher's voice.
                    if (entry.observerNudge) {
                        CenteredAnnotatedBubble(
                            text = entry.markdown ?: entry.preview,
                            icon = Icons.Filled.Visibility,
                            bg = MaterialTheme.colorScheme.tertiaryContainer,
                            fg = MaterialTheme.colorScheme.onTertiaryContainer,
                            label = "observer",
                        )
                    } else {
                        UserBubble(entry = entry, status = userStatus)
                    }
                EntryRoleDto.Assistant -> {
                    // Skip assistant turns that have neither visible body
                    // NOR thinking — the no-chunks tool-call-only case from
                    // `acp_thread::AssistantMessage::to_markdown` ("##
                    // Assistant\n\n\n\n"). Thinking-only turns now render
                    // (as the collapsible Thoughts card in AssistantBubble),
                    // so the visibility check folds both signals together.
                    if (hasVisibleAssistantContent(entry.markdown ?: entry.preview)) {
                        AssistantBubble(entry = entry)
                    }
                }
                EntryRoleDto.ToolCall -> {
                    val tc = entry.toolCall
                    if (tc != null) {
                        ToolCallBubble(
                            call = tc,
                            positionKey = entry.index,
                            onAuthorize = onAuthorizeToolCall,
                        )
                    } else {
                        CenteredAnnotatedBubble(
                            text = entry.preview,
                            icon = Icons.Filled.Build,
                            bg = MaterialTheme.colorScheme.tertiaryContainer,
                            fg = MaterialTheme.colorScheme.onTertiaryContainer,
                            label = "tool",
                        )
                    }
                }
                EntryRoleDto.Plan -> {
                    val plan = entry.plan
                    if (plan != null) {
                        PlanBubble(plan = plan)
                    } else {
                        CenteredAnnotatedBubble(
                            text = entry.preview,
                            icon = Icons.AutoMirrored.Filled.List,
                            bg = MaterialTheme.colorScheme.secondaryContainer,
                            fg = MaterialTheme.colorScheme.onSecondaryContainer,
                            label = "plan",
                        )
                    }
                }
                EntryRoleDto.System -> {
                    // Editor-originated annotation — render distinctly per
                    // severity so it's clearly NOT the agent or the user.
                    val text = entry.markdown ?: entry.preview
                    when (entry.systemLevel) {
                        SystemLevelDto.Error -> CenteredAnnotatedBubble(
                            text = text,
                            icon = Icons.Filled.Warning,
                            bg = MaterialTheme.colorScheme.errorContainer,
                            fg = MaterialTheme.colorScheme.onErrorContainer,
                            label = "system",
                        )
                        SystemLevelDto.Observer -> CenteredAnnotatedBubble(
                            text = text,
                            icon = Icons.Filled.Visibility,
                            bg = MaterialTheme.colorScheme.tertiaryContainer,
                            fg = MaterialTheme.colorScheme.onTertiaryContainer,
                            label = "observer",
                        )
                        else -> CenteredAnnotatedBubble(
                            text = text,
                            icon = Icons.Filled.Info,
                            bg = MaterialTheme.colorScheme.surfaceVariant,
                            fg = MaterialTheme.colorScheme.onSurfaceVariant,
                            label = "system",
                        )
                    }
                }
                EntryRoleDto.Unknown -> CenteredAnnotatedBubble(
                    text = entry.preview,
                    icon = Icons.Filled.Build,
                    bg = MaterialTheme.colorScheme.surfaceVariant,
                    fg = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Best-effort label for the tolerant-fallback case —
                    // the structured DTO collapses unknown wire roles into
                    // `Unknown` (no raw string carried), so render the enum
                    // name verbatim.
                    label = entry.role.name,
                )
            }
        }
        if (timeText != null && revealed) {
            Text(
                text = timeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 1.dp),
                textAlign = when (role) {
                    EntryRoleDto.User -> TextAlign.End
                    else -> TextAlign.Start
                },
            )
        }
    }
}

/** C3: localized centered date-separator chip between day boundaries. */
@Composable
private fun DateSeparatorRow(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun UserBubble(entry: EntrySummary, status: UserBubbleStatus = UserBubbleStatus.None) {
    val rawText = stripInjectedMeta(stripRoleHeading(entry.markdown ?: entry.preview))
    // Fold the auto-injected compact-context prompt (a large, agent-only
    // template) into a tappable placeholder instead of dumping it into the
    // chat — matches the desktop's folded one-line strip. Detected by its
    // stable first heading; the compact prompt is sent as a plain turn so
    // it carries no queue marker and `rawText` already begins with it.
    if (rawText.trimStart().startsWith(COMPACT_PROMPT_HEADING)) {
        CompactPromptCard(bodyText = rawText, index = entry.index)
        return
    }
    val images = entry.images.orEmpty()
    var fullscreen by remember(entry.index) { mutableStateOf<EntryImage?>(null) }
    // Decode images once per entry so the [Image #N] tap target opens
    // the same Painter the assistant-side preview would. Cheap enough
    // to do up-front (the user's own attachments are bounded by the
    // 5 MB cap and the mobile picker enforces a small count).
    val decodedImages: Map<Int, Painter> = remember(images) {
        images.associate { it.index to bitmapPainterFromBase64(it.dataBase64) }
    }
    val linkColor = MaterialTheme.colorScheme.onPrimary
    val annotated = remember(rawText, images, linkColor) {
        buildUserBubbleAnnotatedText(rawText, images.size, linkColor)
    }
    Row(
        // start = 48 dp gives the right-aligned user bubble a clear left
        // gutter so even when it grows to its widthIn max the gradient of
        // "this is the user side" remains visible at a glance.
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 48.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
            // No animateContentSize here. The user's own bubble doesn't
            // grow over time the way an assistant streaming bubble
            // does; the only width transition is the one-shot
            // optimistic→server-echo replacement, which on a fresh
            // send delivers near-identical content. Animating that
            // single sub-pixel layout shift produced a visible "small
            // → big → small" oscillation through the spring tween's
            // overshoot stages, especially when the markdown widget
            // re-measured a new EntrySummary instance on every
            // throttled `agent_session_message_appended` arrival.
            // Discrete one-frame jumps are fine here.
            modifier = Modifier.widthIn(max = 360.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                // Users overwhelmingly send plain text — but accept markdown when
                // the server returns it (e.g. a paste from a markdown source).
                // The pinned light-on-primary palette would clobber inline-code
                // colours, so for user bubbles we never run the full markdown
                // renderer. What we DO rewrite are the desktop-style
                // `[image #N]` placeholders the server emits for image
                // chunks — they become clickable spans that open the
                // image in a fullscreen Dialog, matching the desktop's
                // `clean_user_message_text` → `on_url_click` flow.
                //
                // SelectionContainer enables long-press text selection + the
                // system Copy/Share toolbar without us shipping our own context
                // menu. Scoped per-bubble so handles stay inside the bubble's
                // visual bounds; cross-bubble selection isn't a common UX.
                SelectionContainer {
                    ClickableText(
                        text = annotated,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onPrimary,
                        ),
                        onClick = { offset ->
                            val tag = annotated
                                .getStringAnnotations(IMAGE_LINK_TAG, offset, offset)
                                .firstOrNull()
                                ?: return@ClickableText
                            val idx = tag.item.toIntOrNull() ?: return@ClickableText
                            // Map nth occurrence to nth EntryImage. The
                            // server emits image content blocks in the
                            // same order as the `[image #N]` placeholders
                            // in markdown, so position-based lookup
                            // matches the desktop's `spk-image://idx`
                            // rewrite exactly.
                            val target = images.getOrNull(idx) ?: return@ClickableText
                            fullscreen = target
                        },
                    )
                }
                UserBubbleStatusRow(status = status)
            }
        }
    }
    val tapped = fullscreen
    if (tapped != null) {
        Dialog(onDismissRequest = { fullscreen = null }) {
            Surface(
                color = Color.Black,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp)
                    .clickable { fullscreen = null },
            ) {
                val painter = decodedImages[tapped.index]
                if (painter != null) {
                    androidx.compose.foundation.Image(
                        painter = painter,
                        contentDescription = "Full-screen image",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private const val IMAGE_LINK_TAG = "spk-image"
private val IMAGE_PLACEHOLDER_REGEX = Regex("""\[image #(\d+)]""", RegexOption.IGNORE_CASE)

// The server's ACP `to_markdown` reduces an image chunk to a literal
// `` `Image` `` inline-code marker (acp_thread::ContentBlock::append). The
// mobile surfaces images as its own `[image #N]` links appended at the end
// of the bubble, so the raw marker is just noise mid-body — strip it. The
// desktop does the equivalent in `clean_user_message_text`.
private val IMAGE_CODE_MARKER_REGEX = Regex("""`Image`""")

/**
 * Build the user-bubble body as an [AnnotatedString]:
 *
 *   1. Strip every inline `[image #N]` placeholder from the source
 *      text — the desktop renders them in-place, but the mobile
 *      bubble surfaces image links at the END of the message so the
 *      user-typed body stays clean and the affordances cluster
 *      together at the bottom.
 *   2. Append one clickable, underlined `[image #N]` link per
 *      attachment in `entry.images`, in source order, separated by
 *      single spaces, after a blank-line separator from the body.
 *      The annotation value is the occurrence index so the tap
 *      handler can look up `entry.images[idx]` directly.
 *
 * `N` in the rendered label is the 1-based occurrence number, not
 * the server-side session-monotonic counter the desktop uses —
 * mobile doesn't have the session counter to hand, and the simpler
 * 1..N labelling is what a fresh user expects for "two attachments
 * shown as `[image #1] [image #2]`".
 */
private fun buildUserBubbleAnnotatedText(
    text: String,
    imageCount: Int,
    linkColor: Color,
): AnnotatedString = buildAnnotatedString {
    val linkStyle = SpanStyle(
        color = linkColor,
        textDecoration = TextDecoration.Underline,
    )
    val stripped = IMAGE_CODE_MARKER_REGEX
        .replace(IMAGE_PLACEHOLDER_REGEX.replace(text, ""), "")
        .trim()
    if (stripped.isNotEmpty()) {
        append(stripped)
    }
    if (imageCount > 0) {
        if (stripped.isNotEmpty()) {
            append("\n\n")
        }
        for (idx in 0 until imageCount) {
            if (idx > 0) append(" ")
            pushStringAnnotation(tag = IMAGE_LINK_TAG, annotation = idx.toString())
            withStyle(linkStyle) { append("[image #${idx + 1}]") }
            pop()
        }
    }
}

/**
 * Tiny status row underneath the user-bubble body — shows a per-message
 * delivery / upload state badge. Omits itself entirely for
 * [UserBubbleStatus.None] (historic entries) so the bubble height
 * doesn't bounce when scrolling through old conversations.
 */
@Composable
private fun ColumnScope.UserBubbleStatusRow(status: UserBubbleStatus) {
    if (status is UserBubbleStatus.None) return
    Row(
        // Wrap-content width + align to End of the parent Column. We
        // can NOT use Modifier.fillMaxWidth() here — that would force
        // the enclosing Surface to grow to its `widthIn(max = 360.dp)`
        // ceiling whenever a status badge is present, then collapse
        // back to the body's intrinsic width the instant the badge
        // disappears (e.g. the brief window between popping the local
        // optimistic and the server entry landing via the next delta
        // poll → status flips Queued → None → Delivered and the bubble
        // visibly oscillates wide → narrow → wide).
        // With wrap-content the bubble width is determined by the body
        // alone, which is stable across the send lifecycle.
        modifier = Modifier
            .align(Alignment.End)
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (status) {
            is UserBubbleStatus.Uploading -> {
                if (status.paused) {
                    // No spinner — the loop is NOT making progress.
                    // Schedule icon (clock) signals "waiting, will retry"
                    // so the user can tell stalled-vs-progressing.
                    Icon(
                        imageVector = Icons.Filled.Schedule,
                        contentDescription = "Upload paused",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Spacer(Modifier.size(6.dp))
                val pct = if (status.totalBytes > 0L) {
                    (status.sentBytes * 100L / status.totalBytes).coerceIn(0L, 100L)
                } else 0L
                val label = if (status.paused) {
                    "Paused at ${formatBytes(status.sentBytes)} / ${formatBytes(status.totalBytes)}"
                } else {
                    "Uploading ${formatBytes(status.sentBytes)} / ${formatBytes(status.totalBytes)} ($pct%)"
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
                )
            }
            UserBubbleStatus.Queued -> {
                Icon(
                    imageVector = Icons.Filled.HourglassBottom,
                    contentDescription = "Queued — waiting for current turn",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = "Queued",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
                )
            }
            UserBubbleStatus.Sending -> {
                Icon(
                    imageVector = Icons.Filled.Schedule,
                    contentDescription = "Sending",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
                )
            }
            UserBubbleStatus.Delivered -> {
                Icon(
                    imageVector = Icons.Filled.Done,
                    contentDescription = "Delivered",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                )
            }
            UserBubbleStatus.None -> Unit
        }
    }
}

/**
 * Animate ONLY the height of content-size changes; width settles to the
 * measured value immediately, every frame.
 *
 * `Modifier.animateContentSize()` animates BOTH axes, so any bubble whose
 * WIDTH changes between frames — a tool call whose first frame is icon-only
 * then widens once its name/args populate, or a streamed line that widens
 * the bubble — visibly "compresses then expands horizontally". On a chat
 * bubble that horizontal squeeze is never wanted; we only want the smooth
 * vertical grow as content streams in. This modifier animates the height
 * dimension alone and passes width through untouched.
 *
 * First measure snaps (no animation from zero). `clipToBounds` keeps the
 * mid-animation (shorter-than-content) frame from spilling over.
 */
private fun Modifier.animateHeightOnly(
    durationMillis: Int = 150,
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val heightAnim = remember { Animatable(0, Int.VectorConverter) }
    var initialized by remember { mutableStateOf(false) }
    val spec = remember(durationMillis) {
        tween<Int>(durationMillis = durationMillis, easing = LinearEasing)
    }
    this
        .clipToBounds()
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val target = placeable.height
            val renderHeight: Int = if (!initialized) {
                // First real measure — snap, don't animate up from 0.
                scope.launch {
                    heightAnim.snapTo(target)
                    initialized = true
                }
                target
            } else {
                if (heightAnim.targetValue != target) {
                    scope.launch { heightAnim.animateTo(target, spec) }
                }
                heightAnim.value
            }
            layout(placeable.width, renderHeight) {
                placeable.place(0, 0)
            }
        }
}

@Composable
private fun AssistantBubble(entry: EntrySummary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp),
            modifier = Modifier
                .widthIn(max = 360.dp)
                .animateHeightOnly(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val md = entry.markdown
                if (md != null) {
                    // Split the rendered markdown into thinking chunks and
                    // the remaining answer body. Thinking blocks render as
                    // a collapsible "💭 Thoughts" card above the answer so
                    // extended-thinking turns become discoverable on mobile
                    // (previously stripped entirely — the user saw nothing
                    // for a thought-only turn and reasoning was hidden even
                    // on mixed turns). Default collapsed: thoughts can be
                    // multi-page on complex turns, the user opts in to view.
                    val stripped = stripRoleHeading(md)
                    val thoughts = extractThinkingBlocks(stripped)
                    val body = stripThinkingBlocks(stripped)
                    if (thoughts.isNotBlank()) {
                        ThoughtsCard(thoughts = thoughts)
                    }
                    if (body.isNotBlank()) {
                        // SelectionContainer wraps the assistant body so the
                        // rendered markdown is long-press selectable. The
                        // image-tap handler inside AssistantMarkdownBody
                        // continues to fire on tap (Compose routes long-
                        // press to the selection layer separately).
                        SelectionContainer {
                            AssistantMarkdownBody(
                                markdown = body,
                                images = entry.images.orEmpty(),
                            )
                        }
                    }
                } else {
                    // No full markdown yet (placeholder during streaming, or
                    // pre-R-5e server). Falls back to preview to keep the
                    // bubble informative until the per-entry RPC settles.
                    SelectionContainer {
                        Text(
                            text = stripRoleHeading(entry.preview),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

// Must stay byte-identical to the Rust side's
// `solution_agent::compact::COMPACT_PROMPT_HEADING` (the first line of
// `crates/solution_agent/resources/compact_context_instructions.md`). If
// that heading ever changes, change this too — the Rust test
// `compaction_template_starts_with_heading` guards the desktop half, but
// this copy is a separate codebase with no compile-time link.
private const val COMPACT_PROMPT_HEADING = "# Compact this session and prepare a clean handoff"

/**
 * Distinct, tappable chip standing in for the auto-generated compact-context
 * prompt — a large, agent-only template the editor injects on a Compact
 * action. Tapping opens the full prompt in a dialog rather than expanding it
 * inline: the prompt is hundreds of lines and inline expansion balloons the
 * transcript scroll. Mirrors the desktop's chip + popover. Rendered on the
 * user (right) side because the prompt is delivered as a user message.
 */
@Composable
private fun CompactPromptCard(bodyText: String, index: Int) {
    var showDialog by rememberSaveable(index) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 48.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        // Accent-tinted container + ripple makes it read clearly as a
        // tappable affordance, distinct from ordinary message bubbles.
        Surface(
            onClick = { showDialog = true },
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 0.dp,
            modifier = Modifier.widthIn(max = 360.dp),
        ) {
            Text(
                text = "🗜  Compact-context request",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }

    if (showDialog) {
        Dialog(onDismissRequest = { showDialog = false }) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Compact-context request",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { showDialog = false }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close",
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = bodyText.trim(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Collapsible "💭 Thoughts" panel that surfaces the agent's reasoning on
 * mobile. Default collapsed — extended-thinking content is multi-paragraph
 * and would dominate the bubble; we let the user opt in. Tap the header
 * to toggle expansion. The body renders as muted italic text without the
 * full markdown widget (thinking is usually plain prose; saving the
 * markdown-parsing cost keeps this lightweight when a long-running
 * session accumulates many thought-bearing turns).
 */
@Composable
private fun ThoughtsCard(thoughts: String) {
    var expanded by rememberSaveable(thoughts) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 0.dp,
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "💭 Thoughts",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) {
                        Icons.Filled.KeyboardArrowUp
                    } else {
                        Icons.Filled.KeyboardArrowDown
                    },
                    contentDescription = if (expanded) "Collapse thoughts" else "Expand thoughts",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (expanded) {
                Text(
                    text = thoughts,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.padding(
                        start = 8.dp,
                        end = 8.dp,
                        bottom = 8.dp,
                    ),
                )
            }
        }
    }
}

/**
 * Render an assistant entry's full markdown body via a block-level
 * in-tree parser + per-block `Text(annotated)` rendering. Replaces the
 * `multiplatform-markdown-renderer-m3` library because that library —
 * even with `retainState = true` on its `MarkdownState` — produces a
 * brief tree-swap artifact on every streaming content update (the old
 * tree is held painted while the new tree starts rendering on top of
 * it, so for a frame two snapshots overlap with subtle line-shift).
 * Pure-Compose `Text(annotated)` widgets recompose inside one frame,
 * with no Loading state and no overlap.
 *
 * Supported markdown:
 *   - Paragraphs (blank-line separated).
 *   - `**bold**`, `*italic*` / `_italic_`.
 *   - `` `inline code` `` — monospace + tinted background span.
 *   - ``` ```fenced``` ``` — full-width code block with chrome.
 *   - Bullet lists (`- `, `* `, `+ `) and ordered lists (`1. `).
 *     Single-level only; nested indentation is currently rendered
 *     by preserving the leading whitespace prefix in the item body.
 *   - GFM tables: header row + separator + body rows, all `|`-delimited.
 *   - `[label](url)` — clickable link. `spk-image://N` → fullscreen
 *     Dialog; any other URL opens in the system browser.
 *   - Soft line breaks preserved verbatim within paragraphs.
 *
 * Explicitly NOT supported (kept as raw text — readable, just unstyled):
 *   - Headings (`## …`), blockquotes, horizontal rules.
 *   - Nested inline formatting (e.g. bold inside italic).
 *   - Markdown image embeds (`![alt](url)`) — these become text. Inline
 *     images aren't a real concern: assistant `to_markdown` emits
 *     `` `Image` `` literal markers, never embeds.
 *
 * Tapping a `spk-image://N` link opens the same fullscreen `Dialog` the
 * user bubble uses (consistent interaction across bubble sides).
 */
@Composable
private fun AssistantMarkdownBody(markdown: String, images: List<EntryImage>) {
    var fullscreen by remember { mutableStateOf<EntryImage?>(null) }
    val decoded: Map<Int, Painter> = remember(images) {
        images.associate { it.index to bitmapPainterFromBase64(it.dataBase64) }
    }
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val codeBg = MaterialTheme.colorScheme.surface
    val linkColor = MaterialTheme.colorScheme.primary
    val divider = MaterialTheme.colorScheme.outlineVariant
    val codeSpan = remember(codeBg, onSurfaceVariant) {
        SpanStyle(
            fontFamily = FontFamily.Monospace,
            background = codeBg,
            color = onSurfaceVariant,
        )
    }
    val linkSpan = remember(linkColor) {
        SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
    }
    val blocks = remember(markdown, codeSpan, linkSpan) {
        parseMarkdownBlocks(markdown, codeSpan, linkSpan)
    }
    val uriHandler = LocalUriHandler.current
    val onLinkClick: (String) -> Unit = handler@{ url ->
        if (url.startsWith("spk-image://")) {
            val idx = url.removePrefix("spk-image://").toIntOrNull() ?: return@handler
            fullscreen = images.firstOrNull { it.index == idx }
            return@handler
        }
        // External link: open in the system browser. Previously these were
        // styled as links but did nothing on tap — a dead affordance.
        // `runCatching` guards a malformed URL / no-browser device.
        runCatching { uriHandler.openUri(url) }
    }
    val textStyle = MaterialTheme.typography.bodyMedium.copy(color = onSurfaceVariant)
    val monoStyle = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace,
        color = onSurfaceVariant,
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Paragraph -> MdParagraph(block.text, textStyle, onLinkClick)
                is MdBlock.UnorderedList -> MdUnorderedList(block, textStyle, onLinkClick)
                is MdBlock.OrderedList -> MdOrderedList(block, textStyle, onLinkClick)
                is MdBlock.CodeBlock -> MdCodeBlock(block.body, monoStyle, codeBg)
                is MdBlock.Table -> MdTable(block, textStyle, divider, onLinkClick)
            }
        }
    }

    val tappedImage = fullscreen
    if (tappedImage != null) {
        Dialog(onDismissRequest = { fullscreen = null }) {
            Surface(
                color = Color.Black,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp)
                    .clickable { fullscreen = null },
            ) {
                val painter = decoded[tappedImage.index]
                if (painter != null) {
                    androidx.compose.foundation.Image(
                        painter = painter,
                        contentDescription = "Full-screen image",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private const val ASSISTANT_LINK_TAG = "spk_assistant_link"

private sealed interface MdBlock {
    data class Paragraph(val text: AnnotatedString) : MdBlock
    data class UnorderedList(val items: List<AnnotatedString>) : MdBlock
    data class OrderedList(val items: List<AnnotatedString>, val startNumber: Int) : MdBlock
    data class CodeBlock(val body: String) : MdBlock
    data class Table(
        val header: List<AnnotatedString>,
        val rows: List<List<AnnotatedString>>,
    ) : MdBlock
}

@Composable
private fun MdParagraph(
    text: AnnotatedString,
    style: androidx.compose.ui.text.TextStyle,
    onLinkClick: (String) -> Unit,
) {
    ClickableText(
        text = text,
        style = style,
        onClick = { offset ->
            text.getStringAnnotations(ASSISTANT_LINK_TAG, offset, offset)
                .firstOrNull()
                ?.let { onLinkClick(it.item) }
        },
    )
}

@Composable
private fun MdUnorderedList(
    block: MdBlock.UnorderedList,
    style: androidx.compose.ui.text.TextStyle,
    onLinkClick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (item in block.items) {
            Row {
                Text(text = "•  ", style = style)
                MdParagraph(item, style, onLinkClick)
            }
        }
    }
}

@Composable
private fun MdOrderedList(
    block: MdBlock.OrderedList,
    style: androidx.compose.ui.text.TextStyle,
    onLinkClick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((i, item) in block.items.withIndex()) {
            Row {
                Text(text = "${block.startNumber + i}. ", style = style)
                MdParagraph(item, style, onLinkClick)
            }
        }
    }
}

@Composable
private fun MdCodeBlock(
    body: String,
    style: androidx.compose.ui.text.TextStyle,
    background: Color,
) {
    Surface(
        color = background,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = body,
            style = style,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun MdTable(
    block: MdBlock.Table,
    style: androidx.compose.ui.text.TextStyle,
    divider: Color,
    onLinkClick: (String) -> Unit,
) {
    val columnCount = maxOf(
        block.header.size,
        block.rows.maxOfOrNull { it.size } ?: 0,
    )
    if (columnCount == 0) return
    val headerStyle = style.copy(fontWeight = FontWeight.Bold)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = divider,
                shape = RoundedCornerShape(4.dp),
            ),
    ) {
        MdTableRow(block.header, columnCount, headerStyle, divider, onLinkClick)
        for (row in block.rows) {
            androidx.compose.material3.HorizontalDivider(
                thickness = 1.dp,
                color = divider,
            )
            MdTableRow(row, columnCount, style, divider, onLinkClick)
        }
    }
}

@Composable
private fun MdTableRow(
    cells: List<AnnotatedString>,
    columnCount: Int,
    style: androidx.compose.ui.text.TextStyle,
    divider: Color,
    onLinkClick: (String) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        for (col in 0 until columnCount) {
            if (col > 0) {
                androidx.compose.material3.VerticalDivider(
                    modifier = Modifier.height(IntrinsicSize.Min),
                    thickness = 1.dp,
                    color = divider,
                )
            }
            val cell = cells.getOrNull(col) ?: AnnotatedString("")
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                MdParagraph(cell, style, onLinkClick)
            }
        }
    }
}

/**
 * Line-oriented walker that turns markdown source into [MdBlock]s. Each
 * block boundary is detected on a single line of lookahead; unrecognised
 * lines fall through into a Paragraph block. Inline structure (bold,
 * italic, code, links) is delegated to [parseInlineMarkdown] for every
 * paragraph / list item / table cell.
 */
private fun parseMarkdownBlocks(
    text: String,
    codeSpan: SpanStyle,
    linkSpan: SpanStyle,
): List<MdBlock> {
    val lines = text.lines()
    val out = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.isBlank()) {
            i++
            continue
        }
        // Fenced code block — closing fence consumed; unclosed runs to EOF.
        if (line.trimStart().startsWith("```")) {
            val bodyLines = mutableListOf<String>()
            var j = i + 1
            while (j < lines.size && !lines[j].trimStart().startsWith("```")) {
                bodyLines += lines[j]
                j++
            }
            out += MdBlock.CodeBlock(bodyLines.joinToString("\n"))
            i = if (j < lines.size) j + 1 else lines.size
            continue
        }
        // GFM table: header row + separator (`---`-bearing) + zero-or-more body rows.
        if (line.contains('|') && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
            val header = parseTableRow(line, codeSpan, linkSpan)
            val rows = mutableListOf<List<AnnotatedString>>()
            var j = i + 2
            while (j < lines.size && lines[j].contains('|') && lines[j].isNotBlank()) {
                rows += parseTableRow(lines[j], codeSpan, linkSpan)
                j++
            }
            out += MdBlock.Table(header, rows)
            i = j
            continue
        }
        // Bullet list — collect adjacent `- `/`* `/`+ ` lines.
        if (isUnorderedListLine(line)) {
            val items = mutableListOf<AnnotatedString>()
            var j = i
            while (j < lines.size && isUnorderedListLine(lines[j])) {
                items += parseInlineMarkdown(stripListMarker(lines[j]), codeSpan, linkSpan)
                j++
            }
            out += MdBlock.UnorderedList(items)
            i = j
            continue
        }
        // Ordered list — first item's number becomes the start; subsequent
        // items keep contiguous numbering even if the source skipped values.
        val orderedMatch = ORDERED_LIST_LINE.matchAt(line, 0)
        if (orderedMatch != null) {
            val items = mutableListOf<AnnotatedString>()
            val startNumber = orderedMatch.groupValues[1].toIntOrNull() ?: 1
            var j = i
            while (j < lines.size && ORDERED_LIST_LINE.matchAt(lines[j], 0) != null) {
                items += parseInlineMarkdown(
                    ORDERED_LIST_LINE.replaceFirst(lines[j], ""),
                    codeSpan,
                    linkSpan,
                )
                j++
            }
            out += MdBlock.OrderedList(items, startNumber)
            i = j
            continue
        }
        // Paragraph: take lines up to the next blank or block-boundary line.
        val sb = StringBuilder()
        var j = i
        while (j < lines.size && lines[j].isNotBlank() && !isBlockBoundary(lines, j)) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(lines[j])
            j++
        }
        out += MdBlock.Paragraph(parseInlineMarkdown(sb.toString(), codeSpan, linkSpan))
        i = j
    }
    return out
}

private val ORDERED_LIST_LINE = Regex("""^\s*(\d{1,9})\.\s+""")

private fun isUnorderedListLine(line: String): Boolean {
    val trimmed = line.trimStart()
    return trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")
}

private fun stripListMarker(line: String): String {
    val trimmed = line.trimStart()
    return if (trimmed.length >= 2 && trimmed[1] == ' ') trimmed.substring(2) else trimmed
}

private fun isTableSeparator(line: String): Boolean {
    if (!line.contains("---")) return false
    val cleaned = line.trim().trim('|').trim()
    return cleaned.isNotEmpty() && cleaned.all { it == '-' || it == ':' || it == '|' || it.isWhitespace() }
}

private fun parseTableRow(
    line: String,
    codeSpan: SpanStyle,
    linkSpan: SpanStyle,
): List<AnnotatedString> {
    val trimmed = line.trim().let {
        var s = it
        if (s.startsWith("|")) s = s.drop(1)
        if (s.endsWith("|")) s = s.dropLast(1)
        s
    }
    return trimmed.split('|').map { parseInlineMarkdown(it.trim(), codeSpan, linkSpan) }
}

private fun isBlockBoundary(lines: List<String>, j: Int): Boolean {
    val line = lines[j]
    if (line.trimStart().startsWith("```")) return true
    if (isUnorderedListLine(line)) return true
    if (ORDERED_LIST_LINE.matchAt(line, 0) != null) return true
    if (line.contains('|') && j + 1 < lines.size && isTableSeparator(lines[j + 1])) return true
    return false
}

/**
 * Linear scanner that turns a SINGLE paragraph / list item / table cell
 * into an `AnnotatedString`. Single pass over the input, no nested
 * formatting — the inner content of each construct is taken verbatim.
 *
 * Match priority at every position:
 *   1. Backtick inline code
 *   2. `**bold**`
 *   3. `*italic*` / `_italic_`
 *   4. `[text](url)` link
 *   5. plain character
 *
 * Fenced code blocks are detected at the block level — by the time we
 * get here, a `\`\`\`` token would be inside a Paragraph block, treated
 * as literal text. Unclosed inline openers fall through to "plain
 * character" so the marker text is shown verbatim — never eats text
 * waiting for a close that never arrives.
 */
private fun parseInlineMarkdown(
    text: String,
    codeSpan: SpanStyle,
    linkSpan: SpanStyle,
): AnnotatedString = buildAnnotatedString {
    val boldSpan = SpanStyle(fontWeight = FontWeight.Bold)
    val italicSpan = SpanStyle(fontStyle = FontStyle.Italic)
    var i = 0
    val len = text.length
    while (i < len) {
        val c = text[i]

        if (c == '`') {
            val close = text.indexOf('`', i + 1)
            if (close in (i + 2)..(len - 1)) {
                val inner = text.substring(i + 1, close)
                if ('\n' !in inner) {
                    withStyle(codeSpan) { append(inner) }
                    i = close + 1
                    continue
                }
            }
        }

        if (c == '*' && text.startsWith("**", i)) {
            val close = text.indexOf("**", i + 2)
            if (close in (i + 3)..(len - 2)) {
                val inner = text.substring(i + 2, close)
                if ('\n' !in inner) {
                    withStyle(boldSpan) { append(inner) }
                    i = close + 2
                    continue
                }
            }
        }

        if (c == '*' || c == '_') {
            val nextSame = i + 1 < len && text[i + 1] == c
            if (!nextSame) {
                val close = text.indexOf(c, i + 1)
                if (close in (i + 2)..(len - 1)) {
                    val inner = text.substring(i + 1, close)
                    if ('\n' !in inner) {
                        withStyle(italicSpan) { append(inner) }
                        i = close + 1
                        continue
                    }
                }
            }
        }

        if (c == '[') {
            val closeBracket = text.indexOf(']', i + 1)
            if (closeBracket in (i + 2)..(len - 3) && text[closeBracket + 1] == '(') {
                val closeParen = text.indexOf(')', closeBracket + 2)
                if (closeParen in (closeBracket + 2)..(len - 1)) {
                    val label = text.substring(i + 1, closeBracket)
                    val url = text.substring(closeBracket + 2, closeParen)
                    if ('\n' !in label && '\n' !in url) {
                        pushStringAnnotation(tag = ASSISTANT_LINK_TAG, annotation = url)
                        withStyle(linkSpan) { append(label) }
                        pop()
                        i = closeParen + 1
                        continue
                    }
                }
            }
        }

        append(c)
        i++
    }
}

/**
 * Decode a base64 PNG/JPEG payload into a [BitmapPainter]. Failures (bad
 * base64, malformed image) yield a 1×1 transparent painter — the bubble
 * still renders and the dialog tap is a no-op.
 */
private fun bitmapPainterFromBase64(data: String): Painter {
    val bytes = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull()
        ?: return emptyBitmapPainter()
    val bitmap = runCatching {
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull() ?: return emptyBitmapPainter()
    return BitmapPainter(bitmap.asImageBitmap())
}

private fun emptyBitmapPainter(): Painter {
    val bm = createBitmap(1, 1)
    return BitmapPainter(bm.asImageBitmap())
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ToolCallBubble(
    call: ToolCallSummary,
    positionKey: Int,
    onAuthorize: (toolCallId: String, optionId: String) -> Unit = { _, _ -> },
) {
    // Include the transcript-position component so two identical-content
    // tool calls (same name + same argsPreview) don't share expanded state.
    var expanded by rememberSaveable(positionKey, call.name, call.argsPreview) {
        mutableStateOf(false)
    }
    val contentDesc = if (expanded) {
        "Collapse tool call ${call.name}"
    } else {
        "Expand tool call ${call.name}"
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            shape = RoundedCornerShape(12.dp),
            // Height-only animation: smooths the vertical growth as args /
            // result text stream in, WITHOUT the horizontal squeeze that
            // plain `animateContentSize` produced when the bubble widened
            // from its icon-only first frame (the "compressed square" flash).
            modifier = Modifier
                .widthIn(max = 360.dp)
                .animateHeightOnly()
                .clickable { expanded = !expanded }
                .semantics {
                    role = Role.Button
                    contentDescription = contentDesc
                },
        ) {
            // Outer column: SelectionContainer (selectable text content)
            // followed by the auth-buttons FlowRow as a sibling so that
            // button taps are never intercepted by SelectionContainer's
            // long-press gesture handling on OEM Compose skins.
            Column {
            // SelectionContainer wraps only the tool-call content so the
            // tool name + collapsed args preview + expanded args/result
            // text are all long-press selectable. The Surface's
            // `.clickable { expanded = !expanded }` continues to fire on
            // short tap (no onLongClick set → long-press falls through to
            // the selection layer).
            SelectionContainer {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Build,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = call.name,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    // Live elapsed badge — only on running tool calls
                    // (the desktop's status_row uses the same gating).
                    // The tick is scoped to the row's composition so it
                    // cancels naturally when the status leaves "running"
                    // (LaunchedEffect rekeys on `call.status` and the
                    // re-keyed effect, seeing a non-running status,
                    // returns immediately without scheduling more delays).
                    val startedAt: Long? = call.toolStatusStartedAtMs
                    if (startedAt != null && call.status == ToolCallStatusDto.Running) {
                        var elapsedSeconds by remember(startedAt) {
                            mutableLongStateOf(((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L))
                        }
                        LaunchedEffect(startedAt, call.status) {
                            if (call.status != ToolCallStatusDto.Running) return@LaunchedEffect
                            while (true) {
                                delay(1000L)
                                elapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000L)
                                    .coerceAtLeast(0L)
                            }
                        }
                        Text(
                            text = formatElapsed(elapsedSeconds),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    ToolStatusPill(status = call.status)
                }
                Spacer(Modifier.padding(top = 4.dp))
                // Collapsed view: first line of args preview only.
                if (!expanded) {
                    Text(
                        text = call.argsPreview.lineSequence().firstOrNull().orEmpty(),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Column {
                        Text(
                            text = "args",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            text = call.argsPreview,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                        if (call.resultPreview.isNotEmpty()) {
                            Text(
                                text = "result",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            Text(
                                text = call.resultPreview,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                }
            }
            }
            // Authorization options: rendered OUTSIDE SelectionContainer so
            // button taps are never intercepted by its gesture handler.
            // Present only while the call is awaiting confirmation (server
            // sends a non-empty list exactly then, clearing it on the next
            // broadcast once the user answers — so these buttons vanish
            // without any local optimistic state). Allow-style options render
            // as filled primary buttons, reject-style as outlined.
            if (call.options.isNotEmpty()) {
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (option in call.options) {
                        if (option.isAllow) {
                            androidx.compose.material3.Button(
                                onClick = { onAuthorize(call.toolCallId, option.optionId) },
                            ) {
                                Text(option.label)
                            }
                        } else {
                            androidx.compose.material3.OutlinedButton(
                                onClick = { onAuthorize(call.toolCallId, option.optionId) },
                            ) {
                                Text(option.label)
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

/**
 * Status pill colour-coded by the tool-call's documented status families.
 * Keeps the raw status label on the pill (spaces and all) so the user sees
 * the same phrase the editor logged.
 */
@Composable
private fun ToolStatusPill(status: ToolCallStatusDto) {
    val (bg, fg) = when (status) {
        ToolCallStatusDto.Done -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        ToolCallStatusDto.Failed, ToolCallStatusDto.Rejected ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        ToolCallStatusDto.Running,
        ToolCallStatusDto.Pending,
        ToolCallStatusDto.WaitingForConfirmation ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        ToolCallStatusDto.Canceled ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        ToolCallStatusDto.Unknown ->
            MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.onSurface
    }
    // Render the wire vocabulary verbatim — matches the desktop status log.
    // `WaitingForConfirmation` flattens to the spaced "waiting for confirmation"
    // phrase the editor used; everything else maps to the lowercased variant.
    val label = when (status) {
        ToolCallStatusDto.WaitingForConfirmation -> "waiting for confirmation"
        else -> status.name.lowercase()
    }
    Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.small) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun PlanBubble(plan: PlanSummary) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 360.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(text = "plan", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.padding(top = 4.dp))
                if (plan.items.isEmpty()) {
                    Text(
                        text = "(no items)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    for (item in plan.items) {
                        Text(
                            text = "• $item",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
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
            modifier = Modifier.widthIn(max = 360.dp),
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
                        text = stripRoleHeading(text),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

// stripRoleHeading is defined in :core (RemoteDtos.kt) so the optimistic-
// bubble dedupe in MainViewModel.reconcileOptimistic can share the same
// normalisation that this file applies to rendering.

/**
 * Does this assistant entry have anything visible to render?
 *
 * The upstream `acp_thread::AssistantMessage::to_markdown` always wraps
 * the chunks in `"## Assistant\n\n{chunks}\n\n"`, so an empty chunks list
 * still produces a non-empty markdown string. A bubble is worth drawing
 * if it has EITHER a non-thinking answer body OR thinking content — the
 * collapsible Thoughts card now renders the latter, so a thought-only
 * turn (the no-text-output extended-thinking case) is no longer hidden.
 *
 * Returns false only when stripping the role banner leaves nothing at
 * all (the no-chunks tool-call-only shape of `"## Assistant\n\n\n\n"`).
 * The dispatch site at [ChatBubble] skips the bubble entirely in that
 * case, so the user doesn't see a padded gray rectangle with no content.
 */
internal fun hasVisibleAssistantContent(raw: String): Boolean {
    val stripped = stripRoleHeading(raw)
    val body = stripThinkingBlocks(stripped)
    if (body.isNotBlank()) return true
    return extractThinkingBlocks(stripped).isNotBlank()
}

/**
 * Concatenate every `<thinking>…</thinking>` block in [md] into one
 * blank-line-separated string for the collapsible Thoughts card. Mirrors
 * [stripThinkingBlocks] but in reverse — we KEEP the inner content and
 * drop everything else.
 *
 * Sources of `<thinking>` blocks in our markdown:
 *  - `AssistantMessageChunk::Thought` (the upstream rendering of
 *    `stream_event(thinking_delta)` content);
 *  - The "[encrypted reasoning hidden …]" placeholder our pump emits
 *    for `redacted_thinking` content blocks;
 *  - Local-command thinking blocks recovered by the
 *    `text_streamed_for_current_message` fallback.
 *
 * Returns an empty string when no blocks exist — the dispatch in
 * [AssistantBubble] uses `isNotBlank()` to decide whether to mount the
 * card at all.
 */
internal fun extractThinkingBlocks(md: String): String {
    val matches = THINKING_BLOCK.findAll(md)
        .map { it.groupValues[1].trim() }
        .filter { it.isNotEmpty() }
        .toList()
    if (matches.isEmpty()) return ""
    return matches.joinToString("\n\n")
}

/**
 * Remove every `<thinking>…</thinking>` block from [md] AND tidy the
 * surrounding whitespace so the renderer doesn't see leading newlines
 * (which the multiplatform-markdown-renderer turns into a phantom
 * empty paragraph above the actual content). Shared by the visibility
 * filter and the renderer feed so a thought-only turn is hidden AND a
 * mixed thought+answer turn renders flush-top.
 */
internal fun stripThinkingBlocks(md: String): String {
    // First pass: drop every COMPLETE `<thinking>…</thinking>` block.
    var stripped = THINKING_BLOCK.replace(md, "")
    // Second pass: if a partial `<thinking>` is still mid-stream
    // (open tag without a matching close yet), drop everything from
    // that opener onward. Without this guard the assistant bubble
    // oscillates during streaming — partial thinking text renders
    // and grows the bubble, then disappears the instant
    // `</thinking>` arrives, then the next thinking block repeats
    // the cycle. Discarding the trailing partial keeps the visible
    // body monotonic across streaming snapshots.
    val openIdx = OPEN_THINKING.find(stripped)?.range?.first
    if (openIdx != null) {
        stripped = stripped.substring(0, openIdx)
    }
    return stripped.trim()
}

// `[\s\S]` matches across newlines so multi-line `<thinking>` payloads
// are removed in one pass. The lazy `*?` keeps each match minimal so two
// thinking blocks in the same body each shed independently. Group 1
// captures the inner body so `extractThinkingBlocks` can collect just
// the reasoning text without the surrounding tags.
private val THINKING_BLOCK = Regex(
    pattern = """<thinking>([\s\S]*?)</thinking>""",
    options = setOf(RegexOption.IGNORE_CASE),
)
private val OPEN_THINKING = Regex(
    pattern = """<thinking>""",
    options = setOf(RegexOption.IGNORE_CASE),
)

/**
 * 44 dp content-height app bar — replaces M3's stock `TopAppBar`, which
 * forces a 64 dp container that swallows screen real estate on a phone
 * chat surface. Layout: back arrow, title, optional trailing slot
 * (state pill in this screen).
 */
@Composable
private fun SlimTopBar(
    title: String,
    onBack: () -> Unit,
    onTitleClick: (() -> Unit)? = null,
    // Row-1 trailing: real actions (the overflow menu). Kept on the title
    // line so it's always reachable.
    actions: @Composable RowScope.() -> Unit = {},
    // Row-2 status strip: the read-only indicators (context meter, state
    // pill, elapsed, last-activity). Split onto its own line so the title
    // gets the full width instead of being squeezed to an ellipsis by the
    // indicators sharing the line. Null → no second row (e.g. while the
    // session is still loading and there's nothing to show).
    statusRow: (@Composable RowScope.() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(
            // Top (status bar) + horizontal (landscape cutout / side nav
            // bar) so the back button isn't hidden under the left camera
            // notch in landscape. Applied to the Column so both rows clear
            // the insets.
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                val titleModifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .let { if (onTitleClick != null) it.clickable(onClick = onTitleClick) else it }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = titleModifier,
                )
                actions()
            }
            if (statusRow != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Flush-left at the standard content inset (a deep
                        // indent under the title left the small indicators
                        // floating mid-row). No top padding — it sits
                        // directly under the title row; a small bottom pad
                        // keeps the strip off the content edge. Horizontal
                        // scroll guards against overflow on narrow screens.
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 12.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    statusRow()
                }
            }
        }
    }
}

@OptIn(kotlinx.coroutines.FlowPreview::class, ExperimentalMaterial3Api::class)
@Composable
private fun ComposeBar(
    enabled: Boolean,
    state: DisplayState,
    cancelInFlight: Boolean,
    /**
     * Invoked when the user taps Send. [text] is the trimmed input field
     * contents; [blocks] is the list of ResourceLink blocks pointing at
     * server-side upload handles for every successfully-completed
     * attachment upload (empty when nothing is attached). Pure-text
     * sends go via sendMessage; mixed sends go via sendMessageBlocks
     * with the server resolving each spk-upload://N handle back into
     * the original Image / Text content block.
     */
    onSend: (text: String, blocks: List<ContentBlockDto>) -> Unit,
    /**
     * Deferred-send entry point used when the user taps Send while one
     * or more attachments are still uploading. The bubble appears in
     * chat immediately (with a per-message "Uploading X/Y" badge) and
     * the wire send fires once every upload reaches Done. Drains the
     * ComposeBar field + attachment row synchronously like [onSend].
     */
    onSendDeferred: (text: String, uploads: List<DeferredUpload>) -> Unit,
    /**
     * Invoked when an attachment can't be processed (size cap, upload
     * failed). The caller surfaces the reason via the snackbar host and
     * the failing item is silently dropped from the send.
     */
    onAttachmentError: (reason: String) -> Unit,
    onCancel: () -> Unit,
    /**
     * Structured session state. Used to render the Errored banner message
     * (via [SessionStateDto.erroredMessage]); the [DisplayState] classifier
     * for everything else is already plumbed via [state]. Null until the
     * session detail loads.
     */
    sessionStateDto: SessionStateDto?,
    sessionId: String,
    initialDraft: String,
    seedLoaded: Boolean,
    onDraftChanged: suspend (String) -> Unit,
    /**
     * Synchronous flush of the current draft text, called when the
     * compose bar leaves composition (back-nav). The debounced
     * `onDraftChanged` writer above only fires on the trailing edge of a
     * 500 ms quiet window, so a keystroke followed within < 500 ms by a
     * back-press never made it to disk and the user saw their draft
     * vanish on reopen.
     */
    onDraftFlush: (String) -> Unit,
    /** Initial picked-attachment list, hydrated from the per-session store. */
    initialAttachments: () -> List<PickedAttachment>,
    /** Mirror every mutation of the picked-attachment list back to the store. */
    onAttachmentsChanged: (List<PickedAttachment>) -> Unit,
    /**
     * Kick off a fresh chunked upload for [uri]. Returns the local key
     * (used for cancel / forget) + the StateFlow the preview card
     * collects to drive progress UI.
     */
    onStartUpload: (
        uri: Uri,
        sessionId: String,
        mime: String,
        displayName: String,
        totalSize: Long,
    ) -> Pair<String, StateFlow<UploadManager.State>>,
    /** Abort the upload identified by [localKey] (× tap). */
    onCancelUpload: (localKey: String) -> Unit,
    /** Drop local upload state after a successful send consumed the handle. */
    onForgetUpload: (localKey: String) -> Unit,
    /**
     * Suspend until the upload for [localKey] reaches a terminal state.
     * Returns the server handle on Done, null on Failed. Used by Send
     * to await the very-last ack rather than snapshotting `state.value`
     * (the user can hit Send the instant the last chunk fires; the
     * Done transition lands ~1 RTT later).
     */
    awaitUploadTerminal: suspend (localKey: String) -> String?,
    /**
     * True when the user has at least one optimistic user bubble
     * pending — i.e. there's something the server-side `pending_messages`
     * queue could flush. Drives whether the Force-flush button is
     * shown in the right-action cluster while the agent is mid-turn.
     */
    hasOptimisticSends: Boolean,
    /**
     * Cancel the current agent turn AND request the server-side
     * `flush_pending` so the accumulated `pending_messages` bundle
     * fires immediately as a fresh merged turn.
     */
    onForceFlush: () -> Unit,
) {
    val context = LocalContext.current
    val composeScope = rememberCoroutineScope()
    // Live, non-persisted V1: attachments survive config changes AND
    // back-nav within the same process (via [SessionDetailStore]'s
    // per-session map provided through `initialAttachments` +
    // `onAttachmentsChanged`; the previous composable-local `remember`
    // reset to empty on every chat-screen remount, so a user who
    // attached a file, hit Back, and reopened the chat lost their picks).
    // Process death still drops them — round-tripping Uri/displayName/
    // mime/upload localKey through disk is doable but out of scope.
    var pickedAttachments by remember(sessionId) {
        mutableStateOf(initialAttachments())
    }
    // Mirror every list mutation into the store so a subsequent
    // back→reopen on the same session re-hydrates from the same list.
    LaunchedEffect(sessionId) {
        androidx.compose.runtime.snapshotFlow { pickedAttachments }
            .collect { onAttachmentsChanged(it) }
    }
    var showAttachSheet by remember { mutableStateOf(false) }
    val attachSheetState = rememberModalBottomSheetState()

    val photosLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = MAX_PHOTOS_PER_PICK),
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            composeScope.launch {
                val resolved = withContext(Dispatchers.IO) {
                    uris.mapNotNull { resolvePickedAttachment(context, it) }
                }
                val (accepted, rejected) = resolved.partition { it.sizeBytes <= MAX_ATTACHMENT_BYTES }
                rejected.forEach {
                    onAttachmentError("`${it.displayName}` exceeds the 5 MB attachment cap")
                }
                val started = accepted.map { item ->
                    val (key, flow) = onStartUpload(
                        item.uri, sessionId, item.mimeType, item.displayName, item.sizeBytes,
                    )
                    PickedAttachment(
                        uri = item.uri,
                        displayName = item.displayName,
                        mimeType = item.mimeType,
                        sizeBytes = item.sizeBytes,
                        localKey = key,
                        uploadState = flow,
                    )
                }
                if (started.isNotEmpty()) {
                    pickedAttachments = pickedAttachments + started
                }
            }
        }
    }
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            composeScope.launch {
                val resolved = withContext(Dispatchers.IO) {
                    resolvePickedAttachment(context, uri)
                }
                if (resolved != null) {
                    if (resolved.sizeBytes > MAX_ATTACHMENT_BYTES) {
                        onAttachmentError("`${resolved.displayName}` exceeds the 5 MB attachment cap")
                    } else {
                        val (key, flow) = onStartUpload(
                            resolved.uri, sessionId, resolved.mimeType,
                            resolved.displayName, resolved.sizeBytes,
                        )
                        pickedAttachments = pickedAttachments + PickedAttachment(
                            uri = resolved.uri,
                            displayName = resolved.displayName,
                            mimeType = resolved.mimeType,
                            sizeBytes = resolved.sizeBytes,
                            localKey = key,
                            uploadState = flow,
                        )
                    }
                }
            }
        }
    }
    // R-6d: rememberSaveable for config-change resilience + draft-on-disk
    // for cross-restart resilience. `mutableStateOf(initialDraft)` keyed
    // on sessionId resets when the user navigates between sessions; the
    // disk write below is debounced 500 ms via `snapshotFlow.debounce` so
    // the I/O scheduler isn't hammered by every keystroke.
    var draft by rememberSaveable(sessionId) { mutableStateOf(initialDraft) }
    // The async seed lands AFTER the first composition. Seat it into the
    // text field the moment it arrives so the user sees their saved draft
    // (or a bounce-recovered message) instead of an empty field.
    LaunchedEffect(sessionId, seedLoaded, initialDraft) {
        // Only overwrite if the user hasn't started typing yet — preserve
        // any keystrokes that landed during the brief async window.
        if (seedLoaded && draft.isEmpty()) {
            draft = initialDraft
        }
    }
    // F5: per-session debounced writer. snapshotFlow + debounce(500) +
    // distinctUntilChanged means:
    //   - we don't re-arm the 500 ms timer on every keystroke
    //     (snapshotFlow emits on change, debounce throttles to the
    //     trailing edge of the burst);
    //   - we don't fire a redundant write when `draft` is reset to "" by
    //     the send path (distinctUntilChanged elides the no-op);
    //   - keying on `sessionId` ensures a session switch cancels any
    //     pending write so the previous session's tail keystroke doesn't
    //     bleed into the new session's saved draft.
    LaunchedEffect(sessionId) {
        androidx.compose.runtime.snapshotFlow { draft }
            .debounce(500)
            .distinctUntilChanged()
            .collect { text -> onDraftChanged(text) }
    }
    // Flush the trailing keystrokes synchronously when the compose bar
    // leaves composition (back-nav). Without this the debounced writer
    // misses anything typed within 500 ms of the back-press.
    DisposableEffect(sessionId) {
        onDispose { onDraftFlush(draft) }
    }
    val isRunning = state == DisplayState.Running
    // Stopping is the post-cancel transient state — the server is settling
    // the agent before flipping to Idle/Errored. Render a non-interactive
    // "Stopping…" affordance INSTEAD of the active Stop button: a second
    // press would do nothing useful (the backend already escalates the
    // wedged stop after 30 s on its own).
    val isStopping = state == DisplayState.Stopping
    // Snapshot each attachment's upload state so the Send button reacts
    // to Done / Failed transitions as they happen. collectAsState() is
    // the standard Compose-side flow subscription primitive.
    val uploadStates: List<UploadManager.State> = pickedAttachments.map {
        it.uploadState.collectAsState().value
    }
    // Either there is body text OR there's at least one attachment.
    // Half-uploaded attachments NO LONGER block Send: tapping while
    // uploads are in flight routes through [onSendDeferred], which
    // posts the optimistic bubble immediately and defers the wire
    // send until every upload's StateFlow reaches Done. A bubble that
    // can't reach Done (Failed) drops the whole message — see
    // SessionDetailStore.sendMessageBlocksDeferred.
    //
    // Block Send when an upload is in a Failed state — there's no
    // handle for that one, so the deferred path would just drop the
    // message after the bubble appears. Better to refuse the press +
    // tell the user to remove / retry the failing attachment first.
    val anyUploadFailed = uploadStates.any { it is UploadManager.State.Failed }
    // Send is now enabled regardless of session-running state — the
    // store gates the actual wire dispatch via
    // [SessionDetailStore.gateOnSessionIdle]. Pressing Send during a
    // running turn appends an optimistic bubble with the "Queued"
    // badge; the wire send fires once the turn settles or the user
    // taps Force-flush.
    val sendEnabled = enabled && !anyUploadFailed &&
        (draft.isNotBlank() || pickedAttachments.isNotEmpty())
    val showCancel = isRunning
    val showForceFlush = isRunning && hasOptimisticSends

    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        // No inset modifier. The activity declares
        // windowSoftInputMode="adjustResize" in the manifest, so the
        // system shrinks the window when the keyboard opens. With this
        // bar pinned to the bottom of Scaffold's bottomBar slot, it
        // naturally ends up just above the keyboard (or just above the
        // gesture nav when the keyboard is closed).
        Column(modifier = Modifier.fillMaxWidth()) {
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
                        // Pull the structured Errored message (post-DTO
                        // migration); fall back to "see logs" when the
                        // server reported the state with no payload OR
                        // the session DTO hasn't loaded yet.
                        text = "Session errored: ${sessionStateDto?.erroredMessage()?.ifBlank { null } ?: "see logs"}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            // Picked-attachments preview strip — only when the user has
            // actually picked something. Horizontal scroll because we cap
            // photo picks at 4 + 1 file but a long filename can still
            // make the row wider than the screen.
            if (pickedAttachments.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(pickedAttachments, key = { it.localKey }) { item ->
                        AttachmentPreviewCard(
                            attachment = item,
                            onRemove = {
                                // Abort the in-flight upload server-side
                                // (best-effort upload_abort) AND remove
                                // the local row in one action.
                                onCancelUpload(item.localKey)
                                pickedAttachments = pickedAttachments.filter {
                                    it.localKey != item.localKey
                                }
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Attach affordance — opens a ModalBottomSheet offering
                // Photos / File pickers. Disabled when the field itself is
                // disabled (no session loaded). Visually balances the send
                // IconButton on the right.
                IconButton(
                    onClick = { showAttachSheet = true },
                    enabled = enabled,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.AttachFile,
                        contentDescription = "Attach file or image",
                    )
                }
                // Custom pill-shaped input — M3 OutlinedTextField has a
                // hard-coded 56 dp minimum that's too tall for a chat row.
                // A BasicTextField wrapped in a Surface lets us shrink to
                // ~40 dp while keeping focus-ring, placeholder, and IME
                // behaviour. weight(1f) is the RowScope extension that
                // grows the input to fill width alongside the trailing
                // send/cancel icon.
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                        .heightIn(min = 40.dp, max = 160.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = draft,
                            onValueChange = { if (enabled) draft = it },
                            enabled = enabled,
                            maxLines = 6,
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(
                                MaterialTheme.colorScheme.primary,
                            ),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (draft.isEmpty()) {
                            Text(
                                text = "Send a message",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                // Right-hand action cluster. Send is always present;
                // Cancel appears only while the agent is mid-turn;
                // Force-flush appears only when there's a queued send
                // AND the agent is still running (otherwise the queue
                // would drain on its own once the turn settles).
                if (showForceFlush) {
                    FilledIconButton(
                        onClick = onForceFlush,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                    ) {
                        Icon(
                            Icons.Filled.Bolt,
                            contentDescription = "Force-send queued",
                        )
                    }
                }
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
                } else if (isStopping) {
                    // Non-interactive "Stopping…" affordance. NO onClick wired —
                    // the cancel has been accepted server-side and the backend's
                    // 30 s escalation handles wedged stops, so a second press
                    // would be cargo-cult. Spinner + label mirrors the inflight
                    // Stop button's "we're working on it" feel without granting
                    // the user a button to mash.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Stopping…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                FilledIconButton(
                    onClick = {
                        val toSendText = draft.trim()
                        val attachments = pickedAttachments
                        if (toSendText.isEmpty() && attachments.isEmpty()) return@FilledIconButton

                        // Branch on whether every attachment is
                        // already Done. If so, fire the existing
                        // single-shot path — the optimistic bubble
                        // transitions straight to "Sending →
                        // Delivered". If any upload is still in
                        // flight, route through the deferred path:
                        // the bubble appears immediately with an
                        // "Uploading X/Y" badge and the store
                        // dispatches send_message_blocks once
                        // every upload terminates Done. Failed
                        // attachments are already blocked by
                        // [sendEnabled].
                        val allDone = attachments.isNotEmpty() &&
                            attachments.all {
                                it.uploadState.value is UploadManager.State.Done
                            }
                        if (attachments.isEmpty() || allDone) {
                            composeScope.launch {
                                val blocks = mutableListOf<ContentBlockDto>()
                                for (item in attachments) {
                                    val handle = awaitUploadTerminal(item.localKey)
                                    if (handle == null) {
                                        onAttachmentError(
                                            "`${item.displayName}` failed to upload — drop it and retry",
                                        )
                                        continue
                                    }
                                    blocks += ContentBlockDto.ResourceLink(
                                        name = item.displayName,
                                        uri = handle,
                                    )
                                }
                                if (toSendText.isEmpty() && blocks.isEmpty()) return@launch
                                onSend(toSendText, blocks)
                                attachments.forEach { onForgetUpload(it.localKey) }
                                draft = ""
                                pickedAttachments = emptyList()
                            }
                        } else {
                            // Deferred path. We pass DeferredUpload
                            // descriptors to the store (NOT the
                            // StateFlows themselves, since the
                            // store doesn't know about the
                            // collector — it dereferences localKey
                            // through the upload manager). The
                            // store's `cleanupDeferred` calls
                            // `uploadManager.forget(localKey)` for
                            // every attachment on both success and
                            // failure terminals, so no manual
                            // forget here — same net effect as the
                            // all-Done path above.
                            onSendDeferred(
                                toSendText,
                                attachments.map {
                                    DeferredUpload(
                                        localKey = it.localKey,
                                        displayName = it.displayName,
                                        mime = it.mimeType,
                                    )
                                },
                            )
                            draft = ""
                            pickedAttachments = emptyList()
                        }
                        },
                    enabled = sendEnabled,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }

    if (showAttachSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAttachSheet = false },
            sheetState = attachSheetState,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("Photos") },
                    leadingContent = {
                        Icon(Icons.Filled.Image, contentDescription = null)
                    },
                    supportingContent = {
                        Text("Pick up to $MAX_PHOTOS_PER_PICK images")
                    },
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        photosLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                )
                ListItem(
                    headlineContent = { Text("File") },
                    leadingContent = {
                        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null)
                    },
                    supportingContent = { Text("Text-like files only in V1") },
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        fileLauncher.launch(arrayOf("*/*"))
                    },
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// PickedAttachment now lives in `ru.sipaha.sawe.app.vm.PickedAttachment`
// so [SessionDetailStore] can keep a per-session draft list across the
// chat-detail composable's unmount/remount cycle on back-navigation.

/**
 * Lightweight intermediate the picker callbacks emit from the
 * ContentResolver query before they call `onStartUpload`. The
 * picker partitions over size cap on this shape, then converts the
 * accepted entries into [PickedAttachment] by stamping in the
 * UploadManager-assigned localKey + stateFlow.
 */
private data class ResolvedAttachment(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
)

private const val MAX_PHOTOS_PER_PICK = 4
private const val MAX_ATTACHMENT_BYTES: Long = 5L * 1024 * 1024

/**
 * Resolve a SAF / PhotoPicker [Uri] into a [PickedAttachment] by
 * querying the content resolver for `_display_name` + `_size` and the
 * resolver's MIME type. Unknown size is reported as
 * [MAX_ATTACHMENT_BYTES] + 1 so the caller rejects it the same as a
 * genuine over-cap file — better than reading into memory blindly.
 */
private fun resolvePickedAttachment(context: Context, uri: Uri): ResolvedAttachment? {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    var displayName: String = uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
    var size: Long = MAX_ATTACHMENT_BYTES + 1
    runCatching {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    displayName = cursor.getString(nameIndex) ?: displayName
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
    }
    return ResolvedAttachment(uri = uri, displayName = displayName, mimeType = mime, sizeBytes = size)
}

/**
 * One picked attachment rendered as a small card in the compose-row
 * preview strip. Images get a thumbnail decoded at low resolution
 * (`inSampleSize = 4`) so a 5 MB photo doesn't blow the heap; files get
 * an icon + name + size readout. The corner × button removes the item
 * from the live list (and aborts the upload server-side).
 *
 * Upload-state overlay (chunked-upload integration):
 *   - Queued / Uploading / Paused: a small `CircularProgressIndicator`
 *     in the top-left, plus a percent label rendered below the
 *     filename so the user sees "37%" tick up as chunks ack.
 *   - Done: a small green check icon in the top-left so the user
 *     knows the attachment is ready to send.
 *   - Failed: a small red error icon in the top-left + the failure
 *     reason as the percent label; tap-to-retry isn't wired (cancel
 *     and re-pick is the V1 recovery path; cheaper to ship than a
 *     dedicated retry button on a tiny card).
 */
@Composable
private fun AttachmentPreviewCard(
    attachment: PickedAttachment,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val isImage = attachment.mimeType.startsWith("image/", ignoreCase = true)
    val uploadState by attachment.uploadState.collectAsState()
    // Shared modal trigger for both image and file variants: failure
    // reasons regularly exceed any inline label or banner clamp
    // (`upload_init failed: Network error: Software caused connection abort`
    // is already 4 lines on a phone), and the only place the reason
    // is visible is on this card. A tap on either the bottom banner
    // (image) or the body Surface itself (any failed card) opens an
    // AlertDialog with the selectable full text.
    var failureDialogOpen by rememberSaveable(attachment.localKey) {
        mutableStateOf(false)
    }
    Box(
        modifier = Modifier
            .size(width = 96.dp, height = 72.dp),
    ) {
        val cardClickable = uploadState is UploadManager.State.Failed
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (cardClickable) {
                        Modifier.clickable { failureDialogOpen = true }
                    } else {
                        Modifier
                    }
                ),
        ) {
            if (isImage) {
                val painter: Painter? = remember(attachment.uri) {
                    runCatching {
                        context.contentResolver.openInputStream(attachment.uri)?.use { stream ->
                            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                            val bitmap = BitmapFactory.decodeStream(stream, null, options)
                            bitmap?.let { BitmapPainter(it.asImageBitmap()) }
                        }
                    }.getOrNull()
                }
                if (painter != null) {
                    androidx.compose.foundation.Image(
                        painter = painter,
                        contentDescription = attachment.displayName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Image, contentDescription = null)
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = attachment.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = uploadStatusLabel(uploadState, attachment.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Upload-state overlay in the top-left corner: progress
        // indicator while in-flight, check on Done, error icon on
        // Failed. Positioned opposite the × dismiss button (top-right)
        // so the two never collide.
        UploadStateOverlay(state = uploadState)
        // For image-thumbnail variants the only failure feedback was
        // the tiny ⚠ overlay icon — text labels live on the file
        // variant. Surface the failure reason as a bottom-banner so
        // the user can tell "size mismatch" from "URI permission
        // expired" etc. without long-pressing for an accessibility
        // tooltip.
        if (isImage && uploadState is UploadManager.State.Failed) {
            // Compact 2-line preview clamped on the thumbnail; the full
            // text lives in the modal that any tap opens (the parent
            // Surface above is also `.clickable` when failed, so the
            // user doesn't have to hit the thin banner exactly).
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clickable { failureDialogOpen = true },
            ) {
                Text(
                    text = (uploadState as UploadManager.State.Failed).reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        // Dismiss × in the top-right corner. Tiny tap target by design
        // (compose row real estate is tight) — paired with the larger
        // card body so an accidental dismiss is rare.
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(18.dp)
                .clickable(onClick = onRemove)
                .semantics {
                    contentDescription = "Remove ${attachment.displayName}"
                    role = Role.Button
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        if (failureDialogOpen && uploadState is UploadManager.State.Failed) {
            val reason = (uploadState as UploadManager.State.Failed).reason
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { failureDialogOpen = false },
                title = {
                    androidx.compose.material3.Text("Upload failed: ${attachment.displayName}")
                },
                text = {
                    // SelectionContainer lets the user long-press to
                    // copy the reason for a bug report — the strings
                    // come straight from server / exception messages
                    // and are often the only diagnostic info available.
                    SelectionContainer {
                        androidx.compose.material3.Text(
                            text = reason,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { failureDialogOpen = false },
                    ) {
                        androidx.compose.material3.Text("OK")
                    }
                },
            )
        }
    }
}

/**
 * Render the upload-state overlay icon at the top-left of an
 * [AttachmentPreviewCard]. Receiver is the surrounding [androidx.compose.foundation.layout.BoxScope]
 * so we can call `.align(Alignment.TopStart)` directly without
 * threading the Modifier through the call site.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.UploadStateOverlay(
    state: UploadManager.State,
) {
    val align = Modifier
        .align(Alignment.TopStart)
        .padding(2.dp)
        .size(18.dp)
    when (state) {
        is UploadManager.State.Queued,
        is UploadManager.State.Uploading,
        is UploadManager.State.Paused -> CircularProgressIndicator(
            modifier = align.padding(2.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        is UploadManager.State.Done -> Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = "Upload complete",
            tint = MaterialTheme.colorScheme.primary,
            modifier = align,
        )
        is UploadManager.State.Failed -> Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = "Upload failed: ${state.reason}",
            tint = MaterialTheme.colorScheme.error,
            modifier = align,
        )
    }
}

/**
 * Per-state label rendered below the filename of an
 * [AttachmentPreviewCard]'s non-image variant. Falls back to the file
 * size (the pre-upload-state UI) only on [UploadManager.State.Done] /
 * absence of any state.
 */
private fun uploadStatusLabel(state: UploadManager.State, sizeBytes: Long): String = when (state) {
    is UploadManager.State.Queued -> "0%"
    is UploadManager.State.Uploading -> {
        if (state.total <= 0L) "..." else "${(state.sent * 100L / state.total).coerceIn(0L, 100L)}%"
    }
    is UploadManager.State.Paused ->
        if (state.total <= 0L) "paused" else "${(state.sent * 100L / state.total).coerceIn(0L, 100L)}% (paused)"
    is UploadManager.State.Done -> humanReadableSize(sizeBytes)
    is UploadManager.State.Failed -> state.reason.take(60)
}

private fun humanReadableSize(bytes: Long): String = when {
    bytes < 0 -> "?"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes / 1024)} KB"
    else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
}

/**
 * History-edge affordance rendered at the top of the chat list (= the FIRST
 * item in the natural top-anchored LazyColumn). Absence of the "Load older
 * messages" tap target already conveys "you're at the start" — no separate
 * sentinel is rendered for the start of history.
 */
@Composable
private fun HistoryEdgeRow(
    isLoadingOlder: Boolean,
    hasOlder: Boolean,
    onTap: () -> Unit,
) {
    when {
        isLoadingOlder -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.padding(horizontal = 6.dp))
            Text(
                text = "Loading older messages...",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        hasOlder -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onTap)
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Load older messages",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Sub-agents chip row (F-phone).
 *
 * Sits between the messages LazyColumn and the compose row. Hidden by the
 * caller when there is nothing to show. Renders:
 *   - the "Parent: <title>" chip first when the current session has a
 *     parent (tap → navigate up to the parent session),
 *   - then one chip per child session, in created_at ASC order — tap →
 *     navigate down to the child session.
 *
 * Each chip carries a small state icon (idle / running / awaiting / errored)
 * and the child's running token total, abbreviated.
 *
 * Material 3's `material-icons-core` artifact ships a tight subset of
 * icons — `Outlined.Circle`, `Filled.HourglassEmpty`, and `Filled.ErrorOutline`
 * aren't in it (they live in `material-icons-extended`, which adds ~3 MB to
 * the APK and isn't worth the size for a single row). We use available
 * stand-ins: outlined check / play-arrow / warning / clear.
 */
@Composable
private fun SubAgentChipRow(
    parentId: String?,
    parentTitle: String?,
    children: List<SessionSummary>,
    onChipTap: (sessionId: String) -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (parentId != null) {
                item(key = "parent-$parentId") {
                    AssistChip(
                        onClick = { onChipTap(parentId) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                modifier = Modifier.size(AssistChipDefaults.IconSize),
                            )
                        },
                        label = {
                            // Cap title length similar to child chips so a
                            // long parent title doesn't dominate the row.
                            val raw = parentTitle ?: "Parent"
                            Text(
                                text = "Parent: ${raw.take(20)}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
            items(children, key = { it.id }) { child ->
                ChildChip(child = child, onClick = { onChipTap(child.id) })
            }
        }
    }
}

@Composable
private fun ChildChip(child: SessionSummary, onClick: () -> Unit) {
    val displayState = child.state.displayState()
    val (icon, tint) = stateIconAndTint(displayState)
    AssistChip(
        onClick = onClick,
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
        label = {
            // Title gets the lion's share of the budget; tokens append
            // when the server reported a count (F-server). The 24-char
            // cap matches the spec — wide enough for a meaningful title
            // on a phone, narrow enough to keep 3-4 chips visible.
            val title = child.title.ifBlank { "(untitled)" }.take(24)
            val tokens = child.totalTokens?.let { " · ${abbreviateTokens(it)}" }.orEmpty()
            Text(
                text = title + tokens,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/**
 * Pick the leading icon + tint for a chip based on the child session's
 * classified [DisplayState]. The colors lean on theme containers so they
 * adapt to light/dark; primary is reused as "in progress" green/blue
 * because we don't have a `green` slot on Material 3 by default.
 */
@Composable
private fun stateIconAndTint(state: DisplayState): Pair<androidx.compose.ui.graphics.vector.ImageVector, Color> {
    return when (state) {
        DisplayState.Idle -> Icons.Outlined.CheckCircle to MaterialTheme.colorScheme.onSurfaceVariant
        DisplayState.Running -> Icons.Filled.PlayArrow to MaterialTheme.colorScheme.primary
        // Stopping: still busy from the UI's perspective; hourglass cues the
        // transient "settling the cancel" state without reusing the play
        // icon (which would read as "still running").
        DisplayState.Stopping -> Icons.Outlined.HourglassEmpty to MaterialTheme.colorScheme.primary
        DisplayState.AwaitingInput -> Icons.Filled.Warning to MaterialTheme.colorScheme.tertiary
        DisplayState.Errored -> Icons.Filled.Clear to MaterialTheme.colorScheme.error
        DisplayState.Unknown -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/**
 * Format a running token total compactly:
 *   - `< 1_000` → bare number (`"42"`).
 *   - `< 1_000_000` → `"k"` with one decimal place (`"138.3k"`).
 *   - else → `"M"` with one decimal place (`"1.2M"`).
 *
 * Uses [String.format] with the root locale so a comma decimal separator
 * doesn't leak in for users in locales like ru-RU; the chip label is a
 * compact technical readout, not a localized currency.
 */
/**
 * Mirror of the desktop `solution_agent::status_row::format_elapsed`
 * helper. Output strings match verbatim so a session viewed on both
 * surfaces shows the same elapsed string:
 *   - `secs < 60` → `"Xs"` (e.g. `"4s"`),
 *   - `60 <= secs < 3600` → `"MmSSs"` (e.g. `"1m02s"`),
 *   - `secs >= 3600` → `"HhMMm"` (e.g. `"1h05m"`).
 *
 * `String.format` with the root locale keeps the `%02d` zero-padding
 * culture-neutral (a comma decimal separator would never apply to an
 * integer specifier, but the locale choice is still good hygiene for a
 * compact technical readout).
 */
/** C3: wall-clock `HH:MM` for a message's `created_ms`, device-local zone. */
internal fun formatHm(epochMs: Long): String {
    val time = java.time.Instant.ofEpochMilli(epochMs)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalTime()
    return String.format(java.util.Locale.ROOT, "%02d:%02d", time.hour, time.minute)
}

/** C3: "Today" / "Yesterday" / localized medium date for a separator row. */
internal fun formatDateSeparator(epochDay: Long, today: java.time.LocalDate): String {
    val date = java.time.LocalDate.ofEpochDay(epochDay)
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> java.time.format.DateTimeFormatter
            .ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
            .withLocale(java.util.Locale.getDefault())
            .format(date)
    }
}

internal fun formatElapsed(secs: Long): String {
    val s = if (secs < 0L) 0L else secs
    return when {
        s < 60L -> "${s}s"
        s < 3600L -> String.format(java.util.Locale.ROOT, "%dm%02ds", s / 60L, s % 60L)
        else -> String.format(java.util.Locale.ROOT, "%dh%02dm", s / 3600L, (s % 3600L) / 60L)
    }
}

/**
 * Live "Xs" badge rendered next to the [StatePill] in the chat top bar
 * while the session is in `Running` state. Ticks once per second from
 * [stateStartedAtMs], a wall-clock anchor populated server-side from
 * the monotonic `SessionState::Running { started_at: Instant }`.
 *
 * Renders nothing when state isn't Running OR when the server omits
 * the anchor (pre-`state_started_at_ms` build). Mirrors the per-tool
 * elapsed badge in [ToolCallBubble] both visually and in tick-cancel
 * semantics — the [LaunchedEffect] rekeys on `displayState`, so the
 * coroutine exits the moment the agent reports Idle / AwaitingInput /
 * Errored.
 */
@Composable
internal fun RunningElapsed(displayState: DisplayState, stateStartedAtMs: Long?) {
    if (displayState != DisplayState.Running || stateStartedAtMs == null) return
    var elapsedSeconds by remember(stateStartedAtMs) {
        mutableLongStateOf(
            ((System.currentTimeMillis() - stateStartedAtMs) / 1000L).coerceAtLeast(0L)
        )
    }
    LaunchedEffect(stateStartedAtMs, displayState) {
        if (displayState != DisplayState.Running) return@LaunchedEffect
        while (true) {
            delay(1000L)
            elapsedSeconds = ((System.currentTimeMillis() - stateStartedAtMs) / 1000L)
                .coerceAtLeast(0L)
        }
    }
    Text(
        text = formatElapsed(elapsedSeconds),
        style = MaterialTheme.typography.labelSmall,
    )
}

/**
 * "Last activity" relative-time label in the [SlimTopBar], next to the
 * [StatePill] / [RunningElapsed]. Shows e.g. `8 мин назад` (device locale)
 * for the newest chat entry's wall-clock time, so a stalled agent
 * (Running but no recent activity) is obvious at a glance.
 *
 * - Relative formatting is delegated to Android's localized
 *   [DateUtils.getRelativeTimeSpanString] (minute granularity).
 * - A `now` state ticks every ~15s so the label stays current without a
 *   per-second redraw; the coroutine rekeys on [lastActivityMs] so a
 *   fresh entry restarts the clock cleanly.
 * - Long-press shows a [TooltipBox] with the ABSOLUTE localized date-time,
 *   built from [java.text.DateFormat.getDateTimeInstance] (matches the
 *   formatting style already used in CrashLogsScreen).
 *
 * Renders nothing when [lastActivityMs] is null (no real timestamp /
 * empty session) so the bar looks exactly as before.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LastActivityLabel(lastActivityMs: Long?) {
    if (lastActivityMs == null) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lastActivityMs) {
        while (true) {
            delay(15_000L)
            now = System.currentTimeMillis()
        }
    }
    val relative = android.text.format.DateUtils.getRelativeTimeSpanString(
        lastActivityMs,
        now,
        android.text.format.DateUtils.MINUTE_IN_MILLIS,
        android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
    val absolute = java.text.DateFormat.getDateTimeInstance()
        .format(java.util.Date(lastActivityMs))
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            androidx.compose.material3.TooltipAnchorPosition.Above,
        ),
        tooltip = { PlainTooltip { Text(absolute) } },
        state = rememberTooltipState(),
    ) {
        Text(
            text = relative,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 88.dp),
        )
    }
}

/**
 * Slim context-fill meter rendered in [SlimTopBar] between the title and
 * the [StatePill]. Shows `used / max` token usage as a 64dp linear
 * progress bar plus a percent label.
 *
 * Renders nothing when either value is missing so a pre-`max_tokens`
 * server build or an agent that hasn't reported usage yet doesn't leave
 * an empty pill cluttering the bar.
 *
 * Color thresholds match the desktop's `status_row` pattern:
 *   - ≥80% used → `error` (red zone, near context exhaustion);
 *   - 50-80% → `tertiary` (warning-ish — M3 has no dedicated warning
 *     slot, tertiary is the closest semantic approximation);
 *   - <50% → `primary` (healthy headroom).
 */
@Composable
private fun ContextFillMeter(totalTokens: Long?, maxTokens: Long?) {
    // Sleeping / restored sessions arrive with `maxTokens == null` (the
    // desktop never restores `cached_max_tokens` from disk), so resolve a
    // default window instead of blanking the meter — see [ContextFill].
    val fill = ContextFill.compute(totalTokens, maxTokens) ?: return
    val fraction: Float = fill.fraction
    val percent: Int = fill.percent
    val color: Color = when {
        fraction >= 0.80f -> MaterialTheme.colorScheme.error
        fraction >= 0.50f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(horizontal = 4.dp),
    ) {
        LinearProgressIndicator(
            progress = { fraction },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            // 48dp (was 64): trims the trailing cluster so the top-bar
            // title + meter + pill + elapsed + overflow icon are less
            // likely to overflow / clip the overflow button on narrow
            // phones. Title already yields first via weight(1f).
            modifier = Modifier.width(48.dp),
        )
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun abbreviateTokens(n: Long): String {
    if (n < 0) return n.toString()
    return when {
        n < 1_000L -> n.toString()
        n < 1_000_000L -> String.format(java.util.Locale.ROOT, "%.1fk", n / 1_000.0)
        else -> String.format(java.util.Locale.ROOT, "%.1fM", n / 1_000_000.0)
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

/**
 * Horizontally-scrollable strip of pills under the top app bar — one
 * per transcript stream (wire schema v3), with Main first (its own
 * `kind = main` pill; no separate hardcoded "Main" pill). Hidden entirely
 * when only Main exists so a plain conversation looks unchanged.
 *
 * Order is the server's `streams` list — we iterate as-is and never
 * re-sort, so Main stays leftmost and a freshly-spawned teammate/shell
 * stream appears at the tail regardless of label.
 */
// `internal` (not `private`) so the per-source-streams render can be verified
// offscreen by a Roborazzi snapshot test — mirrors how `WorkspaceListContent`
// is testable. No behavioural change.
@Composable
internal fun SubagentTabStrip(
    streams: List<StreamDto>,
    selected: StreamIdDto,
    onSelect: (StreamIdDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Only Main present → nothing to switch between; hide the strip
    // (matches the old "no sub-agents in flight" hide).
    if (streams.size <= 1) return
    val scrollState = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (stream in streams) {
            SubagentPill(
                label = stream.label,
                isActive = stream.id == selected,
                onClick = { onSelect(stream.id) },
            )
        }
    }
}

/**
 * ModalBottomSheet for Supervisor settings on the currently-open session.
 *
 * Shows:
 *  - An enabled/disabled Switch (calls [onToggleEnabled] immediately on
 *    toggle so the server is the source of truth; store does optimistic
 *    update + reload).
 *  - A multiline [OutlinedTextField] for the custom instruction prompt —
 *    saved via [onSavePrompt] when the user taps "Save"; a blank value
 *    passes null, clearing the custom prompt on the server.
 *  - A read-only stats block: status string, consecutive-continues meter,
 *    and a compact verdict count row (total · ↻continues ⚙compact ✓done ❗ask).
 *
 * [state] is null while the initial RPC is in flight — a loading indicator
 * is shown instead of the controls so the Switch doesn't flash its default
 * (false) state before the real value arrives.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SupervisorSettingsSheet(
    state: SupervisorStateDto?,
    onToggleEnabled: (Boolean) -> Unit,
    onSavePrompt: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var promptText by rememberSaveable { mutableStateOf(state?.customPrompt ?: "") }
    // Sync the local draft when the server state first arrives (i.e. on open).
    LaunchedEffect(state?.customPrompt) {
        if (state != null && promptText.isEmpty()) {
            promptText = state.customPrompt ?: ""
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Supervisor",
                style = MaterialTheme.typography.titleMedium,
            )
            if (state == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                // Enabled toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("Enabled", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = state.status.ifBlank { if (state.enabled) "active" else "off" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = state.enabled,
                        onCheckedChange = onToggleEnabled,
                    )
                }
                // Stats block
                if (state.verdictsTotal > 0 || state.consecutiveContinues > 0) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Continues: ${state.consecutiveContinues} / ${state.maxContinues}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = buildString {
                                append("Verdicts: ${state.verdictsTotal}")
                                if (state.verdictsContinue > 0) append(" · ↻${state.verdictsContinue}")
                                if (state.verdictsCompact > 0) append(" · ⚙${state.verdictsCompact}")
                                if (state.verdictsDone > 0) append(" · ✓${state.verdictsDone}")
                                if (state.verdictsAsk > 0) append(" · ❗${state.verdictsAsk}")
                                if (state.verdictsAskAgent > 0) append(" · 💬${state.verdictsAskAgent}")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Custom prompt editor
                Text("Custom instruction", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp),
                    placeholder = { Text("Leave blank to use the default prompt") },
                    minLines = 3,
                    maxLines = 8,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = {
                        onSavePrompt(promptText.trim().takeIf { it.isNotEmpty() })
                        onDismiss()
                    }) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubagentPill(label: String, isActive: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                       else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}


