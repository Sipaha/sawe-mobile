package ru.sipaha.sawe.app.ui.solutions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.runtime.derivedStateOf
import ru.sipaha.sawe.app.vm.MainViewModel
import ru.sipaha.sawe.app.vm.UiData
import ru.sipaha.sawe.core.AgentSummary
import ru.sipaha.sawe.core.SolutionMember

/**
 * "New session" dialog used by the create-session flow. Launched from the
 * workspace screen's per-solution "new session" affordance
 * (`onCreateNewSessionFor`).
 *
 * Flow:
 *  1. On mount, [MainViewModel.loadAgents] runs once and populates the
 *     agent picker. Loading shows a spinner; an empty result disables the
 *     Create button and shows a "no adapters available" message; a
 *     transport error reports the message and lets the user dismiss.
 *  2. The user picks an agent (first agent is auto-selected on Loaded so
 *     the common case is "tap Create"). An optional multi-line initial
 *     message field forwards through as `create_session.initial_message`.
 *  3. Create dispatches [MainViewModel.createSession]. On success the
 *     dialog dismisses and navigates to the new session detail; on
 *     failure the error surfaces via the parent screen's snackbar (the
 *     dialog stays open so the user can adjust and retry).
 *
 * The dialog itself is intentionally a single column inside a Material 3
 * AlertDialog rather than a separate full-screen route — "new session"
 * is a quick one-shot decision and doesn't warrant its own nav entry.
 */
