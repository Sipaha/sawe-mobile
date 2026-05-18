package ru.sipaha.spkremote.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import ru.sipaha.spkremote.app.data.PairedServer
import ru.sipaha.spkremote.core.PairingUrl

/**
 * Edit the host / port / label of an already-paired server.
 *
 * The pairing secret + cert fingerprint + client name are NOT editable —
 * those are part of the cryptographic handshake and the server's own
 * authorised-client list. Changing them would require re-pairing. Only
 * the connection address (host:port) and the user-visible label are
 * mutable here.
 *
 * @param current the row being edited; the dialog seeds its fields from
 *   the parsed pairing URL.
 * @param onDismiss close the dialog without saving.
 * @param onSave invoked when the user taps Save with non-empty / valid
 *   inputs. The caller (Settings or ServersListScreen) plumbs through to
 *   [ru.sipaha.spkremote.app.vm.MainViewModel.editServer] and gets back
 *   `null` on success or a short error string for inline display.
 */
@Composable
fun EditServerDialog(
    current: PairedServer,
    onDismiss: () -> Unit,
    onSave: (label: String, host: String, port: Int) -> String?,
) {
    // Parse once; if the existing URL is malformed (shouldn't happen
    // — we persisted it via PairingUrl.parse) treat host/port as
    // blanks so the user can fill them in.
    val parsed = remember(current.id) { PairingUrl.parse(current.pairingUrl).getOrNull() }
    val initialHost = parsed?.host.orEmpty()
    val initialPort = parsed?.port?.toString().orEmpty()
    var host by rememberSaveable(current.id) { mutableStateOf(initialHost) }
    var portText by rememberSaveable(current.id) { mutableStateOf(initialPort) }
    var label by rememberSaveable(current.id) { mutableStateOf(current.label) }
    // labelManual = true when the user typed something that doesn't match
    // the auto-default "host:port". Starts true if the stored label already
    // diverged from "$initialHost:$initialPort" (i.e. user customised it
    // previously). Going false (by clearing or matching auto) re-engages
    // the auto-sync below.
    var labelManual by rememberSaveable(current.id) {
        mutableStateOf(current.label.isNotEmpty() && current.label != "$initialHost:$initialPort")
    }
    var error by remember { mutableStateOf<String?>(null) }

    // Auto-sync the label to "host:port" while the user hasn't typed a
    // custom value. So changing host from 37.1.199.69 to 198.51.100.5
    // also updates the visible label without making the user re-type it,
    // BUT a custom "Home desktop" stays put.
    LaunchedEffect(host, portText, labelManual) {
        if (!labelManual) {
            label = "$host:$portText"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit server") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "The pairing secret and fingerprint stay the same — only the " +
                        "connection address can change here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = label,
                    onValueChange = { typed ->
                        label = typed
                        // Empty re-engages auto-sync; anything else (including
                        // explicitly typing what auto would produce) counts as
                        // user-customised and stops the host/port → label
                        // mirroring.
                        labelManual = typed.isNotEmpty() && typed != "$host:$portText"
                    },
                    label = { Text("Label") },
                    placeholder = { Text("$host:$portText") },
                    supportingText = {
                        Text(
                            if (labelManual)
                                "Custom name. Clear the field to track host:port again."
                            else
                                "Auto-tracks the address. Type a custom name like \"Home desktop\" to fix it.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host / IP") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val port = portText.toIntOrNull()
                if (port == null) {
                    error = "Port must be a number."
                    return@TextButton
                }
                val result = onSave(label, host, port)
                if (result == null) {
                    onDismiss()
                } else {
                    error = result
                }
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
