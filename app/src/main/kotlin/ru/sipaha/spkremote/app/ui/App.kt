package ru.sipaha.spkremote.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.sipaha.spkremote.app.vm.MainViewModel
import ru.sipaha.spkremote.app.vm.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    Scaffold(
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
            when (val s = state) {
                is UiState.Disconnected -> DisconnectedView(
                    initialUrl = s.lastUrl.orEmpty(),
                    error = s.error,
                    onConnect = vm::connect,
                )
                is UiState.Connecting -> CircularProgressIndicator()
                is UiState.Connected -> Text("Connected. Protocol: ${s.protocolVersion}")
            }
        }
    }
}

@Composable
private fun DisconnectedView(initialUrl: String, error: String?, onConnect: (String) -> Unit) {
    var url by remember { mutableStateOf(initialUrl) }
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("Pairing URL") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Button(onClick = { onConnect(url) }, enabled = url.isNotBlank()) {
        Text("Connect")
    }
    if (error != null) {
        Text(text = error)
    }
}
