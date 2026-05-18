package ru.sipaha.spkremote.core

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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
 * **Handshake (each attempt):**
 *   1. WebSocket upgrade to `wss://host:port/remote` (TLS pinned by
 *      [PairingUrl.fingerprint]).
 *   2. Receive 16-byte binary nonce.
 *   3. Send 32-byte binary HMAC-SHA256 response (see [HmacChallengeAuth]).
 *   4. Receive ASCII verdict `OK` (accept) or `REJECT` (terminate).
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
 *     [ConnectionState.Connected] transition or until its TTL expires.
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
     * Always called from the lifecycle coroutine. Implementations must
     * not block — schedule disk I/O on a separate dispatcher if needed.
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
    private val _notifications = MutableSharedFlow<JsonElement>(extraBufferCapacity = 64)
    val notifications: SharedFlow<JsonElement> = _notifications.asSharedFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * Event kinds the caller has asked to receive. Mutated under [stateLock]
     * to keep [subscribe]/[unsubscribe] linearizable, and replayed on every
     * successful reconnect handshake.
     */
    private val activeSubscriptions = mutableSetOf<String>()
    private val stateLock = Any()

    /**
     * Outbound queue — in-memory wrappers pairing each persisted
     * [QueuedMessage] with its caller-side [CompletableDeferred]. The
     * authoritative ordering and survival across restarts comes from
     * [queueStore]; this deque is just a fast lookup for the in-flight
     * coroutines awaiting their response.
     */
    private val queued = ArrayDeque<QueuedCall>()

    /** Events the lifecycle coroutine consumes. */
    private val events = Channel<LifecycleEvent>(Channel.UNLIMITED)

    /** Set after the first successful [connect]; reused by subsequent reconnects. */
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var lifecycleJob: Job? = null
    @Volatile private var firstConnect: CompletableDeferred<Unit>? = null
    /**
     * The most recent [ConnectFailure] observed by the lifecycle loop.
     * Read by [call] when [transport] is null so the resulting
     * [NotConnectedException] carries a specific reason instead of just
     * "not connected". Cleared back to null on every successful Connected
     * transition.
     */
    @Volatile private var lastConnectFailure: ConnectFailure? = null

    /** Current transport (or null while reconnecting). Mutated only from the lifecycle coroutine. */
    private var transport: RemoteTransport? = null

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
        check(lifecycleJob == null) { "RemoteClient already connecting/connected" }
        this.scope = target
        // Rehydrate the queue from disk before the lifecycle loop boots.
        // The 24h TTL is enforced by [flushQueue] on every reconnect, so
        // we just have to walk the persisted entries and wire them into
        // the in-memory wrappers — already-expired ones get bounced (and
        // removed from disk) by the next [flushQueue] tick which happens
        // on Connected.
        rehydrateQueue()
        val gate = CompletableDeferred<Unit>()
        firstConnect = gate
        lifecycleJob = target.launch { lifecycleLoop() }
        // Caller awaits the first Connected transition (or terminal failure).
        gate.await()
    }

    /**
     * Read every previously-persisted [QueuedMessage] from [queueStore]
     * into [queued], wrapping each in a fresh [CompletableDeferred].
     *
     * **Bounce semantics for orphaned deferreds:** an entry restored
     * from disk has no caller awaiting its [CompletableDeferred] — the
     * coroutine that originally called [queueCall] died with the
     * previous process. We still complete the deferred on success /
     * failure so the bookkeeping is symmetric, but no one will observe
     * the result. The user-visible recovery path is [onMessageExpired]
     * — that's where the `:app` layer plumbs the bounced text back into
     * the draft repository for retry.
     */
    private fun rehydrateQueue() {
        val persisted = queueStore.loadAll()
        if (persisted.isEmpty()) return
        synchronized(stateLock) {
            for (msg in persisted.sortedBy { it.enqueuedAtMs }) {
                queued += QueuedCall(
                    message = msg,
                    deferred = CompletableDeferred(),
                    ttlMs = DEFAULT_QUEUE_TTL_MS,
                )
            }
        }
    }

    /** Whether a programmatic close has been requested (lifecycle ends). */
    @Volatile private var closing = false

    fun close() {
        closing = true
        events.trySend(LifecycleEvent.UserClose)
        // Fail pending in-flight calls — the wire is gone.
        pending.values.forEach { it.cancel() }
        pending.clear()
        // Queued items are dropped with ClosedException for symmetry with TTL.
        // We deliberately do NOT remove them from [queueStore] — close() can
        // mean "tear down before process exit / config change" and persistent
        // entries should survive into the next RemoteClient incarnation.
        // forgetPairing() in :app calls queueStore.clear() explicitly when
        // the user re-pairs, which is the only situation where draining is
        // correct.
        synchronized(stateLock) {
            queued.forEach { it.deferred.completeExceptionally(ClosedException()) }
            queued.clear()
        }
        // Unblock callers still awaiting their first handshake.
        firstConnect?.takeIf { !it.isCompleted }
            ?.completeExceptionally(ClosedException())
        transport?.close()
        transport = null
        lifecycleJob?.cancel()
        lifecycleJob = null
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
        val active = transport ?: throw NotConnectedException(lastConnectFailure)
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonRpcResponse>()
        pending[id] = deferred
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
            val sent = active.send(req)
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
     * spent queued + in flight.
     *
     * Default TTL is **24 hours** (R-6d). The realistic offline scenario
     * is a metro / flight / overnight outage — the user expects a tapped
     * Send to wake up and deliver itself. The bounce-to-input recovery
     * path in `:app` (`MainViewModel.handleExpiredMessage`) gives the
     * user a second chance even when the TTL does fire.
     *
     * **Persistence:** the message is written to [queueStore] before
     * this call awaits, so a force-kill between enqueue and reconnect
     * preserves it for the next [connect] / [rehydrateQueue] pair.
     *
     * @throws TimeoutException via the returned response promise on TTL
     *   expiry (wrapped as a [JsonRpcResponse] failure).
     */
    suspend fun queueCall(
        method: String,
        params: JsonElement? = null,
        ttlMs: Long = DEFAULT_QUEUE_TTL_MS,
    ): JsonRpcResponse {
        // Fast path: if we're connected, hand off to `call` directly. The
        // TTL only applies while the call is *queued* — once the wire
        // delivers it, the server's per-method timeout is what we trust.
        // (Wrapping in withTimeout here would interfere with TestScope
        //  virtual-time advancement and isn't what the spec asked for.)
        if (_connectionState.value is ConnectionState.Connected) {
            return call(method, params)
        }
        val deferred = CompletableDeferred<JsonRpcResponse>()
        val message = QueuedMessage(
            id = UUID.randomUUID().toString(),
            method = method,
            params = params,
            enqueuedAtMs = nowMs(),
            attemptCount = 0,
        )
        val item = QueuedCall(
            message = message,
            deferred = deferred,
            ttlMs = ttlMs,
        )
        val sendSynchronously = synchronized(stateLock) {
            // Re-check inside the lock so a flush in flight doesn't strand us.
            if (_connectionState.value is ConnectionState.Connected) {
                true
            } else {
                queued += item
                false
            }
        }
        if (sendSynchronously) {
            // Connected raced us; send directly. No need to persist —
            // the call is one round-trip away from a real response.
            return call(method, params)
        }
        // Persist *after* adding to the in-memory deque so the lifecycle
        // coroutine can see the entry as soon as it picks up QueueChanged.
        // EncryptedSharedPreferences performs synchronous I/O on commit();
        // we rely on the `:app` impl using apply()-equivalent semantics.
        runCatching { queueStore.add(message) }
        events.trySend(LifecycleEvent.QueueChanged)
        return awaitWithTtl(item)
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

    // ---------------------------------------------------------------------
    // Lifecycle coroutine
    // ---------------------------------------------------------------------

    private suspend fun lifecycleLoop() {
        var attempt = 0
        while (!closing && (scope?.isActive == true)) {
            if (attempt == 0) {
                _connectionState.value = ConnectionState.Connecting
            } else {
                val delayMs = backoff.nextDelayMs(attempt)
                _connectionState.value =
                    ConnectionState.Reconnecting(attempt, delayMs, lastConnectFailure)
                delay(delayMs)
                if (closing) break
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
                    // First-attempt failure: surface to the caller of
                    // connect() so the UI doesn't pin "Connecting…" while
                    // we churn through reconnect attempts in the
                    // background. The lifecycle loop keeps running; the
                    // banner (driven by [connectionState] observers) shows
                    // ongoing Reconnecting progress.
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
        val listener = HandshakeListener(handshake, stage)
        val tx = transportFactory.connect(url, listener)
        transport = tx
        return try {
            // Bound the full attempt by the handshake timeout — if the
            // server never sends a nonce (peer isn't spk-editor, half-open
            // connection survived TLS, etc.) the WS would otherwise hang
            // forever. Once HandshakeStage.Established we're done with
            // this timeout — the steady-state has its own pingInterval.
            val outcome = withTimeoutOrNull(ConnectFailure.HANDSHAKE_TIMEOUT_MS) {
                handshake.await()
            } ?: AttemptOutcome.TransientFailure(
                ConnectFailure.HandshakeTimeout(ConnectFailure.HANDSHAKE_TIMEOUT_MS)
            )
            if (outcome is AttemptOutcome.Connected) {
                _connectionState.value = ConnectionState.Connected
                onConnected()
                firstConnect?.takeIf { !it.isCompleted }?.complete(Unit)
            } else {
                tx.close()
                transport = null
            }
            outcome
        } catch (t: Throwable) {
            tx.close()
            transport = null
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
                    flushQueue()
                }
            }
        }
        return DisconnectReason.UserClose
    }

    /**
     * Re-subscribe + flush queued calls after a successful handshake.
     *
     * The re-subscribe RPC is fired on a child coroutine — we don't want to
     * block the lifecycle loop on its response, and a failure is non-fatal
     * (the next reconnect retries). Queue flushing is synchronous w.r.t.
     * the deque scan but dispatches each item on its own coroutine.
     */
    private fun onConnected() {
        val kinds = synchronized(stateLock) { activeSubscriptions.toList() }
        val activeScope = scope
        if (kinds.isNotEmpty() && activeScope != null) {
            activeScope.launch {
                runCatching {
                    call(
                        "remote.editor.subscribe",
                        buildJsonObject {
                            put("kinds", JsonArray(kinds.map { JsonPrimitive(it) }))
                        },
                    )
                }
            }
        }
        flushQueue()
    }

    /**
     * Send every still-fresh queued item; TTL-expire the stale ones. Held
     * under [stateLock] just long enough to drain the deque into a local
     * list so we don't hold the monitor across `call()`.
     *
     * On transport loss mid-flush, items not yet dispatched are re-enqueued
     * at the head of the deque so FIFO order survives across reconnects.
     *
     * **Persistence:** expired entries are removed from [queueStore] and
     * [onMessageExpired] fires before the deferred fails so the `:app`
     * bounce-to-input path sees the payload exactly once.
     */
    private fun flushQueue() {
        val (survivors, expired) = synchronized(stateLock) {
            val now = nowMs()
            val sv = ArrayList<QueuedCall>(queued.size)
            val ex = ArrayList<QueuedCall>()
            for (item in queued) {
                if (now - item.message.enqueuedAtMs >= item.ttlMs) {
                    ex += item
                } else {
                    sv += item
                }
            }
            queued.clear()
            sv to ex
        }
        for (item in expired) {
            runCatching { queueStore.remove(item.message.id) }
            runCatching { onMessageExpired?.invoke(item.message) }
            item.deferred.completeExceptionally(QueueTtlException())
        }
        val toSend = survivors
        if (toSend.isEmpty()) return
        val activeScope = scope ?: run {
            // Without a scope we can't fire — restore everything.
            synchronized(stateLock) { queued.addAll(toSend) }
            return
        }
        for ((index, item) in toSend.withIndex()) {
            val active = transport
            if (active == null) {
                // Wire dropped mid-flush — push the rest back at the head.
                synchronized(stateLock) {
                    val remaining = toSend.subList(index, toSend.size)
                    val combined = ArrayDeque<QueuedCall>(remaining.size + queued.size)
                    combined.addAll(remaining)
                    combined.addAll(queued)
                    queued.clear()
                    queued.addAll(combined)
                }
                return
            }
            val remainingTtl = item.ttlMs - (nowMs() - item.message.enqueuedAtMs)
            if (remainingTtl <= 0) {
                runCatching { queueStore.remove(item.message.id) }
                runCatching { onMessageExpired?.invoke(item.message) }
                item.deferred.completeExceptionally(QueueTtlException())
                continue
            }
            // The caller-side awaitWithTtl already enforces the overall TTL
            // budget. We just send + resolve here without an additional
            // timeout wrapper — wrapping with withTimeout would schedule a
            // virtual-time job that `advanceUntilIdle` can run past in
            // tests even when no real network latency exists.
            activeScope.launch {
                try {
                    val resp = call(item.message.method, item.message.params)
                    // Successful round-trip — remove from disk so the next
                    // restart doesn't replay.
                    runCatching { queueStore.remove(item.message.id) }
                    item.deferred.complete(resp)
                } catch (t: Throwable) {
                    // Transient (transport drop) — leave in the persisted
                    // store so the next reconnect retries. The deferred
                    // failure is observed by [awaitWithTtl] which will
                    // bounce-on-TTL if the retries don't succeed in time.
                    item.deferred.completeExceptionally(t)
                }
            }
        }
    }

    private suspend fun awaitWithTtl(item: QueuedCall): JsonRpcResponse {
        // Race: TTL timer vs the deferred completing via flushQueue.
        return try {
            withTimeout(item.ttlMs) { item.deferred.await() }
        } catch (t: TimeoutCancellationException) {
            val removed = synchronized(stateLock) { queued.remove(item) }
            // If we were still queued (not yet dispatched), clean up disk +
            // fire the bounce callback. If we'd already been dispatched on
            // the wire and the response just hasn't landed yet, do neither —
            // the in-flight pending entry will resolve via [dispatchJsonRpc]
            // or fail via [failPendingOnDisconnect], at which point its
            // disk entry is cleaned up there.
            if (removed) {
                runCatching { queueStore.remove(item.message.id) }
                runCatching { onMessageExpired?.invoke(item.message) }
            }
            if (!item.deferred.isCompleted) {
                item.deferred.completeExceptionally(QueueTtlException())
            }
            throw QueueTtlException()
        }
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
    ) : RemoteTransportListener {

        override fun onBinary(bytes: ByteArray) {
            // The R-2 handshake is entirely text/JSON — any binary frame
            // before Established means the peer speaks a different
            // protocol. Post-Established the protocol is also text-only
            // (JSON-RPC) so binary stays unexpected; drop silently to
            // avoid logging every keep-alive ping payload that might
            // sneak through.
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
                    val tx = transport ?: return
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
        val id = obj["id"]?.jsonPrimitive?.let { runCatching { it.long }.getOrNull() }
        if (id != null) {
            val deferred = pending.remove(id)
            if (deferred != null) {
                val response = runCatching { JsonRpc.decodeResponse(text) }
                response.fold(
                    onSuccess = { deferred.complete(it) },
                    onFailure = { deferred.completeExceptionally(it) },
                )
                return
            }
        }
        _notifications.tryEmit(parsed)
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

    private sealed interface LifecycleEvent {
        data object UserClose : LifecycleEvent
        data class TransportClosed(val failure: ConnectFailure) : LifecycleEvent
        data class TransportFailure(val failure: ConnectFailure, val terminal: Boolean) : LifecycleEvent
        data object QueueChanged : LifecycleEvent
    }

    /**
     * In-memory wrapper around a persisted [QueuedMessage] holding its
     * caller-side [CompletableDeferred] + the TTL chosen by the caller.
     * The [message] is the disk-canonical view; [deferred] is process-
     * local and re-created (orphaned) on rehydrate.
     */
    private data class QueuedCall(
        val message: QueuedMessage,
        val deferred: CompletableDeferred<JsonRpcResponse>,
        val ttlMs: Long,
    )

    /** Surface marker for TTL-expired queue items. */
    class QueueTtlException : RuntimeException("queued call timed out")

    /** Surface marker for queue items dropped at [close]. */
    class ClosedException : RuntimeException("client closed before flush")

    companion object {
        /**
         * Default TTL for [queueCall] — **24 hours** (R-6d).
         *
         * Trade-off vs the original 5-minute value: the realistic offline
         * scenario the user complained about is a metro ride / flight /
         * overnight outage, all of which are well above 5 min. The cost
         * of overshooting (a 24h-old "send" firing on resume) is
         * mitigated by the bounce-to-input recovery in `:app` — if the
         * intent's stale, the user notices the bubble and edits.
         */
        const val DEFAULT_QUEUE_TTL_MS: Long = 24L * 60L * 60L * 1_000L

        /**
         * Default per-call timeout for [call]. Without this a broken /
         * stuck server post-handshake would hang any RPC indefinitely —
         * the UI spinner waiting on the result would stay forever. 30s
         * is generous for chat-shaped operations (the longest realistic
         * one is `solution_agent.create_session` which spawns an agent
         * subprocess) and short enough that the user can tell something
         * went wrong and tap a retry button.
         */
        const val DEFAULT_CALL_TIMEOUT_MS: Long = 30_000L
    }
}
