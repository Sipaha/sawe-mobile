package ru.sipaha.sawe.app.vm

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import ru.sipaha.sawe.app.data.AttachmentDraftRepository
import ru.sipaha.sawe.app.data.DraftRepository
import ru.sipaha.sawe.app.data.EncryptedQueueStore
import ru.sipaha.sawe.app.data.ListCacheRepository
import ru.sipaha.sawe.app.data.InFlightUploadsRepository
import ru.sipaha.sawe.app.data.PairedServer
import ru.sipaha.sawe.app.data.PairingRepository
import ru.sipaha.sawe.app.data.PendingSendsRepository
import ru.sipaha.sawe.app.data.SessionHistoryRepository
import ru.sipaha.sawe.app.data.warmUpEncryptedPrefs
import ru.sipaha.sawe.core.CapabilitiesDto
import ru.sipaha.sawe.core.ConnectionState
import ru.sipaha.sawe.core.JsonRpc
import ru.sipaha.sawe.core.JsonRpcResponse
import ru.sipaha.sawe.core.PairingUrl
import ru.sipaha.sawe.core.QueuedMessage
import ru.sipaha.sawe.core.RemoteClient
import ru.sipaha.sawe.core.SUPPORTED_WIRE_SCHEMA_VERSION
import ru.sipaha.sawe.core.ServerFeatures
import ru.sipaha.sawe.core.isServerTooNew
import ru.sipaha.sawe.core.isServerTooOld
import ru.sipaha.sawe.core.toServerFeatures
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
 * ### The connection is driven by the OBSERVED wire state
 *
 * [RemoteClient.connect] reports only the outcome of the FIRST handshake
 * attempt; the client's own lifecycle loop keeps retrying afterwards
 * regardless. So the connection observer is installed *before* `connect()`
 * and [state] is derived from the observed [ConnectionState] via
 * [deriveUiState] — never from the one-shot `connect()` result. A cold
 * start in a lift therefore recovers on its own the moment the link comes
 * back, instead of pinning the UI to a "Disconnected" that nothing ever
 * clears.
 *
 * The wire-schema gate ([schemaGate]) runs on EVERY rising `Connected`
 * edge rather than once per client, and only a response that actually
 * carries a `wire_schema_version` can trip it: a JSON-RPC error envelope
 * (the desktop answers `-32603` while its local MCP proxy is still
 * starting), or any object without the field, is a transient probe
 * failure — not "server too old".
 *
 * ### Network awareness is handover-only
 *
 * [registerNetworkCallback] watches `onAvailable` / `onLost` on the DEFAULT
 * network, which covers the Wi-Fi → cellular case. It does NOT cover a
 * captive portal that keeps a validated-looking network while black-holing
 * traffic; `onCapabilitiesChanged` is deliberately not observed (it fires
 * on every signal fluctuation) and that case still falls back to the ping
 * timeout.
 *
 * ### Threading & callback contract
 *
 *  - **[client] is mutated only on coroutines launched on [scope]** —
 *    almost always inside [connectionMutex] which serialises every
 *    add/switch/edit/remove/forget transition. UI threads read it via
 *    [activeClient]; the read is a single Kotlin field load and is
 *    safe-publish by virtue of being assigned inside the mutex's
 *    happens-before fence.
 *  - **[_rawConnectionState] / [_connectionBanner] / [_state] are written
 *    from the [startObservingConnectionState] collector** which runs on
 *    [scope]'s default dispatcher (Main for `viewModelScope`).
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

    private val _serverFeatures = MutableStateFlow(ServerFeatures.NONE)

    /**
     * What the peer on the CURRENT socket advertised — a mirror of
     * [RemoteClient.serverFeatures] for the bound client, and
     * [ServerFeatures.NONE] whenever there is no client at all.
     *
     * The mirror is a plain relay: the client owns the reset (it drops the
     * set on every transition out of `Connected`), and [startSchemaGate] is
     * the only writer of a non-empty set. Stores read this — rather than
     * reaching for `activeClient()?.serverFeatures` — when they need the
     * negotiated set as a *flow*; a one-shot read of the bound client's
     * value is equivalent and is what the RPC call sites use.
     */
    val serverFeatures: StateFlow<ServerFeatures> = _serverFeatures.asStateFlow()

    /**
     * Cold-start landing route, resolved asynchronously off the Main
     * thread (the pairing list lives in an encrypted prefs file, whose first
     * open unwraps a Tink keyset through the Android Keystore).
     * Null until the pairing repository has been read; the nav graph shows
     * a splash while it is.
     */
    private val _landingRoute = MutableStateFlow<String?>(null)
    val landingRoute: StateFlow<String?> = _landingRoute.asStateFlow()

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

    /**
     * `@Volatile` because [activeClient] is read WITHOUT the mutex, from
     * whatever thread a store happens to be on (`WorkspaceClientImpl` reads
     * it off-Main). The mutex orders the writers against each other; it
     * publishes nothing to a reader that never takes it.
     */
    @Volatile
    private var client: RemoteClient? = null
    private var connectionObserverJob: Job? = null

    /**
     * Relays the bound client's [RemoteClient.serverFeatures] into
     * [_serverFeatures]. Bound and cancelled in lock-step with
     * [connectionObserverJob] so the mirror can never outlive its source.
     */
    private var featuresMirrorJob: Job? = null

    /**
     * Per-client liveness heartbeat — pings the wire on a slow cadence
     * while the app is in the foreground to catch a server whose WebSocket
     * reader still answers pings but whose dispatcher is wedged. Cancelled
     * in [tearDownConnection] and on the background edge; rebound by
     * [startObservingConnectionState] / [onAppForegrounded].
     */
    private var heartbeatJob: Job? = null

    /** In-flight wire-schema probe for the current `Connected` edge. */
    private var schemaGateJob: Job? = null

    /**
     * False between the background edge ([onAppBackgrounded]) and the next
     * foreground edge. Drives [visibilityPolicy]: a phone on a table with
     * the screen off has nobody to show a "server unresponsive" banner to,
     * and both the RPC heartbeat and the reconnect ladder wake the radio
     * for a UI nobody is looking at.
     */
    private var appInForeground: Boolean = true

    /**
     * Delayed park after a background edge — see [BACKGROUND_PARK_GRACE_MS].
     */
    private var parkGraceJob: Job? = null

    /**
     * False from a background edge until [BACKGROUND_PARK_GRACE_MS] later.
     * A trip through the system file picker or the share sheet stops our
     * Activity, so a park applied on the edge itself would fire for an
     * excursion measured in seconds (the same spurious-edge class the audit
     * flagged for the liveness probe).
     */
    private var parkGraceElapsed: Boolean = false

    /**
     * Last park state pushed to the client, so [applyVisibility] can skip a
     * redundant `setReconnectPaused(false)` — that call pokes the client's
     * wake channel and would spend a dial on a backoff that is legitimately
     * counting down. A freshly constructed [RemoteClient] is unparked, so
     * `false` is the honest initial value — starting from `null` spent one
     * pointless poke on the very first background edge.
     */
    private var ladderParked: Boolean = false

    /**
     * Whether the durable queue still holds something to send, re-sampled on
     * every park evaluation. Parking on top of a message the user sent a
     * moment before locking the screen would silently hold it until the next
     * foreground edge.
     */
    private var pendingWireWork: Boolean = false

    /**
     * Whether an attachment upload still needs the wire, re-sampled on every
     * park evaluation. Same reasoning as [pendingWireWork]: an upload that is
     * `Paused` waiting for a reconnect can only be revived by the ladder we
     * would be parking.
     */
    private var uploadWireWork: Boolean = false

    /**
     * Wall-clock of the background edge that armed the current park. Bounds
     * how long the queue carve-out may defer the park — see
     * [BACKGROUND_PARK_MAX_DEFERRAL_MS].
     */
    private var backgroundedAtMs: Long = 0L

    /**
     * Reads the upload layer's "still needs the wire" snapshot. Set by the
     * coordinator; null in tests and until wiring runs, which reads as idle
     * (park normally).
     */
    internal var uploadWireWorkProvider: (() -> UploadWireWork)? = null

    /**
     * Wall-clock of the last time the wire answered anything — a probe, a
     * heartbeat, or the schema gate. Lets a foreground edge skip a redundant
     * round-trip when the socket demonstrably worked moments ago (N-15/CM-4).
     */
    private var lastWireResponseMs: Long = 0L

    /** Per-observer scratch state; see [publishUiState]. */
    private var observed: ObservedConnection = ObservedConnection()

    /**
     * The default network our socket is bound to, or null before the first
     * callback. Deliberately NOT cleared by `onLost`: a null here must mean
     * "we have never been told", because that is the one case where doing
     * nothing is right.
     */
    private var lastDefaultNetworkHandle: Long? = null

    /**
     * Set by `onLost` and consumed by the next `onAvailable`. An abrupt loss
     * (walking out of Wi-Fi range) delivers `onLost(old)` BEFORE
     * `onAvailable(new)`, and the replacement can even carry the same handle
     * after a flap — so the loss itself, not just a handle change, has to
     * count as "the socket underneath us is gone".
     */
    private var sawDefaultNetworkLoss: Boolean = false

    /**
     * Serialises every server-lifecycle mutation. See class KDoc
     * invariant 1.
     */
    private val connectionMutex = Mutex()

    init {
        scope.launch { hydrateAndAutoConnect() }
        registerNetworkCallback()
    }

    fun activeClient(): RemoteClient? = client

    /**
     * Pay the encrypted-prefs opens (Tink keyset unwrap through the Android
     * Keystore) once, eagerly, off the Main thread — instead of once per
     * store on Main across the cold-start path (N-56). The pairing repo and
     * the queue store are ours; the coordinator passes the rest.
     *
     * [drafts], [attachmentDrafts] and [listCache] joined the list when they
     * moved off plain files: `flushDraft` writes on Main from the compose
     * bar's `onDispose`, and the cached solutions list is read on the
     * cold-start path, so their first open must not be the one that unwraps
     * a keyset.
     *
     * [inFlightUploads] is warmed here rather than made async at its call
     * site: `UploadManager.resumeAllFromDisk` reads it on Main from the
     * `Connected` edge, and it has to stay synchronous because the deferred
     * -send waivers revived right after it require a populated `states` map.
     * Opening the file ahead of time is what takes the keystore unwrap off
     * that path without disturbing the ordering (UP-9). A plain read is the
     * warm-up: it resolves the same `by lazy` a `warmUp()` would.
     */
    suspend fun warmUpPersistence(
        pendingSends: PendingSendsRepository,
        history: SessionHistoryRepository,
        inFlightUploads: InFlightUploadsRepository,
        drafts: DraftRepository,
        attachmentDrafts: AttachmentDraftRepository,
        listCache: ListCacheRepository,
    ) {
        warmUpEncryptedPrefs(
            pairing = pairingRepository,
            queue = queueStore,
            pendingSends = pendingSends,
            history = history,
            drafts = drafts,
            attachmentDrafts = attachmentDrafts,
            listCache = listCache,
        )
        withContext(Dispatchers.IO) { runCatching { inFlightUploads.list() } }
    }

    /**
     * Read the paired-server list off Main, publish the cold-start landing
     * route, and connect to the preferred server.
     *
     * The auto-connect lives HERE, not in [MainActivity], so a ViewModel
     * constructed after the OS killed the process in the background
     * reconnects on its own. Gating it on `savedInstanceState == null`
     * used to leave the restored Activity staring at a permanent spinner
     * with no client behind it (N-10).
     */
    private suspend fun hydrateAndAutoConnect() {
        // The landing route MUST be published, whatever happens below. It is
        // the only thing that ends the nav graph's cold-start splash, and that
        // splash has no timeout, no error state and no back-press escape — an
        // exception out of the keystore-backed pairing read left the app on a
        // permanent spinner recoverable only by force-stop. `MainActivity`
        // used to compute the route synchronously as a backstop; it no longer
        // does (N-56), so the guarantee has to live here.
        val loaded: Result<Pair<List<PairedServer>, String?>> = runCatching {
            pairingRepository.loadAllOnIo() to pairingRepository.activeServerIdOnIo()
        }
        loaded.exceptionOrNull()?.let { failure ->
            if (failure is CancellationException) throw failure
            Log.e(TAG, "cold-start pairing read failed; landing on the QR screen", failure)
        }
        try {
            connectionMutex.withLock {
                val landing = resolveColdStartLanding(
                    loadServers = {
                        // A QR scan can win the race against the keystore
                        // open; its list is read from the same repository and
                        // is newer.
                        val servers = loaded.getOrThrow().first
                        if (_pairedServers.value.isEmpty()) {
                            _pairedServers.value = servers
                        }
                        _pairedServers.value
                    },
                    loadActiveId = { loaded.getOrThrow().second },
                )
                // Publish INSIDE the lock but BEFORE the connect attempt: a
                // `switchToServerLocked` that blocks on a slow keystore open
                // or the 20 s pre-open timeout must not hold the splash up.
                _landingRoute.value = landing.route
                val target = landing.connectToServerId
                if (target != null && client == null && _activeServerId.value == null) {
                    switchToServerLocked(target, force = false)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            Log.e(TAG, "cold-start auto-connect failed", failure)
        } finally {
            // Belt to the braces above: nothing reaches the nav graph unless
            // this flow moves off null.
            if (_landingRoute.value == null) {
                _landingRoute.value = FALLBACK_LANDING.route
            }
        }
    }

    /**
     * On-demand liveness probe for the foreground-resume edge. After a
     * Doze / background window a socket that still reads `Connected` can
     * be a zombie (the OS reports ESTABLISHED but no traffic flows), which
     * would otherwise only be caught by OkHttp's ping timeout.
     *
     *  - answered (including a JSON-RPC *error* envelope — the peer is
     *    talking, the wire is fine) → returns `true`; the caller refetches
     *    over the known-good socket.
     *  - silent twice in a row → [RemoteClient.forceReconnect] tears the
     *    zombie down so the lifecycle loop rebuilds it and
     *    [ConnectionLifecycle.onReconnected] drives the session catch-up;
     *    returns `false`.
     *
     * The first strike gets the full [PROBE_TIMEOUT_MS] because a single
     * timeout is not evidence on a roaming link — killing a healthy socket
     * costs a TLS handshake plus a full workspace refetch (N-15). The
     * second gets only [PROBE_RETRY_TIMEOUT_MS]: by then a slow-but-alive
     * link has already had its long budget, and the caller is a user
     * staring at a screen, so the worst case is ~17s rather than 24s.
     *
     * Skipped outright — reported alive — when the wire answered something
     * within [PROBE_SKIP_WINDOW_MS], so bouncing off a file picker doesn't
     * pay a round-trip per return.
     *
     * No-op returning `false` when not [ConnectionState.Connected] — a
     * reconnect is already running, so there's nothing to probe (and
     * `forceReconnect` would be a no-op there anyway).
     */
    suspend fun probeLivenessNow(): Boolean {
        val live = client ?: return false
        if (_rawConnectionState.value !is ConnectionState.Connected) {
            // Not Connected → a reconnect should already be progressing, but
            // after a Doze / VPN freeze the lifecycle can sit on the 30s
            // backoff cap. A caller that just opened a chat (or foregrounded)
            // must not be left on the stale cache placeholder waiting that out,
            // so kick the backoff now. `wakeReconnect` is a no-op unless we're
            // actually in Reconnecting, so this can't thrash a healthy wire.
            // Report not-live: the caller relies on the onReconnected →
            // resumeSession catch-up (the same path a user send trips via
            // broken-pipe), which is proven to land.
            live.wakeReconnect()
            return false
        }
        if (shouldSkipLivenessProbe(
                nowMs = System.currentTimeMillis(),
                lastWireResponseMs = lastWireResponseMs,
                windowMs = PROBE_SKIP_WINDOW_MS,
            )
        ) {
            return true
        }
        val first = pingWire(live, PROBE_TIMEOUT_MS)
        val decision = probeDecision(first, null).let { verdict ->
            if (verdict == ProbeDecision.Retry) {
                probeDecision(first, pingWire(live, PROBE_RETRY_TIMEOUT_MS))
            } else {
                verdict
            }
        }
        if (decision == ProbeDecision.Dead) {
            Log.w(TAG, "foreground-resume liveness probe: 2 pings unanswered — forcing reconnect")
            live.forceReconnect("Reconnecting…")
            return false
        }
        return true
    }

    /**
     * One `capabilities` round-trip capped at [timeoutMs].
     *
     * Mirrors the watchdog's cancellation discipline: `withTimeoutOrNull`
     * cancels the inner call on timeout, so don't wrap it in `runCatching`
     * — that would swallow `CancellationException` and disarm structured
     * cancellation.
     *
     * Any answer at all — including a JSON-RPC error envelope — stamps
     * [lastWireResponseMs]: the peer talked, which is the only thing this
     * probe is asking about.
     */
    private suspend fun pingWire(live: RemoteClient, timeoutMs: Long): ProbeAttempt {
        val resp = try {
            withTimeoutOrNull(timeoutMs) {
                live.call("remote.editor.capabilities")
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.d(TAG, "liveness ping failed: ${t.message}")
            null
        }
        if (resp != null) lastWireResponseMs = System.currentTimeMillis()
        return when {
            resp == null -> ProbeAttempt.Silent
            resp.error != null -> ProbeAttempt.ErrorEnvelope
            else -> ProbeAttempt.Answered
        }
    }

    /**
     * Same classification used by both the nav banner and the
     * per-screen `UiData.Error` text. See KDoc on the original
     * `notConnectedMessage` for the contract.
     */
    /**
     * Surface a pairing-URL problem without pinning a [UiState] that nothing
     * will recompute.
     *
     * `_state` is otherwise derived from the observed `ConnectionState`, and
     * `ConnectionState` is a `StateFlow` — an unchanged value emits nothing.
     * So writing `Disconnected` here while a DIFFERENT server is happily
     * connected sent the nav graph back to the picker and left it there
     * until the live client's wire state next moved, which is the same
     * stuck-UI shape N-09 set out to remove (CM-6). With a client bound the
     * message goes to the app-level notice channel instead; with no client
     * the pairing screen's inline error is still the right surface.
     */
    private fun reportPairingProblem(message: String?) {
        val text = message ?: "That pairing link isn't valid."
        if (client == null) {
            _state.value = UiState.Disconnected(lastUrl = null, error = text)
        } else {
            lifecycle.onError(text)
        }
    }

    fun notConnectedMessage(): String =
        notConnectedText(_rawConnectionState.value, _pairedServers.value.isNotEmpty())

    /** See [MainViewModel.addServer] KDoc — behaviour is preserved verbatim. */
    fun addServer(rawUrl: String) {
        val parsed = PairingUrl.parse(rawUrl).getOrElse {
            // SECURITY: do NOT echo the raw user URL — it may contain
            // the HMAC secret query param, which would shoulder-surf
            // out of the manual-entry text field on the QrPairingScreen
            // (audit Fix G).
            reportPairingProblem(it.message)
            return
        }
        scope.launch {
            connectionMutex.withLock {
                val fingerprintHex = parsed.fingerprint.joinToString("") { "%02x".format(it) }
                val (server, refreshed, previousUrl) = withContext(Dispatchers.IO) {
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
                    Triple(srv, pairingRepository.loadAll(), existing?.pairingUrl)
                }
                _pairedServers.value = refreshed
                // Re-scanning the QR of an already-paired server is the
                // user's recovery action after the desktop regenerated its
                // pairing secret: the merged entry keeps its id, so without
                // the force the idempotence guard would drop the rescan on
                // the floor and leave the old client holding a secret the
                // server no longer accepts (N-18).
                val force = shouldForceRebindOnPair(
                    previousUrl = previousUrl,
                    newUrl = rawUrl,
                    isTerminal = _rawConnectionState.value is ConnectionState.FailedTerminal,
                )
                switchToServerLocked(server.id, force = force)
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
        if (!shouldRebind(
                force = false,
                targetServerId = serverId,
                activeServerId = _activeServerId.value,
                hasClient = client != null,
                connectionState = _rawConnectionState.value,
            )
        ) {
            return
        }
        scope.launch {
            connectionMutex.withLock {
                switchToServerLocked(serverId, force = false)
            }
        }
    }

    /**
     * Rebuild the connection to the currently-active server. Backs the
     * "Retry" affordances on the terminal surfaces (incompatible-server
     * gate, re-pair banner) — those states tear the client down or park it
     * in `FailedTerminal`, from which nothing else in the app recovers
     * without a force-stop (N-20).
     */
    fun retryActiveServer() {
        val serverId = _activeServerId.value ?: return
        // Impatient taps: `force = true` defeats [shouldRebind]'s dedupe, so
        // without this each tap would enqueue its own teardown + TLS cycle
        // behind the mutex the previous one is holding (CM-7).
        if (connectionMutex.isLocked) return
        scope.launch {
            connectionMutex.withLock {
                switchToServerLocked(serverId, force = true)
            }
        }
    }

    /**
     * Core switch implementation. Caller MUST hold [connectionMutex].
     * When [force] is true the no-op-if-same check is skipped — used by
     * [editServer] to rebind after a transport change, by a QR re-scan of
     * an already-paired server, and by [retryActiveServer].
     */
    private suspend fun switchToServerLocked(serverId: String, force: Boolean) {
        if (!shouldRebind(
                force = force,
                targetServerId = serverId,
                activeServerId = _activeServerId.value,
                hasClient = client != null,
                connectionState = _rawConnectionState.value,
            )
        ) {
            return
        }
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
            reportPairingProblem(it.message)
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
            // Cross-process replay policy. Only `:app` knows the csid-dedupe
            // rules, and only the queue knows which editor process a frame
            // was actually written to — hence a predicate injected into
            // `:core` rather than a decision made on either side alone.
            replayGate = ::canReplayRehydratedSend,
        )
        client = newClient
        // A fresh client has its own ladder, unparked by construction.
        ladderParked = false
        // A client bound while the app is already backgrounded past its
        // grace must be parked from birth, not on the next visibility edge.
        applyVisibility()
        lifecycle.onClientBound(parsed, newClient)
        // Observe BEFORE connecting. `connect()` completes on the outcome
        // of the FIRST handshake attempt only; the client's lifecycle loop
        // keeps retrying afterwards either way, so the observer is the only
        // thing that can tell the UI the connection eventually came up
        // (N-09).
        startObservingConnectionState(newClient)
        newClient.connect(scope).onFailure { t ->
            if (_rawConnectionState.value is ConnectionState.Disconnected && client === newClient) {
                // The lifecycle loop never started (single-shot guard, an
                // already-closing client, a throwing rehydrate), so nothing
                // will ever emit a wire state and `deriveUiState` would hold
                // the UI on Connecting forever with `shouldRebind` refusing
                // to re-bind. Publish the failure so the user gets a surface
                // and the guards see a re-bindable state (CM-7).
                _state.value = UiState.Disconnected(lastUrl = null, error = t.message)
            }
            // Not a UI state transition: the observer already published
            // Reconnecting/FailedTerminal, and a transient first failure
            // resolves itself on the next attempt.
            Log.i(TAG, "first connect attempt failed (lifecycle loop keeps retrying): ${t.message}")
        }
    }

    /**
     * Remove [serverId]. The coordinator is responsible for wiping the
     * per-server scoped repositories (drafts / attachment drafts / nav /
     * list-cache / history cache / in-flight uploads / pending sends)
     * BEFORE calling this — those repositories live on the stores, not on
     * us, and we can't reach across without coupling. The outbound queue
     * is owned by us and is wiped here.
     *
     * Suspend so the coordinator can await completion before returning
     * to the caller (audit Fix J). MUST be called on the coordinator's
     * Main-confined scope — it mutates [client] and fires the
     * [ConnectionLifecycle] callbacks that the class KDoc pins to Main
     * (N-27).
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
        if (client == null && connectionObserverJob == null && heartbeatJob == null) return
        connectionObserverJob?.cancel()
        connectionObserverJob = null
        featuresMirrorJob?.cancel()
        featuresMirrorJob = null
        // No client ⇒ nothing has been negotiated. Written here rather than
        // left to the mirror because the mirror is gone by this point.
        _serverFeatures.value = ServerFeatures.NONE
        heartbeatJob?.cancel()
        heartbeatJob = null
        schemaGateJob?.cancel()
        schemaGateJob = null
        observed = ObservedConnection()
        // Close the client synchronously BEFORE firing onTearDown so
        // any per-message-dispatcher coroutines spawned by RemoteClient
        // observe the closed state before the stores wipe their caches
        // (audit Fix S).
        client?.close()
        client = null
        // Reset only the connection-owned state. Per-store UI caches are
        // wiped by the [onTearDown] callback so the catalog / session
        // stores can drop their own flows on the same edge.
        lifecycle.onTearDown()
        _pairing.value = null
        _connectionBanner.value = ConnectionBanner.Hidden
        _rawConnectionState.value = ConnectionState.Disconnected
        _lastConnectedMs.value = null
    }

    /**
     * Background edge from [ForegroundEventBus].
     *
     * The heartbeat stops at once — it is cheap to restart and there is
     * nobody to show its banner to. The reconnect ladder is parked only
     * after [BACKGROUND_PARK_GRACE_MS], and only if the durable queue is
     * empty by then (CM-3): our "background" edge is really "no started
     * Activity", which a full-screen system file picker also produces, and
     * parking on top of a message the user just sent would hold it silently
     * until they next open the app.
     */
    fun onAppBackgrounded() {
        appInForeground = false
        parkGraceElapsed = false
        applyVisibility()
        scheduleLadderPark()
    }

    /** Foreground edge from [ForegroundEventBus] — see [applyVisibility]. */
    fun onAppForegrounded() {
        appInForeground = true
        parkGraceJob?.cancel()
        parkGraceJob = null
        parkGraceElapsed = false
        pendingWireWork = false
        uploadWireWork = false
        applyVisibility()
    }

    /**
     * Arm (or re-arm) the delayed ladder park. Also used after a background
     * network change, which unparks to let the recovery dial through and
     * needs the park to come back on its own afterwards (CM-5).
     */
    private fun scheduleLadderPark() {
        parkGraceJob?.cancel()
        backgroundedAtMs = System.currentTimeMillis()
        parkGraceJob = scope.launch {
            delay(BACKGROUND_PARK_GRACE_MS)
            parkGraceElapsed = true
            // Re-evaluate until the park actually takes. A carve-out DEFERS
            // the park, it never cancels it: without this loop the first
            // evaluation was the only one, so a queue that drained (or an
            // upload that finished) two minutes into the background window
            // left the ladder running until the user came back.
            while (true) {
                val now = System.currentTimeMillis()
                // NOT runCatching: this is a suspend read, and swallowing its
                // CancellationException would let a foreground edge that
                // cancelled us finish one more evaluation and park the ladder
                // the user just came back to (N-28).
                val queueNonEmpty = try {
                    queueStore.loadAllOnIo().isNotEmpty()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    Log.w(TAG, "queue read failed during park evaluation", failure)
                    false
                }
                pendingWireWork = queueHoldsLadderOpen(
                    queueNonEmpty = queueNonEmpty,
                    nowMs = now,
                    backgroundedAtMs = backgroundedAtMs,
                    maxDeferralMs = BACKGROUND_PARK_MAX_DEFERRAL_MS,
                )
                val uploads = uploadWireWorkProvider?.invoke() ?: UploadWireWork.IDLE
                uploadWireWork = uploadHoldsLadderOpen(
                    uploadsActive = uploads.active,
                    nowMs = now,
                    lastProgressAtMs = uploads.lastProgressAtMs,
                    stallTimeoutMs = UPLOAD_STALL_PARK_MS,
                )
                applyVisibility()
                if (!pendingWireWork && !uploadWireWork) return@launch
                delay(LADDER_PARK_RECHECK_MS)
            }
        }
    }

    /**
     * Apply [visibilityPolicy] to the currently-bound client: park or
     * release the wire heartbeat and the reconnect ladder (N-19).
     *
     * Parking is deliberately cheap to be wrong about.
     * [RemoteClient.setReconnectPaused] only suspends the **backoff step**:
     * a connection that is up stays up and keeps delivering notifications,
     * an in-flight handshake finishes, and the durable queue and the
     * subscription set are untouched. What it stops is the thing that
     * actually costs battery — a phone whose screen has been off for an
     * hour bringing the radio up every 30s to redial a server it can't
     * reach.
     *
     * Release is prompt and resets the attempt counter, so a foreground
     * edge retries immediately instead of serving out a backoff that
     * elapsed in the dark. [RemoteClient.wakeReconnect] also releases it,
     * which is how a background network change that only needs a nudge
     * ([onDefaultNetworkAvailable]'s `Wake` branch) still gets through: an
     * event-driven retry is admitted, a periodic one is not. The branch
     * that tears the socket down cannot use that route — `wakeReconnect` is
     * gated on `Reconnecting` and the state is still `Connected` at that
     * instant — so it releases the park explicitly.
     *
     * Redundant transitions are suppressed ([ladderParked]): re-asserting
     * "not parked" pokes the client's wake channel, which would short-
     * circuit a backoff that is legitimately running.
     *
     * Called from both visibility edges, from the delayed park
     * ([scheduleLadderPark]) and from the client-bind path, so a client
     * constructed while the app has already been in the background past its
     * grace is parked from birth. (The first dial of [RemoteClient.connect]
     * is user-initiated and runs regardless.)
     *
     * Both carve-outs are bounded, because either could otherwise pin the
     * radio on all night: the queue one by wall-clock from the background
     * edge, the upload one by how long an upload has gone without moving.
     * See [queueHoldsLadderOpen] / [uploadHoldsLadderOpen].
     */
    private fun applyVisibility() {
        val policy = visibilityPolicy(
            appInForeground = appInForeground,
            hasClient = client != null,
            parkGraceElapsed = parkGraceElapsed,
            pendingWireWork = pendingWireWork,
            uploadWireWork = uploadWireWork,
        )
        if (policy.parkReconnectLadder != ladderParked) {
            ladderParked = policy.parkReconnectLadder
            client?.setReconnectPaused(policy.parkReconnectLadder)
        }
        if (policy.runHeartbeat) {
            startHeartbeat(client)
        } else {
            heartbeatJob?.cancel()
            heartbeatJob = null
        }
    }

    /**
     * The `spk_client_send_id`s still parked in the active server's durable
     * queue.
     *
     * Backs `SessionDetailStore.queuedCsidsProvider`: a persisted
     * pending-send marker is only still live while the send it names can
     * still happen, and after a process death the one remaining proof of
     * that is a matching entry in this queue (N-03). The queue store is
     * ours, so the lookup lives here; the store owns what to do with the
     * answer.
     *
     * Reads the whole per-server blob off Main. Not stamped csids (the
     * legacy text-only `send_message` method) simply don't appear.
     */
    suspend fun queuedClientSendIds(): Set<Long> =
        queuedClientSendIdsOf(queueStore.loadAllOnIo())

    /**
     * Withdraw the unwritten queue entry [queueId] on the user's behalf
     * (N-07), returning true only if it was taken back before anything was
     * written for it — whether it was still parked or already claimed by a
     * flush that had not reached its frame.
     *
     * Backs `SessionDetailStore.cancelQueuedCallProvider`. A false means the
     * message is unknown or no longer withdrawable — its frame is being
     * written, was written, or was refused and is being restored — so it may
     * have been delivered and the caller must not report it as cancelled.
     * Runs on IO because [RemoteClient.cancelQueued] deletes the record
     * synchronously through the encrypted queue store.
     */
    suspend fun cancelQueuedCall(queueId: String): Boolean = withContext(Dispatchers.IO) {
        client?.cancelQueued(queueId) ?: false
    }

    private suspend fun drainExpiredQueueEntries() {
        val now = System.currentTimeMillis()
        val ttl = RemoteClient.DEFAULT_QUEUE_TTL_MS
        val expired = queueStore.loadAllOnIo().filter { now - it.enqueuedAtMs >= ttl }
        if (expired.isEmpty()) return
        for (msg in expired) {
            lifecycle.onMessageExpired(msg)
        }
        withContext(Dispatchers.IO) {
            for (msg in expired) {
                queueStore.remove(msg.id)
            }
        }
    }

    /**
     * Per-observer scratch state. Recreated on every client bind; read by
     * [publishUiState] so the wire state, the "have we ever been up"
     * latch and the schema-gate outcome are projected from one place.
     */
    private class ObservedConnection {
        var everConnected: Boolean = false
        var protocolVersion: String? = null
        var firstGatePending: Boolean = false
    }

    private fun publishUiState() {
        val next = deriveUiState(
            state = _rawConnectionState.value,
            everConnected = observed.everConnected,
            protocolVersion = observed.protocolVersion,
            firstGatePending = observed.firstGatePending,
        )
        _state.value = next
    }

    private fun startObservingConnectionState(boundClient: RemoteClient) {
        connectionObserverJob?.cancel()
        featuresMirrorJob?.cancel()
        _serverFeatures.value = ServerFeatures.NONE
        observed = ObservedConnection()
        featuresMirrorJob = scope.launch {
            boundClient.serverFeatures.collect { _serverFeatures.value = it }
        }
        connectionObserverJob = scope.launch {
            var previousConnected = false
            boundClient.connectionState.collect { state ->
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
                    // "last exchange N min ago" while we're offline, and
                    // surface to the coordinator so flows that actively
                    // drive the wire (chunked uploads, etc.) can pause
                    // proactively instead of hanging on a send/ack until
                    // their own timeout fires — which would otherwise race
                    // the next onReconnected's resumeAll.
                    _lastConnectedMs.value = System.currentTimeMillis()
                    lifecycle.onConnectionInterrupted()
                }
                if (isConnected && !previousConnected) {
                    // Rising edge. Re-run the wire-schema probe every time:
                    // the first one can land in the window where the
                    // desktop's listener is already accepting but its local
                    // MCP proxy hasn't bound yet, and that must not be
                    // mistaken for an incompatible server (N-11).
                    observed.firstGatePending = !observed.everConnected
                    observed.everConnected = true
                    publishUiState()
                    startSchemaGate(boundClient)
                    startHeartbeat(boundClient)
                    lifecycle.onReconnected()
                    _activeServerId.value?.let { sid ->
                        // Audit Fix H: the encrypted prefs layer = AES-GCM
                        // + disk. Don't pin the collector on Main while the
                        // keystore subsystem is busy — and don't SUSPEND the
                        // collector on it either: `connectionState` is a
                        // StateFlow, so a sub-100ms Connected → Reconnecting
                        // → Connected cycle that elapsed during the write
                        // would be conflated away and cost us the
                        // `onReconnected` that re-subscribes (CM-7).
                        scope.launch {
                            val refreshed = withContext(Dispatchers.IO) {
                                pairingRepository.setLastConnected(sid, System.currentTimeMillis())
                                pairingRepository.loadAll()
                            }
                            _pairedServers.value = refreshed
                        }
                    }
                } else {
                    publishUiState()
                }
                previousConnected = isConnected
            }
        }
    }

    /**
     * Probe `remote.editor.capabilities` and apply the wire-schema gate.
     *
     * Runs in its own job (not inside the state collector) so a teardown
     * triggered by the gate does not tear down the state observer from
     * inside itself.
     *
     * The gate DOES cancel its own job when it decides `Incompatible`:
     * `tearDownConnection` cancels [schemaGateJob], which is this coroutine.
     * That is safe only because everything after the cancel point here is
     * non-suspending — `_state` is written BEFORE the teardown, and
     * `tearDownConnection` itself is not `suspend`. Making it suspend would
     * silently stop the `IncompatibleServer` state from ever being seen.
     */
    private fun startSchemaGate(boundClient: RemoteClient) {
        schemaGateJob?.cancel()
        schemaGateJob = scope.launch {
            val probe = try {
                Result.success(boundClient.call("remote.editor.capabilities"))
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Result.failure(t)
            }
            if (client !== boundClient) return@launch
            if (probe.isSuccess) lastWireResponseMs = System.currentTimeMillis()
            when (val decision = schemaGate(probe)) {
                is SchemaGateDecision.Compatible -> {
                    observed.protocolVersion = decision.capabilities.protocolVersion
                    boundClient.publishServerFeatures(featuresFromGate(decision))
                    observed.firstGatePending = false
                    publishUiState()
                    lifecycle.onFeaturesNegotiated()
                }
                is SchemaGateDecision.ProbeFailed -> {
                    // Transient: an error envelope / timeout says nothing
                    // about the wire schema. Let the app run against the
                    // live socket and re-probe on the next rising edge.
                    // Nothing was negotiated, so every gated parameter stays
                    // off for the life of this socket — published explicitly
                    // rather than left implicit, so the intent is readable.
                    Log.i(TAG, "capabilities probe failed (transient): ${decision.reason}")
                    boundClient.publishServerFeatures(featuresFromGate(decision))
                    observed.firstGatePending = false
                    publishUiState()
                    lifecycle.onFeaturesNegotiated()
                }
                is SchemaGateDecision.Incompatible -> {
                    // Hard gate: the server speaks a chat-wire shape we
                    // can't decode. Publish the terminal screen BEFORE the
                    // teardown (which cancels this job) and drop the live
                    // client — any notification it pushed would land on
                    // observers that can't decode it.
                    observed.firstGatePending = false
                    _state.value = UiState.IncompatibleServer(
                        serverWireSchemaVersion = decision.serverWireSchemaVersion,
                        supportedWireSchemaVersion = SUPPORTED_WIRE_SCHEMA_VERSION,
                        message = decision.message,
                    )
                    tearDownConnection()
                }
            }
        }
    }

    /**
     * Liveness watchdog: while Connected AND in the foreground, fire a
     * cheap RPC every [HEARTBEAT_INTERVAL_MS].
     *
     * This is deliberately slow and deliberately foreground-only. OkHttp's
     * own 30s ping/pong is what actually catches a dead socket (it fails
     * the connection with a `SocketTimeoutException` when a pong doesn't
     * come back), and the server's 60s idle timer catches the other
     * direction; the RPC ping only adds coverage for a peer whose
     * WebSocket reader still answers pings while its dispatcher is wedged.
     * Running it every 30s in the background bought nothing and woke the
     * radio all night (N-31, N-19).
     *
     * A JSON-RPC *error* envelope counts as a healthy wire — the peer
     * answered. Two consecutive silences call `forceReconnect` so the
     * lifecycle loop tears the dead WS down and reconnects (plain
     * `wakeReconnect` is gated on Reconnecting and would be a no-op
     * against a quietly-dead Connected socket).
     *
     * Lives in its own [heartbeatJob] handle. Cancelled from
     * [tearDownConnection] and from [onAppBackgrounded].
     */
    private fun startHeartbeat(boundClient: RemoteClient?) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        if (boundClient == null || !appInForeground) return
        heartbeatJob = scope.launch {
            var consecutiveFailures = 0
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (_rawConnectionState.value !is ConnectionState.Connected) {
                    // Re-check on the next tick. While the lifecycle is
                    // already in Reconnecting / Connecting / FailedTerminal
                    // we don't pile on extra RPCs — forceReconnect is also
                    // gated on Connected, so firing it here would be a
                    // no-op anyway.
                    consecutiveFailures = 0
                    continue
                }
                // Both of these are belt-and-braces: a server switch
                // cancels this job in tearDownConnection before the new
                // client is bound, so neither has a known live path.
                val live = client ?: break
                if (live !== boundClient) break
                if (pingWire(live, PROBE_TIMEOUT_MS) != ProbeAttempt.Silent) {
                    consecutiveFailures = 0
                    continue
                }
                consecutiveFailures += 1
                if (consecutiveFailures >= HEARTBEAT_FAILURE_THRESHOLD) {
                    // Wire is a zombie: state reads Connected but the
                    // server isn't responding at all. The reason string is
                    // what the user sees on the reconnect banner via
                    // ConnectFailure.Unreachable.userMessage, so use a
                    // plain user-facing phrase here and log the
                    // watchdog-specific detail separately.
                    Log.w(
                        TAG,
                        "heartbeat watchdog: $HEARTBEAT_FAILURE_THRESHOLD pings unanswered — forcing reconnect",
                    )
                    live.forceReconnect("Server stopped responding — reconnecting…")
                    consecutiveFailures = 0
                }
            }
        }
    }

    /**
     * Watch the default network so a Wi-Fi → cellular handover recovers in
     * seconds instead of waiting out OkHttp's ping timeout plus a backoff
     * that may already be pinned at its 30s cap (N-16).
     *
     * Deliberately cheap: only `onAvailable` / `onLost` on the DEFAULT
     * network, no `onCapabilitiesChanged` (which fires on every signal
     * fluctuation), and the action is de-duplicated per network handle so
     * a re-delivered callback for the same network does nothing.
     */
    private fun registerNetworkCallback() {
        val connectivity = application.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Binder thread — hop onto the manager's scope before
                // touching any of its state.
                scope.launch { onDefaultNetworkAvailable(network.networkHandle) }
            }

            override fun onLost(network: Network) {
                scope.launch {
                    // Record the loss; do NOT clear the handle. An abrupt
                    // Wi-Fi exit delivers onLost(wifi) before
                    // onAvailable(cell), and clearing here made the arrival
                    // of the replacement look like the very first callback —
                    // which is deliberately a no-op while Connected, so the
                    // handover this whole callback exists for was the one
                    // case it ignored (CM-2).
                    if (lastDefaultNetworkHandle == network.networkHandle) {
                        sawDefaultNetworkLoss = true
                    }
                }
            }
        }
        val registered = runCatching {
            connectivity.registerDefaultNetworkCallback(callback)
        }.isSuccess
        if (!registered) {
            Log.w(TAG, "default-network callback unavailable — falling back to timeouts")
            return
        }
        scope.launch {
            try {
                awaitCancellation()
            } finally {
                runCatching { connectivity.unregisterNetworkCallback(callback) }
            }
        }
    }

    private fun onDefaultNetworkAvailable(handle: Long) {
        val live = client ?: run {
            lastDefaultNetworkHandle = handle
            sawDefaultNetworkLoss = false
            return
        }
        val action = networkChangeAction(
            previousNetworkHandle = lastDefaultNetworkHandle,
            newNetworkHandle = handle,
            connected = _rawConnectionState.value is ConnectionState.Connected,
            sawLoss = sawDefaultNetworkLoss,
        )
        lastDefaultNetworkHandle = handle
        sawDefaultNetworkLoss = false
        when (action) {
            NetworkChangeAction.Ignore -> Unit
            NetworkChangeAction.Wake -> live.wakeReconnect()
            NetworkChangeAction.ForceReconnect -> {
                Log.i(TAG, "default network changed while connected — rebuilding the socket")
                live.forceReconnect(
                    reason = "Network changed — reconnecting…",
                    force = action.bypassesForceReconnectGrace,
                )
                // Tearing the socket down while the ladder is parked would
                // buy nothing: the loop would reach Reconnecting and park
                // there with no poke until the next foreground edge. Release
                // the park explicitly — `wakeReconnect` can't do it here
                // because it is gated on Reconnecting and the state is still
                // Connected at this instant — then re-arm the delayed park
                // so an unreachable server can't ladder all night (CM-5).
                live.setReconnectPaused(false)
                ladderParked = false
                if (!appInForeground) {
                    parkGraceElapsed = false
                    scheduleLadderPark()
                }
            }
        }
    }

    companion object {
        private const val TAG = "ConnectionManager"

        /**
         * How often to ping the wire while Connected AND foregrounded.
         * See [startHeartbeat] for why this is minutes, not seconds.
         */
        internal const val HEARTBEAT_INTERVAL_MS: Long = 120_000L

        /**
         * First-strike wall-clock cap. Sized for the slowest link we care
         * about (roaming EDGE round-trips can exceed 5s), not for a LAN.
         */
        internal const val PROBE_TIMEOUT_MS: Long = 12_000L

        /**
         * Second-strike cap. Deliberately short: a link slow enough to miss
         * [PROBE_TIMEOUT_MS] has already had its benefit of the doubt, and a
         * user is waiting on the answer (CM-4).
         */
        internal const val PROBE_RETRY_TIMEOUT_MS: Long = 5_000L

        /**
         * Skip the foreground probe entirely if the wire answered anything
         * this recently.
         */
        internal const val PROBE_SKIP_WINDOW_MS: Long = 5_000L

        /**
         * How long a background edge must hold before the reconnect ladder
         * is parked. Covers the file-picker / share-sheet excursion, which
         * stops our Activity exactly like a real backgrounding.
         */
        internal const val BACKGROUND_PARK_GRACE_MS: Long = 60_000L

        /** Cadence of the park re-evaluation while a carve-out defers it. */
        internal const val LADDER_PARK_RECHECK_MS: Long = 60_000L

        /**
         * Longest the durable queue may defer the park, measured from the
         * background edge. See [queueHoldsLadderOpen].
         */
        internal const val BACKGROUND_PARK_MAX_DEFERRAL_MS: Long = 5L * 60_000L

        /**
         * How long an upload may go without progress before it stops
         * holding the ladder open. See [uploadHoldsLadderOpen].
         */
        internal const val UPLOAD_STALL_PARK_MS: Long = 5L * 60_000L

        /**
         * Consecutive silent heartbeats before we treat the wire as a
         * zombie and force a reconnect. With [HEARTBEAT_INTERVAL_MS] this
         * puts worst-case detection of a wedged-but-pinging peer around
         * 4 minutes — acceptable because OkHttp's own 30s ping/pong, not
         * this, is what catches an actually dead socket.
         */
        private const val HEARTBEAT_FAILURE_THRESHOLD: Int = 2
    }
}

