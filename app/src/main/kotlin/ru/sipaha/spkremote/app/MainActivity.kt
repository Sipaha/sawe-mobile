package ru.sipaha.spkremote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import ru.sipaha.spkremote.app.ui.App
import ru.sipaha.spkremote.app.ui.theme.SpkRemoteTheme
import ru.sipaha.spkremote.app.vm.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // No enableEdgeToEdge(). The manifest declares
        // windowSoftInputMode="adjustResize", so the system shrinks the
        // activity window when the keyboard opens — the chat compose
        // bar (positioned at the bottom of the window) ends up just
        // above the keyboard automatically, with no inset modifiers
        // needed inside the Composable tree.
        super.onCreate(savedInstanceState)
        // Cold-start auto-resume: the VM inspects [PairingRepository.loadAll]
        // and returns the appropriate landing destination — `pairing` when
        // nothing is paired, `solutions` for the single-server R-6b
        // auto-resume path, or `servers` when 2+ are paired. The VM also
        // kicks off [switchToServer] synchronously for the
        // most-recently-active server in the latter two cases, so by the
        // time the nav graph renders we're already in Connecting state.
        val initialRoute = if (savedInstanceState == null) {
            viewModel.coldStartLandingRoute()
        } else {
            // Recreated activity (e.g. orientation change). The VM survived
            // and is already in the right state — let the nav graph
            // restore its own back stack.
            null
        }
        setContent {
            SpkRemoteTheme {
                App(vm = viewModel, initialRoute = initialRoute)
            }
        }
    }
}
