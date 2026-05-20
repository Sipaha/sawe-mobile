package ru.sipaha.spkremote.app.ui.solutions

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.sipaha.spkremote.app.vm.MainViewModel
import ru.sipaha.spkremote.app.vm.UiData
import ru.sipaha.spkremote.core.SolutionSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolutionsListScreen(
    viewModel: MainViewModel,
    onOpenSolution: (SolutionSummary) -> Unit,
    onOpenSolutionById: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val solutionsState by viewModel.solutions.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshSolutions() }

    LaunchedEffect(solutionsState) {
        val current = solutionsState
        if (current is UiData.Error) {
            snackbarHostState.showSnackbar(current.message)
        }
    }

    // The create / delete RPCs surface failures through the shared
    // error channel; mirror the SolutionDetailScreen pattern so the
    // user sees them as a snackbar instead of silent no-ops.
    LaunchedEffect(Unit) {
        viewModel.sendError.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Solutions") },
                actions = {
                    IconButton(onClick = { viewModel.refreshSolutions() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New solution") },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val s = solutionsState) {
                is UiData.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                is UiData.Error -> EmptyState(
                    title = "Couldn't load solutions",
                    body = s.message,
                )

                is UiData.Loaded -> if (s.value.isEmpty()) {
                    EmptyState(
                        title = "No solutions open",
                        body = "Tap \"New solution\" to create one, or open an existing solution in SPK Editor on your computer.",
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        // Clear the gesture nav bar + the FAB so the last
                        // row stays tappable instead of sitting under them.
                        contentPadding = PaddingValues(
                            bottom = 88.dp +
                                WindowInsets.navigationBars.asPaddingValues()
                                    .calculateBottomPadding(),
                        ),
                    ) {
                        items(s.value, key = { it.id }) { solution ->
                            SolutionRow(
                                solution = solution,
                                onClick = { onOpenSolution(solution) },
                                onDelete = { viewModel.deleteSolution(solution.id) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }

        if (showCreateDialog) {
            CreateSolutionDialog(
                onCreate = { name ->
                    showCreateDialog = false
                    // Create empty, then land on the solution screen where
                    // projects are added via the Projects panel.
                    viewModel.createSolutionWith(name, emptyList(), emptyList()) { newId ->
                        onOpenSolutionById(newId)
                    }
                },
                onDismiss = { showCreateDialog = false },
            )
        }
    }
}

@Composable
private fun SolutionRow(
    solution: SolutionSummary,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    // Confirm via a dialog (not an inline button swap): the old inline
    // "Delete? Yes/Cancel" replaced the 48dp icon with a wider/taller Row,
    // reflowing the whole row + the divider below it. A dialog keeps the
    // row geometry stable and matches the destructive-confirm pattern used
    // for server removal.
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
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = solution.name,
                style = MaterialTheme.typography.titleMedium,
            )
            // No `solution.root`: the server-side filesystem path is
            // meaningless on the phone (and leaks server layout). Name +
            // member/window summary is enough to identify the solution.
            val memberLabel = "${solution.memberCount} ${if (solution.memberCount == 1) "member" else "members"}"
            val windowLabel = if (solution.windowOpen) "open" else "closed"
            Text(
                text = "$memberLabel | $windowLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { confirmDelete = true }) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete solution",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete solution?") },
            text = { Text("Delete the solution \"${solution.name}\" from SPK Editor. The member projects' files on the computer are kept.") },
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
private fun CreateSolutionDialog(
    onCreate: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val submit: () -> Unit = {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            validationError = "Name can't be empty"
        } else {
            onCreate(trimmed)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New solution") },
        // Name only — projects are added afterwards on the solution's
        // Projects panel. Keeps creation a one-field decision.
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    if (validationError != null) validationError = null
                },
                label = { Text("Solution name") },
                singleLine = true,
                isError = validationError != null,
                supportingText = {
                    val message = validationError
                    if (message != null) Text(message)
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
        confirmButton = { TextButton(onClick = submit) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyState(title: String, body: String) {
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
