package ru.sipaha.spkremote.app.vm

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.sipaha.spkremote.app.data.EncryptedQueueStore
import ru.sipaha.spkremote.app.data.PairedServer
import ru.sipaha.spkremote.app.data.PairingRepository
import ru.sipaha.spkremote.core.CapabilitiesDto
import ru.sipaha.spkremote.core.ConnectionState
import ru.sipaha.spkremote.core.JsonRpc
import ru.sipaha.spkremote.core.PairingUrl
import ru.sipaha.spkremote.core.RemoteClient
import ru.sipaha.spkremote.core.SUPPORTED_WIRE_SCHEMA_VERSION
import ru.sipaha.spkremote.core.isServerTooNew
import java.util.UUID

/**
 * Owns the multi-server pairing lifecycle and the single active
 * [RemoteClient] instance. Carved out of `MainViewModel` so the
 * coordinator stays focused on wiring; pairing CRUD + connection state
 * observation live here.
 *
 * Shares the `viewModelScope` with the coordinator — coroutines launched
 * here are tied to the same `onCleared` boundary.
 *
 * ### Threading & callback contract
 *
 *  - **[client] is mutated only on coroutines launched on [scope]** —
 *    almost always inside [connectionMutex] which serialises every
 *    add/switch/edit/remove/forget transition. UI threads read it via
 *    [activeClient]; the read is a single Kotlin field load and is
 *    safe-publish by virtue of being assigned inside the mutex's
 *    happens-before fence.
 *  - **[_rawConnectionState] / [_connectionBanner] are written from the
 *    [startObservingConnectionState] collector** which runs on [scope]'s
 *    default dispatcher (Main for `viewModelScope`).
 *  - **[ConnectionLifecycle] callbacks** fire on whichever dispatcher
 *    [ConnectionManager] happened to be on when they were invoked:
 *      * `onClientBound` / `onTearDown` / `onBeforeSwitch` — Main (called
 *        from inside the [connectionMutex.withLock] block of a
 *        scope.launch).
 *      * `onReconnected` — Main (called from inside
 *        [startObservingConnectionState]'s collector).
 *      * `onMessageExpired` — invoked from [RemoteClient]'s background
 *        worker; the callback runs on whatever thread Remoteclient
 *        emits on. Listeners must self-route.
 *      * `onError` — typically Main.
 *
 * ### Concurrency invariants
 *
 *  1. [switchToServer] / [addServer] / [editServer] / [removeServer] /
 *     [forgetAllServers] are wrapped in [connectionMutex] so a concurrent
 *     UI tap can't interleave a teardown + connect of one transition
 *     with the connect of another (audit Fix D).
 *  2. [tearDownConnection] is idempotent and synchronous from the
 *     caller's perspective — `client.close()` returns before the
 *     [onTearDown] callback fires (audit Fix S).
 */
