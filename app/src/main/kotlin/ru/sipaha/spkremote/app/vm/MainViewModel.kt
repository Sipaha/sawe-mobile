package ru.sipaha.spkremote.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.sipaha.spkremote.core.PairingUrl
import ru.sipaha.spkremote.core.RemoteClient

sealed interface UiState {
    data class Disconnected(val lastUrl: String? = null, val error: String? = null) : UiState
    data object Connecting : UiState
    data class Connected(val protocolVersion: String) : UiState
}

class MainViewModel : ViewModel() {
    private val _state = MutableStateFlow<UiState>(UiState.Disconnected())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var client: RemoteClient? = null

    fun connect(rawUrl: String) {
        val parsed = PairingUrl.parse(rawUrl).getOrElse {
            _state.value = UiState.Disconnected(lastUrl = rawUrl, error = it.message)
            return
        }
        _state.value = UiState.Connecting
        viewModelScope.launch {
            val newClient = RemoteClient(parsed)
            client = newClient
            newClient.connect()
                .onFailure {
                    _state.value = UiState.Disconnected(lastUrl = rawUrl, error = it.message)
                    return@launch
                }
            runCatching { newClient.call("remote.editor.capabilities") }
                .onSuccess { resp ->
                    val version = (resp.result as? JsonObject)
                        ?.get("protocol_version")
                        ?.jsonPrimitive
                        ?.content
                        ?: "unknown"
                    _state.value = UiState.Connected(version)
                }
                .onFailure {
                    _state.value = UiState.Disconnected(lastUrl = rawUrl, error = it.message)
                }
        }
    }

    override fun onCleared() {
        client?.close()
        client = null
    }
}
