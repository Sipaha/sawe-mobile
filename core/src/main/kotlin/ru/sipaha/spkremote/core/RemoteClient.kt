package ru.sipaha.spkremote.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * Resilient connection to one SPK Editor instance.
 *
 * **Handshake (each attempt):** all three frames are JSON TEXT — the
 *   wire is text-only end to end, see [HmacChallengeAuth] kdoc for the
 *   server-side framing reference.
 *   1. WebSocket upgrade to `wss://host:port/remote` (TLS pinned by
 *      [PairingUrl.fingerprint]).
 *   2. Receive a server challenge TEXT frame:
 *      `{"type":"challenge","challenge":"<32 hex chars>","v":1}` — the
 *      `challenge` field is hex of a 16-byte nonce.
 *   3. Send a client response TEXT frame:
 *      `{"type":"response","response":"<64 hex chars>"}` — the
 *      `response` is hex of the 32-byte HMAC-SHA256 over
 *      `domain_tag || nonce_bytes` (see [HmacChallengeAuth.respond]).
 *   4. On accept the server sends `{"type":"welcome","client":"<name>"}`;
 *      on reject the server closes the WebSocket with close code 1008
 *      (policy violation) and the lifecycle classifies that as
 *      [ConnectFailure.ServerClosed] / [ConnectFailure.AuthRejected].
 *   5. Carry JSON-RPC text frames in both directions. Frames with an `id`
 *      that matches an outstanding [call] resolve that call. Frames without
 *      an `id` (or with an unknown id) are emitted on [notifications].
 *
 * **Resilience (R-6a):**
 *   - A long-running lifecycle coroutine owns the WS. When a transport-level
 *     drop happens (network change, NAT timeout, server restart), the
 *     coroutine transitions [connectionState] to [ConnectionState.Reconnecting]
 *     and retries with [BackoffStrategy.Default].
 *   - Terminal failures (TLS pin mismatch, HMAC reject, version skew) drop
 *     to [ConnectionState.FailedTerminal] without auto-retry — the user
 *     must re-pair.
 *   - [subscribe]/[unsubscribe] track the active event-kind set; on every
 *     successful reconnect handshake the set is replayed so subscribers see
 *     no notification gap longer than the reconnect window itself.
 *   - [queueCall] is the production-grade send entry point — if the wire is
 *     down, the request is held in an in-memory FIFO until the next
 *     [ConnectionState.Connected] transition or until its TTL expires. The
 *     queue lives in [QueueController]; this class is a thin facade for
 *     the queue-shaped API.
 *
 * **Concurrency model:** every state mutation that's visible across awaits
 * (pending requests, subscription set, queued items) goes through suspending
 * methods running on the supplied [scope]; OkHttp's I/O threads only drive
 * the [RemoteTransportListener] callbacks, which post events onto the
 * lifecycle channel and return immediately.
 */