internal class ConnectionManager(
    private val application: Application,
    private val scope: CoroutineScope,
    private val lifecycle: ConnectionLifecycle,
) {

    private val pairingRepository: PairingRepository = PairingRepository.get(application)

    private val _activeServerId = MutableStateFlow<String?>(null)
    val activeServerId: StateFlow<String?> = _activeServerId.asStateFlow()

    private val _pairedServers = MutableStateFlow<List<PairedServer>>(emptyList())
    val pairedServers: StateFlow<List<PairedServer>> = _pairedServers.asStateFlow()

    private val _state = MutableStateFlow<UiState>(UiState.Disconnected())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _pairing = MutableStateFlow<PairingUrl?>(null)
    val pairing: StateFlow<PairingUrl?> = _pairing.asStateFlow()

    private val _connectionBanner = MutableStateFlow<ConnectionBanner>(ConnectionBanner.Hidden)
    val connectionBanner: StateFlow<ConnectionBanner> = _connectionBanner.asStateFlow()

    private val _rawConnectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val rawConnectionState: StateFlow<ConnectionState> = _rawConnectionState.asStateFlow()

    /**
     * Wall-clock of the moment the connection last dropped from [Connected]
     * (falling edge). Read by the chat connection banner to render "last
     * exchange N min ago" while we're NOT connected. Null until the first
     * drop this run.
     */
    private val _lastConnectedMs = MutableStateFlow<Long?>(null)
    val lastConnectedMs: StateFlow<Long?> = _lastConnectedMs.asStateFlow()

    /** Disk-backed outbound-queue store (R-6d). Scoped per-server via [activeServerId]. */
    private val queueStore: EncryptedQueueStore =
        EncryptedQueueStore.get(application) { _activeServerId.value }

    private var client: RemoteClient? = null
    private var connectionObserverJob: Job? = null

    /**
     * Serialises every server-lifecycle mutation. See class KDoc
     * invariant 1.
     */
    private val connectionMutex = Mutex()

    init {
        // Hydrate active-id + server list off Main (audit Fix I).
        // EncryptedSharedPreferences reads can hit disk + the keystore
        // subsystem; doing them inside the AndroidViewModel construction
        // path used to risk an ANR on cold start.
        scope.launch(Dispatchers.IO) {
            val initialActive = pairingRepository.activeServerId()
            val initialServers = pairingRepository.loadAll()
            withContext(Dispatchers.Main) {
                _activeServerId.value = initialActive
                _pairedServers.value = initialServers
            }
        }
    }

    fun activeClient(): RemoteClient? = client

    /**
     * Same classification used by both the nav banner and the
     * per-screen `UiData.Error` text. See KDoc on the original
     * `notConnectedMessage` for the contract.
     */
    fun notConnectedMessage(): String = when (val s = _rawConnectionState.value) {
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

    /** See [MainViewModel.addServer] KDoc — behaviour is preserved verbatim. */
    fun addServer(rawUrl: String) {
        val parsed = PairingUrl.parse(rawUrl).getOrElse {
            // SECURITY: do NOT echo the raw user URL — it may contain
            // the HMAC secret query param, which would shoulder-surf
            // out of the manual-entry text field on the QrPairingScreen
            // (audit Fix G).
            _state.value = UiState.Disconnected(lastUrl = null, error = it.message)
            return
        }
        scope.launch {
            connectionMutex.withLock {
                val fingerprintHex = parsed.fingerprint.joinToString("") { "%02x".format(it) }
                val (server, refreshed) = withContext(Dispatchers.IO) {
                    val existing = pairingRepository.loadAll()
                        .firstOrNull { it.fingerprintHex == fingerprintHex }
                    val srv = if (existing != null) {
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
                    pairingRepository.upsert(srv)
                    srv to pairingRepository.loadAll()
                }
                _pairedServers.value = refreshed
                switchToServerLocked(server.id, force = false)
            }
        }
    }

    /** See [MainViewModel.editServer] KDoc. */
    fun editServer(
        serverId: String,
        label: String,
        host: String,
        port: Int,
    ): String? {
        val trimmedHost = host.trim()
        if (trimmedHost.isEmpty()) return "Host can't be empty."
        if (port !in 1..65_535) return "Port must be 1..65535."

        scope.launch {
            connectionMutex.withLock {
                val existing = withContext(Dispatchers.IO) { pairingRepository.get(serverId) }
                if (existing == null) {
                    lifecycle.onError("Server not found.")
                    return@withLock
                }
                val query = existing.pairingUrl.substringAfter('?', "")
                if (query.isEmpty()) {
                    lifecycle.onError("Existing pairing has no query — re-pair from scratch.")
                    return@withLock
                }
                val newUrl = "${PairingUrl.SCHEME}://$trimmedHost:$port?$query"
                val newParsed = PairingUrl.parse(newUrl).getOrElse {
                    lifecycle.onError("Address invalid: ${it.message}")
                    return@withLock
                }
                val newLabel = label.trim().ifEmpty { "$trimmedHost:$port" }
                val updated = existing.copy(pairingUrl = newUrl, label = newLabel)
                val refreshed = withContext(Dispatchers.IO) {
                    pairingRepository.upsert(updated)
                    pairingRepository.loadAll()
                }
                _pairedServers.value = refreshed

                // Audit Fix F: only force a re-bind if a transport field
                // changed. Label-only edits used to bounce the active
                // SessionDetailScreen into a permanent spinner because
                // `onBeforeSwitch` nulls openSessionId and the DisposableEffect
                // doesn't re-fire on identity-equal sessionId.
                val previous = PairingUrl.parse(existing.pairingUrl).getOrNull()
                val transportChanged = previous == null ||
                    previous.host != newParsed.host ||
                    previous.port != newParsed.port ||
                    !previous.secret.contentEquals(newParsed.secret) ||
                    !previous.fingerprint.contentEquals(newParsed.fingerprint)

                if (_activeServerId.value == serverId && transportChanged) {
                    // Force [switchToServerLocked] to actually re-bind
                    // even though the active id matches (audit Fix E
                    // — no more `_activeServerId = null` transient).
                    switchToServerLocked(serverId, force = true)
                }
            }
        }
        return null
    }

    /** Switch the active server. See [MainViewModel.switchToServer] KDoc. */
    fun switchToServer(serverId: String) {
        if (_activeServerId.value == serverId && client != null) return
        scope.launch {
            connectionMutex.withLock {
                switchToServerLocked(serverId, force = false)
            }
        }
    }

    /**
     * Core switch implementation. Caller MUST hold [connectionMutex].
     * When [force] is true the no-op-if-same check is skipped — used by
     * [editServer] to rebind after a transport change without going
     * through the public [switchToServer]'s idempotence guard
     * (audit Fix E).
     */
    private suspend fun switchToServerLocked(serverId: String, force: Boolean) {
        if (!force && _activeServerId.value == serverId && client != null) return
        val server = withContext(Dispatchers.IO) { pairingRepository.get(serverId) }
            ?: return
        val parsed = PairingUrl.parse(server.pairingUrl).getOrElse {
            // SECURITY: do NOT surface the full pairing URL — `lastUrl`
            // is read by [QrPairingScreen]'s ManualEntry which pre-
            // fills it into a plain `OutlinedTextField`, and the raw
            // pairing URL contains the HMAC `secret` query param
            // (shoulder-surfing leak). Saved-server retries don't
            // need the URL in the UI text field anyway — the next
            // [switchToServer] re-reads it from [PairingRepository]
            // by id.
            _state.value = UiState.Disconnected(lastUrl = null, error = it.message)
            return
        }
        // Per-session UI state lives in SessionStore — drop it first
        // so a stale openSessionId doesn't try to resume against the
        // new server's client.
        lifecycle.onBeforeSwitch()
        tearDownConnection()
        _activeServerId.value = serverId
        val refreshed = withContext(Dispatchers.IO) {
            pairingRepository.setActive(serverId)
            pairingRepository.setLastConnected(serverId, System.currentTimeMillis())
            pairingRepository.loadAll()
        }
        _pairedServers.value = refreshed
        _pairing.value = parsed
        _state.value = UiState.Connecting
        drainExpiredQueueEntries()
        val newClient = RemoteClient(
            url = parsed,
            queueStore = queueStore,
            onMessageExpired = lifecycle::onMessageExpired,
        )
        client = newClient
        // Let the coordinator hydrate per-server caches now that the
        // client exists (even pre-connect — list cache reads are
        // synchronous on the SharedPreferences-backed repo).
        lifecycle.onClientBound(parsed, newClient)
        newClient.connect(scope)
            .onFailure {
                // SECURITY: see the parse-failure branch above.
                _state.value = UiState.Disconnected(lastUrl = null, error = it.message)
                return
            }
        runCatching { newClient.call("remote.editor.capabilities") }
            .onSuccess { resp ->
                // Decode the typed CapabilitiesDto so both the version
                // banner and the wire-schema gate read from one source of
                // truth (and so missing fields fall back to documented
                // sentinels — "unknown" / 0 — without inline jsonPrimitive
                // scraping). `ignoreUnknownKeys` on JsonRpc.json drops the
                // build-metadata / transport hints the server emits.
                val capabilities = resp.structuredContent()
                    ?.let { runCatching {
                        JsonRpc.json.decodeFromJsonElement(
                            CapabilitiesDto.serializer(),
                            it,
                        )
                    }.getOrNull() }
                    ?: CapabilitiesDto()
                if (isServerTooNew(capabilities.wireSchemaVersion)) {
                    // Hard gate: the server speaks a chat-wire shape we
                    // don't understand yet. Tear down the live client —
                    // any subsequent notification it pushes would land
                    // on observers that can't decode it, and the user's
                    // only recourse is to update the app anyway.
                    tearDownConnection()
                    _state.value = UiState.IncompatibleServer(
                        serverWireSchemaVersion = capabilities.wireSchemaVersion,
                        supportedWireSchemaVersion = SUPPORTED_WIRE_SCHEMA_VERSION,
                        message = "This server needs a newer version of the app. Please update.",
                    )
                    return@onSuccess
                }
                _state.value = UiState.Connected(capabilities.protocolVersion)
                startObservingConnectionState(newClient)
            }
            .onFailure {
                // SECURITY: see the parse-failure branch above.
                _state.value = UiState.Disconnected(lastUrl = null, error = it.message)
                startObservingConnectionState(newClient)
            }
    }

    /**
     * Remove [serverId]. The coordinator is responsible for wiping the
     * per-server scoped repositories (drafts / lastSeen / nav /
     * list-cache) BEFORE calling this — those repositories live on the
     * stores, not on us, and we can't reach across without coupling.
     * The outbound queue is owned by us and is wiped here.
     *
     * Suspend so the coordinator can await completion before returning
     * to the caller (audit Fix J).
     */
    suspend fun removeServer(serverId: String) {
        connectionMutex.withLock {
            val wasActive = _activeServerId.value == serverId
            val refreshed = withContext(Dispatchers.IO) {
                pairingRepository.remove(serverId)
                queueStore.clearFor(serverId)
                pairingRepository.loadAll()
            }
            _pairedServers.value = refreshed
            if (wasActive) {
                tearDownConnection()
                _activeServerId.value = null
                val next = refreshed.firstOrNull()
                if (next != null) {
                    switchToServerLocked(next.id, force = false)
                } else {
                    _state.value = UiState.Disconnected()
                }
            }
        }
    }

    /**
     * See [MainViewModel.forgetAllServers] KDoc. Suspends so the
     * coordinator's coroutine can chain its scoped-repository wipes
     * after this returns (audit Fix A — without suspending the
     * coordinator's wipes would race the active-id null-out and
     * silently target a stale server).
     */
    suspend fun forgetAllServers() {
        connectionMutex.withLock {
            withContext(Dispatchers.IO) {
                for (server in pairingRepository.loadAll()) {
                    pairingRepository.remove(server.id)
                }
                // Audit Fix A: wipe the entire queue-store prefs file,
                // not just the active server's blob. The old
                // `queueStore.clear()` only touched
                // `queued_messages_v2:<activeServerId>` and left every
                // other server's blob persisted.
                queueStore.clearAllServers()
            }
            _pairedServers.value = emptyList()
            tearDownConnection()
            _activeServerId.value = null
            _state.value = UiState.Disconnected()
        }
    }

    /** Tear down the per-server connection / observers. */
    fun tearDownConnection() {
        // Idempotent fast-exit: if there's no client we have nothing to
        // tear down, and re-firing [onTearDown] would needlessly reset
        // the stores to Loading.
        if (client == null && connectionObserverJob == null) return
        connectionObserverJob?.cancel()
        connectionObserverJob = null
        // Close the client synchronously BEFORE firing onTearDown so
        // any per-message-dispatcher coroutines spawned by RemoteClient
        // observe the closed state before the stores wipe their caches
        // (audit Fix S).
        client?.close()
        client = null
        // Reset only the connection-owned state. Per-store UI caches are
        // wiped by the [onTearDown] callback so the SolutionStore /
        // SessionStore can drop their own flows on the same edge.
        lifecycle.onTearDown()
        _pairing.value = null
        _connectionBanner.value = ConnectionBanner.Hidden
        _rawConnectionState.value = ConnectionState.Disconnected
        _lastConnectedMs.value = null
    }

    /**
     * Cold-start helper. Returns the landing route.
     *
     * NOTE: this still reads the pairing repo synchronously on the
     * Main thread because the caller ([MainActivity.onCreate]) needs
     * a synchronous return for [setContent]'s `initialRoute`
     * parameter. EncryptedSharedPreferences first-read can briefly
     * touch disk + the keystore subsystem; the cost is bounded but
     * non-zero on a cold device. Converting to a suspended /
     * StateFlow-driven splash is tracked as backlog (audit Fix I —
     * full variant deferred).
     */
    fun coldStartLandingRoute(): String {
        val servers = pairingRepository.loadAll()
        _pairedServers.value = servers
        if (servers.isEmpty()) return "pairing"
        val preferredId = pairingRepository.activeServerId()
            ?.takeIf { id -> servers.any { it.id == id } }
            ?: servers.first().id
        switchToServer(preferredId)
        return if (servers.size == 1) "workspace" else "servers"
    }

    private fun drainExpiredQueueEntries() {
        val now = System.currentTimeMillis()
        val ttl = RemoteClient.DEFAULT_QUEUE_TTL_MS
        for (msg in queueStore.loadAll()) {
            if (now - msg.enqueuedAtMs >= ttl) {
                lifecycle.onMessageExpired(msg)
                queueStore.remove(msg.id)
            }
        }
    }

    private fun startObservingConnectionState(client: RemoteClient) {
        connectionObserverJob?.cancel()
        connectionObserverJob = scope.launch {
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
                if (previousConnected && !isConnected) {
                    // Falling edge: the connection just dropped. Stamp the
                    // moment it last worked so the chat banner can show
                    // "last exchange N min ago" while we're offline.
                    _lastConnectedMs.value = System.currentTimeMillis()
                }
                if (isConnected && !previousConnected) {
                    lifecycle.onReconnected()
                    _activeServerId.value?.let { sid ->
                        // Audit Fix H: EncryptedSharedPreferences =
                        // AES-GCM + disk. Don't pin the collector on
                        // Main while the keystore subsystem is busy.
                        val refreshed = withContext(Dispatchers.IO) {
                            pairingRepository.setLastConnected(sid, System.currentTimeMillis())
                            pairingRepository.loadAll()
                        }
                        _pairedServers.value = refreshed
                    }
                }
                previousConnected = isConnected
            }
        }
    }
}