// ---------------------------------------------------------------------------
// Pure decision helpers.
//
// Everything below is side-effect free so the connection lifecycle's
// decisions are testable from plain JVM unit tests
// (`ConnectionLifecycleDecisionsTest`) instead of needing a live socket.
// ---------------------------------------------------------------------------

/** Protocol version reported while the capabilities probe hasn't landed. */
internal const val UNKNOWN_PROTOCOL_VERSION: String = "unknown"

/**
 * The one `capabilities` field the incompatible-server gate may act on.
 * Its PRESENCE is the evidence; see [schemaGate].
 */
internal const val WIRE_SCHEMA_VERSION_KEY: String = "wire_schema_version"

/** Cold-start landing route + the server to auto-connect to, if any. */
internal data class LandingDecision(
    val route: String,
    val connectToServerId: String?,
)

/**
 * Where to land when the paired-server list cannot be read at all (keystore
 * failure, corrupt prefs). The QR screen: we cannot prove the user has a
 * server, and every other route assumes one.
 */
internal val FALLBACK_LANDING = LandingDecision(route = "pairing", connectToServerId = null)

/**
 * [coldStartLanding] wrapped so a throwing [loadServers] / [loadActiveId]
 * still yields a route.
 *
 * Split out from [ConnectionManager.hydrateAndAutoConnect] purely so the
 * "never leave the splash up" guarantee is testable without an Android
 * Keystore — the failure it guards against is precisely the one that cannot
 * be reproduced in a unit test through the real repository.
 */
