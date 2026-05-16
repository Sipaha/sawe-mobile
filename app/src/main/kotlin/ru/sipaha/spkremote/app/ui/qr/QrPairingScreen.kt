package ru.sipaha.spkremote.app.ui.qr

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

/**
 * Pairing screen: scan a QR code from the editor or enter the pairing URL manually.
 *
 * @param onPair invoked when we have a candidate `spk-remote://...` URL. The
 *   ViewModel parses it via [ru.sipaha.spkremote.core.PairingUrl.parse] and
 *   transitions UI state — parse failures land back in `UiState.Disconnected`
 *   with an error message rendered inline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrPairingScreen(
    initialUrl: String,
    error: String?,
    onPair: (String) -> Unit,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showManual by remember { mutableStateOf(false) }
    var pendingScan by remember { mutableStateOf(false) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents
        if (raw.isNullOrBlank()) {
            // User cancelled or back-pressed: stay on this screen silently.
            return@rememberLauncherForActivityResult
        }
        onPair(raw)
    }

    fun launchScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setBeepEnabled(false)
            setOrientationLocked(false)
            setPrompt("Point at the QR shown by SPK Editor")
            setBarcodeImageEnabled(false)
        }
        scanLauncher.launch(options)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            if (pendingScan) {
                pendingScan = false
                launchScanner()
            }
        } else {
            pendingScan = false
            scope.launch {
                snackbarHostState.showSnackbar(
                    "Camera access is required to scan the pairing QR.",
                )
            }
        }
    }

    fun onScanClicked() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            launchScanner()
        } else {
            pendingScan = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(error) {
        if (!error.isNullOrBlank()) {
            snackbarHostState.showSnackbar(error)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Pair with SPK Editor") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Open the Remote Control pairing dialog in SPK Editor and " +
                    "scan the QR code shown there.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = ::onScanClicked,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Scan pairing QR")
            }
            TextButton(onClick = { showManual = !showManual }) {
                Text(if (showManual) "Hide manual entry" else "Enter URL manually")
            }
            if (showManual) {
                HorizontalDivider()
                ManualEntry(initialUrl = initialUrl, onConnect = onPair)
            }
        }
    }
}

@Composable
private fun ManualEntry(initialUrl: String, onConnect: (String) -> Unit) {
    var url by remember { mutableStateOf(initialUrl) }
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("Pairing URL") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = { onConnect(url) },
        enabled = url.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Connect")
    }
}
