package ru.sipaha.sawe.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import ru.sipaha.sawe.app.ui.App
import ru.sipaha.sawe.app.ui.theme.SaweMobileTheme
import ru.sipaha.sawe.app.vm.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // No explicit enableEdgeToEdge(), but Android 15+ (targetSdk 35+)
        // FORCES edge-to-edge regardless — adjustResize alone no longer
        // lifts the activity window above the IME on those targets.
        // SessionDetailScreen's Scaffold zeroes contentWindowInsets and
        // its bottomBar applies WindowInsets.ime.union(navigationBars)
        // explicitly so the compose row stays above the keyboard AND
        // clears the system nav bar at rest.
        super.onCreate(savedInstanceState)
        // NOTE: no cold-start branch on `savedInstanceState` here.
        // Connecting is the ViewModel's job — it hydrates the pairing list
        // off the Main thread and binds the most-recently-used server as
        // soon as it exists, whether this Activity is a fresh launch, a
        // rotation, or a re-creation after the OS killed the process in
        // the background. Gating that on `savedInstanceState == null` used
        // to leave the restored Activity with no client at all and no way
        // to get one (N-10). The nav graph resolves its own landing route
        // from [MainViewModel.landingRoute].
        setContent {
            SaweMobileTheme {
                App(vm = viewModel)
            }
        }
    }
}