internal fun resolveColdStartLanding(
    loadServers: () -> List<PairedServer>,
    loadActiveId: () -> String?,
): LandingDecision = runCatching { coldStartLanding(loadServers(), loadActiveId()) }
    .getOrDefault(FALLBACK_LANDING)

/**
 * Resolve the cold-start landing route from the paired-server list.
 *
 *  - nothing paired → the QR screen, no connection to make,
 *  - exactly one → straight to the workspace,
 *  - two or more → the server picker.
 *
 * [storedActiveId] wins when it still names a paired server; a dangling id
 * (server removed on another install, prefs restored) falls back to the
 * most-recently-used entry, which is what [PairingRepository.loadAll]
 * sorts first.
 */
internal fun coldStartLanding(
    servers: List<PairedServer>,
    storedActiveId: String?,
): LandingDecision {
    if (servers.isEmpty()) return LandingDecision(route = "pairing", connectToServerId = null)
    val preferred = storedActiveId?.takeIf { id -> servers.any { it.id == id } }
        ?: servers.first().id
    return LandingDecision(
        route = if (servers.size == 1) "workspace" else "servers",
        connectToServerId = preferred,
    )
}

/**
 * Should a switch request actually rebuild the connection?
 *
 * The plain idempotence check ("same server and a client exists") used to
 * be the whole answer, which made every user-visible recovery action a
 * no-op once the client had parked in [ConnectionState.FailedTerminal] —
 * tapping the active server, re-scanning the QR and "Retry" all returned
 * early and the only way out was a force-stop (N-09 / N-18).
 */
