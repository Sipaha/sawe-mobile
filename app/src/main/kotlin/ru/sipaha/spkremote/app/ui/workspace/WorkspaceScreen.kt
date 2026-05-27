package ru.sipaha.spkremote.app.ui.workspace

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    onCreateNewSolution: () -> Unit,
    onCreateNewSessionFor: (solutionId: String) -> Unit,
) {
    val state by viewModel.workspaceState.collectAsState()
    // Picker visibility lives inside the screen now (E1): the FAB and
    // EmptyState both flip it true, and the sheet itself flips it back
    // false on dismiss / "Open" tap. Survives config-change via
    // rememberSaveable so a rotation mid-pick doesn't snap the sheet
    // shut.
    var showPicker by rememberSaveable { mutableStateOf(false) }

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
                onCreateNew = onCreateNewSolution,
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
                            onCreateNew = onCreateNewSolution,
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
                            onCreateNewSessionFor = onCreateNewSessionFor,
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
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(sol.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${sol.memberCount} ${if (sol.memberCount == 1) "member" else "members"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Solution menu")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(session.title.ifBlank { "(untitled session)" }, style = MaterialTheme.typography.titleSmall)
            Row {
                StatePill(state = session.state.displayState(), raw = "")
                Text(
                    text = " · ${relativeTime(session.lastActivityAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = { menuExpanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Session menu")
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
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
    }
}

@Composable
private fun LoadingState() = Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

@Composable
private fun ErrorState(msg: String) = Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) { Text(msg) }

@Composable
private fun StaleProgressBar() = LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

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
