package ru.sipaha.spkremote.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.sipaha.spkremote.app.ui.qr.QrPairingScreen
import ru.sipaha.spkremote.app.vm.MainViewModel
import ru.sipaha.spkremote.app.vm.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    when (val s = state) {
        is UiState.Disconnected -> QrPairingScreen(
            initialUrl = s.lastUrl.orEmpty(),
            error = s.error,
            onPair = vm::connect,
        )
        is UiState.Connecting -> Scaffold(
            topBar = { TopAppBar(title = { Text("SPK Remote") }) },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        is UiState.Connected -> Scaffold(
            topBar = { TopAppBar(title = { Text("SPK Remote") }) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Connected. Protocol: ${s.protocolVersion}")
            }
        }
    }
}