internal fun shouldRebind(
    force: Boolean,
    targetServerId: String,
    activeServerId: String?,
    hasClient: Boolean,
    connectionState: ConnectionState,
): Boolean = force ||
    activeServerId != targetServerId ||
    !hasClient ||
    connectionState is ConnectionState.FailedTerminal

/**
 * Should pairing [newUrl] force a rebind of the already-paired server it
 * merged into?
 *
 * A re-scan that carries a new pairing URL (the desktop regenerated its
 * secret) or that happens while the client sits in a terminal state is the
 * user's recovery action and must re-bind; re-scanning the identical QR of
 * a healthy connection must not churn it.
 */
internal fun shouldForceRebindOnPair(
    previousUrl: String?,
    newUrl: String,
    isTerminal: Boolean,
): Boolean {
    if (previousUrl == null) return false
    return previousUrl != newUrl || isTerminal
}

/** Outcome of one `remote.editor.capabilities` liveness ping. */
internal enum class ProbeAttempt {
    /** A decodable response came back. */
    Answered,

    /**
     * A JSON-RPC error envelope came back. The peer is talking, so the
     * WIRE is healthy — this says something about the desktop's local MCP
     * proxy, not about the socket.
     */
    ErrorEnvelope,

    /** Nothing came back within the budget (or the call threw). */
    Silent,
}