@Composable
fun NewSessionDialog(
    viewModel: MainViewModel,
    solutionId: String,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val agentsState by viewModel.agents.collectAsState()
    val solutionDetailsState by viewModel.solutionDetails.collectAsState()
    val inFlight by viewModel.createSessionInFlight.collectAsState()
    val autoOpened by viewModel.lastCreateAutoOpened.collectAsState()

    // Trigger loadAgents + loadSolutionDetails exactly once for this
    // dialog instance. We do NOT re-key on the state flows — that would
    // relaunch on every flip and thrash the server with duplicate calls.
    LaunchedEffect(Unit) {
        viewModel.loadAgents()
        viewModel.loadSolutionDetails(solutionId)
    }

    var selectedAgentId by rememberSaveable { mutableStateOf<String?>(null) }
    var initialMessage by rememberSaveable { mutableStateOf("") }
    var sessionTitle by rememberSaveable { mutableStateOf("") }
    var selectedCwd by rememberSaveable { mutableStateOf<String?>(null) }
    val loadedSolution = (solutionDetailsState as? UiData.Loaded)?.value?.solution
    val members: List<SolutionMember> = loadedSolution?.members.orEmpty()
    // Working-directory choices: member projects only. The solution root is
    // deliberately NOT offered — a session on mobile always runs inside one
    // project worktree. A solution with no members sends no `cwd` at all and
    // lets the server pick.
    val cwdOptions: List<CwdOption> = remember(members) { cwdOptionsFor(members) }
    // Default cwd: the first member project. The picker UI is hidden when
    // there's fewer than two choices.
    LaunchedEffect(members) {
        if (cwdOptions.none { it.path == selectedCwd }) {
            selectedCwd = members.firstOrNull()?.localPath
        }
    }

    // Pre-select the first agent when the list lands (or refreshes). We
    // re-run the auto-pick whenever the loaded list changes so a user
    // landing on Loaded → Error → Loaded gets a sensible default again.
    LaunchedEffect(agentsState) {
        val loaded = agentsState as? UiData.Loaded ?: return@LaunchedEffect
        val first = loaded.value.firstOrNull()
        if (selectedAgentId == null && first != null) {
            selectedAgentId = first.id
        } else if (selectedAgentId != null && loaded.value.none { it.id == selectedAgentId }) {
            // Server's adapter set changed underneath us — fall back to
            // the first available rather than leaving a stale id selected.
            selectedAgentId = first?.id
        }
    }

    val agentsLoaded = agentsState as? UiData.Loaded
    val hasAgents = agentsLoaded?.value?.isNotEmpty() == true
    val canCreate = !inFlight && hasAgents && selectedAgentId != null

    AlertDialog(
        onDismissRequest = { if (!inFlight) onDismiss() },
        title = { Text("New session") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Agent",
                    style = MaterialTheme.typography.labelLarge,
                )
                AgentPicker(
                    state = agentsState,
                    selectedId = selectedAgentId,
                    enabled = !inFlight,
                    onSelected = { selectedAgentId = it },
                )

                if (cwdOptions.size >= 2) {
                    Text(
                        text = "Working directory",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    CwdPicker(
                        options = cwdOptions,
                        selectedPath = selectedCwd,
                        enabled = !inFlight,
                        onSelected = { selectedCwd = it },
                    )
                }

                OutlinedTextField(
                    value = sessionTitle,
                    onValueChange = { sessionTitle = it },
                    label = { Text("Session name (optional)") },
                    enabled = !inFlight,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = initialMessage,
                    onValueChange = { initialMessage = it },
                    label = { Text("Initial message (optional)") },
                    enabled = !inFlight,
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (autoOpened) {
                    Text(
                        text = "Opened solution on desktop first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (inFlight) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val agentId = selectedAgentId ?: return@TextButton
                    val msg = initialMessage.trim().ifBlank { null }
                    val title = sessionTitle.trim().ifBlank { null }
                    val cwd = selectedCwd
                    viewModel.createSession(solutionId, agentId, msg, title, cwd, onCreated)
                },
                enabled = canCreate,
            ) {
                Text(if (inFlight) "Creating…" else "Create")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !inFlight,
            ) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Agent selection list with three render modes mapped from [UiData]:
 *
 *  - Loading: small inline spinner — list_agents is cheap, expected ≤1s.
 *  - Error: shows the message verbatim so the user sees transport issues
 *    (the server-side method is stateless; an error here is almost always
 *    "connection dropped" and a snackbar isn't needed on top).
 *  - Loaded empty: prose explaining that no adapters are registered.
 *  - Loaded populated: a stack of RadioButton rows. We don't use
 *    DropdownMenu here — there are typically only 1-3 adapters, so a
 *    visible radio list is faster to scan and one fewer tap.
 */
@Composable
private fun AgentPicker(
    state: UiData<List<AgentSummary>>,
    selectedId: String?,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    when (state) {
        is UiData.Loading -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.padding(end = 4.dp))
            Text(
                text = "Loading agents…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is UiData.Error -> Text(
            text = "Couldn't load agents: ${state.message}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )

        is UiData.Loaded -> if (state.value.isEmpty()) {
            Text(
                text = "No agents available — install an adapter on the desktop.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                for (agent in state.value) {
                    val isSelected = agent.id == selectedId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = isSelected,
                                enabled = enabled,
                                role = Role.RadioButton,
                                onClick = { onSelected(agent.id) },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = isSelected,
                            enabled = enabled,
                            onClick = { onSelected(agent.id) },
                        )
                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            Text(
                                text = agent.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = agent.id,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
    // Spacer so RadioButton rows don't kiss the OutlinedTextField directly.
    Box(modifier = Modifier.padding(top = 4.dp))
}

/** One working-directory choice: a human [label] and the [path] sent as `cwd`. */
internal data class CwdOption(val label: String, val path: String)

/**
 * Working-directory choices offered when creating a session: one per member
 * project, labelled by catalog id.
 *
 * The solution root is **not** a choice — a session started on mobile always
 * runs inside a single project worktree. A member-less solution yields an
 * empty list, and the dialog then sends no `cwd` at all (server decides).
 */
internal fun cwdOptionsFor(members: List<SolutionMember>): List<CwdOption> =
    members.map { CwdOption(label = it.catalogId, path = it.localPath) }

/**
 * Dropdown for selecting the new session's working directory. Options are
 * the solution's member projects — the solution root is not selectable; only
 * rendered when there's more than one choice.
 *
 * Implementation note: I tried OutlinedTextField(readOnly=true) with a
 * .selectable overlay first — but the TextField consumes touch events
 * internally even when read-only, so the overlay never sees the tap. Using
 * a clickable Surface styled like a field surface is the simplest robust
 * approach (and matches what M3 ExposedDropdownMenuBox builds under the
 * hood, without dragging in its trigger-anchor machinery for a
 * single-screen dialog). The full server-side path is never shown — it's
 * meaningless on the phone and only the label identifies the choice.
 */
@Composable
internal fun CwdPicker(
    options: List<CwdOption>,
    selectedPath: String?,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption by remember(selectedPath, options) {
        derivedStateOf {
            options.firstOrNull { it.path == selectedPath } ?: options.firstOrNull()
        }
    }
    Box(modifier = Modifier.fillMaxWidth()) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { expanded = true },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Directory",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = selectedOption?.label ?: "(none)",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Open directory picker",
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(),
        ) {
            for (option in options) {
                DropdownMenuItem(
                    text = {
                        Text(option.label, style = MaterialTheme.typography.bodyLarge)
                    },
                    onClick = {
                        expanded = false
                        onSelected(option.path)
                    },
                )
            }
        }
    }
}
