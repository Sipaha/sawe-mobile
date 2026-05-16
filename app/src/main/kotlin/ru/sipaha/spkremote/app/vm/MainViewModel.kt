package ru.sipaha.spkremote.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ru.sipaha.spkremote.core.JsonRpc
import ru.sipaha.spkremote.core.ListSessionsResult
import ru.sipaha.spkremote.core.ListSolutionsResult
import ru.sipaha.spkremote.core.PairingUrl
import ru.sipaha.spkremote.core.RemoteClient
import ru.sipaha.spkremote.core.SessionSummary
import ru.sipaha.spkremote.core.SolutionSummary

sealed interface UiState {
    data class Disconnected(val lastUrl: String? = null, val error: String? = null) : UiState
    data object Connecting : UiState
    data class Connected(val protocolVersion: String) : UiState
}

/** Lightweight loadable wrapper for async-backed UI state. */
sealed interface UiData<out T> {
    data object Loading : UiData<Nothing>
    data class Loaded<T>(val value: T) : UiData<T>
    data class Error(val message: String) : UiData<Nothing>
}

class MainViewModel : ViewModel() {
    private val _state = MutableStateFlow<UiState>(UiState.Disconnected())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _solutions = MutableStateFlow<UiData<List<SolutionSummary>>>(UiData.Loading)
    val solutions: StateFlow<UiData<List<SolutionSummary>>> = _solutions.asStateFlow()

    private val _sessions = MutableStateFlow<UiData<List<SessionSummary>>>(UiData.Loading)
    val sessions: StateFlow<UiData<List<SessionSummary>>> = _sessions.asStateFlow()

    private var client: RemoteClient? = null

    // Track session-event subscription so a screen entering/leaving the
    // SolutionDetailScreen doesn't double-subscribe on the server (the
    // editor's subscription store is idempotent in practice, but we still
    // skip duplicate frames to keep the wire chatty-but-not-noisy).
    private var sessionStateSubscribed = false
    private var sessionObserverJob: Job? = null

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

    fun refreshSolutions() {
        val active = client
        if (active == null) {
            _solutions.value = UiData.Error("not connected")
            return
        }
        _solutions.value = UiData.Loading
        viewModelScope.launch {
            runCatching { active.call("remote.solutions.list") }
                .mapCatching { resp ->
                    val err = resp.error
                    if (err != null) error(err.message)
                    val result = resp.result ?: error("missing result")
                    JsonRpc.json
                        .decodeFromJsonElement(ListSolutionsResult.serializer(), result)
                        .solutions
                }
                .onSuccess { _solutions.value = UiData.Loaded(it) }
                .onFailure { _solutions.value = UiData.Error(it.message ?: "unknown error") }
        }
    }

    fun refreshSessions(solutionId: String) {
        val active = client
        if (active == null) {
            _sessions.value = UiData.Error("not connected")
            return
        }
        // Don't clobber the existing list with Loading on refetch — the UI
        // would flash empty during the live-subscribe-driven re-poll. Only
        // show Loading if we have nothing to display yet.
        if (_sessions.value !is UiData.Loaded) {
            _sessions.value = UiData.Loading
        }
        val params = buildJsonObject { put("solution_id", solutionId) }
        viewModelScope.launch {
            runCatching { active.call("remote.solution_agent.list_sessions", params) }
                .mapCatching { resp ->
                    val err = resp.error
                    if (err != null) error(err.message)
                    val result = resp.result ?: error("missing result")
                    JsonRpc.json
                        .decodeFromJsonElement(ListSessionsResult.serializer(), result)
                        .sessions
                }
                .onSuccess { _sessions.value = UiData.Loaded(it) }
                .onFailure { _sessions.value = UiData.Error(it.message ?: "unknown error") }
        }
    }

    /**
     * Begin watching `agent_session_state_changed` events for `solutionId`.
     *
     * The server-side notification carries only a session id (NOT the new
     * state value), so on every relevant frame we re-fetch list_sessions
     * for the active solution. Lists are small; the round-trip is cheap.
     *
     * Safe to call repeatedly — the underlying subscription is only sent
     * once. Pair with [stopObservingSessions] when leaving the screen.
     */
    fun startObservingSessions(solutionId: String) {
        val active = client ?: return
        sessionObserverJob?.cancel()
        sessionObserverJob = viewModelScope.launch {
            if (!sessionStateSubscribed) {
                val params = buildJsonObject {
                    put("kinds", JsonArray(listOf(JsonPrimitive("agent_session_state_changed"))))
                }
                runCatching { active.call("remote.editor.subscribe", params) }
                    .onSuccess { sessionStateSubscribed = true }
                // failure is non-fatal — we still display the list, just no
                // live updates. The screen surfaces a one-shot Refresh button.
            }
            active.notifications.collect { frame ->
                val params = (frame as? JsonObject)?.get("params") as? JsonObject ?: return@collect
                val kind = params["kind"]?.jsonPrimitive?.content ?: return@collect
                if (kind != "agent_session_state_changed") return@collect
                // We could narrow further by inspecting data.session_id /
                // data.solution_id, but list_sessions is a single round-trip
                // and the event carries no other useful info — keep it simple.
                refreshSessions(solutionId)
            }
        }
    }

    fun stopObservingSessions() {
        sessionObserverJob?.cancel()
        sessionObserverJob = null
    }

    fun clearSessions() {
        _sessions.value = UiData.Loading
    }

    override fun onCleared() {
        sessionObserverJob?.cancel()
        client?.close()
        client = null
    }
}