/** Verdict of the foreground liveness probe. */
internal enum class ProbeDecision { Alive, Retry, Dead }

/**
 * Two-strike policy for the foreground liveness probe (N-15). A single
 * timeout is not evidence of a dead socket on a slow link, and the cost of
 * being wrong is a TLS handshake plus a full workspace refetch.
 */
internal fun probeDecision(first: ProbeAttempt, second: ProbeAttempt?): ProbeDecision = when {
    first != ProbeAttempt.Silent -> ProbeDecision.Alive
    second == null -> ProbeDecision.Retry
    second != ProbeAttempt.Silent -> ProbeDecision.Alive
    else -> ProbeDecision.Dead
}

/** Outcome of the wire-schema gate on one `capabilities` probe. */
internal sealed interface SchemaGateDecision {
    /** The server decoded cleanly and speaks a schema we support. */
    data class Compatible(val capabilities: CapabilitiesDto) : SchemaGateDecision

    /** The server decoded cleanly and speaks a schema we can't drive. */
    data class Incompatible(
        val serverWireSchemaVersion: Int,
        val message: String,
    ) : SchemaGateDecision

    /** The probe told us nothing about the schema. Retry on the next edge. */
    data class ProbeFailed(val reason: String) : SchemaGateDecision
}

/**
 * Classify a `remote.editor.capabilities` probe.
 *
 * The gate fires only on POSITIVE evidence: a response that actually
 * carries a `wire_schema_version`. Everything else — a transport failure, a
 * JSON-RPC error envelope, a tool-level error, a missing
 * `structuredContent`, a payload that isn't an object, an object without
 * the field — is a probe failure and is retried on the next rising edge.
 *
 * "It decoded" is NOT evidence, which is the trap the first cut of this fix
 * fell into: [CapabilitiesDto] defaults `wireSchemaVersion` to `0` and
 * `JsonRpc.json` sets `ignoreUnknownKeys`, so decoding succeeds against
 * *any* JSON object and yields `0` → `isServerTooOld(0)` → the permanent
 * "server too old" screen. `{"ok":true}` from a rewriting proxy, or a
 * degraded partial capabilities object, was enough. The DTO's own KDoc says
 * a missing `wire_schema_version` means "not gated"; this is where that
 * contract is honoured (N-11 / CM-1).
 *
 * The distinction is load-bearing: the desktop answers `-32603` for every
 * call while its local MCP proxy is still starting, and treating that as
 * version `0` stranded the phone on the terminal screen a couple of seconds
 * before the proxy came up.
 */
