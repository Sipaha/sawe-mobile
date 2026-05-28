package ru.sipaha.spkremote.app.ui.settings

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ru.sipaha.spkremote.app.BuildConfig
import ru.sipaha.spkremote.app.data.PairedServer
import ru.sipaha.spkremote.app.diagnostics.CrashLogger
import ru.sipaha.spkremote.app.vm.MainViewModel
import ru.sipaha.spkremote.core.ConnectionState
import ru.sipaha.spkremote.core.PairingUrl

/**
 * Settings / About screen. Reachable from the gear icon on
 * [ru.sipaha.spkremote.app.ui.workspace.WorkspaceScreen] and the
 * Servers list (R-6c-multi).
 *
 * Surfaces:
 *  - **Server info** — host:port + client name + fingerprint excerpt
 *    (first 8 + last 8 hex chars of the SHA-256 cert pin, monospace).
 *  - **Connection** — live [ConnectionState] from the underlying transport.
 *  - **Actions** — Forget THIS server (destructive, with confirm dialog).
 *    R-6c-multi: also a "Switch server" affordance when 2+ paired.
 *  - **All servers (R-6c-multi)** — quick switcher to other paired
 *    servers when ≥2 exist.
 *  - **About** — version, GitHub link, license blurb.
 *
 * [onForget] is invoked after a confirmed Forget; the nav graph
 * decides whether to land on `servers` (multi-paired) or `pairing`
 * (none left). [onSwitchServer] is invoked when the user taps "Switch
 * server" — the nav graph navigates to `servers`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onForget: () -> Unit,
    onSwitchServer: () -> Unit = {},
    onOpenCrashLogs: () -> Unit = {},
) {
    val pairing by viewModel.pairing.collectAsState()
    val connectionState by viewModel.rawConnectionState.collectAsState()
    val pairedServers by viewModel.pairedServers.collectAsState()
    val activeServerId by viewModel.activeServerId.collectAsState()
    val context = LocalContext.current

    var showForgetDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    val activeServer = pairedServers.firstOrNull { it.id == activeServerId }

    val otherServers = pairedServers.filter { it.id != activeServerId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                // Lift the scroll tail above the gesture nav bar so the
                // last "About" lines aren't permanently half-hidden.
                .navigationBarsPadding()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            SectionHeader("Server")
            ServerInfo(pairing)
            HorizontalDivider()

            SectionHeader("Connection")
            ConnectionRow(connectionState)
            HorizontalDivider()

            SectionHeader("Actions")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (pairedServers.size >= 2) {
                    OutlinedButton(
                        onClick = onSwitchServer,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Switch server (${pairedServers.size} paired)") }
                }
                OutlinedButton(
                    onClick = { showEditDialog = true },
                    enabled = activeServer != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Edit address / label") }
                OutlinedButton(
                    onClick = { showForgetDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Forget this server") }
            }
            HorizontalDivider()

            if (otherServers.isNotEmpty()) {
                SectionHeader("All servers")
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (other in otherServers) {
                        OtherServerRow(
                            server = other,
                            onSwitch = { viewModel.switchToServer(other.id) },
                        )
                    }
                }
                HorizontalDivider()
            }

            SectionHeader("Diagnostics")
            // The file count is read at composition time and is *not*
            // reactive. Crash files only appear between process
            // lifetimes (the process is being killed when one is
            // written), so we can't observe new files arriving from
            // within a running Settings screen — and a `remember` is
            // enough.
            val crashFileCount = remember { CrashLogger.listCrashFiles(context).size }
            ListItem(
                headlineContent = { Text("Crash logs") },
                supportingContent = {
                    Text(
                        if (crashFileCount == 0) "No crashes recorded"
                        else "$crashFileCount file(s) — tap to view",
                    )
                },
                leadingContent = {
                    // material-icons-core ships ~30 commonly used icons;
                    // `Icons.Filled.BugReport` is only in the
                    // extended set (~3 MB). `Warning` is in core and
                    // reads as "diagnostics-y" well enough.
                    Icon(Icons.Filled.Warning, contentDescription = null)
                },
                trailingContent = {
                    if (crashFileCount > 0) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    }
                },
                modifier = Modifier.clickable(enabled = crashFileCount > 0) {
                    onOpenCrashLogs()
                },
            )
            HorizontalDivider()

            SectionHeader("About")
            AboutInfo(
                onOpenGithub = {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        "https://github.com/Sipaha/spk-editor".toUri(),
                    )
                    runCatching { context.startActivity(intent) }
                },
            )
        }
    }

    if (showEditDialog && activeServer != null) {
        EditServerDialog(
            current = activeServer,
            onDismiss = { showEditDialog = false },
            onSave = { newLabel, newHost, newPort ->
                viewModel.editServer(activeServer.id, newLabel, newHost, newPort)
            },
        )
    }

    if (showForgetDialog) {
        AlertDialog(
            onDismissRequest = { showForgetDialog = false },
            title = { Text("Forget this server?") },
            text = {
                Text(
                    "This server will be removed from this device, along with " +
                        "any drafts, queued messages, and saved chat history. " +
                        "Other paired servers are unaffected. You'll need to " +
                        "scan the QR again to reconnect.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showForgetDialog = false
                        onForget()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Forget") }
            },
            dismissButton = {
                TextButton(onClick = { showForgetDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun ServerInfo(pairing: PairingUrl?) {
    val rowPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        if (pairing == null) {
            Text(
                text = "Not paired.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(rowPadding),
            )
            return
        }
        LabelValue("Server", "${pairing.host}:${pairing.port}", padding = rowPadding)
        LabelValue("Client name", pairing.client, padding = rowPadding)
        LabelValue(
            "Fingerprint",
            // Render only first 8 + last 8 hex chars (≈ 64 bits of entropy
            // shown, which is enough for human-eyeballing a mismatch against
            // the editor's Remote Control UI without filling 64 chars of
            // small monospace text).
            fingerprintShort(pairing.fingerprint),
            padding = rowPadding,
            monospace = true,
        )
    }
}

@Composable
private fun ConnectionRow(state: ConnectionState) {
    val (text, color) = when (state) {
        ConnectionState.Connected ->
            "Connected" to MaterialTheme.colorScheme.primary
        ConnectionState.Connecting ->
            "Connecting…" to MaterialTheme.colorScheme.onSurfaceVariant
        ConnectionState.Disconnected ->
            "Disconnected" to MaterialTheme.colorScheme.onSurfaceVariant
        is ConnectionState.Reconnecting -> {
            val seconds = (state.nextRetryMs / 1000).coerceAtLeast(1)
            "Reconnecting (attempt ${state.attempt}, retry in ${seconds}s)" to
                MaterialTheme.colorScheme.tertiary
        }
        is ConnectionState.FailedTerminal ->
            "Re-pair required (${state.reason})" to MaterialTheme.colorScheme.error
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun OtherServerRow(server: PairedServer, onSwitch: () -> Unit) {
    val parsed = PairingUrl.parse(server.pairingUrl).getOrNull()
    val hostPort = if (parsed != null) "${parsed.host}:${parsed.port}" else "(unparseable URL)"
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.label,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = hostPort,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onSwitch) { Text("Switch") }
    }
}

@Composable
private fun AboutInfo(onOpenGithub: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "SPK Remote · v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onOpenGithub, contentPadding = PaddingValues(0.dp)) {
            Text("Sipaha/spk-editor on GitHub")
        }
        Text(
            text = "Apache 2.0 · © 2026 Pavel Simonov",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LabelValue(
    label: String,
    value: String,
    padding: PaddingValues,
    monospace: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(padding)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
    }
}

/**
 * Render a SHA-256 fingerprint as `aabbccdd…11223344` — enough characters
 * for a human to spot-check against the editor's QR/pairing dialog without
 * displaying the full 64-char string in a phone-sized label.
 */
private fun fingerprintShort(fp: ByteArray): String {
    val hex = fp.joinToString(separator = "") { "%02x".format(it) }
    if (hex.length <= 16) return hex
    return hex.substring(0, 8) + "…" + hex.substring(hex.length - 8)
}
