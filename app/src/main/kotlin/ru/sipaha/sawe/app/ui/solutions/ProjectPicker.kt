package ru.sipaha.sawe.app.ui.solutions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ru.sipaha.sawe.app.vm.MemberAddProgress
import ru.sipaha.sawe.core.CatalogProjectInfo

/**
 * Reusable project-picker body — mirrors the desktop "+" project picker
 * minus the git-clone-from-URL row (desktop-only on this fork). Used by
 * both the New Solution dialog and the Solution detail "+ Add project"
 * sheet.
 *
 * Renders as a [Column] so the caller owns the surrounding container
 * (AlertDialog text slot, bottom sheet, …). Two affordances:
 *   - a "Create new project" row that expands to a name field, calling
 *     [onCreateEmpty] with the trimmed name on confirm,
 *   - one checkbox row per [CatalogProjectInfo], toggling membership in
 *     [selected] via [onToggle].
 *
 * Selection is fully caller-owned ([selected] + [onToggle]) so the picker
 * stays stateless across recompositions and works identically whether the
 * target solution already exists or is about to be created.
 */
@Composable
fun ProjectPicker(
    catalog: List<CatalogProjectInfo>,
    selected: Set<Long>,
    onToggle: (catalogId: Long) -> Unit,
    onCreateEmpty: (String) -> Unit,
    modifier: Modifier = Modifier,
    // When non-null, each registry row gets a trash affordance that
    // removes the project from the catalog (after confirmation). The
    // server refuses while a solution still uses it.
    onRemoveCatalog: ((catalogId: Long) -> Unit)? = null,
) {
    var pendingRemove by remember { mutableStateOf<CatalogProjectInfo?>(null) }
    Column(modifier = modifier.fillMaxWidth()) {
        CreateEmptyProjectRow(onCreateEmpty = onCreateEmpty)

        if (catalog.isEmpty()) {
            Text(
                text = "No registry projects available. Add them in SPK Editor on your computer, or create a new empty project above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            for (project in catalog) {
                val checked = project.catalogId in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(project.catalogId) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { onToggle(project.catalogId) },
                    )
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .weight(1f),
                    )
                    if (onRemoveCatalog != null) {
                        IconButton(onClick = { pendingRemove = project }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Remove ${project.name} from catalog",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    val toRemove = pendingRemove
    if (toRemove != null && onRemoveCatalog != null) {
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove from catalog?") },
            text = {
                Text("Remove \"${toRemove.name}\" from the project registry. Solutions already using it keep their files; the server won't remove it while a solution still references it.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveCatalog(toRemove.catalogId)
                        pendingRemove = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CreateEmptyProjectRow(onCreateEmpty: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }

    if (!expanded) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(
                text = "Create new project",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        return
    }

    val confirm: () -> Unit = {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) {
            onCreateEmpty(trimmed)
            name = ""
            expanded = false
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("New project name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { confirm() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { name = ""; expanded = false }) { Text("Cancel") }
            TextButton(onClick = confirm, enabled = name.isNotBlank()) { Text("Add") }
        }
    }
}

/**
 * One in-flight / failed member-add rendered as a ghost row: a small
 * spinner + the project name + a progress/stage line, or an error. Shared
 * by the Solution detail header (create-with-projects progress) and the
 * Projects panel.
 */
@Composable
fun MemberAddGhostRow(add: MemberAddProgress, displayName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (add.error == null) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        }
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(text = displayName, style = MaterialTheme.typography.bodyMedium)
            val sub = when {
                add.error != null -> add.error
                add.percent != null -> "${add.stage ?: "Cloning"}… ${add.percent}%"
                else -> "${add.stage ?: "Cloning"}…"
            }
            Text(
                text = sub,
                style = MaterialTheme.typography.labelSmall,
                color = if (add.error != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * "Add project" dialog hosting [ProjectPicker] — accumulates selected
 * registry projects + queued new empty-project names, then fires
 * [onAdd] once on confirm. Shared between the New Solution flow and the
 * Projects panel.
 */
@Composable
fun AddProjectDialog(
    catalog: List<CatalogProjectInfo>,
    onAdd: (catalogIds: List<Long>, emptyNames: List<String>) -> Unit,
    onDismiss: () -> Unit,
    onRemoveCatalog: ((catalogId: Long) -> Unit)? = null,
) {
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var emptyNames by remember { mutableStateOf(emptyList<String>()) }
    val hasSelection = selected.isNotEmpty() || emptyNames.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add project") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (pending in emptyNames) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "New: $pending",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { emptyNames = emptyNames - pending }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove $pending")
                        }
                    }
                }
                ProjectPicker(
                    catalog = catalog,
                    selected = selected,
                    onToggle = { id ->
                        selected = if (id in selected) selected - id else selected + id
                    },
                    onCreateEmpty = { newName ->
                        if (newName !in emptyNames) emptyNames = emptyNames + newName
                    },
                    onRemoveCatalog = onRemoveCatalog,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(selected.toList(), emptyNames) },
                enabled = hasSelection,
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