internal fun schemaGate(probe: Result<JsonRpcResponse>): SchemaGateDecision {
    val resp = probe.getOrElse {
        return SchemaGateDecision.ProbeFailed(it.message ?: it::class.java.simpleName)
    }
    resp.error?.let {
        return SchemaGateDecision.ProbeFailed("JSON-RPC error ${it.code}: ${it.message}")
    }
    resp.toolError()?.let { return SchemaGateDecision.ProbeFailed(it) }
    val structured = resp.structuredContent()
        ?: return SchemaGateDecision.ProbeFailed("missing structuredContent")
    val fields = structured as? JsonObject
        ?: return SchemaGateDecision.ProbeFailed("structuredContent is not an object")
    if (WIRE_SCHEMA_VERSION_KEY !in fields) {
        return SchemaGateDecision.ProbeFailed("capabilities carried no $WIRE_SCHEMA_VERSION_KEY")
    }
    val capabilities = runCatching {
        JsonRpc.json.decodeFromJsonElement(CapabilitiesDto.serializer(), fields)
    }.getOrElse {
        return SchemaGateDecision.ProbeFailed("undecodable capabilities: ${it.message}")
    }
    if (isServerTooNew(capabilities.wireSchemaVersion)) {
        return SchemaGateDecision.Incompatible(
            serverWireSchemaVersion = capabilities.wireSchemaVersion,
            message = "This server needs a newer version of the app. Please update.",
        )
    }
    if (isServerTooOld(capabilities.wireSchemaVersion)) {
        return SchemaGateDecision.Incompatible(
            serverWireSchemaVersion = capabilities.wireSchemaVersion,
            message = "This server is too old for this app version. Please update the editor.",
        )
    }
    return SchemaGateDecision.Compatible(capabilities)
}

