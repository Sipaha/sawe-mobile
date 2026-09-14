package ru.sipaha.sawe.app.ui.solutions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ru.sipaha.sawe.app.vm.MainViewModel
import ru.sipaha.sawe.app.vm.UiData
import ru.sipaha.sawe.core.AgentSummary

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
 *     the common case is "tap Create"). The session is created in the
 *     solution with a server-generated name and no initial message.
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
    solutionId: Long,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val agentsState by viewModel.agents.collectAsState()
    val inFlight by viewModel.createSessionInFlight.collectAsState()
    val autoOpened by viewModel.lastCreateAutoOpened.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadAgents()
    }

    var selectedAgentId by rememberSaveable { mutableStateOf<String?>(null) }

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

    NewSessionDialogContent(
        agentsState = agentsState,
        selectedAgentId = selectedAgentId,
        inFlight = inFlight,
        autoOpened = autoOpened,
        onSelected = { selectedAgentId = it },
        onDismiss = onDismiss,
        onCreate = {
            selectedAgentId?.let { viewModel.createSession(solutionId, it, onCreated) }
        },
    )
}

@Composable
internal fun NewSessionDialogContent(
    agentsState: UiData<List<AgentSummary>>,
    selectedAgentId: String?,
    inFlight: Boolean,
    autoOpened: Boolean,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
) {
    val canCreate = !inFlight &&
        (agentsState as? UiData.Loaded)?.value?.any { it.id == selectedAgentId } == true

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
                    text = "Provider",
                    style = MaterialTheme.typography.labelLarge,
                )
                AgentPicker(
                    state = agentsState,
                    selectedId = selectedAgentId,
                    enabled = !inFlight,
                    onSelected = onSelected,
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
                onClick = onCreate,
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
}