class RemoteClient internal constructor(
    private val url: PairingUrl,
    private val transportFactory: RemoteTransportFactory,
    private val backoff: BackoffStrategy = BackoffStrategy.Default,
    /**
     * `now()` source for queue-TTL arithmetic. Defaults to wall clock.
     * Tests inject a fake whose progression is driven by `TestScope`.
     */
    private val nowMs: () -> Long = System::currentTimeMillis,
    /**
     * Persistence backend for the outbound queue (R-6d). Defaults to the
     * pure-in-memory store for backwards compatibility with `:cli`,
     * `:core` tests, and any caller that hasn't opted into disk-backed
     * persistence. `:app` injects an `EncryptedQueueStore` so typed-but-
     * unsent messages survive a process kill.
     */
    private val queueStore: QueueStore = InMemoryQueueStore(),
    /**
     * Optional hook invoked when a queued message is dropped without
     * delivery — TTL expiry, terminal failure during replay, or
     * programmatic [close]. The handler receives the persisted
     * [QueuedMessage] (NOT the in-memory wrapper) so it can route the
     * payload back to the user via the `:app` draft repository.
     *
     * May be called from any coroutine context. Implementations must
     * be thread-safe and should not block — schedule disk I/O on a
     * separate dispatcher if needed. (Audit Phase 2 M1: the original
     * "always lifecycle coroutine" contract was inconsistent across
     * the three TTL-expiry paths; the unified contract is "any thread,
     * thread-safe consumer".)
     */
    private val onMessageExpired: ((QueuedMessage) -> Unit)? = null,
) {
    constructor(
        url: PairingUrl,
        httpClientBuilder: OkHttpClient.Builder = OkHttpRemoteTransportFactory.defaultBuilder(url),
        queueStore: QueueStore = InMemoryQueueStore(),
        onMessageExpired: ((QueuedMessage) -> Unit)? = null,
    ) : this(
        url = url,
        transportFactory = OkHttpRemoteTransportFactory { _ -> httpClientBuilder },
        queueStore = queueStore,
        onMessageExpired = onMessageExpired,
    )

    private val auth = HmacChallengeAuth(url.secret)
    private val nextId = AtomicLong(1L)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonRpcResponse>>()
    /**
     * Server-initiated `editor/notification` frames. Backed by an
     * UNLIMITED channel exposed as a single-consumer [Flow] so that
     * bursts of state-update notifications during a long agent turn
     * (text streaming + tool-call arg deltas, easily 100+ frames in
     * rapid succession on a multi-step reply) never get dropped. An
     * earlier revision used `MutableSharedFlow(extraBufferCapacity = 64)`
     * + `tryEmit`, which silently discarded the overflow tail; the
     * symptom was tool-call cards stuck on the initial empty
     * `args_preview = "{}"` (the [acp_thread::ToolCall.raw_input] is
     * absent on the create event and only arrives via later updates)
     * and assistant text bubbles stuck at the first preview chunk
     * (e.g. "Также для" / "Build +"). FIFO is preserved because the
     * WS dispatcher pumps frames serially.
     */
    private val notificationChannel = Channel<JsonElement>(capacity = Channel.UNLIMITED)
    val notifications: Flow<JsonElement> = notificationChannel.receiveAsFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * Event kinds the caller has asked to receive. Mutated under [stateLock]
     * to keep [subscribe]/[unsubscribe] linearizable, and replayed on every
     * successful reconnect handshake.
     */
    private val activeSubscriptions = mutableSetOf<String>()
    private val stateLock = Any()

    /** Events the lifecycle coroutine consumes. */
    private val events = Channel<LifecycleEvent>(Channel.UNLIMITED)

    /**
     * Pokes the lifecycle loop to short-circuit the current backoff
     * delay and retry NOW. CONFLATED so a burst of foreground-edge
     * pokes collapses to one wake — only the most recent matters.
     *
     * Listened to ONLY while the loop is sitting in the
     * `Reconnecting → delay(delayMs) → Connecting` step. Pokes that
     * arrive in other states (Connecting / Connected / FailedTerminal)
     * are silently dropped — interrupting an in-flight handshake or a
     * live socket would only churn state for no benefit.
     */
    private val wakeRequests = Channel<Unit>(Channel.CONFLATED)

    /** Set after the first successful [connect]; reused by subsequent reconnects. */
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var lifecycleJob: Job? = null
    @Volatile private var firstConnect: CompletableDeferred<Unit>? = null
    /**
     * Atomic guard against concurrent [connect] calls — single-shot
     * semantics, [close] does NOT reset. A [RemoteClient] is one-shot;
     * `:app` ConnectionManager builds a fresh instance per server switch.
     */
    private val connectGuard = AtomicBoolean(false)
    /**
     * Most recent [ConnectFailure] observed by the lifecycle loop. Read
     * by [call] when [transport] is null so [NotConnectedException]
     * carries a specific reason. Cleared on each successful Connected.
     */
    @Volatile private var lastConnectFailure: ConnectFailure? = null

    /**
     * Current transport (null while reconnecting). Written only by the
     * lifecycle coroutine and [close]; read from arbitrary coroutines
     * via [callInternal] — @Volatile so non-Connected reads see the
     * most recent write.
     */
    @Volatile private var transport: RemoteTransport? = null

    /**
     * Queue half of the state machine. Set lazily in [connect]. All
     * queue-shaped operations delegate here.
     */
    @Volatile private var queueController: QueueController? = null

    /**
     * Connect (and from then on, stay connected) using [scope] as the
     * supervisor. Returns success once the first handshake completes; later
     * disconnects do **not** propagate to this caller — they show up as
     * [ConnectionState.Reconnecting] on [connectionState].
     *
     * If [scope] is omitted, the client creates an internal supervisor; the
     * caller must remember to invoke [close] to release it.
     */
    suspend fun connect(scope: CoroutineScope? = null): Result<Unit> = runCatching {
        val target = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
        check(connectGuard.compareAndSet(false, true)) {
            "RemoteClient is single-shot — connect() can be called at most " +
                "once per instance (already connecting/connected or previously closed)"
        }
        this.scope = target
        val controller = QueueController(
            scope = target,
            nowMs = nowMs,
            queueStore = queueStore,
            onMessageExpired = onMessageExpired,
            stateLock = stateLock,
            transportAccessor = { transport },
            callRpc = { method, params -> callInternal(method, params) },
            events = events,
            connectionState = _connectionState,
        )
        queueController = controller
        // Rehydrate from disk before the lifecycle loop boots; expired
        // entries are bounced by the first [flushQueue] on Connected.
        controller.rehydrate()
        val gate = CompletableDeferred<Unit>()
        // Serialize start with [close] under [stateLock] so a close()
        // racing past our CAS observes the lifecycleJob it must cancel.
        synchronized(stateLock) {
            if (closing) throw ClosedException()
            firstConnect = gate
            lifecycleJob = target.launch { lifecycleLoop() }
        }
        gate.await()
    }

    /**
     * Short-circuit the current reconnect backoff and try to connect
     * NOW. Wired into the foreground-edge hook so a phone waking from
     * Doze doesn't sit staring at "next try in 30s" — the screen lights
     * up, the connection retry fires within a frame.
     *
     * Gated on the loop ACTUALLY sitting in [ConnectionState.Reconnecting].
     * Without this guard a wake fired while [runOneAttempt] is mid-flight
     * would buffer on the CONFLATED channel and short-circuit the NEXT
     * backoff the moment a handshake fails — turning a single foreground
     * edge into a tight retry storm. On a flaky link each retry then
     * trips the server's pre-auth transport-error path, which (before
     * the matching server fix) escalated the subnet through the
     * 30s→5min→1h→24h ban ladder. Skipping the wake during
     * Connecting / Connected / FailedTerminal also makes alt-tab
     * patterns (user flipping to logs and back) safe: each foreground
     * fires `wakeReconnect()`, but only the rare in-Reconnecting one
     * actually pokes the channel.
     *
     * Safe to call from any coroutine / thread.
     */
    fun wakeReconnect() {
        if (_connectionState.value is ConnectionState.Reconnecting) {
            wakeRequests.trySend(Unit)
        }
    }

    /**
     * Tear down the live transport and trigger a fresh reconnect cycle.
     * Used by the mobile heartbeat watchdog when a zombie socket is
     * detected (state reads `Connected` but a `capabilities` ping has
     * failed N times in a row) — [wakeReconnect] is gated on
     * `Reconnecting` and would be a silent no-op in that scenario.
     *
     * Closes the active transport and pushes a synthetic
     * [LifecycleEvent.TransportClosed] so the lifecycle loop's
     * `awaitDisconnect` sees a Transient reason and rebuilds the socket
     * via the standard backoff path. Safe to call from any thread.
     *
     * No-ops when [_connectionState] is not `Connected` (any other
     * value means a reconnect is either already running or terminal,
     * neither of which we want to disturb).
     */
    fun forceReconnect(reason: String = "heartbeat watchdog: server unresponsive") {
        if (_connectionState.value !is ConnectionState.Connected) return
        val toClose: RemoteTransport?
        synchronized(stateLock) {
            if (closing) return
            toClose = transport
            transport = null
        }
        toClose?.close()
        events.trySend(
            LifecycleEvent.TransportClosed(ConnectFailure.Unreachable(reason)),
        )
    }

    /** Whether a programmatic close has been requested (lifecycle ends). */
    @Volatile private var closing = false

    fun close() {
        // Serialize close/connect and close/callInternal through
        // [stateLock]: callInternal installs its pending entry inside the
        // same lock and re-checks `closing` + `transport` AFTER the
        // install, closing the install-after-clear window. Queued items
        // are dropped with [ClosedException] AND removed from
        // [queueStore] so a next-process [RemoteClient] won't replay an
        // item the caller already observed as failed (avoids duplicates
        // if the caller retried). [RemoteClient] is single-shot —
        // [connectGuard] and [closing] are NOT reset.
        val toClose: RemoteTransport?
        val pendingSnapshot: List<CompletableDeferred<JsonRpcResponse>>
        val queuedSnapshot: List<QueuedCall>
        val gate: CompletableDeferred<Unit>?
        val job: Job?
        synchronized(stateLock) {
            closing = true
            // Null transport FIRST so a racing callInternal observes a
            // missing transport and bails before installing pending.
            toClose = transport
            transport = null
            pendingSnapshot = pending.values.toList()
            pending.clear()
            queuedSnapshot = queueController?.drainOnClose().orEmpty()
            gate = firstConnect
            job = lifecycleJob
            lifecycleJob = null
        }
        events.trySend(LifecycleEvent.UserClose)
        pendingSnapshot.forEach { it.cancel() }
        queuedSnapshot.forEach {
            runCatching { queueStore.remove(it.message.id) }
            it.deferred.completeExceptionally(ClosedException())
        }
        gate?.takeIf { !it.isCompleted }?.completeExceptionally(ClosedException())
        toClose?.close()
        job?.cancel()
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * Fire a JSON-RPC call now, expecting the wire to be live. If the
     * transport is closed or refuses the frame, fails with
     * [NotConnectedException]. If the server doesn't reply within
     * [timeoutMs] (default [DEFAULT_CALL_TIMEOUT_MS] = 30s), fails with
     * [kotlinx.coroutines.TimeoutCancellationException] — without this
     * a broken server / allow-list miss / dead proxy could hang the UI
     * spinner indefinitely.
     *
     * Most call sites that produce user-typed messages should prefer
     * [queueCall] which handles the disconnected case + persistence.
     */
    suspend fun call(
        method: String,
        params: JsonElement? = null,
        timeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
    ): JsonRpcResponse = withTimeout(timeoutMs) { callInternal(method, params) }

    private suspend fun callInternal(method: String, params: JsonElement?): JsonRpcResponse {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonRpcResponse>()
        // Install pending under [stateLock] and re-check `closing`/
        // `transport` so a concurrent close() (which locks, sets
        // closing=true, nulls transport, then clears pending) cannot
        // leave an orphaned deferred behind a pending.clear().
        val active: RemoteTransport = synchronized(stateLock) {
            if (closing) throw NotConnectedException(lastConnectFailure)
            val tx = transport ?: throw NotConnectedException(lastConnectFailure)
            pending[id] = deferred
            tx
        }
        val req = JsonRpc.encodeRequest(JsonRpcRequest(method = method, params = params, id = id))
        return suspendCancellableCoroutine { cont ->
            deferred.invokeOnCompletion { cause ->
                if (cause != null) {
                    cont.resumeWithException(cause)
                } else {
                    cont.resume(deferred.getCompleted())
                }
            }
            cont.invokeOnCancellation {
                pending.remove(id)?.cancel()
            }
            val sent = try {
                active.send(req)
            } catch (t: Throwable) {
                pending.remove(id)
                cont.resumeWithException(t)
                return@suspendCancellableCoroutine
            }
            if (!sent) {
                pending.remove(id)
                cont.resumeWithException(IllegalStateException("websocket refused frame"))
            }
        }
    }

    /**
     * Queue a JSON-RPC call to be sent when (or as soon as) the connection
     * is [ConnectionState.Connected]. If already connected, sends
     * immediately. Fails the deferred response after [ttlMs] of total time
     * spent queued + in flight. Default TTL is **24 hours** (R-6d).
     *
     * **Persistence:** the message is written to [queueStore] before
     * this call awaits, so a force-kill between enqueue and reconnect
     * preserves it for the next [connect] / rehydrate pair.
     *
     * Delegated to [QueueController]; calling before [connect] throws
     * [NotConnectedException] (same shape as a wire-direct [call] would).
     */
    suspend fun queueCall(
        method: String,
        params: JsonElement? = null,
        ttlMs: Long = DEFAULT_QUEUE_TTL_MS,
    ): JsonRpcResponse {
        val controller = queueController ?: throw NotConnectedException(lastConnectFailure)
        return controller.queueCall(method, params, ttlMs)
    }

    /**
     * Send a raw binary frame on the active WebSocket. Used by the
     * chunked-upload path ([buildUploadChunkFrame]) which multiplexes
     * its 16-byte-header + payload frames alongside the JSON-RPC text
     * traffic on the same socket — the OkHttp / okio layer dispatches
     * by WS frame type (text vs. binary).
     *
     * Returns `false` when there's no live transport OR the transport
     * refused the frame (closed / closing socket). The caller must
     * treat that as "the upload is paused; resume on the next
     * Connected edge".
     *
     * No queuing semantics — unlike [queueCall], a binary frame doesn't
     * sit in the durable outbound queue. The upload state machine owns
     * its own resume protocol (call `upload_status` after reconnect,
     * restart the chunk loop from the server-reported offset).
     */
    fun sendBinary(bytes: ByteArray): Boolean {
        val tx = transport ?: return false
        return runCatching { tx.send(bytes) }.getOrDefault(false)
    }

    /** Add [kinds] to the active subscription set and notify the server. */
    suspend fun subscribe(kinds: List<String>): JsonRpcResponse {
        synchronized(stateLock) { activeSubscriptions += kinds }
        return call(
            "remote.editor.subscribe",
            buildJsonObject {
                put("kinds", JsonArray(kinds.map { JsonPrimitive(it) }))
            },
        )
    }

    /** Remove [kinds] from the active subscription set and notify the server. */
    suspend fun unsubscribe(kinds: List<String>): JsonRpcResponse {
        synchronized(stateLock) { activeSubscriptions -= kinds.toSet() }
        return call(
            "remote.editor.unsubscribe",
            buildJsonObject {
                put("kinds", JsonArray(kinds.map { JsonPrimitive(it) }))
            },
        )
    }

    /**
     * Snapshot of currently-tracked subscription kinds. Read-only,
     * exposed for tests and the UI layer.
     */
    fun activeSubscriptionKinds(): Set<String> =
        synchronized(stateLock) { activeSubscriptions.toSet() }

    /**
     * Convenience helper around `remote.solution_agent.get_session_entry`.
     * See R-5f: on `agent_session_message_appended` we re-fetch only the
     * single new (or mutated) entry rather than the whole transcript.
     */
    suspend fun getSessionEntry(
        sessionId: String,
        index: Int,
        includeImages: Boolean = true,
    ): GetSessionEntryResult {
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("index", index)
            put("include_images", includeImages)
        }
        val response = call("remote.solution_agent.get_session_entry", params)
        val err = response.error
        if (err != null) {
            error("get_session_entry failed: ${err.message}")
        }
        val result = response.structuredContent()
            ?: error("get_session_entry returned no structuredContent")
        return JsonRpc.json.decodeFromJsonElement(GetSessionEntryResult.serializer(), result)
    }

    /**
     * List the registry (catalog) projects available for adding to a
     * Solution. Backs the mobile project picker. Returns an empty list
     * when the catalog is empty (a normal state, not an error).
     */
    suspend fun catalogList(): CatalogListResult {
        val response = call("remote.catalog.list")
        val err = response.error
        if (err != null) error("catalog.list failed: ${err.message}")
        val result = response.structuredContent()
            ?: error("catalog.list returned no structuredContent")
        return JsonRpc.json.decodeFromJsonElement(CatalogListResult.serializer(), result)
    }

    /**
     * Add an existing catalog project as a member of [solutionId]. The
     * server clones it in the background and returns an `operation_id`
     * immediately; clone progress arrives via `solution_member_add_*`
     * notifications.
     */
    suspend fun addMember(solutionId: String, catalogId: String): AddMemberResult {
        val params = buildJsonObject {
            put("solution_id", solutionId)
            put("catalog_id", catalogId)
        }
        val response = call("remote.solutions.add_member", params)
        val err = response.error
        if (err != null) error("add_member failed: ${err.message}")
        val result = response.structuredContent()
            ?: error("add_member returned no structuredContent")
        return JsonRpc.json.decodeFromJsonElement(AddMemberResult.serializer(), result)
    }

    /**
     * Create a new empty (non-git) project named [name] as a member of
     * [solutionId]. Synchronous server-side — the returned `catalog_id`
     * is the slug the server assigned the new member.
     */
    suspend fun addEmptyMember(solutionId: String, name: String): AddEmptyMemberResult {
        val params = buildJsonObject {
            put("solution_id", solutionId)
            put("name", name)
        }
        val response = call("remote.solutions.add_empty_member", params)
        val err = response.error
        if (err != null) error("add_empty_member failed: ${err.message}")
        val result = response.structuredContent()
            ?: error("add_empty_member returned no structuredContent")
        return JsonRpc.json.decodeFromJsonElement(AddEmptyMemberResult.serializer(), result)
    }

    /**
     * Remove a member from [solutionId]. Config-only on the server — the
     * on-disk worktree directory is left untouched.
     */
    suspend fun removeMember(solutionId: String, catalogId: String) {
        val params = buildJsonObject {
            put("solution_id", solutionId)
            put("catalog_id", catalogId)
        }
        val response = call("remote.solutions.remove_member", params)
        val err = response.error
        if (err != null) error("remove_member failed: ${err.message}")
    }

    /**
     * Remove a project from the registry (catalog). The server refuses
     * (returns an error listing the solutions) if any solution still has
     * it as a member, so cached clones of in-use projects are never
     * orphaned. The tool-level rejection surfaces via [JsonRpcResponse.toolError].
     */
    suspend fun catalogRemove(catalogId: String) {
        val params = buildJsonObject { put("catalog_id", catalogId) }
        val response = call("remote.catalog.remove_project", params)
        val err = response.error
        if (err != null) error(err.message)
        val toolErr = response.toolError()
        if (toolErr != null) error(toolErr)
    }

    // ---------------------------------------------------------------------
    // Lifecycle coroutine
    // ---------------------------------------------------------------------

    private suspend fun lifecycleLoop() {
        var attempt = 0
        // Drain any pre-loop wake pokes so a wakeReconnect() that fires
        // before the loop is observing the channel doesn't short-circuit
        // the very first connect (attempt==0 already runs immediately).
        while (wakeRequests.tryReceive().isSuccess) { /* drain */ }
        // scope is non-null and active inside the lifecycle loop; the close()
        // path nulls scope via teardown, but `closing=true` is the actual
        // exit signal that this coroutine observes first.
        while (!closing) {
            if (attempt == 0) {
                _connectionState.value = ConnectionState.Connecting
            } else {
                val delayMs = backoff.nextDelayMs(attempt)
                _connectionState.value =
                    ConnectionState.Reconnecting(attempt, delayMs, lastConnectFailure)
                // Race the backoff timer against a wakeReconnect() poke.
                // Receiving null = timer fired naturally; receiving Unit =
                // wake fired, reset the attempt counter so a subsequent
                // failure restarts the 1s → 2s → … schedule rather than
                // pinning at the 30s cap forever.
                val woken = withTimeoutOrNull(delayMs) { wakeRequests.receive() } != null
                if (closing) break
                if (woken) {
                    attempt = 0
                }
                _connectionState.value = ConnectionState.Connecting
            }
            val outcome = runOneAttempt()
            when (outcome) {
                AttemptOutcome.Connected -> {
                    attempt = 0
                    lastConnectFailure = null
                    // Wait for a close/failure event before looping.
                    val end = awaitDisconnect()
                    if (closing || end is DisconnectReason.UserClose) break
                    if (end is DisconnectReason.Terminal) {
                        lastConnectFailure = end.failure
                        _connectionState.value = ConnectionState.FailedTerminal(end.failure)
                        firstConnect?.takeIf { !it.isCompleted }
                            ?.completeExceptionally(ConnectException(end.failure))
                        return
                    }
                    // Transient — loop with attempt=1 carrying the cause.
                    lastConnectFailure = (end as DisconnectReason.Transient).failure
                    attempt = 1
                }
                is AttemptOutcome.TerminalFailure -> {
                    lastConnectFailure = outcome.failure
                    _connectionState.value = ConnectionState.FailedTerminal(outcome.failure)
                    firstConnect?.takeIf { !it.isCompleted }
                        ?.completeExceptionally(ConnectException(outcome.failure))
                    return
                }
                is AttemptOutcome.TransientFailure -> {
                    lastConnectFailure = outcome.failure
                    // Surface the first-attempt failure to connect() so
                    // the UI doesn't pin "Connecting…" forever; the loop
                    // continues and Reconnecting state drives the banner.
                    firstConnect?.takeIf { !it.isCompleted }
                        ?.completeExceptionally(ConnectException(outcome.failure))
                    attempt = if (attempt == 0) 1 else attempt + 1
                }
            }
        }
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * Drive one transport from connect → handshake → ready or → failure.
     * Returns once the handshake completes, fails, or the transport hangs
     * up before reaching Established.
     */
    private suspend fun runOneAttempt(): AttemptOutcome {
        val handshake = CompletableDeferred<AttemptOutcome>()
        val stage = HandshakeStageRef()
        // Handshake-private transport ref. The HandshakeListener uses
        // THIS to send the HMAC response (it can't read the shared
        // `transport` field because we deliberately don't publish to
        // it until handshake completes — see below). After Established
        // the listener stops using this and the shared field takes
        // over for app-side RPC traffic.
        val handshakeTransportRef =
            AtomicReference<RemoteTransport?>(null)
        val listener = HandshakeListener(handshake, stage, handshakeTransportRef)
        val tx = transportFactory.connect(url, listener)
        handshakeTransportRef.set(tx)
        // DO NOT publish to the shared `transport` field yet. Doing so
        // pre-handshake opens a TOCTOU window where any `client.call(...)`
        // path (e.g. a post-reconnect `SessionDetailStore.refreshSession`
        // firing on `ConnectionState.Reconnecting`) reads a non-null
        // `transport` and ships a JSON-RPC frame to the server BEFORE
        // the HMAC response. The server is mid-handshake-read at that
        // point, treats the unsolicited frame as a malformed handshake
        // response, and adds the subnet to the auth-failure ladder.
        // Diagnosed 2026-05-19 via the server's payload-preview WARN
        // log; the offending payload was a literal
        // `{"jsonrpc":"2.0","method":"remote.solution_agent.get_session",…}`.
        return try {
            // Bound the full attempt by the handshake timeout — if the
            // server never sends a nonce, the WS would otherwise hang
            // forever. Post-Established, steady-state has its own
            // pingInterval.
            val outcome = withTimeoutOrNull(ConnectFailure.HANDSHAKE_TIMEOUT_MS) {
                handshake.await()
            } ?: AttemptOutcome.TransientFailure(
                ConnectFailure.HandshakeTimeout(ConnectFailure.HANDSHAKE_TIMEOUT_MS)
            )
            if (outcome is AttemptOutcome.Connected) {
                // Handshake passed → it's safe to publish. Order is
                // important: `transport` first so `onConnected`'s queue
                // flush + resubscribe (which both call through
                // `callInternal`) see a non-null transport.
                transport = tx
                handshakeTransportRef.set(null)
                // Re-subscribe BEFORE transitioning to Connected — R-6a
                // reconnect-handshake atomicity. Also serializes the
                // queue flush after resubscribe so queued calls hit a
                // fully-registered server.
                onConnected()
                _connectionState.value = ConnectionState.Connected
                firstConnect?.takeIf { !it.isCompleted }?.complete(Unit)
            } else {
                tx.close()
                handshakeTransportRef.set(null)
                // `transport` was never published this attempt — nothing
                // to clear on the shared field.
            }
            outcome
        } catch (t: Throwable) {
            tx.close()
            handshakeTransportRef.set(null)
            AttemptOutcome.TransientFailure(ConnectFailure.classify(t))
        }
    }

    /** Wait for a Close / Failure event from the active transport listener. */
    private suspend fun awaitDisconnect(): DisconnectReason {
        for (event in events) {
            when (event) {
                LifecycleEvent.UserClose -> return DisconnectReason.UserClose
                is LifecycleEvent.TransportClosed -> {
                    transport = null
                    failPendingOnDisconnect(event.failure.userMessage)
                    return DisconnectReason.Transient(event.failure)
                }
                is LifecycleEvent.TransportFailure -> {
                    transport = null
                    failPendingOnDisconnect(event.failure.userMessage)
                    return if (event.terminal) {
                        DisconnectReason.Terminal(event.failure)
                    } else {
                        DisconnectReason.Transient(event.failure)
                    }
                }
                LifecycleEvent.QueueChanged -> {
                    // We're Connected here — flush whatever just arrived.
                    queueController?.flushQueue()
                }
            }
        }
        return DisconnectReason.UserClose
    }

    /**
     * Re-subscribe + flush queued calls after a successful handshake.
     * Subscribe is awaited before the Connected transition (R-6a
     * atomicity); a subscribe error is non-fatal — we still flush.
     */
    private suspend fun onConnected() {
        val kinds = synchronized(stateLock) { activeSubscriptions.toList() }
        if (kinds.isNotEmpty()) {
            runCatching {
                call(
                    "remote.editor.subscribe",
                    buildJsonObject {
                        put("kinds", JsonArray(kinds.map { JsonPrimitive(it) }))
                    },
                )
            }
        }
        queueController?.flushQueue()
    }

    private fun failPendingOnDisconnect(reason: String) {
        val cause = IllegalStateException("connection closed: $reason")
        pending.values.forEach { it.completeExceptionally(cause) }
        pending.clear()
    }

    // ---------------------------------------------------------------------
    // Handshake listener
    // ---------------------------------------------------------------------

    private class HandshakeStageRef {
        @Volatile var stage: HandshakeStage = HandshakeStage.AwaitingNonce
    }

    private inner class HandshakeListener(
        private val handshake: CompletableDeferred<AttemptOutcome>,
        private val ref: HandshakeStageRef,
        /**
         * Transport handle for THIS handshake only — used to send the
         * HMAC response. Read instead of the shared [transport] field
         * because that field is deliberately not published until
         * handshake completion (see [runOneAttempt]'s rationale on
         * the TOCTOU between `transport = tx` and the post-Established
         * publish). Cleared by [runOneAttempt] once the listener's
         * state advances past AwaitingNonce, so post-Established
         * onText paths fall back to whatever the shared field carries.
         */
        private val handshakeTx: AtomicReference<RemoteTransport?>,
    ) : RemoteTransportListener {

        override fun onBinary(bytes: ByteArray) {
            // R-2 handshake is text/JSON only — binary before Established
            // means the peer speaks a different protocol. Post-Established
            // binary is unexpected too (JSON-RPC is text); drop silently.
            if (ref.stage != HandshakeStage.Established) {
                completeTransient(
                    ConnectFailure.ProtocolError(
                        "unexpected binary frame during handshake — peer isn't spk-editor"
                    )
                )
            }
        }

        override fun onText(text: String) {
            when (ref.stage) {
                HandshakeStage.AwaitingNonce -> {
                    val challengeBytes = parseChallengeFrame(text)
                    if (challengeBytes == null) {
                        completeTerminal(
                            ConnectFailure.ProtocolError(
                                "expected challenge JSON frame, got: " +
                                    text.take(60)
                            )
                        )
                        return
                    }
                    // Use the per-handshake transport ref, not the
                    // shared `transport` field — the shared field is
                    // deliberately null until handshake completes (see
                    // `runOneAttempt`).
                    val tx = handshakeTx.get() ?: return
                    val responseBytes = auth.respond(challengeBytes)
                    val responseFrame = JsonRpc.json.encodeToString(
                        HandshakeResponseFrame.serializer(),
                        HandshakeResponseFrame(response = HexCodec.encode(responseBytes)),
                    )
                    ref.stage = HandshakeStage.AwaitingVerdict
                    tx.send(responseFrame)
                }
                HandshakeStage.AwaitingVerdict -> {
                    val obj = runCatching { JsonRpc.json.parseToJsonElement(text).jsonObject }
                        .getOrNull()
                    val type = obj?.get("type")?.jsonPrimitive?.contentOrNull
                    when (type) {
                        "welcome" -> {
                            ref.stage = HandshakeStage.Established
                            handshake.complete(AttemptOutcome.Connected)
                        }
                        else -> completeTerminal(
                            ConnectFailure.AuthRejected(
                                "Unexpected handshake response: ${text.take(60)}"
                            )
                        )
                    }
                }
                HandshakeStage.Established -> dispatchJsonRpc(text)
            }
        }

        /**
         * Parse a `{"type":"challenge","challenge":"<hex>","v":1}` frame
         * and return the decoded 16-byte challenge. Returns null on any
         * shape mismatch — caller turns null into a ProtocolError.
         */
        private fun parseChallengeFrame(text: String): ByteArray? {
            val obj = runCatching { JsonRpc.json.parseToJsonElement(text).jsonObject }
                .getOrNull() ?: return null
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            if (type != "challenge") return null
            val hex = obj["challenge"]?.jsonPrimitive?.contentOrNull ?: return null
            val bytes = runCatching { HexCodec.decode(hex) }.getOrNull() ?: return null
            return bytes.takeIf { it.size == HmacChallengeAuth.NONCE_LEN }
        }

        override fun onFailure(t: Throwable) {
            val failure = ConnectFailure.classify(t)
            // Pin = terminal even on Established (re-pair required).
            val isTerminal = failure is ConnectFailure.TlsPinMismatch ||
                failure is ConnectFailure.AuthRejected
            if (ref.stage == HandshakeStage.Established) {
                events.trySend(LifecycleEvent.TransportFailure(failure, terminal = isTerminal))
            } else if (!handshake.isCompleted) {
                handshake.complete(
                    if (isTerminal) AttemptOutcome.TerminalFailure(failure)
                    else AttemptOutcome.TransientFailure(failure)
                )
            }
        }

        override fun onClosed(code: Int, reason: String) {
            val failure = ConnectFailure.ServerClosed(code, reason)
            val isTerminal = !failure.isRetryable
            if (ref.stage == HandshakeStage.Established) {
                events.trySend(LifecycleEvent.TransportClosed(failure))
            } else if (!handshake.isCompleted) {
                handshake.complete(
                    if (isTerminal) AttemptOutcome.TerminalFailure(failure)
                    else AttemptOutcome.TransientFailure(failure)
                )
            }
        }

        private fun completeTerminal(failure: ConnectFailure) {
            if (!handshake.isCompleted) handshake.complete(AttemptOutcome.TerminalFailure(failure))
        }
        private fun completeTransient(failure: ConnectFailure) {
            if (!handshake.isCompleted) handshake.complete(AttemptOutcome.TransientFailure(failure))
        }
    }

    private fun dispatchJsonRpc(text: String) {
        val parsed = runCatching { JsonRpc.json.parseToJsonElement(text) }.getOrNull() ?: return
        val obj = parsed.jsonObject
        val idElement = obj["id"]
        if (idElement != null) {
            // Response frame. Match [pending]; if no waiter (call was
            // cancelled or timed out), drop silently — routing it to
            // [_notifications] would pollute the bounded buffer and
            // mislead subscribers expecting server-initiated frames.
            val id = idElement.jsonPrimitive.let { runCatching { it.long }.getOrNull() }
                ?: return
            val deferred = pending.remove(id) ?: return
            val response = runCatching { JsonRpc.decodeResponse(text) }
            response.fold(
                onSuccess = { deferred.complete(it) },
                onFailure = { deferred.completeExceptionally(it) },
            )
            return
        }
        // No id at all — server-initiated notification. Channel is
        // UNLIMITED so `trySend` always succeeds; see
        // [notificationChannel] kdoc for why we don't use a bounded
        // SharedFlow with `tryEmit` here.
        notificationChannel.trySend(parsed)
    }

    // ---------------------------------------------------------------------
    // Internal types
    // ---------------------------------------------------------------------

    internal enum class HandshakeStage { AwaitingNonce, AwaitingVerdict, Established }

    /**
     * Wire shape for the response side of the handshake. See
     * [HmacChallengeAuth] kdoc for the full three-frame protocol.
     */
    @Serializable
    private data class HandshakeResponseFrame(
        val type: String = "response",
        val response: String,
    )

    private sealed interface AttemptOutcome {
        data object Connected : AttemptOutcome
        data class TerminalFailure(val failure: ConnectFailure) : AttemptOutcome
        data class TransientFailure(val failure: ConnectFailure) : AttemptOutcome
    }

    private sealed interface DisconnectReason {
        data object UserClose : DisconnectReason
        data class Transient(val failure: ConnectFailure) : DisconnectReason
        data class Terminal(val failure: ConnectFailure) : DisconnectReason
    }

    /**
     * Surface marker for queue items dropped at [close].
     *
     * **Persistence semantics:** when this exception is raised, the
     * corresponding [QueuedMessage] has already been removed from the
     * [queueStore], so a fresh [RemoteClient] instance built after the
     * close will NOT replay it. Callers that want the message delivered
     * are responsible for re-issuing the [queueCall] themselves.
     */
    class ClosedException : RuntimeException("client closed before flush")

    companion object {
        /**
         * Default TTL for [queueCall] — **24 hours** (R-6d). Sized for
         * a metro / flight / overnight outage; stale-send risk is
         * mitigated by the `:app` bounce-to-input recovery path.
         */
        const val DEFAULT_QUEUE_TTL_MS: Long = 24L * 60L * 60L * 1_000L

        /**
         * Default per-call timeout for [call]. Bounds RPC duration so a
         * stuck server doesn't pin the UI spinner forever. 30s suits
         * chat-shaped ops (longest realistic: `create_session` spawning
         * an agent subprocess) and lets users notice + retry.
         */
        const val DEFAULT_CALL_TIMEOUT_MS: Long = 30_000L
    }
}
