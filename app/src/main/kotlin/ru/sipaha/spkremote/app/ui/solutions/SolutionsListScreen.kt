package ru.sipaha.spkremote.app.ui.solutions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
) {
    val solutionsState by viewModel.solutions.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.refreshSolutions() }

    LaunchedEffect(solutionsState) {
        val current = solutionsState
        if (current is UiData.Error) {
            snackbarHostState.showSnackbar(current.message)
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
                        body = "Open a solution in SPK Editor on your computer to see it here.",
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(s.value, key = { it.id }) { solution ->
                            SolutionRow(
                                solution = solution,
                                onClick = { onOpenSolution(solution) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SolutionRow(solution: SolutionSummary, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = solution.name,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = solution.root,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val memberLabel = "${solution.memberCount} ${if (solution.memberCount == 1) "member" else "members"}"
        val windowLabel = if (solution.windowOpen) "open" else "closed"
        Text(
            text = "$memberLabel | $windowLabel",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
