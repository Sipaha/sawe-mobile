package ru.sipaha.spkremote.app.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ru.sipaha.spkremote.app.data.DraftRepository
import ru.sipaha.spkremote.app.data.EncryptedQueueStore
import ru.sipaha.spkremote.app.data.LastSeenRepository
import ru.sipaha.spkremote.app.data.NavStateRepository
import ru.sipaha.spkremote.app.data.PairedServer
import ru.sipaha.spkremote.app.data.PairingRepository
import ru.sipaha.spkremote.core.AgentSummary
import ru.sipaha.spkremote.core.ConnectionState
import ru.sipaha.spkremote.core.CreateSessionResult
import ru.sipaha.spkremote.core.EntrySummary
import ru.sipaha.spkremote.core.GetSessionChildrenResult
import ru.sipaha.spkremote.core.GetSessionResult
import ru.sipaha.spkremote.core.JsonRpc
import ru.sipaha.spkremote.core.ListAgentsResult
import ru.sipaha.spkremote.core.ListSessionsResult
import ru.sipaha.spkremote.core.ListSolutionsResult
import ru.sipaha.spkremote.core.MessageAppendedPayload
import ru.sipaha.spkremote.core.PairingUrl
import ru.sipaha.spkremote.core.QueuedMessage
import ru.sipaha.spkremote.core.RemoteClient
import ru.sipaha.spkremote.core.SessionCreatedPayload
import ru.sipaha.spkremote.core.SessionSummary
import ru.sipaha.spkremote.core.SolutionSummary
import java.util.UUID

sealed interface UiState {
    data class Disconnected(val lastUrl: String? = null, val error: String? = null) : UiState
    data object Connecting : UiState
    data class Connected(val protocolVersion: String) : UiState
}

/**
 * Lightweight projection of [ConnectionState] for the navigation banner.
 *
 * Mirrors the underlying [ConnectionState] but drops the Connecting state
 * (no banner needed — the user is on the connecting spinner screen) and
 * only carries the fields the banner needs to render. Decoupling here
 * means the Compose layer doesn't need to import `:core` types directly.
 */
sealed interface ConnectionBanner {
    data object Hidden : ConnectionBanner

    /**
     * @param reason short user-facing summary of the failure that triggered
     *   the current Reconnecting cycle. Null only on the very first
     *   transition before any attempt has failed. Drives the banner's
     *   contextual text ("Reconnecting (host unreachable, attempt 3, next
     *   try in 4s)…") instead of a generic "Reconnecting…".
     */
    data class Reconnecting(
        val attempt: Int,
        val nextRetryMs: Long,
        val reason: String? = null,
    ) : ConnectionBanner

    data class FailedTerminal(val reason: String) : ConnectionBanner
}

/** Lightweight loadable wrapper for async-backed UI state. */
sealed interface UiData<out T> {
    data object Loading : UiData<Nothing>
    data class Loaded<T>(val value: T) : UiData<T>
    data class Error(val message: String) : UiData<Nothing>
}

/** Page size for [MainViewModel.openSession] and [MainViewModel.loadOlder]. */
private const val SESSION_PAGE_SIZE = 50

class MainViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * Persistence backend for the paired-server list. Lazily opens an
     * EncryptedSharedPreferences file on first read/write and runs the
     * R-6b → R-6c-multi migration there; see [PairingRepository] for
     * the cold-start contract.
     */
    private val pairingRepository: PairingRepository = PairingRepository.get(application)

    /**
     * R-6c-multi: active server id, observed by the per-server scoped
     * repositories via their `activeServerProvider` closures. Mutated
     * by [switchToServer] / [removeServer] / [addServer] — every state
     * change rebuilds the underlying [RemoteClient].
     */
    private val _activeServerId = MutableStateFlow<String?>(pairingRepository.activeServerId())
    val activeServerId: StateFlow<String?> = _activeServerId.asStateFlow()

    /**
     * Observable paired-server list for the Servers screen and the
     * Settings "All servers" sub-section. Updated synchronously on
     * every [addServer] / [removeServer] / [switchToServer].
     */
    private val _pairedServers = MutableStateFlow<List<PairedServer>>(pairingRepository.loadAll())
    val pairedServers: StateFlow<List<PairedServer>> = _pairedServers.asStateFlow()

    /**
     * Disk-backed outbound-queue store for [RemoteClient.queueCall]
     * (R-6d). Stored separately from [pairingRepository] in a dedicated
     * `spk_queue` prefs file so we can clear queue entries without
     * touching the pairing URL (e.g. on forget-pairing the queue is
     * irrelevant; on a per-session reset, the pairing stays). The
     * R-6c-multi scoping keys each server's queue under its own blob.
     */
    private val queueStore: EncryptedQueueStore =
        EncryptedQueueStore.get(application) { _activeServerId.value }

    /**
     * Per-session compose-bar drafts + bounced-on-failure recovery slots.
     * See [DraftRepository] for the two-channel semantics. R-6c-multi
     * scopes keys by the active server id.
     */
    val draftRepository: DraftRepository =
        DraftRepository.get(application) { _activeServerId.value }

    /**
     * Per-session "last seen entry index" markers, scoped per-server
     * (R-6c-multi). R-6e wires the incremental-resume RPC that uses
     * the marker on reconnect.
     */
    private val lastSeenRepository: LastSeenRepository =
        LastSeenRepository.get(application) { _activeServerId.value }

    /**
     * Active navigation route for cold-start resume, scoped per-server
     * (R-6c-multi). Persisted from `AppNavGraph` on every back-stack
     * change; read from `MainActivity` after the pairing replay completes.
     */
    val navStateRepository: NavStateRepository =
        NavStateRepository.get(application) { _activeServerId.value }

    private val _state = MutableStateFlow<UiState>(UiState.Disconnected())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * The parsed [PairingUrl] for the active connection, or `null` when no
     * connection has been attempted in this VM instance. Surfaced to the
     * Settings screen so it can render server host / fingerprint / client
     * name without re-parsing the raw string.
     */
    private val _pairing = MutableStateFlow<PairingUrl?>(null)
    val pairing: StateFlow<PairingUrl?> = _pairing.asStateFlow()

    private val _solutions = MutableStateFlow<UiData<List<SolutionSummary>>>(UiData.Loading)
    val solutions: StateFlow<UiData<List<SolutionSummary>>> = _solutions.asStateFlow()

    private val _sessions = MutableStateFlow<UiData<List<SessionSummary>>>(UiData.Loading)
    val sessions: StateFlow<UiData<List<SessionSummary>>> = _sessions.asStateFlow()

    private val _session = MutableStateFlow<UiData<GetSessionResult>>(UiData.Loading)
    val session: StateFlow<UiData<GetSessionResult>> = _session.asStateFlow()

    /**
     * True while a [loadOlder] request is in flight for the open session.
     *
     * Exposed alongside [session] (rather than folded into the
     * `UiData<GetSessionResult>` shape) so the LazyColumn auto-trigger
     * in `SessionDetailScreen` can dedupe re-fires while a previous slice
     * is still on the wire — without churning the `_session` value (which
     * would trigger a recomposition cycle for every entry row).
     */
    private val _isLoadingOlder = MutableStateFlow(false)
    val isLoadingOlder: StateFlow<Boolean> = _isLoadingOlder.asStateFlow()

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

    /**
     * Child sessions tracked per parent session id (F-phone).
     *
     * The chip row on `SessionDetailScreen` reads the entry for the
     * currently-open session — present iff that session has spawned
     * sub-agents server-side. Populated by [loadChildren] on screen
     * entry and refreshed on `agent_session_created` notifications
     * whose `parent_session_id` matches.
     *
     * Map keyed by parent session id so navigating between parent and
     * child preserves the parent's chip data — both screens consult the
     * same observable map without re-fetching.
     */
    private val _sessionChildren = MutableStateFlow<Map<String, List<SessionSummary>>>(emptyMap())
    val sessionChildren: StateFlow<Map<String, List<SessionSummary>>> = _sessionChildren.asStateFlow()

    /**
     * Agent adapters available on the paired editor. Populated lazily by
     * [loadAgents] when a screen opens the "New session" dialog. Empty list
     * is a legitimate Loaded state — the dialog disables the Create button
     * and shows "no adapters available" rather than treating it as an error.
     */
    private val _agents = MutableStateFlow<UiData<List<AgentSummary>>>(UiData.Loading)
    val agents: StateFlow<UiData<List<AgentSummary>>> = _agents.asStateFlow()

    /** True while a `create_session` (possibly with auto-open retry) is in flight. */
    private val _createSessionInFlight = MutableStateFlow(false)
    val createSessionInFlight: StateFlow<Boolean> = _createSessionInFlight.asStateFlow()

    /**
     * Signalled when the auto-open-and-retry path was exercised during the
     * last successful create_session — the dialog surfaces a small info
     * line so the user understands why their solution window jumped into
     * the foreground on the desktop. Reset by [loadAgents].
     */
    private val _lastCreateAutoOpened = MutableStateFlow(false)
    val lastCreateAutoOpened: StateFlow<Boolean> = _lastCreateAutoOpened.asStateFlow()

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

    /**
     * Banner displayed by the navigation graph when the underlying socket
     * is in trouble — Reconnecting or FailedTerminal. Hidden in normal
     * Connected / Disconnected states.
     */
    private val _connectionBanner = MutableStateFlow<ConnectionBanner>(ConnectionBanner.Hidden)
    val connectionBanner: StateFlow<ConnectionBanner> = _connectionBanner.asStateFlow()

    /**
     * Last entry index we observed for each session via
     * `agent_session_message_appended`.
     *
     * R-6c-multi: this in-memory map is wiped on every [switchToServer]
     * because the marker is now scoped to the active server in
     * [lastSeenRepository]. Stale entries from server A would shadow
     * the on-disk markers for server B.
     */
    private val lastSeenEntryIndex = mutableMapOf<String, Int>()

    /** Job that observes [RemoteClient.connectionState]. */
    private var connectionObserverJob: Job? = null

    /**
     * Pair a NEW server from a freshly-scanned QR code.
     *
     * Parses [rawUrl], generates a UUID for the entry, persists it as
     * a [PairedServer] in [pairingRepository], marks it active, and
     * connects via [switchToServer]. The "is this URL a duplicate of
     * an already-paired server" question is answered by fingerprint
     * equality — pairing twice with the same editor (e.g. user
     * re-scans because the previous secret expired) reuses the
     * existing entry id so per-server scoped state (drafts, queue,
     * nav state, lastSeen markers) is preserved.
     *
     * [onAdded] is invoked from the main thread with the resulting
     * server id once persistence completes, so callers can navigate
     * to `solutions` for the newly-paired server.
     */
    fun addServer(rawUrl: String, onAdded: (serverId: String) -> Unit = {}) {
        val parsed = PairingUrl.parse(rawUrl).getOrElse {
            _state.value = UiState.Disconnected(lastUrl = rawUrl, error = it.message)
            return
        }
        val fingerprintHex = parsed.fingerprint.joinToString("") { "%02x".format(it) }
        val existing = pairingRepository.loadAll().firstOrNull { it.fingerprintHex == fingerprintHex }
        val server = if (existing != null) {
            existing.copy(pairingUrl = rawUrl, label = "${parsed.host}:${parsed.port}")
        } else {
            PairedServer(
                id = UUID.randomUUID().toString(),
                pairingUrl = rawUrl,
                label = "${parsed.host}:${parsed.port}",
                fingerprintHex = fingerprintHex,
                firstPairedAtMs = System.currentTimeMillis(),
                lastConnectedAtMs = null,
            )
        }
        pairingRepository.upsert(server)
        _pairedServers.value = pairingRepository.loadAll()
        switchToServer(server.id)
        onAdded(server.id)
    }

    /**
     * Edit the host / port / label of an existing paired server without
     * re-pairing. Secret + fingerprint + client name come from the
     * existing pairing URL — only the address and the user-facing label
     * change. Used when the server's public IP rotates, when the
     * port-forward moves, or just to give a paired server a friendlier
     * name.
     *
     * Returns `null` on success; a short error string when the new
     * host:port combination doesn't produce a parseable URL. The caller
     * (an [EditServerDialog]) surfaces the error inline.
     *
     * If [serverId] is the active server, the connection is restarted
     * against the new address.
     */
    fun editServer(
        serverId: String,
        label: String,
        host: String,
        port: Int,
    ): String? {
        val existing = pairingRepository.get(serverId)
            ?: return "Server not found."
        val trimmedHost = host.trim()
        if (trimmedHost.isEmpty()) return "Host can't be empty."
        if (port !in 1..65_535) return "Port must be 1..65535."

        // Preserve the query string (secret, client, server_fp, plus any
        // future params) verbatim — we only rewrite the authority.
        val query = existing.pairingUrl.substringAfter('?', "")
        if (query.isEmpty()) return "Existing pairing has no query — re-pair from scratch."
        val newUrl = "${PairingUrl.SCHEME}://$trimmedHost:$port?$query"

        // Round-trip through the parser to catch any invalid hostname
        // characters before we persist.
        PairingUrl.parse(newUrl).getOrElse {
            return "Address invalid: ${it.message}"
        }

        val newLabel = label.trim().ifEmpty { "$trimmedHost:$port" }
        val updated = existing.copy(pairingUrl = newUrl, label = newLabel)
        pairingRepository.upsert(updated)
        _pairedServers.value = pairingRepository.loadAll()

        // If this is the active server, reconnect against the new address.
        // switchToServer no-ops on (active && client != null), so we tear
        // down explicitly to force a fresh connect attempt.
        if (_activeServerId.value == serverId) {
            tearDownConnection()
            _activeServerId.value = null   // make switchToServer treat us as a new bind
            switchToServer(serverId)
        }
        return null
    }

    /**
     * Switch the active connection to [serverId]: tear down the
     * existing [RemoteClient], reset per-session UI caches, and connect
     * to the new server's pairing URL.
     *
     * No-op when [serverId] is already active. Returns silently when
     * the server id is unknown — caller is responsible for ensuring it
     * came from [pairedServers].
     */
    fun switchToServer(serverId: String) {
        if (_activeServerId.value == serverId && client != null) return
        val server = pairingRepository.get(serverId) ?: return
        val parsed = PairingUrl.parse(server.pairingUrl).getOrElse {
            _state.value = UiState.Disconnected(lastUrl = server.pairingUrl, error = it.message)
            return
        }
        // Tear down anything tied to the previous server. Per-session
        // caches are wiped here (not on the new connect) so the user
        // doesn't briefly see server A's transcript while server B's
        // initial fetch is pending.
        tearDownConnection()
        _activeServerId.value = serverId
        pairingRepository.setActive(serverId)
        pairingRepository.setLastConnected(serverId, System.currentTimeMillis())
        _pairedServers.value = pairingRepository.loadAll()
        _pairing.value = parsed
        _state.value = UiState.Connecting
        viewModelScope.launch {
            drainExpiredQueueEntries()
            val newClient = RemoteClient(
                url = parsed,
                queueStore = queueStore,
                onMessageExpired = ::handleExpiredMessage,
            )
            client = newClient
            newClient.connect(viewModelScope)
                .onFailure {
                    _state.value = UiState.Disconnected(lastUrl = server.pairingUrl, error = it.message)
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
                    _state.value = UiState.Disconnected(lastUrl = server.pairingUrl, error = it.message)
                }
            startObservingConnectionState(newClient)
        }
    }

    /**
     * Remove [serverId] from the paired-server list, also wiping its
     * scoped repository state (drafts, queue, lastSeen markers, nav
     * route). If it was the active server, fall back to the next-most-
     * recently-connected entry; if none remain, drop into the
     * unpaired / pairing-screen state.
     */
    fun removeServer(serverId: String) {
        val wasActive = _activeServerId.value == serverId
        // Wipe per-server scoped state BEFORE removing the entry so
        // the active-server provider still reports [serverId] for the
        // clearAll() calls below.
        if (wasActive) {
            queueStore.clear()
            draftRepository.clearAll()
            lastSeenRepository.clearAll()
            navStateRepository.clear()
        } else {
            // Briefly point repositories at [serverId] so we wipe the
            // *right* server's keys, then restore.
            val previous = _activeServerId.value
            _activeServerId.value = serverId
            queueStore.clear()
            draftRepository.clearAll()
            lastSeenRepository.clearAll()
            navStateRepository.clear()
            _activeServerId.value = previous
        }
        pairingRepository.remove(serverId)
        _pairedServers.value = pairingRepository.loadAll()
        if (wasActive) {
            tearDownConnection()
            _activeServerId.value = null
            val next = _pairedServers.value.firstOrNull()
            if (next != null) {
                switchToServer(next.id)
            } else {
                _state.value = UiState.Disconnected()
            }
        }
    }

    /**
     * Forget every paired server in one shot — the destructive
     * "factory reset" path. Used by `:app/test` smoke or a hypothetical
     * "Forget all" affordance; the per-server Settings screen calls
     * [removeServer] instead.
     */
    fun forgetAllServers() {
        for (server in pairingRepository.loadAll()) {
            pairingRepository.remove(server.id)
        }
        _pairedServers.value = emptyList()
        tearDownConnection()
        _activeServerId.value = null
        // Wipe the scoped repositories' entire prefs files — the
        // active-server provider is now null so clearAll falls back to
        // wiping the whole file.
        queueStore.clear()
        draftRepository.clearAll()
        lastSeenRepository.clearAll()
        navStateRepository.clear()
        _state.value = UiState.Disconnected()
    }

    /**
     * Tear down the per-server connection / observers / UI caches.
     * Does NOT clear the active-server pointer — that's the caller's
     * responsibility (because [switchToServer] reassigns it
     * immediately, while [removeServer] sometimes routes through
     * "fall back to another server").
     */
    private fun tearDownConnection() {
        connectionObserverJob?.cancel()
        sessionObserverJob?.cancel()
        sessionDetailObserverJob?.cancel()
        client?.close()
        client = null
        sessionStateSubscribed = false
        sessionDetailSubscribed = false
        openSessionId = null
        lastSeenEntryIndex.clear()
        _pairing.value = null
        _solutions.value = UiData.Loading
        _sessions.value = UiData.Loading
        _session.value = UiData.Loading
        _agents.value = UiData.Loading
        _optimisticEntries.value = emptyList()
        _isLoadingOlder.value = false
        _sessionChildren.value = emptyMap()
        _connectionBanner.value = ConnectionBanner.Hidden
        _rawConnectionState.value = ConnectionState.Disconnected
    }

    /**
     * Walk the disk-backed queue and TTL-expire any entry older than
     * [RemoteClient.DEFAULT_QUEUE_TTL_MS]. Each expired message gets
     * routed through [handleExpiredMessage] so the bounce-to-input
     * recovery fires once at boot — equivalent to what
     * [RemoteClient.flushQueue] would do on the next Connected
     * transition, but earlier so the user sees the bounce as soon as
     * they open the offending session.
     *
     * Called from [switchToServer] before constructing the new
     * [RemoteClient] so the rehydrate-on-connect path inside the
     * client only sees surviving entries.
     */
    private fun drainExpiredQueueEntries() {
        val now = System.currentTimeMillis()
        val ttl = RemoteClient.DEFAULT_QUEUE_TTL_MS
        for (msg in queueStore.loadAll()) {
            if (now - msg.enqueuedAtMs >= ttl) {
                handleExpiredMessage(msg)
                queueStore.remove(msg.id)
            }
        }
    }

    /**
     * Bounce-to-input recovery: when a queued send expires or fails
     * terminally, stash the user's content as a pending bounce on
     * [draftRepository] so the next time the user opens that session,
     * the OutlinedTextField prefills with the failed text and a
     * snackbar surfaces. Survives app restarts because both halves
     * (the queue + the bounce slot) are on disk.
     *
     * Only `remote.solution_agent.send_message` payloads carry a
     * recoverable user-visible body. Other queued methods (none today,
     * but the API is open) are dropped silently — the bounce contract
     * is specifically about "the text I typed shouldn't disappear",
     * not generic RPC recovery.
     */
    private fun handleExpiredMessage(message: QueuedMessage) {
        if (message.method != "remote.solution_agent.send_message") return
        val params = (message.params as? JsonObject) ?: return
        val sessionId = params["session_id"]?.jsonPrimitive?.content ?: return
        val content = params["content"]?.jsonPrimitive?.content ?: return
        if (content.isBlank()) return
        draftRepository.setBounced(sessionId, content)
    }

    /**
     * Auto-resume entry point called from `MainActivity.onCreate`.
     *
     * Returns the cold-start landing destination based on the paired-
     * server count:
     *   - `pairing` when nothing is paired (true first-launch).
     *   - `solutions` when exactly one server is paired (R-6b
     *     auto-resume behavior preserved).
     *   - `servers` when 2+ are paired so the user picks which to
     *     connect to.
     *
     * Side-effect: in the single- and multi-server cases, also
     * synchronously kicks off the connect for whichever server should
     * be active (the previously-active one if set, else the first in
     * the [PairingRepository.loadAll] sort order). Multi-server callers
     * land on `servers` regardless — the connect-while-on-Servers state
     * is harmless (the screen shows the per-row connection pill from
     * [rawConnectionState]).
     */
    fun coldStartLandingRoute(): String {
        val servers = pairingRepository.loadAll()
        _pairedServers.value = servers
        if (servers.isEmpty()) return "pairing"
        val preferredId = pairingRepository.activeServerId()
            ?.takeIf { id -> servers.any { it.id == id } }
            ?: servers.first().id
        switchToServer(preferredId)
        return if (servers.size == 1) "solutions" else "servers"
    }

    /**
     * Forget the persisted pairing for the currently-active server and
     * tear down the active connection. Used by the Settings screen's
     * "Forget this server" affordance — multi-paired case keeps the
     * other entries; single-server case falls back to the unpaired
     * state and the user lands on the pairing screen.
     */
    fun forgetPairing() {
        val active = _activeServerId.value ?: return
        removeServer(active)
    }

    /**
     * The raw [ConnectionState] of the active client, suitable for direct
     * surfacing on the Settings screen (the [connectionBanner] flow elides
     * Connected/Disconnected because the nav-level banner is "transient
     * trouble" only). Emits [ConnectionState.Disconnected] when there is
     * no active client. Updated from [startObservingConnectionState] on
     * every wire-level state transition.
     */
    private val _rawConnectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val rawConnectionState: StateFlow<ConnectionState> = _rawConnectionState.asStateFlow()

    /**
     * Human-readable reason why a load-from-server attempt can't be made
     * right now. Used to populate `UiData.Error` messages on the
     * solutions/sessions/session/agents screens when [client] is null
     * (pre-first-connect) or its underlying transport is between
     * lifecycle states. Surfaces the SAME classified text the
     * [ConnectionStateBanner] uses, so the user doesn't see one screen
     * saying "not connected" while the banner says "Server unreachable
     * — Reconnecting in 4s".
     */
    private fun notConnectedMessage(): String = when (val s = _rawConnectionState.value) {
        is ConnectionState.FailedTerminal -> s.failure.userMessage
        is ConnectionState.Reconnecting ->
            s.lastFailure?.userMessage ?: "Reconnecting to the server…"
        ConnectionState.Connecting ->
            "Still connecting to the server — try again in a moment."
        ConnectionState.Connected ->
            "Connection just dropped. Retrying in the background."
        ConnectionState.Disconnected ->
            "No active server connection. Pick or pair a server first."
    }

    /**
     * Translate [RemoteClient.connectionState] into the UI banner and
     * trigger a session re-fetch whenever we transition into Connected
     * from a non-Connected state — that's the "I just came back, my
     * desktop may have moved on" recovery hook the user-facing chat
     * surface depends on for not silently lagging behind.
     */
    private fun startObservingConnectionState(client: RemoteClient) {
        connectionObserverJob?.cancel()
        connectionObserverJob = viewModelScope.launch {
            var previousConnected = false
            client.connectionState.collect { state ->
                _rawConnectionState.value = state
                _connectionBanner.value = when (state) {
                    is ConnectionState.Reconnecting -> ConnectionBanner.Reconnecting(
                        attempt = state.attempt,
                        nextRetryMs = state.nextRetryMs,
                        reason = state.lastFailure?.userMessage,
                    )
                    is ConnectionState.FailedTerminal ->
                        ConnectionBanner.FailedTerminal(state.failure.userMessage)
                    ConnectionState.Connected,
                    ConnectionState.Connecting,
                    ConnectionState.Disconnected -> ConnectionBanner.Hidden
                }
                val isConnected = state is ConnectionState.Connected
                if (isConnected && !previousConnected) {
                    // Re-entered Connected — fetch any entries that landed
                    // on the desktop while we were offline. R-6e wires
                    // `resumeSession` which uses the persisted lastSeen
                    // marker to issue an incremental `after_index` slice
                    // instead of a full transcript refetch, and falls back
                    // to a paginated initial pull when the marker is absent
                    // or the slice reveals a gap.
                    val activeSession = openSessionId
                    if (activeSession != null) {
                        resumeSession(activeSession)
                    }
                    // Stamp last-connected on the active server so the
                    // Servers screen reflects "most recently used" order.
                    _activeServerId.value?.let { sid ->
                        pairingRepository.setLastConnected(sid, System.currentTimeMillis())
                        _pairedServers.value = pairingRepository.loadAll()
                    }
                }
                previousConnected = isConnected
            }
        }
    }

    fun refreshSolutions() {
        val active = client
        if (active == null) {
            _solutions.value = UiData.Error(notConnectedMessage())
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
            _sessions.value = UiData.Error(notConnectedMessage())
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
                // Routed through RemoteClient.subscribe so the active
                // subscription set is tracked and replayed on every
                // successful reconnect handshake (R-6a).
                runCatching { active.subscribe(listOf("agent_session_state_changed")) }
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
     * Open the chat surface for one session — initial poll + diff streaming.
     *
     * **Initial fetch:** `get_session` with `include_full_content=true` and
     * `include_images=true` so the first paint already has rich content.
     *
     * **Diff streaming (R-5e wire shape):** the post-R-5e
     * `agent_session_message_appended` notification carries the new
     * `entry_index` + `preview`. We append a placeholder entry built from
     * those fields immediately (so the bubble appears with truncated text
     * the moment the frame lands), then fire a per-entry
     * `get_session_entry` request in the background to upgrade the
     * placeholder to its full markdown + images. Same flow on mutate (when
     * `entry_index < currentEntries.size`).
     *
     * The previous R-5d behaviour re-fetched the whole transcript on every
     * append. With long sessions and tool-heavy turns that was bandwidth-
     * happy and added ≥1-RTT lag per append; the new flow keeps the user
     * looking at a stable list and stitches one entry at a time.
     *
     * `agent_session_state_changed` is handled by re-polling `get_session`
     * — state changes are infrequent and we want a clean rebase against
     * the server's view (handles edge cases like cancelled-then-resumed).
     *
     * Subscriptions for `agent_session_message_appended` and
     * `agent_session_state_changed` happen here too — they overlap with
     * [startObservingSessions]'s state subscription, but the server is
     * idempotent against duplicate kinds in subscribe calls.
     */
    fun openSession(sessionId: String) {
        val active = client
        if (active == null) {
            _session.value = UiData.Error(notConnectedMessage())
            return
        }
        openSessionId = sessionId
        _session.value = UiData.Loading
        _isLoadingOlder.value = false
        _optimisticEntries.value = emptyList()
        // Seed the in-memory high-water marker from disk if absent —
        // a cold-started VM doesn't have any previous-process state.
        // The disk read is one SharedPreferences.getInt call; trivial.
        if (lastSeenEntryIndex[sessionId] == null) {
            lastSeenRepository.get(sessionId)?.let { lastSeenEntryIndex[sessionId] = it }
        }
        // R-6e: paginated initial load — `count: 50` pulls the newest 50
        // entries instead of the entire transcript. The reconnect-resume
        // path goes through [resumeSession] which uses `after_index` to
        // catch up incrementally; this entry point is for "I just opened
        // the chat surface, give me the most recent page".
        viewModelScope.launch {
            fetchInitialPage(active, sessionId)
        }
        // F-phone: prime the chip row data. The screen renders the row
        // hidden while children is empty, so this is a no-flicker request
        // — by the time the children data lands the messages list is also
        // ready and the row appears alongside it.
        loadChildren(sessionId)
        sessionDetailObserverJob?.cancel()
        sessionDetailObserverJob = viewModelScope.launch {
            if (!sessionDetailSubscribed) {
                // Routed through RemoteClient.subscribe so the kinds are
                // tracked + auto-replayed across reconnects (R-6a). F-phone
                // adds `agent_session_created` so the chip row can refresh
                // when the parent agent spawns a new sub-agent.
                runCatching {
                    active.subscribe(
                        listOf(
                            "agent_session_message_appended",
                            "agent_session_state_changed",
                            "agent_session_created",
                        ),
                    )
                }
                    .onSuccess { sessionDetailSubscribed = true }
                // failure is non-fatal — initial transcript is still shown.
            }
            active.notifications.collect { frame ->
                val params = (frame as? JsonObject)?.get("params") as? JsonObject ?: return@collect
                val kind = params["kind"]?.jsonPrimitive?.content ?: return@collect
                val data = params["data"] as? JsonObject
                when (kind) {
                    "agent_session_created" -> {
                        // F-phone: a new session appeared. When the parent
                        // matches the session this screen is showing,
                        // re-fetch children so the chip row picks up the
                        // newcomer. Top-level creates (parent_session_id
                        // null) are no-ops for this screen.
                        val payload = data?.let {
                            runCatching {
                                JsonRpc.json.decodeFromJsonElement(
                                    SessionCreatedPayload.serializer(),
                                    it,
                                )
                            }.getOrNull()
                        } ?: return@collect
                        val parent = payload.parentSessionId ?: return@collect
                        if (parent == openSessionId) {
                            loadChildren(parent)
                        }
                    }
                    "agent_session_message_appended" -> {
                        if (data == null) {
                            // Defensive — refetch whole transcript so we
                            // never miss an entry from a malformed frame.
                            refreshSession(sessionId)
                            return@collect
                        }
                        val payload = runCatching {
                            JsonRpc.json.decodeFromJsonElement(
                                MessageAppendedPayload.serializer(),
                                data,
                            )
                        }.getOrNull()
                        if (payload == null) {
                            // Older server (pre-R-5e) sends frames without
                            // entry_index — fall back to full refetch.
                            refreshSession(sessionId)
                            return@collect
                        }
                        if (payload.sessionId != openSessionId) return@collect
                        // Track the high-water mark — used by the
                        // reconnect path in startObservingConnectionState
                        // to decide whether a full re-fetch is needed.
                        val prev = lastSeenEntryIndex[payload.sessionId] ?: -1
                        if (payload.entryIndex > prev) {
                            lastSeenEntryIndex[payload.sessionId] = payload.entryIndex
                            // R-6d: persist on every advance. Disk writes
                            // are debounced inside SharedPreferences;
                            // a burst of 5 message_appended frames in a
                            // running turn won't trash the I/O scheduler.
                            lastSeenRepository.set(payload.sessionId, payload.entryIndex)
                        }
                        applyAppendedPlaceholder(payload)
                        fetchAndReplaceEntry(sessionId, payload.entryIndex)
                    }
                    "agent_session_state_changed" -> {
                        val notifSessionId = data?.get("session_id")?.jsonPrimitive?.content
                        if (notifSessionId != null && notifSessionId != openSessionId) return@collect
                        refreshSession(sessionId)
                    }
                    else -> return@collect
                }
            }
        }
    }

    /**
     * Fetch the immediate children of [sessionId] via
     * `remote.solution_agent.get_session_children` (F-server) and seat them
     * in [sessionChildren] keyed by parent id. Used by `SessionDetailScreen`
     * to populate the sub-agents chip row on entry.
     *
     * Failure is silent: the chip row collapses to its "no children" state
     * (hidden) when the entry is absent. Re-attempt happens on the next
     * `agent_session_created` notification or the next screen entry.
     */
    fun loadChildren(sessionId: String) {
        val active = client ?: return
        val params = buildJsonObject { put("session_id", sessionId) }
        viewModelScope.launch {
            runCatching { active.call("remote.solution_agent.get_session_children", params) }
                .mapCatching { resp ->
                    val err = resp.error
                    if (err != null) error(err.message)
                    val result = resp.result ?: error("missing result")
                    JsonRpc.json.decodeFromJsonElement(
                        GetSessionChildrenResult.serializer(),
                        result,
                    )
                }
                .onSuccess { result ->
                    _sessionChildren.value = _sessionChildren.value + (sessionId to result.children)
                }
        }
    }

    /**
     * Apply the optimistic placeholder for an `agent_session_message_appended`
     * notification. Two cases:
     *
     *  - `entryIndex == entries.size`: pure append. We extend the entries
     *    list with a placeholder built from `(role, preview)`.
     *  - `entryIndex < entries.size`: mutation of an existing entry (e.g.
     *    tool-call status flipped from `running` to `done`). We replace
     *    the slot in place.
     *  - `entryIndex > entries.size`: gap (we missed frames or the server
     *    skipped indices). Fall back to a full transcript refetch — safer
     *    than fabricating empty intermediate entries.
     *
     * Either way the per-entry RPC then runs to upgrade the placeholder
     * to its full markdown + images.
     */
    private fun applyAppendedPlaceholder(payload: MessageAppendedPayload) {
        val current = _session.value
        if (current !is UiData.Loaded) {
            // No baseline yet — let the in-flight `get_session` settle.
            return
        }
        val entries = current.value.entries
        val placeholder = EntrySummary(role = payload.role, preview = payload.preview)
        val newEntries = when {
            payload.entryIndex == entries.size -> entries + placeholder
            payload.entryIndex < entries.size ->
                entries.toMutableList().also { it[payload.entryIndex] = placeholder }
            else -> {
                // Index past the end with a gap — defer to a full refetch.
                val active = client ?: return
                viewModelScope.launch {
                    runCatching { fetchFullSession(active, payload.sessionId) }
                }
                return
            }
        }
        _session.value = UiData.Loaded(current.value.copy(entries = newEntries))
    }

    /**
     * Fetch one entry by index and splice it into the loaded transcript,
     * replacing whatever placeholder is currently at that slot. Silently
     * no-ops if the user navigated away mid-flight, or if the index is
     * past the (possibly stale) entries list after the RPC returns.
     */
    private fun fetchAndReplaceEntry(sessionId: String, index: Int) {
        val active = client ?: return
        viewModelScope.launch {
            val result = runCatching {
                active.getSessionEntry(sessionId, index, includeImages = true)
            }.getOrNull() ?: return@launch
            if (openSessionId != sessionId) return@launch
            val current = _session.value as? UiData.Loaded ?: return@launch
            val entries = current.value.entries
            val newEntries = when {
                index < entries.size ->
                    entries.toMutableList().also { it[index] = result.entry }
                index == entries.size -> entries + result.entry
                else -> {
                    // Gap appeared — full refetch is the simplest recovery.
                    runCatching { fetchFullSession(active, sessionId) }
                    return@launch
                }
            }
            _session.value = UiData.Loaded(current.value.copy(entries = newEntries))
            // Optimistic user bubbles get reconciled against the upgraded
            // entry too — if the new entry is the user echo, the bubble
            // can now drop.
            reconcileOptimistic(newEntries)
        }
    }

    /** Side-effecting helper that re-runs `refreshSession` from a coroutine. */
    private suspend fun fetchFullSession(active: RemoteClient, sessionId: String) {
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("include_full_content", true)
            put("include_images", true)
        }
        runCatching { active.call("remote.solution_agent.get_session", params) }
            .mapCatching { resp ->
                val err = resp.error
                if (err != null) error(err.message)
                val result = resp.result ?: error("missing result")
                JsonRpc.json.decodeFromJsonElement(GetSessionResult.serializer(), result)
            }
            .onSuccess { result ->
                if (openSessionId != sessionId) return@onSuccess
                _session.value = UiData.Loaded(result)
                reconcileOptimistic(result.entries)
            }
    }

    fun closeSession() {
        sessionDetailObserverJob?.cancel()
        sessionDetailObserverJob = null
        openSessionId = null
        _session.value = UiData.Loading
        _isLoadingOlder.value = false
        _optimisticEntries.value = emptyList()
    }

    /**
     * Paginated initial fetch: pull the newest [SESSION_PAGE_SIZE] entries
     * via `get_session { count }` and seat them as the baseline `_session`
     * value. Updates the persistent lastSeen marker to the absolute index
     * of the newest entry so the next reconnect's [resumeSession] can
     * issue an `after_index` slice rather than another initial pull.
     *
     * Stops being a no-op when the user has navigated away mid-flight
     * ([openSessionId] differs from [sessionId]). The check guards
     * `_session` from being clobbered by a stale response from a session
     * the user already closed.
     */
    private suspend fun fetchInitialPage(active: RemoteClient, sessionId: String) {
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("include_full_content", true)
            put("include_images", true)
            put("count", SESSION_PAGE_SIZE)
        }
        runCatching { active.call("remote.solution_agent.get_session", params) }
            .mapCatching { resp ->
                val err = resp.error
                if (err != null) error(err.message)
                val result = resp.result ?: error("missing result")
                JsonRpc.json.decodeFromJsonElement(GetSessionResult.serializer(), result)
            }
            .onSuccess { result ->
                if (openSessionId != sessionId) return@onSuccess
                _session.value = UiData.Loaded(result)
                _isLoadingOlder.value = false
                reconcileOptimistic(result.entries)
                // Advance the persistent lastSeen marker to the newest
                // entry's absolute index. Guarded against the -1 sentinel
                // from pre-R-6e servers — those clients fall back to a
                // full refetch on reconnect, identical to the R-6d path.
                result.entries.lastOrNull()?.takeIf { it.index >= 0 }?.let { newest ->
                    val prev = lastSeenEntryIndex[sessionId] ?: -1
                    if (newest.index > prev) {
                        lastSeenEntryIndex[sessionId] = newest.index
                        lastSeenRepository.set(sessionId, newest.index)
                    }
                }
            }
            .onFailure {
                if (openSessionId != sessionId) return@onFailure
                if (_session.value !is UiData.Loaded) {
                    _session.value = UiData.Error(it.message ?: "unknown error")
                }
            }
    }

    /**
     * Fetch the page of entries immediately preceding the oldest entry
     * already loaded for the open session and prepend them. Caller (the
     * `SessionDetailScreen` LazyColumn scroll observer) is responsible
     * for not firing while [isLoadingOlder] is true; we also dedupe
     * inside this method so a sloppy caller doesn't double-fire.
     *
     * No-ops when:
     *   - the user navigated away,
     *   - no baseline session yet (still loading),
     *   - the oldest loaded entry is index 0 (we have the whole history),
     *   - or the oldest loaded entry has the `-1` sentinel from a
     *     pre-R-6e server (can't compute a `before_index` cursor → bail).
     */
    fun loadOlder(sessionId: String) {
        if (openSessionId != sessionId) return
        if (_isLoadingOlder.value) return
        val active = client ?: return
        val current = _session.value as? UiData.Loaded ?: return
        val oldest = current.value.entries.firstOrNull() ?: return
        val oldestIndex = oldest.index
        if (oldestIndex <= 0) return
        _isLoadingOlder.value = true
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("include_full_content", true)
            put("include_images", true)
            put("before_index", oldestIndex)
            put("count", SESSION_PAGE_SIZE)
        }
        viewModelScope.launch {
            runCatching { active.call("remote.solution_agent.get_session", params) }
                .mapCatching { resp ->
                    val err = resp.error
                    if (err != null) error(err.message)
                    val result = resp.result ?: error("missing result")
                    JsonRpc.json.decodeFromJsonElement(GetSessionResult.serializer(), result)
                }
                .onSuccess { result ->
                    if (openSessionId != sessionId) {
                        _isLoadingOlder.value = false
                        return@onSuccess
                    }
                    val latest = _session.value as? UiData.Loaded
                    if (latest == null) {
                        _isLoadingOlder.value = false
                        return@onSuccess
                    }
                    // Prepend, deduplicating by index just in case the
                    // optimistic-append path raced us and seated an entry
                    // at an overlapping slot (shouldn't happen because
                    // `agent_session_message_appended` always points past
                    // the tail, but the guard is cheap).
                    val existingIndices = latest.value.entries.mapNotNull {
                        it.index.takeIf { i -> i >= 0 }
                    }.toHashSet()
                    val older = result.entries.filterNot {
                        it.index >= 0 && existingIndices.contains(it.index)
                    }
                    val merged = older + latest.value.entries
                    // Carry the higher totalCount value (server should
                    // report the same value across both pages; we just
                    // prefer the freshest one).
                    val newTotal = maxOf(latest.value.totalCount, result.totalCount)
                    _session.value = UiData.Loaded(
                        latest.value.copy(entries = merged, totalCount = newTotal),
                    )
                    _isLoadingOlder.value = false
                }
                .onFailure {
                    _isLoadingOlder.value = false
                    // Surface as a one-shot snackbar via the existing
                    // _sendError channel — the chat list stays usable;
                    // the user can scroll-trigger again to retry.
                    _sendError.tryEmit("Couldn't load older messages: ${it.message ?: "?"}")
                }
        }
    }

    /**
     * Reconnect-resume path: when the WebSocket flips back to Connected
     * and a session detail screen is active, fetch only the entries that
     * landed on the desktop while we were offline using `after_index =
     * lastSeen`. Falls back to a paginated initial pull ([fetchInitialPage])
     * when:
     *   - no persisted marker exists (first-time open of this session),
     *   - the server reports a `total_count` such that
     *     `entries.size + slice.size < totalCount` — that's the "gap
     *     detected" branch from the spec, meaning either our marker is
     *     stale (e.g. session was reset server-side) or entries were
     *     deleted. Full refetch is the safest recovery.
     *
     * Also a no-op when there's no current session loaded (a defensive
     * guard — the connection observer only calls this when [openSessionId]
     * is set, but multiple Disconnected → Connected cycles racing the
     * tear-down of closeSession could land us here).
     */
    fun resumeSession(sessionId: String) {
        val active = client ?: return
        if (openSessionId != sessionId) return
        val current = _session.value
        val lastSeen = lastSeenEntryIndex[sessionId]
            ?: lastSeenRepository.get(sessionId)
        if (lastSeen == null || current !is UiData.Loaded) {
            // No marker or no baseline — do a paginated initial pull.
            viewModelScope.launch { fetchInitialPage(active, sessionId) }
            return
        }
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("include_full_content", true)
            put("include_images", true)
            put("after_index", lastSeen)
        }
        viewModelScope.launch {
            runCatching { active.call("remote.solution_agent.get_session", params) }
                .mapCatching { resp ->
                    val err = resp.error
                    if (err != null) error(err.message)
                    val result = resp.result ?: error("missing result")
                    JsonRpc.json.decodeFromJsonElement(GetSessionResult.serializer(), result)
                }
                .onSuccess { result ->
                    if (openSessionId != sessionId) return@onSuccess
                    val latest = _session.value as? UiData.Loaded ?: return@onSuccess
                    // Gap detection: if the server reports a totalCount
                    // larger than what current page + slice can account
                    // for, our lastSeen marker is stale (likely because
                    // entries were deleted, or the session was reset on
                    // the desktop while we were offline). Fall back to a
                    // full paginated initial pull so the user lands on
                    // a coherent view rather than a torn one.
                    //
                    // Skip the check entirely when totalCount is the -1
                    // sentinel (pre-R-6e server): we have no signal to
                    // detect a gap, so we trust the incremental slice and
                    // append it.
                    if (result.totalCount >= 0 &&
                        latest.value.entries.size + result.entries.size < result.totalCount
                    ) {
                        fetchInitialPage(active, sessionId)
                        return@onSuccess
                    }
                    // Dedupe — the server may include the lastSeen entry
                    // itself depending on inclusive/exclusive boundary
                    // semantics. The current spk-editor server treats
                    // `after_index` as exclusive (R-6e protocol), but the
                    // guard costs nothing.
                    val existingIndices = latest.value.entries.mapNotNull {
                        it.index.takeIf { i -> i >= 0 }
                    }.toHashSet()
                    val fresh = result.entries.filterNot {
                        it.index >= 0 && existingIndices.contains(it.index)
                    }
                    if (fresh.isEmpty()) {
                        // Server is in sync with us — still update the
                        // totalCount (best-known value).
                        if (result.totalCount >= 0 && result.totalCount != latest.value.totalCount) {
                            _session.value = UiData.Loaded(
                                latest.value.copy(totalCount = result.totalCount),
                            )
                        }
                        return@onSuccess
                    }
                    val merged = latest.value.entries + fresh
                    val newTotal = maxOf(latest.value.totalCount, result.totalCount)
                    _session.value = UiData.Loaded(
                        latest.value.copy(entries = merged, totalCount = newTotal),
                    )
                    reconcileOptimistic(merged)
                    fresh.lastOrNull()?.takeIf { it.index >= 0 }?.let { newest ->
                        val prev = lastSeenEntryIndex[sessionId] ?: -1
                        if (newest.index > prev) {
                            lastSeenEntryIndex[sessionId] = newest.index
                            lastSeenRepository.set(sessionId, newest.index)
                        }
                    }
                }
                .onFailure {
                    // Failed resume is recoverable — the next
                    // message_appended frame will trigger the per-entry
                    // RPC, and the next manual refresh re-tries this
                    // path. No user-visible surface needed.
                }
        }
    }

    private fun refreshSession(sessionId: String) {
        val active = client ?: return
        // Ask for rich content + images on every full refetch — the per-
        // entry RPC handles diff streaming, but full refetches happen on
        // initial open, on state_changed, and on the gap-recovery path so
        // we want them to land as fully-populated as the wire allows.
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("include_full_content", true)
            put("include_images", true)
        }
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
     * Optimistically append [text] as a user entry, then queue the
     * server-side `send_message`.
     *
     * R-6a switched from [RemoteClient.call] to
     * [RemoteClient.queueCall] so that a "tap Send while in a tunnel"
     * survives a network gap: the request waits in memory until the next
     * Connected transition (or expires after the default 5-minute TTL).
     * On failure (TTL or terminal close), the optimistic bubble is
     * removed and the user gets a snackbar.
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
            runCatching { active.queueCall("remote.solution_agent.send_message", params) }
                .mapCatching { resp ->
                    val err = resp.error
                    if (err != null) error(err.message)
                }
                .onFailure {
                    // Remove only the specific optimistic entry — another
                    // send may have raced past us and we don't want to
                    // drop the wrong one.
                    _optimisticEntries.value = _optimisticEntries.value.filterNot { it === optimistic }
                    val msg = when (it) {
                        is RemoteClient.QueueTtlException ->
                            "send timed out — the editor was offline for too long"
                        is RemoteClient.ClosedException ->
                            "send cancelled — connection closed"
                        else -> it.message ?: "send failed"
                    }
                    _sendError.tryEmit(msg)
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

    /**
     * Fetch the list of agent adapters registered on the paired editor.
     *
     * Calls `remote.solution_agent.list_agents` and populates [agents]. Also
     * resets [lastCreateAutoOpened] — the flag belongs to a single dialog
     * lifecycle, and re-opening the dialog re-loads agents anyway.
     */
    fun loadAgents() {
        val active = client
        _lastCreateAutoOpened.value = false
        if (active == null) {
            _agents.value = UiData.Error(notConnectedMessage())
            return
        }
        _agents.value = UiData.Loading
        viewModelScope.launch {
            runCatching {
                active.call("remote.solution_agent.list_agents", buildJsonObject {})
            }
                .mapCatching { resp ->
                    val err = resp.error
                    if (err != null) error(err.message)
                    val result = resp.result ?: error("missing result")
                    JsonRpc.json
                        .decodeFromJsonElement(ListAgentsResult.serializer(), result)
                        .agents
                }
                .onSuccess { _agents.value = UiData.Loaded(it) }
                .onFailure { _agents.value = UiData.Error(it.message ?: "unknown error") }
        }
    }

    /**
     * Create a new session for [solutionId] backed by [agentId].
     *
     * Production behaviour: if the desktop reports
     * `no_active_workspace_for_solution`, we transparently call
     * `remote.solutions.open` for the same solution and retry
     * `create_session` exactly once. This makes the cold-start path —
     * user pairs from QR, immediately wants a new session — work without
     * making them go back to the desktop to manually open the solution
     * first. We only retry once: a second failure surfaces the original
     * error message via [sendError] so the user can adjust and retry.
     *
     * [initialMessage] is optional. When provided we pass it through as
     * `initial_message`; the server interprets that as the first user
     * turn for the freshly-created session.
     *
     * [onCreated] is invoked from the main thread with the new session
     * id once the server confirms creation — the dialog uses this to
     * navigate the user into the chat surface.
     */
    fun createSession(
        solutionId: String,
        agentId: String,
        initialMessage: String?,
        onCreated: (sessionId: String) -> Unit,
    ) {
        val active = client
        if (active == null) {
            _sendError.tryEmit(notConnectedMessage())
            return
        }
        if (_createSessionInFlight.value) return
        _createSessionInFlight.value = true
        _lastCreateAutoOpened.value = false
        viewModelScope.launch {
            val firstAttempt = attemptCreateSession(active, solutionId, agentId, initialMessage)
            firstAttempt
                .onSuccess { sessionId ->
                    _createSessionInFlight.value = false
                    onCreated(sessionId)
                }
                .onFailure { firstError ->
                    val message = firstError.message.orEmpty()
                    if (message.contains("no_active_workspace_for_solution", ignoreCase = true)) {
                        val opened = attemptOpenSolution(active, solutionId)
                        if (opened.isFailure) {
                            _createSessionInFlight.value = false
                            val openErr = opened.exceptionOrNull()?.message ?: "open failed"
                            _sendError.tryEmit("Couldn't open solution: $openErr")
                            return@launch
                        }
                        val retry = attemptCreateSession(active, solutionId, agentId, initialMessage)
                        retry
                            .onSuccess { sessionId ->
                                _lastCreateAutoOpened.value = true
                                _createSessionInFlight.value = false
                                onCreated(sessionId)
                            }
                            .onFailure { retryErr ->
                                _createSessionInFlight.value = false
                                _sendError.tryEmit(
                                    "Create session failed after opening: ${retryErr.message ?: "?"}",
                                )
                            }
                    } else {
                        _createSessionInFlight.value = false
                        _sendError.tryEmit("Create session failed: ${message.ifBlank { "?" }}")
                    }
                }
        }
    }

    /**
     * Single attempt at `remote.solution_agent.create_session`. Returns a
     * `Result` so the caller (with its own retry policy) can branch on the
     * error message without swallowing the failure.
     */
    private suspend fun attemptCreateSession(
        active: RemoteClient,
        solutionId: String,
        agentId: String,
        initialMessage: String?,
    ): Result<String> {
        val params = buildJsonObject {
            put("solution_id", solutionId)
            put("agent_id", agentId)
            if (!initialMessage.isNullOrBlank()) {
                put("initial_message", initialMessage)
            }
        }
        return runCatching {
            val resp = active.call("remote.solution_agent.create_session", params)
            val err = resp.error
            if (err != null) error(err.message)
            val result = resp.result ?: error("missing result")
            JsonRpc.json
                .decodeFromJsonElement(CreateSessionResult.serializer(), result)
                .sessionId
        }
    }

    /**
     * Single attempt at `remote.solutions.open`. Returns `Result<Unit>`
     * because the server response is `{}`; we only care whether the RPC
     * succeeded for use in the auto-open-retry path of [createSession].
     */
    private suspend fun attemptOpenSolution(
        active: RemoteClient,
        solutionId: String,
    ): Result<Unit> {
        val params = buildJsonObject { put("solution_id", solutionId) }
        return runCatching {
            val resp = active.call("remote.solutions.open", params)
            val err = resp.error
            if (err != null) error(err.message)
        }
    }

    private val _sendError = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    val sendError: kotlinx.coroutines.flow.SharedFlow<String> = _sendError

    override fun onCleared() {
        sessionObserverJob?.cancel()
        sessionDetailObserverJob?.cancel()
        connectionObserverJob?.cancel()
        client?.close()
        client = null
    }
}
