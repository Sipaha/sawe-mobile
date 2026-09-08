package ru.sipaha.sawe.app.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.sipaha.sawe.app.diagnostics.CrashLogger
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Settings → Crash logs. Lists every file under `filesDir/crash-logs`
 * newest-first, lets the user tap one to view the contents, share it
 * via Intent.ACTION_SEND (text/plain), or clear them all. No automatic
 * upload to anywhere — the user is in charge of where the report goes.
 *
 * The listing and every file read happen on [Dispatchers.IO] and are cached
 * per-file: the directory scan is a `produceState` that runs once per
 * `reloadToken`, and a viewed file's text is read once instead of on every
 * recomposition of the dialog (it used to be read inside the `Text(...)`
 * call, i.e. from the main thread, repeatedly — N-60). The list is reseeded
 * only when the user clears: crashes happen between process lifetimes, so it
 * can't change while this screen is up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashLogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var reloadToken by remember { mutableIntStateOf(0) }
    val files: List<File> by produceState(initialValue = emptyList(), context, reloadToken) {
        value = withContext(Dispatchers.IO) { CrashLogger.listCrashFiles(context) }
    }
    var viewing by remember { mutableStateOf<File?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crash logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    if (files.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear all")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            // Clear the gesture nav bar so the last crash-log row isn't
            // half-hidden at rest.
            contentPadding = WindowInsets.navigationBars.asPaddingValues(),
        ) {
            if (files.isEmpty()) {
                item {
                    Text(
                        text = "No crash logs.",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(files, key = { it.name }) { file ->
                    ListItem(
                        headlineContent = { Text(file.name) },
                        supportingContent = {
                            Text(
                                text = DateFormat.getDateTimeInstance()
                                    .format(Date(file.lastModified())),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        modifier = Modifier.clickable { viewing = file },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    viewing?.let { file ->
        // Read once, here, for BOTH the dialog body and the share intent.
        // Sharing used to kick off its own read on `rememberCoroutineScope()`
        // and then set `viewing = null`; a Back press before that read
        // finished cancelled the scope and the chooser simply never appeared —
        // no error, no chooser, nothing. Reusing the text the user is already
        // looking at means Share has no async work left to lose.
        val body: String? by produceState<String?>(initialValue = null, file) {
            value = withContext(Dispatchers.IO) { CrashLogger.readCrashFile(file) }
        }
        val loaded = body
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text(file.name) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = loaded ?: "Loading…",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    // Nothing to share until the read lands. Disabled rather
                    // than queued: a few KB off a local file is imperceptible,
                    // and a Share that silently does nothing is the bug.
                    enabled = loaded != null,
                    onClick = {
                        loaded?.let { shareCrashLog(context, file.name, it) }
                        viewing = null
                    },
                ) { Text("Share") }
            },
            dismissButton = {
                TextButton(onClick = { viewing = null }) { Text("Close") }
            },
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear crash logs?") },
            text = { Text("All ${files.size} crash file(s) will be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    scope.launch {
                        withContext(Dispatchers.IO) { CrashLogger.clearAll(context) }
                        reloadToken++
                    }
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Fire an ACTION_SEND chooser so the user can pipe the crash text into
 * mail / Telegram / Files. Takes the already-read [body] so there is no I/O —
 * and therefore no cancellable coroutine — between the tap and the chooser.
 * We attach the body as EXTRA_TEXT (not a `content://` Uri via FileProvider)
 * because:
 *  1. We don't ship a FileProvider authority yet — adding one is more
 *     work than the share flow benefits from at this stage.
 *  2. Crash files are small (a few KB) and fit in an intent extra.
 *  3. The text body is the same shape the user sees on screen, so
 *     "send the message you just read to me" is intuitively the
 *     correct mental model.
 */
private fun shareCrashLog(context: Context, fileName: String, body: String) {
    context.startActivity(
        Intent.createChooser(crashLogShareIntent(fileName, body), "Share crash log"),
    )
}

/**
 * The ACTION_SEND intent for one crash log. A pure function of text the caller
 * already holds — which is the whole point: there is no I/O, and therefore no
 * cancellable coroutine, between the Share tap and the chooser.
 */
internal fun crashLogShareIntent(fileName: String, body: String): Intent =
    Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "spk-editor crash: $fileName")
        putExtra(Intent.EXTRA_TEXT, body)
    }