/**
 * The feature set a [SchemaGateDecision] entitles the connection to.
 *
 * Only a [SchemaGateDecision.Compatible] carries a capabilities object, and
 * only that object can turn a gated wire parameter on. Everything else is
 * [ServerFeatures.NONE] — a probe that failed says nothing about the peer,
 * and an incompatible peer is about to be torn down; in both cases the
 * connection must behave exactly as it did before feature negotiation
 * existed.
 *
 * Note that a *successful* probe against a server that predates negotiation
 * also lands here as `NONE`: `wire_features` is absent, so
 * [ru.sipaha.sawe.core.CapabilitiesDto.wireFeatures] is `null` and
 * [toServerFeatures] yields an empty token set.
 */
internal fun featuresFromGate(decision: SchemaGateDecision): ServerFeatures = when (decision) {
    is SchemaGateDecision.Compatible -> decision.capabilities.toServerFeatures()
    is SchemaGateDecision.ProbeFailed -> ServerFeatures.NONE
    is SchemaGateDecision.Incompatible -> ServerFeatures.NONE
}

/**
 * Project the observed wire [ConnectionState] onto the navigation-level
 * [UiState] (N-09).
 *
 * Two rules carry all the weight:
 *  - once we have been connected ([everConnected]), a transient
 *    `Connecting` / `Reconnecting` must NOT read as `Disconnected` — the
 *    nav graph pops back to the pairing / servers screen on that state, so
 *    every Wi-Fi blip would eject the user out of the chat they're reading;
 *  - before the first successful handshake, `Disconnected` is just "the
 *    lifecycle loop hasn't started yet" (the observer is installed before
 *    `connect()`), so it reads as `Connecting`.
 *
 * [firstGatePending] holds the UI on `Connecting` while the very first
 * capabilities probe is in flight, so an incompatible server never flashes
 * the workspace before the gate lands.
 */
internal fun deriveUiState(
    state: ConnectionState,
    everConnected: Boolean,
    protocolVersion: String?,
    firstGatePending: Boolean,
): UiState = when (state) {
    ConnectionState.Connected ->
        if (firstGatePending) {
            UiState.Connecting
        } else {
            UiState.Connected(protocolVersion ?: UNKNOWN_PROTOCOL_VERSION)
        }
    ConnectionState.Connecting,
    is ConnectionState.Reconnecting ->
        if (everConnected) {
            UiState.Connected(protocolVersion ?: UNKNOWN_PROTOCOL_VERSION)
        } else {
            UiState.Connecting
        }
    is ConnectionState.FailedTerminal ->
        UiState.Disconnected(lastUrl = null, error = state.failure.userMessage)
    ConnectionState.Disconnected ->
        if (everConnected) UiState.Disconnected() else UiState.Connecting
}

