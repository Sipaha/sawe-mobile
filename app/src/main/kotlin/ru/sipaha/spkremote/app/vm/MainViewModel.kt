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
import ru.sipaha.spkremote.core.EntrySummary
import ru.sipaha.spkremote.core.GetSessionResult
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

    private val _session = MutableStateFlow<UiData<GetSessionResult>>(UiData.Loading)
    val session: StateFlow<UiData<GetSessionResult>> = _session.asStateFlow()

    /**
     * Locally-appended user messages awaiting server echo. We render them
     * inline with [_session] so the user sees their text immediately. Each
     * pending entry is dropped from this list as soon as the server's
     * `get_session` result contains a matching `(role=user, preview=text)`
     * pair — see [reconcileOptimistic].
     */
    private val _optimisticEntries = MutableStateFlow<List<EntrySummary>>(emptyList())
    val optimisticEntries: StateFlow<List<EntrySummary>> = _optimisticEntries.asStateFlow()

    /** True while a `cancel_turn` request is in flight (button is debounced). */
    private val _cancelInFlight = MutableStateFlow(false)
    val cancelInFlight: StateFlow<Boolean> = _cancelInFlight.asStateFlow()

    private var client: RemoteClient? = null

    // Track session-event subscription so a screen entering/leaving the
    // SolutionDetailScreen doesn't double-subscribe on the server (the
    // editor's subscription store is idempotent in practice, but we still
    // skip duplicate frames to keep the wire chatty-but-not-noisy).
    private var sessionStateSubscribed = false
    private var sessionObserverJob: Job? = null

    // Per-session observer: subscribes to message_appended + state_changed
    // and re-polls get_session on any frame matching the open session id.
    private var openSessionId: String? = null
    private var sessionDetailObserverJob: Job? = null
    private var sessionDetailSubscribed = false

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

    /**
     * Open the chat surface for one session — initial poll + live updates.
     *
     * The server sends id-only `agent_session_message_appended` notifications
     * (no delta, no entry index) so the only way to keep the transcript in
     * sync is to re-call `get_session` on every relevant frame. Lists are
     * small (single session, ≤200-char previews); the round-trip is cheap.
     *
     * Subscriptions for `agent_session_message_appended` and
     * `agent_session_state_changed` happen here too — they overlap with
     * [startObservingSessions]'s state subscription, but the server is
     * idempotent against duplicate kinds in subscribe calls.
     */
    fun openSession(sessionId: String) {
        val active = client
        if (active == null) {
            _session.value = UiData.Error("not connected")
            return
        }
        openSessionId = sessionId
        _session.value = UiData.Loading
        _optimisticEntries.value = emptyList()
        refreshSession(sessionId)
        sessionDetailObserverJob?.cancel()
        sessionDetailObserverJob = viewModelScope.launch {
            if (!sessionDetailSubscribed) {
                val params = buildJsonObject {
                    put(
                        "kinds",
                        JsonArray(
                            listOf(
                                JsonPrimitive("agent_session_message_appended"),
                                JsonPrimitive("agent_session_state_changed"),
                            ),
                        ),
                    )
                }
                runCatching { active.call("remote.editor.subscribe", params) }
                    .onSuccess { sessionDetailSubscribed = true }
                // failure is non-fatal — initial transcript is still shown.
            }
            active.notifications.collect { frame ->
                val params = (frame as? JsonObject)?.get("params") as? JsonObject ?: return@collect
                val kind = params["kind"]?.jsonPrimitive?.content ?: return@collect
                if (kind != "agent_session_message_appended" && kind != "agent_session_state_changed") {
                    return@collect
                }
                val data = params["data"] as? JsonObject
                val notifSessionId = data?.get("session_id")?.jsonPrimitive?.content
                // Defensive: data may be absent or session_id may be missing.
                // If we can't narrow, refetch anyway — better stale-than-stale.
                if (notifSessionId != null && notifSessionId != openSessionId) return@collect
                refreshSession(sessionId)
            }
        }
    }

    fun closeSession() {
        sessionDetailObserverJob?.cancel()
        sessionDetailObserverJob = null
        openSessionId = null
        _session.value = UiData.Loading
        _optimisticEntries.value = emptyList()
    }

    private fun refreshSession(sessionId: String) {
        val active = client ?: return
        val params = buildJsonObject { put("session_id", sessionId) }
        viewModelScope.launch {
            runCatching { active.call("remote.solution_agent.get_session", params) }
                .mapCatching { resp ->
                    val err = resp.error
                    if (err != null) error(err.message)
                    val result = resp.result ?: error("missing result")
                    JsonRpc.json.decodeFromJsonElement(GetSessionResult.serializer(), result)
                }
                .onSuccess { result ->
                    // Drop the session-detail observer if the user already
                    // navigated away (openSessionId cleared by closeSession).
                    if (openSessionId != sessionId) return@onSuccess
                    _session.value = UiData.Loaded(result)
                    reconcileOptimistic(result.entries)
                }
                .onFailure {
                    if (openSessionId != sessionId) return@onFailure
                    // If we already have content, leave it visible and emit
                    // the error only if there's nothing to show.
                    if (_session.value !is UiData.Loaded) {
                        _session.value = UiData.Error(it.message ?: "unknown error")
                    }
                }
        }
    }

    /**
     * Drop optimistic entries that the server has now echoed back.
     *
     * Match is best-effort: same `role == "user"` plus exact `preview`
     * equality. The server preview is ≤200 chars truncated, so for short
     * messages (the common case) match is exact; for long messages the
     * optimistic bubble survives until the user navigates away. Acceptable
     * — duplicates resolve on the next poll once the assistant adds turns
     * beyond the truncation horizon, and the only cost is a brief stutter.
     */
    private fun reconcileOptimistic(serverEntries: List<EntrySummary>) {
        if (_optimisticEntries.value.isEmpty()) return
        val serverUsers = serverEntries.filter { it.role == "user" }.map { it.preview }.toMutableList()
        val remaining = mutableListOf<EntrySummary>()
        for (optimistic in _optimisticEntries.value) {
            val idx = serverUsers.indexOf(optimistic.preview)
            if (idx >= 0) {
                serverUsers.removeAt(idx)
            } else {
                remaining.add(optimistic)
            }
        }
        _optimisticEntries.value = remaining
    }

    /**
     * Optimistically append [text] as a user entry, then fire-and-forget
     * the server-side `send_message`. On failure, surface an error and
     * pop the optimistic bubble so the user can retry without a phantom.
     */
    fun sendMessage(text: String) {
        if (text.isBlank()) return
        val active = client ?: return
        val sessionId = openSessionId ?: return
        val optimistic = EntrySummary(role = "user", preview = text)
        _optimisticEntries.value = _optimisticEntries.value + optimistic
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("content", text)
        }
        viewModelScope.launch {
            runCatching { active.call("remote.solution_agent.send_message", params) }
                .mapCatching { resp ->
                    val err = resp.error
                    if (err != null) error(err.message)
                }
                .onFailure {
                    // Remove only the specific optimistic entry — another
                    // send may have raced past us and we don't want to
                    // drop the wrong one.
                    _optimisticEntries.value = _optimisticEntries.value.filterNot { it === optimistic }
                    _sendError.tryEmit(it.message ?: "send failed")
                }
        }
    }

    fun cancelTurn() {
        val active = client ?: return
        val sessionId = openSessionId ?: return
        if (_cancelInFlight.value) return
        _cancelInFlight.value = true
        val params = buildJsonObject { put("session_id", sessionId) }
        viewModelScope.launch {
            runCatching { active.call("remote.solution_agent.cancel_turn", params) }
                .mapCatching { resp ->
                    val err = resp.error
                    if (err != null) error(err.message)
                }
                .onFailure { _sendError.tryEmit("cancel failed: ${it.message ?: "?"}") }
            _cancelInFlight.value = false
        }
    }

    private val _sendError = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    val sendError: kotlinx.coroutines.flow.SharedFlow<String> = _sendError

    override fun onCleared() {
        sessionObserverJob?.cancel()
        sessionDetailObserverJob?.cancel()
        client?.close()
        client = null
    }
}
