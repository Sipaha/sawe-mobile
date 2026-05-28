package ru.sipaha.spkremote.app.ui.workspace

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.sipaha.spkremote.app.ui.solutions.NewSessionDialog
import ru.sipaha.spkremote.app.ui.solutions.StatePill
import ru.sipaha.spkremote.app.vm.MainViewModel
import ru.sipaha.spkremote.app.vm.OpenSessionVM
import ru.sipaha.spkremote.app.vm.OpenSolutionVM
import ru.sipaha.spkremote.app.vm.WorkspaceUiState
import ru.sipaha.spkremote.core.displayState

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun WorkspaceScreen(
    viewModel: MainViewModel,
    onOpenSession: (sessionId: String) -> Unit,
    onOpenProjects: (solutionId: String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.workspaceState.collectAsState()
    // Force a snapshot fetch every time the screen lands. The bulk RPC is
    // cheap (< 50 ms typical) and side-steps two desync paths that
    // otherwise leave the strip stale:
    //   - desktop restart → WS reconnect; the in-process `subscribe(...)`
    //     for `workspace.*` was a once-per-app-launch flag, so a desktop
    //     restart could lose the subscription on the new connection;
    //   - desktop opening additional solutions while the mobile was
    //     backgrounded but not yet hitting the foreground-resume hook.
    // Idempotent inside the store (snapshotMutex + `stale`-marked
    // refresh), so a duplicate fetch on legitimate-fresh state is harmless.
    LaunchedEffect(Unit) { viewModel.refreshWorkspace() }
    // Picker visibility lives inside the screen now (E1): the FAB and
    // EmptyState both flip it true, and the sheet itself flips it back
    // false on dismiss / "Open" tap. Survives config-change via
    // rememberSaveable so a rotation mid-pick doesn't snap the sheet
    // shut.
    var showPicker by rememberSaveable { mutableStateOf(false) }
    // F2 reintroduces the create-solution + new-session flows inside the
    // workspace. Both pieces of state survive config-change so a rotation
    // doesn't dismiss an in-progress dialog.
    var showCreateSolution by rememberSaveable { mutableStateOf(false) }
    var newSessionForSolution by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Workspace") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            ExpandableFab(
                onCreateNew = { showCreateSolution = true },
                onOpenPicker = { showPicker = true },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (val s = state) {
                WorkspaceUiState.Loading -> LoadingState()
                is WorkspaceUiState.Error -> ErrorState(s.message)
                is WorkspaceUiState.Loaded -> {
                    if (s.snapshot.solutions.isEmpty()) {
                        EmptyState(
                            onOpenPicker = { showPicker = true },
                            onCreateNew = { showCreateSolution = true },
                        )
                    } else {
                        if (s.stale) StaleProgressBar()
                        WorkspaceListContent(
                            solutions = s.snapshot.solutions,
                            onOpenSession = onOpenSession,
                            onOpenProjects = onOpenProjects,
                            onCloseSolution = { id -> viewModel.closeSolution(id) },
                            onDeleteSolution = { id -> viewModel.deleteSolution(id) },
                            onCloseSession = { id -> viewModel.closeSessionTab(id) },
                            onDeleteSession = { id -> viewModel.deleteSession(id) },
                            onCreateNewSessionFor = { solutionId ->
                                newSessionForSolution = solutionId
                            },
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        ClosedSolutionsPickerSheet(
            viewModel = viewModel,
            onDismiss = { showPicker = false },
        )
    }
    if (showCreateSolution) {
        CreateSolutionDialog(
            onDismiss = { showCreateSolution = false },
            onCreate = { name -> viewModel.createSolution(name) },
        )
    }
    newSessionForSolution?.let { solutionId ->
        NewSessionDialog(
            viewModel = viewModel,
            solutionId = solutionId,
            onDismiss = { newSessionForSolution = null },
            onCreated = { newSessionId ->
                newSessionForSolution = null
                onOpenSession(newSessionId)
            },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun WorkspaceListContent(
    solutions: List<OpenSolutionVM>,
    onOpenSession: (String) -> Unit = {},
    onOpenProjects: (String) -> Unit = {},
    onCloseSolution: (String) -> Unit = {},
    onDeleteSolution: (String) -> Unit = {},
    onCloseSession: (String) -> Unit = {},
    onDeleteSession: (String) -> Unit = {},
    onCreateNewSessionFor: (String) -> Unit = {},
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        solutions.forEach { sol ->
            stickyHeader(key = "header-${sol.id}") {
                SolutionHeader(
                    sol = sol,
                    onOpenProjects = { onOpenProjects(sol.id) },
                    onCloseSolution = { onCloseSolution(sol.id) },
                    onDeleteSolution = { onDeleteSolution(sol.id) },
                )
            }
            items(sol.sessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    onClick = { onOpenSession(session.id) },
                    onCloseConsole = { onCloseSession(session.id) },
                    onDeleteSession = { onDeleteSession(session.id) },
                )
            }
            item(key = "new-${sol.id}") {
                NewConsoleRow(onClick = { onCreateNewSessionFor(sol.id) })
            }
            item { HorizontalDivider() }
        }
    }
}

@Composable
private fun SolutionHeader(
    sol: OpenSolutionVM,
    onOpenProjects: () -> Unit,
    onCloseSolution: () -> Unit,
    onDeleteSolution: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Workspaces,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            // Wrap name + count in a weighted Row so the kebab gets pushed to
            // the very right edge. A flat `name.weight(1f, fill=false)` + a
            // trailing `Spacer.weight(1f)` LOOKED right but didn't deliver:
            // both weighted children get an equal share of remaining width,
            // and the slack a fill=false child leaves is not redistributed
            // to its weighted siblings — it just becomes dead space inside
            // the row, leaving the kebab adrift somewhere in the middle.
            // Nesting moves the weight allocation up one level, so the
            // kebab is laid out flush right regardless of name length.
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = sol.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${sol.memberCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                )
            }
            KebabMenuButton(
                contentDescription = "Solution menu",
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it },
            ) {
                DropdownMenuItem(text = { Text("Projects") }, onClick = { menuExpanded = false; onOpenProjects() })
                DropdownMenuItem(text = { Text("Close solution") }, onClick = { menuExpanded = false; confirmClose = true })
                DropdownMenuItem(text = { Text("Delete solution") }, onClick = { menuExpanded = false; confirmDelete = true })
            }
        }
    }
    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Close solution?") },
            text = { Text("Terminate the agents and terminals for \"${sol.name}\". Session conversations are preserved.") },
            confirmButton = { TextButton(onClick = { confirmClose = false; onCloseSolution() }) { Text("Close") } },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete solution?") },
            text = { Text("Delete \"${sol.name}\" and remove all its session conversations from the computer. This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmDelete = false; onDeleteSolution() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SessionRow(
    session: OpenSessionVM,
    onClick: () -> Unit,
    onCloseConsole: () -> Unit,
    onDeleteSession: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = 28.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Chat,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = session.title.ifBlank { "(untitled session)" },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // weight(1f) (fill = true) makes the title eat all remaining width
            // so the pill / time / kebab align flush to the right edge — without
            // it short titles let the meta cluster float in the middle.
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        StatePill(state = session.state.displayState(), raw = "")
        Spacer(Modifier.width(6.dp))
        Text(
            text = relativeTime(session.lastActivityAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        KebabMenuButton(
            contentDescription = "Session menu",
            expanded = menuExpanded,
            onExpandedChange = { menuExpanded = it },
        ) {
            DropdownMenuItem(text = { Text("Close console") }, onClick = { menuExpanded = false; onCloseConsole() })
            DropdownMenuItem(text = { Text("Delete session") }, onClick = { menuExpanded = false; confirmDelete = true })
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete session?") },
            text = { Text("Permanently delete \"${session.title.ifBlank { "this session" }}\" and its conversation. This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmDelete = false; onDeleteSession() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NewConsoleRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = 28.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Text("New console", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ExpandableFab(onCreateNew: () -> Unit, onOpenPicker: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.End) {
        if (expanded) {
            ExtendedFloatingActionButton(
                onClick = { expanded = false; onCreateNew() },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("New solution") },
                modifier = Modifier.padding(bottom = 8.dp),
            )
            ExtendedFloatingActionButton(
                onClick = { expanded = false; onOpenPicker() },
                icon = { Icon(Icons.Filled.FolderOpen, null) },
                text = { Text("Open closed solution…") },
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        FloatingActionButton(onClick = { expanded = !expanded }) {
            Icon(if (expanded) Icons.Filled.Close else Icons.Filled.Add, contentDescription = "Expand actions")
        }
    }
}

@Composable
internal fun EmptyState(onOpenPicker: () -> Unit = {}, onCreateNew: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No open solutions", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap + to create a new one or open an existing one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCreateNew) { Text("New solution") }
            OutlinedButton(onClick = onOpenPicker) { Text("Open closed solution") }
        }
    }
}

@Composable
private fun LoadingState() = Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

@Composable
private fun ErrorState(msg: String) = Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) { Text(msg) }

@Composable
private fun StaleProgressBar() = LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

/**
 * Compact 36-dp kebab trigger. [IconButton]'s baked-in 40 dp visual + 48 dp
 * touch target made every row at least 48 dp tall — too roomy for a dense
 * list. This is a manual clickable Box at 36 dp (still above Material's
 * 32 dp small-component threshold) plus its anchored [DropdownMenu].
 */
@Composable
private fun KebabMenuButton(
    contentDescription: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    menuContent: @Composable () -> Unit,
) {
    Box {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable { onExpandedChange(true) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            menuContent()
        }
    }
}

/**
 * Format `last_activity_at` (epoch millis) into a relative string like "5m ago".
 * Private to this screen — `ServersListScreen` keeps its own copy with the
 * same shape; YAGNI extraction left for if/when a third copy lands.
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
