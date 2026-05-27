package ru.sipaha.spkremote.app.ui.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.sipaha.spkremote.app.vm.ClosedSolutionRow
import ru.sipaha.spkremote.app.vm.MainViewModel
import ru.sipaha.spkremote.app.vm.UiData

/**
 * Bottom sheet shown when the user taps "Open closed solution…" on the
 * Workspace FAB. Lazily fetches the closed-solutions list on first
 * compose (the WorkspaceStore otherwise leaves it `Loading`), surfaces
 * load/error/empty states, and lets the user re-open or delete a row.
 *
 * Tapping "Open" fires the optimistic [MainViewModel.openSolution]
 * wrapper and then dismisses the sheet — the delta from the server
 * lands shortly after and folds the row into the open snapshot.
 * Delete uses the destructive [MainViewModel.deleteSolution] flow
 * (same as the workspace row menu); it is intentionally NOT followed
 * by an automatic dismiss so the user can verify the row disappears
 * from the picker before closing it themselves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClosedSolutionsPickerSheet(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    val closed by viewModel.closedSolutions.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshClosedSolutions() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        ClosedSolutionsPickerSheetContent(
            state = closed,
            onOpen = { id -> viewModel.openSolution(id); onDismiss() },
            onDelete = { id -> viewModel.deleteSolution(id) },
        )
    }
}

/**
 * Body of the picker sheet, extracted from the public composable so
 * Roborazzi can render it inside a plain `MaterialTheme { Surface }`
 * host without depending on the real [ModalBottomSheet] (which won't
 * lay out under Robolectric — needs a window decor / dialog host).
 */
@Composable
internal fun ClosedSolutionsPickerSheetContent(
    state: UiData<List<ClosedSolutionRow>>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Open a closed solution", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        when (state) {
            is UiData.Loading -> Box(
                modifier = Modifier.fillMaxWidth().height(80.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            is UiData.Error -> Text("Couldn't load: ${state.message}")
            is UiData.Loaded -> if (state.value.isEmpty()) {
                Text("No closed solutions on this server.")
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(state.value, key = { it.id }) { row ->
                        ClosedSolutionRowComposable(
                            row = row,
                            onOpen = { onOpen(row.id) },
                            onDelete = { onDelete(row.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/**
 * Test-only overload — accepts a raw `List<ClosedSolutionRow>` so the
 * Roborazzi golden doesn't have to construct a [UiData.Loaded] wrapper
 * literal at the call site. Kept `internal` because it's only useful
 * for tests; production code goes through the [UiData]-shaped overload
 * above.
 */
@Composable
internal fun ClosedSolutionsPickerSheetContent(
    rows: List<ClosedSolutionRow>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
) = ClosedSolutionsPickerSheetContent(
    state = UiData.Loaded(rows),
    onOpen = onOpen,
    onDelete = onDelete,
)

@Composable
private fun ClosedSolutionRowComposable(
    row: ClosedSolutionRow,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = row.lastOpenedAt?.let { "last opened $it" }
                    ?: "${row.memberCount} member(s)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onOpen) { Text("Open") }
        IconButton(onClick = { menuExpanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Row menu")
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = { menuExpanded = false; onDelete() },
            )
        }
    }
}
