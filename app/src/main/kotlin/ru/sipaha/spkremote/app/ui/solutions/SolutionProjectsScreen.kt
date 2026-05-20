package ru.sipaha.spkremote.app.ui.solutions

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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.sipaha.spkremote.app.vm.MainViewModel
import ru.sipaha.spkremote.app.vm.UiData
import ru.sipaha.spkremote.core.SolutionMember

/**
 * Project (member) management panel for one Solution — the list reachable
 * by tapping the member count on [SolutionDetailScreen]. Shows each member
 * with its on-disk status, lets the user add catalog/empty projects and
 * remove existing ones, and renders in-flight clone progress as ghost rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolutionProjectsScreen(
    viewModel: MainViewModel,
    solutionId: String,
    onBack: () -> Unit,
) {
    val detailsState by viewModel.solutionDetails.collectAsState()
    val catalog by viewModel.catalog.collectAsState()
    val memberAdds by viewModel.memberAdds.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showAddProjectDialog by rememberSaveable { mutableStateOf(false) }

    val pendingAdds = memberAdds.values.filter { it.solutionId == solutionId }

    LaunchedEffect(solutionId) {
        viewModel.loadSolutionDetails(solutionId)
        viewModel.refreshCatalog()
    }
    LaunchedEffect(showAddProjectDialog) {
        if (showAddProjectDialog) viewModel.refreshCatalog()
    }
    LaunchedEffect(Unit) {
        viewModel.sendError.collect { snackbarHostState.showSnackbar(it) }
    }

    fun displayName(m: SolutionMember): String =
        catalog.firstOrNull { it.catalogId == m.catalogId }?.name ?: m.catalogId

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projects") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { showAddProjectDialog = true }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(text = "Add project", modifier = Modifier.padding(start = 4.dp))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val s = detailsState) {
                is UiData.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                is UiData.Error -> ProjectsMessage(
                    title = "Couldn't load projects",
                    body = s.message,
                )

                is UiData.Loaded -> {
                    val members = s.value.solution.members
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            bottom = WindowInsets.navigationBars.asPaddingValues()
                                .calculateBottomPadding(),
                        ),
                    ) {
                        items(pendingAdds, key = { "ghost-${it.catalogId}" }) { add ->
                            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                                MemberAddGhostRow(
                                    add = add,
                                    displayName = catalog.firstOrNull {
                                        it.catalogId == add.catalogId
                                    }?.name ?: add.catalogId,
                                )
                            }
                            HorizontalDivider()
                        }
                        if (members.isEmpty() && pendingAdds.isEmpty()) {
                            item {
                                ProjectsMessage(
                                    title = "No projects yet",
                                    body = "Tap \"Add project\" to clone a registry project or create a new empty one.",
                                )
                            }
                        } else {
                            items(members, key = { it.catalogId }) { member ->
                                ProjectRow(
                                    name = displayName(member),
                                    status = member.status,
                                    onRemove = {
                                        viewModel.removeMember(solutionId, member.catalogId)
                                    },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }

        if (showAddProjectDialog) {
            AddProjectDialog(
                catalog = catalog,
                onAdd = { catalogIds, emptyNames ->
                    showAddProjectDialog = false
                    catalogIds.forEach { viewModel.addMemberFromCatalog(solutionId, it) }
                    emptyNames.forEach { viewModel.createEmptyMember(solutionId, it) }
                },
                onDismiss = { showAddProjectDialog = false },
                onRemoveCatalog = { catalogId -> viewModel.removeCatalogProject(catalogId) },
            )
        }
    }
}

@Composable
private fun ProjectRow(
    name: String,
    status: String,
    onRemove: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = name, style = MaterialTheme.typography.titleMedium)
            if (status != "ok") {
                Text(
                    text = "missing on disk",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        IconButton(onClick = { confirmRemove = true }) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Remove project",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove project?") },
            text = {
                Text("Remove \"$name\" from this solution. Its files on the computer are not deleted.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        onRemove()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ProjectsMessage(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
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