/**
 * User-facing explanation of why the wire is unusable right now.
 *
 * [hasPairedServers] separates "nothing is paired yet, go scan a QR" from
 * "we have a server, we're just not on it at this instant" — the old copy
 * told a user with a perfectly good pairing to "pick or pair a server",
 * which reads as data loss (N-10).
 */
internal fun notConnectedText(state: ConnectionState, hasPairedServers: Boolean): String =
    when (state) {
        is ConnectionState.FailedTerminal -> state.failure.userMessage
        is ConnectionState.Reconnecting ->
            state.lastFailure?.userMessage ?: "Reconnecting to the server…"
        ConnectionState.Connecting ->
            "Still connecting to the server — try again in a moment."
        ConnectionState.Connected ->
            "Connection just dropped. Retrying in the background."
        ConnectionState.Disconnected ->
            if (hasPairedServers) {
                "Not connected — reconnecting…"
            } else {
                "No active server connection. Pick or pair a server first."
            }
    }

/**
 * The set of `spk_client_send_id`s carried by [queued].
 *
 * Entries that carry no stamp — the legacy text-only `send_message`, or any
 * payload shape without `blocks[0]._meta.spk_client_send_id` — are dropped
 * rather than mapped to a placeholder: the caller uses membership to decide
 * whether a persisted pending-send marker is still live, and a placeholder
 * would make an unrelated marker look queued.
 */
internal fun queuedClientSendIdsOf(queued: List<QueuedMessage>): Set<Long> =
    queued.mapNotNullTo(mutableSetOf(), ::parseQueuedClientSendId)

/** What a default-network change should do to the current connection. */
internal enum class NetworkChangeAction { Ignore, Wake, ForceReconnect }

/**
 * True when the action is direct evidence that the current socket is dead,
 * and may therefore skip [RemoteClient.FORCE_RECONNECT_GRACE_MS].
 *
 * The grace exists to damp *inferences* — a probe that timed out, a
 * heartbeat that went unanswered — which can misjudge a socket that simply
 * hasn't had a chance to prove itself yet. A default-network handover is
 * not an inference: the socket is bound to an interface that no longer
 * routes, and waiting 12s to admit it is 12s of a frozen chat. Nothing
 * periodic may set this (N-16).
 */
internal val NetworkChangeAction.bypassesForceReconnectGrace: Boolean
    get() = this == NetworkChangeAction.ForceReconnect

/** What the app's visibility implies for the wire's background work. */
internal data class VisibilityPolicy(
    val runHeartbeat: Boolean,
    val parkReconnectLadder: Boolean,
)

/**
 * Decide what background wire work is allowed at the current visibility
 * (N-19).
 *
 * Both halves are foreground-only for the same reason: the RPC heartbeat
 * exists to raise a banner, and the reconnect ladder exists to have a live
 * socket for a screen to render — neither is worth a radio wake-up while
 * nobody is looking. Nothing is lost by parking: the durable queue,
 * subscriptions and any established connection are untouched, and an
 * event-driven retry (network change, foreground edge) releases the park
 * at once.
 *
 * The ladder gets two extra conditions the heartbeat doesn't need, because
 * parking it can hold back real work (CM-3):
 *  - [parkGraceElapsed] — our background edge is "no started Activity",
 *    which a full-screen system file picker also produces. A park is worth
 *    delaying past those.
 *  - [pendingWireWork] — something is still queued to send. Parking on top
 *    of it holds it, invisibly, until the app is opened again.
 */
internal fun visibilityPolicy(
    appInForeground: Boolean,
    hasClient: Boolean,
    parkGraceElapsed: Boolean,
    pendingWireWork: Boolean,
    uploadWireWork: Boolean,
): VisibilityPolicy = VisibilityPolicy(
    runHeartbeat = appInForeground && hasClient,
    parkReconnectLadder = !appInForeground &&
        parkGraceElapsed &&
        !pendingWireWork &&
        !uploadWireWork,
)

/**
 * What the upload layer still needs from the wire.
 *
 * Produced by `UploadManager.wireWorkSnapshot`, consumed by
 * [uploadHoldsLadderOpen]. [lastProgressAtMs] is a wall-clock stamp of the
 * last chunk ack or fresh user intent; `0` means nothing has ever moved.
 */
internal data class UploadWireWork(
    val active: Boolean,
    val lastProgressAtMs: Long,
) {
    companion object {
        val IDLE = UploadWireWork(active = false, lastProgressAtMs = 0L)
    }
}

/**
 * Whether an in-flight upload should hold the reconnect ladder open (CM-3).
 *
 * Parking is harmless to an upload that is actually running — the park only
 * suspends the backoff step, and an established connection is untouched —
 * so this matters in exactly one situation: the socket dropped, the upload
 * is `Paused`, and only a redial can revive it. Backgrounding there used to
 * strand a 40 MB attachment until the user next opened the app.
 *
 * Bounded by progress rather than by a fixed deadline, because that is the
 * distinction that matters: an upload that keeps getting chunk acks keeps
 * earning the radio, and one that has not moved for [stallTimeoutMs] has
 * stopped earning it and must not keep the ladder alive all night. Progress
 * restarts the clock, so a slow-but-live 40 MB upload is never cut off
 * mid-flight; a wedged one parks a few minutes in.
 */
internal fun uploadHoldsLadderOpen(
    uploadsActive: Boolean,
    nowMs: Long,
    lastProgressAtMs: Long,
    stallTimeoutMs: Long,
): Boolean {
    if (!uploadsActive) return false
    if (lastProgressAtMs <= 0L) return false
    val sinceProgress = nowMs - lastProgressAtMs
    // A negative age means the clock moved backwards (NTP correction); treat
    // it as "no recent progress" rather than as an unbounded hold.
    return sinceProgress in 0 until stallTimeoutMs
}

/**
 * Whether unsent queued messages should hold the reconnect ladder open.
 *
 * Same shape as [uploadHoldsLadderOpen] but bounded by wall-clock from the
 * background edge instead of by progress, because the queue has no progress
 * to report: an item that cannot be sent looks exactly like one that has not
 * been tried. Without the bound, a message queued against a server that is
 * simply switched off would redial for its full 24h TTL with the screen
 * dark, which is the battery drain N-19 exists to prevent.
 */
internal fun queueHoldsLadderOpen(
    queueNonEmpty: Boolean,
    nowMs: Long,
    backgroundedAtMs: Long,
    maxDeferralMs: Long,
): Boolean {
    if (!queueNonEmpty) return false
    if (backgroundedAtMs <= 0L) return false
    val backgrounded = nowMs - backgroundedAtMs
    return backgrounded in 0 until maxDeferralMs
}

/**
 * Whether a foreground liveness probe can be skipped because the wire
 * demonstrably answered something within [windowMs].
 *
 * A foreground edge fires for every return from a system file picker or
 * share sheet, and each one used to cost a `capabilities` round-trip whose
 * answer we already had (CM-4).
 */
internal fun shouldSkipLivenessProbe(
    nowMs: Long,
    lastWireResponseMs: Long,
    windowMs: Long,
): Boolean {
    if (lastWireResponseMs <= 0L) return false
    val age = nowMs - lastWireResponseMs
    return age in 0 until windowMs
}

/**
 * Decide how to react to the default network becoming available (N-16).
 *
 * A *different* default network while we read `Connected` means the socket
 * is bound to an interface that no longer routes (the classic Wi-Fi →
 * cellular handover), so it has to be rebuilt rather than waited out. The
 * same network re-delivering `onAvailable` is a no-op, and a network
 * arriving while we're already retrying just short-circuits the backoff.
 */
internal fun networkChangeAction(
    previousNetworkHandle: Long?,
    newNetworkHandle: Long,
    connected: Boolean,
    sawLoss: Boolean,
): NetworkChangeAction {
    // `registerDefaultNetworkCallback` delivers onAvailable for the network
    // we are already on, at registration time. That one is not a change.
    if (previousNetworkHandle == null && !sawLoss) {
        return if (connected) NetworkChangeAction.Ignore else NetworkChangeAction.Wake
    }
    // A loss counts as a change even when the replacement carries the same
    // handle: the interface our socket is bound to went away and came back.
    val changed = sawLoss || previousNetworkHandle != newNetworkHandle
    if (!changed) return NetworkChangeAction.Ignore
    return if (connected) NetworkChangeAction.ForceReconnect else NetworkChangeAction.Wake
}
