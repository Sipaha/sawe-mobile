package ru.sipaha.spkremote.app.ui.solutions

import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import ru.sipaha.spkremote.app.vm.MainViewModel
import ru.sipaha.spkremote.app.vm.UiData
import ru.sipaha.spkremote.core.DisplayState
import ru.sipaha.spkremote.core.EntryImage
import ru.sipaha.spkremote.core.EntryRole
import ru.sipaha.spkremote.core.EntrySummary
import ru.sipaha.spkremote.core.GetSessionResult
import ru.sipaha.spkremote.core.PlanSummary
import ru.sipaha.spkremote.core.SessionSummary
import ru.sipaha.spkremote.core.ToolCallSummary
import ru.sipaha.spkremote.core.parseDisplayState
import ru.sipaha.spkremote.core.parseEntryRole
import ru.sipaha.spkremote.core.stripRoleHeading

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
    val cancelInFlight by viewModel.cancelInFlight.collectAsState()
    val isLoadingOlder by viewModel.isLoadingOlder.collectAsState()
    val childrenMap by viewModel.sessionChildren.collectAsState()
    val sessionsList by viewModel.sessions.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    DisposableEffect(sessionId) {
        viewModel.openSession(sessionId)
        onDispose { viewModel.closeSession() }
    }

    LaunchedEffect(Unit) {
        viewModel.sendError.collect { msg -> snackbarHostState.showSnackbar(msg) }
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

    val displayTitle: String = (sessionState as? UiData.Loaded)?.value?.title?.ifBlank { "Session" }
        ?: "Session"
    val displayState: DisplayState = (sessionState as? UiData.Loaded)?.value
        ?.let { parseDisplayState(it.state) } ?: DisplayState.Unknown
    val rawState: String = (sessionState as? UiData.Loaded)?.value?.state ?: ""

    // F-phone chip row inputs.
    //
    // `parentId` is read from the loaded session (`GetSessionResult` now
    // carries `parent_session_id`). The parent's *title* — the label we
    // want to render on the chip — isn't in that payload, so we cross-
    // reference `_sessions` (the list-of-sessions cache for the active
    // solution). When the user deep-linked into a child whose parent
    // isn't in that cache, fall back to a short hash of the id.
    val loadedSession: GetSessionResult? = (sessionState as? UiData.Loaded)?.value
    val parentId: String? = loadedSession?.parentSessionId
    val sessionsCache: List<SessionSummary> = (sessionsList as? UiData.Loaded)?.value.orEmpty()
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
    Scaffold(
        topBar = {
            SlimTopBar(
                title = displayTitle,
                onBack = onBack,
                onTitleClick = { if (sessionState is UiData.Loaded) showRenameDialog = true },
                trailing = {
                    if (sessionState is UiData.Loaded) {
                        StatePill(state = displayState, raw = rawState)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column {
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
                    onSend = { text ->
                        viewModel.sendMessage(text)
                        // On send, the regular draft is invalidated. We
                        // optimistically clear here so a quick re-open
                        // doesn't repopulate the field even before the
                        // server echo lands. Bounce-on-failure is handled
                        // separately by handleExpiredMessage.
                        viewModel.clearDraft(sessionId)
                    },
                    onCancel = viewModel::cancelTurn,
                    rawState = rawState,
                    sessionId = sessionId,
                    initialDraft = seedText,
                    seedLoaded = seedLoaded,
                    onDraftChanged = { text -> viewModel.saveDraft(sessionId, text) },
                )
            }
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
                    isLoadingOlder = isLoadingOlder,
                    onRequestOlder = { viewModel.loadOlder(sessionId) },
                )
            }
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
    isLoadingOlder: Boolean,
    onRequestOlder: () -> Unit,
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

    // R-6e: are there older entries we haven't loaded yet?
    //   - The oldest currently-loaded entry has index N (or -1 sentinel
    //     from a pre-R-6e server, which means "we got the whole thing
    //     in one shot and pagination doesn't apply").
    //   - If N > 0, there are N older entries on the server we can fetch.
    //   - If N <= 0, we've reached the start of history.
    val oldestLoadedIndex: Int = server.entries.firstOrNull()?.index ?: -1
    val hasOlder: Boolean = oldestLoadedIndex > 0

    // Auto-trigger when the user scrolls towards the top of loaded history.
    // With reverseLayout = true and our `combined.asReversed()` rendering,
    // the LAST item in the lazy list (highest index) is the OLDEST entry —
    // so "near top of history" = firstVisibleItemIndex within a small
    // window of totalItemsCount. `distinctUntilChanged()` keeps the
    // collector quiet between identical pairs (e.g. recompositions that
    // didn't actually shift the viewport).
    LaunchedEffect(lazyState, hasOlder) {
        if (!hasOlder) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow {
            val info = lazyState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible to info.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (lastVisible, total) ->
                // Trigger when the oldest-area sentinel is within ~5 items
                // of the viewport bottom-of-history (= reverse-layout top).
                // `total - 5` keeps the trigger one screen ahead of the
                // user hitting the literal end — gives the fetch a head
                // start so the user perceives near-instant loading.
                if (total > 0 && lastVisible >= total - 5 && !isLoadingOlder) {
                    onRequestOlder()
                }
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
                // R-6e: history-edge affordance. Sits at the LOGICAL TOP
                // of the visible list (= last item in the reverse-layout
                // LazyColumn). Two states:
                //   - hasOlder && isLoadingOlder: spinner row.
                //   - hasOlder && !isLoadingOlder: "Load older" tappable.
                //     The scroll trigger usually fires this automatically,
                //     but the explicit tap gives the user a way to recover
                //     if the trigger missed (e.g. fling stopped just shy
                //     of the trigger window).
                item("history-edge") {
                    HistoryEdgeRow(
                        isLoadingOlder = isLoadingOlder,
                        hasOlder = hasOlder,
                        onTap = onRequestOlder,
                    )
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
        EntryRole.User -> UserBubble(entry = entry)
        EntryRole.Assistant -> {
            // Skip assistant turns whose body is effectively invisible:
            //  - tool-call-only turns produce `## Assistant\n\n\n\n` (no
            //    chunks at all);
            //  - thought-only turns produce `## Assistant\n\n<thinking>…
            //    </thinking>\n\n` and our markdown widget silently
            //    swallows the unknown HTML tag, drawing a padded gray
            //    rectangle with nothing in it.
            // Strip the role banner, strip `<thinking>…</thinking>`
            // blocks, then check what's left.
            if (hasVisibleAssistantBody(entry.markdown ?: entry.preview)) {
                AssistantBubble(entry = entry)
            }
        }
        EntryRole.ToolCall -> {
            val tc = entry.toolCall
            if (tc != null) {
                ToolCallBubble(call = tc, positionKey = entry.index)
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
        EntryRole.Plan -> {
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
private fun UserBubble(entry: EntrySummary) {
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
            modifier = Modifier.widthIn(max = 360.dp),
        ) {
            // Users overwhelmingly send plain text — but accept markdown when
            // the server returns it (e.g. a paste from a markdown source).
            // The pinned light-on-primary palette would clobber inline-code
            // colours, so for user bubbles we never run the markdown
            // renderer (stays as legible plain Text). Strip the upstream
            // `## User` header so bubbles don't echo what alignment+color
            // already encode.
            Text(
                text = stripRoleHeading(entry.markdown ?: entry.preview),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
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
            modifier = Modifier.widthIn(max = 360.dp),
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                val md = entry.markdown
                if (md != null) {
                    AssistantMarkdownBody(markdown = stripRoleHeading(md), images = entry.images.orEmpty())
                } else {
                    // No full markdown yet (placeholder during streaming, or
                    // pre-R-5e server). Falls back to preview to keep the
                    // bubble informative until the per-entry RPC settles.
                    Text(
                        text = stripRoleHeading(entry.preview),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/**
 * Render an assistant entry's full markdown body, plumbing in a custom
 * `ImageTransformer` so `spk-image://N` URLs in the markdown resolve to the
 * matching base64 blob from `entry.images`. The `multiplatform-markdown-
 * renderer-m3` library handles paragraph / code / list / link layout and
 * lets us override only the image fetch step.
 *
 * Tapping an inline image opens a full-screen `Dialog` with the original
 * pixel dimensions; long markdown bodies wrap naturally inside the bubble.
 */
@Composable
private fun AssistantMarkdownBody(markdown: String, images: List<EntryImage>) {
    var fullscreen by remember { mutableStateOf<EntryImage?>(null) }
    // Decode all images up-front. Sessions with many images may want a
    // lazier strategy, but in practice an assistant message rarely embeds
    // more than 2-3 — and decoding here means the Markdown renderer's
    // per-image transform() call is non-blocking + does no work on layout.
    val decoded: Map<Int, Painter> = remember(images) {
        images.associate { it.index to bitmapPainterFromBase64(it.dataBase64) }
    }
    val transformer = remember(images) { SpkImageTransformer(decoded, images) { fullscreen = it } }

    // Clamp markdown heading sizes. Library defaults map h1..h3 to
    // M3 `displayLarge` / `displayMedium` / `displaySmall` (≥36 sp), which
    // looks absurd inside a 360 dp chat bubble — and absolutely catastrophic
    // when the server-side `acp_thread` emits a `## Assistant` heading at the
    // top of every entry. Pin headings to the title/label scale so even
    // model-generated headings stay readable in the bubble.
    Markdown(
        content = markdown,
        // markdown-renderer 0.39.0 dropped the dedicated `codeText` param —
        // inline code text now inherits from `text`. Visual regression is
        // minor (code text loses its onSurface tint, picks up onSurfaceVariant
        // alongside body text); revisit when the lib exposes a finer-grained
        // theming API again.
        colors = markdownColor(
            text = MaterialTheme.colorScheme.onSurfaceVariant,
            codeBackground = MaterialTheme.colorScheme.surface,
        ),
        typography = markdownTypography(
            text = MaterialTheme.typography.bodyMedium,
            paragraph = MaterialTheme.typography.bodyMedium,
            code = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            inlineCode = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            h1 = MaterialTheme.typography.titleLarge,
            h2 = MaterialTheme.typography.titleMedium,
            h3 = MaterialTheme.typography.titleSmall,
            h4 = MaterialTheme.typography.labelLarge,
            h5 = MaterialTheme.typography.labelMedium,
            h6 = MaterialTheme.typography.labelSmall,
        ),
        imageTransformer = transformer,
    )

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

/**
 * Custom [ImageTransformer] that maps `spk-image://N` URLs to a decoded
 * base64 `Painter` carried in the [EntrySummary]. Unknown URLs return null
 * so the markdown renderer falls back to its own missing-image placeholder
 * (a blank box) — better than a hard crash on a missing image index.
 *
 * Only [transform] is overridden; `intrinsicSize` + `placeholderConfig`
 * inherit the library defaults.
 */
private class SpkImageTransformer(
    private val painters: Map<Int, Painter>,
    private val rawImages: List<EntryImage>,
    private val onClick: (EntryImage) -> Unit,
) : ImageTransformer {
    @Composable
    override fun transform(link: String): ImageData? {
        val index = link.removePrefix("spk-image://").toIntOrNull() ?: return null
        val painter = painters[index] ?: return null
        val image = rawImages.firstOrNull { it.index == index } ?: return null
        // Cap the long edge at 240dp. Tap → caller-supplied dialog.
        return ImageData(
            painter = painter,
            modifier = Modifier
                .sizeIn(maxWidth = 240.dp, maxHeight = 240.dp)
                .clickable { onClick(image) },
            contentDescription = "Inline image #$index",
            alignment = Alignment.Center,
            contentScale = ContentScale.Fit,
            alpha = 1f,
            colorFilter = null,
        )
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
    val bm = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
    return BitmapPainter(bm.asImageBitmap())
}

@Composable
private fun ToolCallBubble(call: ToolCallSummary, positionKey: Int) {
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
            modifier = Modifier
                .widthIn(max = 360.dp)
                .clickable { expanded = !expanded }
                .semantics {
                    role = Role.Button
                    contentDescription = contentDesc
                },
        ) {
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
    }
}

/**
 * Status pill colour-coded by the tool-call's documented status families.
 * Keeps the raw status label on the pill (spaces and all) so the user sees
 * the same phrase the editor logged.
 */
@Composable
private fun ToolStatusPill(status: String) {
    val (bg, fg) = when (status) {
        "done" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        "failed", "rejected" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        "running", "pending", "waiting for confirmation" ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        "canceled" -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.onSurface
    }
    Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.small) {
        Text(
            text = status,
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
 * still produces a non-empty markdown string. And a chunks list that
 * contains only `<thinking>` blocks looks non-blank but renders nothing
 * — our markdown widget treats `<thinking>` as raw HTML and drops it.
 *
 * Returns false when both filters strip everything away. The dispatch
 * site at [ChatBubble] skips the bubble entirely in that case, so the
 * user doesn't see a padded gray rectangle with no content inside.
 */
private fun hasVisibleAssistantBody(raw: String): Boolean {
    val stripped = stripRoleHeading(raw)
    return THINKING_BLOCK.replace(stripped, "").isNotBlank()
}

// `[\s\S]` matches across newlines so multi-line `<thinking>` payloads
// are removed in one pass. The lazy `*?` keeps each match minimal so two
// thinking blocks in the same body each shed independently.
private val THINKING_BLOCK = Regex(
    pattern = """<thinking>[\s\S]*?</thinking>""",
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
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
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
            trailing()
            Spacer(Modifier.padding(end = 8.dp))
        }
    }
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
private fun ComposeBar(
    enabled: Boolean,
    state: DisplayState,
    cancelInFlight: Boolean,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    rawState: String,
    sessionId: String,
    initialDraft: String,
    seedLoaded: Boolean,
    onDraftChanged: suspend (String) -> Unit,
) {
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
    val isRunning = state == DisplayState.Running
    val sendEnabled = enabled && !isRunning && draft.isNotBlank()
    val showCancel = isRunning

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
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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

/**
 * History-edge affordance rendered at the logical top of the chat list
 * (= LazyColumn's last item, with `reverseLayout = true`). Absence of
 * the "Load older messages" tap target already conveys "you're at the
 * start" — no separate sentinel is rendered for the start of history.
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
    val displayState = parseDisplayState(child.state)
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
