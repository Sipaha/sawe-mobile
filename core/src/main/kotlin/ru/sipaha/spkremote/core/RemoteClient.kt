package ru.sipaha.spkremote.core

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
     * Atomic guard against concurrent [connect] calls. The previous
     * `check(lifecycleJob == null)` was a non-atomic read; two threads
     * could both pass it and both launch a lifecycle loop.
     *
     * Single-shot semantics: once tripped, [close] does NOT reset it.
     * A [RemoteClient] is a one-shot lifecycle — re-connecting an
     * already-closed instance would require also clearing [closing],
     * the queue rehydration state, and the failed-pending bookkeeping;
     * the natural Kotlin idiom is "build a fresh instance per connect
     * cycle", and the `:app` ConnectionManager already does so via
     * `tearDownConnection` + a new `RemoteClient(...)` per server switch.
     */
    private val connectGuard = AtomicBoolean(false)
    /**
     * The most recent [ConnectFailure] observed by the lifecycle loop.
     * Read by [call] when [transport] is null so the resulting
     * [NotConnectedException] carries a specific reason instead of just
     * "not connected". Cleared back to null on every successful Connected
     * transition.
     */
    @Volatile private var lastConnectFailure: ConnectFailure? = null

    /**
     * Current transport (or null while reconnecting). Written only by
     * the lifecycle coroutine and [close]; read from arbitrary
     * coroutines via [callInternal]. The lifecycle-coroutine writes
     * "happen-before" the reads ordered by the [_connectionState]
     * flow's volatile semantics, but a non-Connected read of [transport]
     * still crosses threads — mark @Volatile so the read sees the most
     * recent write rather than a stale CPU-cached value.
     */
    @Volatile private var transport: RemoteTransport? = null

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
        // Rehydrate the queue from disk before the lifecycle loop boots.
        // The 24h TTL is enforced by [flushQueue] on every reconnect, so
        // we just have to walk the persisted entries and wire them into
        // the in-memory wrappers — already-expired ones get bounced (and
        // removed from disk) by the next [flushQueue] tick which happens
        // on Connected.
        rehydrateQueue()
        val gate = CompletableDeferred<Unit>()
        // Serialize the start edge with [close] under [stateLock]. Without
        // this, close() can run between the connectGuard CAS and the
        // lifecycleJob assignment: close() sees lifecycleJob == null,
        // no-ops the cancel, then we proceed to launch a coroutine that
        // immediately exits because `closing` is true. The launched-then-
        // quit job is wasteful and the ordering is fragile. Taking the
        // same lock close() takes around `closing = true` + reading
        // lifecycleJob serializes these edges.
        synchronized(stateLock) {
            if (closing) throw ClosedException()
            firstConnect = gate
            lifecycleJob = target.launch { lifecycleLoop() }
        }
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
        // Serialize the close/connect edges + the close/callInternal
        // edges through [stateLock] so callInternal cannot install a
        // pending entry after we've cleared the map. The pending-install
        // path also takes [stateLock] and re-checks `closing` /
        // `transport` AFTER installing — that pair of checks closes the
        // install-after-clear window.
        val toClose: RemoteTransport?
        val pendingSnapshot: List<CompletableDeferred<JsonRpcResponse>>
        val queuedSnapshot: List<QueuedCall>
        val gate: CompletableDeferred<Unit>?
        val job: Job?
        synchronized(stateLock) {
            closing = true
            // Null the transport FIRST so any callInternal that races
            // past our `closing` check observes a missing transport and
            // bails out cleanly before installing a pending entry.
            toClose = transport
            transport = null
            pendingSnapshot = pending.values.toList()
            pending.clear()
            // Queued items are dropped with ClosedException for symmetry
            // with TTL. We also remove them from [queueStore] below
            // (outside the lock, to avoid holding the monitor across
            // disk I/O) — see the drain loop after `synchronized`.
            // This is intentional: leaving persisted entries behind
            // would let a next-process RemoteClient replay them after
            // callers already observed ClosedException and (possibly)
            // retried, producing duplicates. ClosedException now means
            // "the message is gone; retry from your side if you want
            // it sent again."
            queuedSnapshot = queued.toList()
            queued.clear()
            gate = firstConnect
            job = lifecycleJob
            lifecycleJob = null
        }
        events.trySend(LifecycleEvent.UserClose)
        pendingSnapshot.forEach { it.cancel() }
        // Drain the queued items: complete with ClosedException AND
        // remove the disk record. Without removal a next-process
        // RemoteClient instance would replay these on rehydrate, but
        // callers that observed the ClosedException are free to retry
        // — that would produce duplicates. Clean-on-close gives a
        // single, predictable failure surface: ClosedException means
        // "this message is gone; retry if you want it sent". The
        // `:app` ConnectionManager treats ClosedException as a normal
        // user-initiated teardown and does not retry.
        queuedSnapshot.forEach {
            runCatching { queueStore.remove(it.message.id) }
            it.deferred.completeExceptionally(ClosedException())
        }
        // Unblock callers still awaiting their first handshake.
        gate?.takeIf { !it.isCompleted }?.completeExceptionally(ClosedException())
        toClose?.close()
        job?.cancel()
        // [close] is terminal — we deliberately do NOT reset [connectGuard]
        // or [closing]. Resetting the guard while [closing] stays true
        // would let a subsequent [connect] pass the guard, but the
        // lifecycle loop would immediately exit (its `while (!closing)`
        // check fails) leaving the new [firstConnect] gate to hang
        // forever. A [RemoteClient] is single-shot; callers build a
        // fresh instance to reconnect.
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
        // Install the pending entry under [stateLock] and re-check the
        // close state AFTER the install so a concurrent close() — which
        // also takes the lock, nulls transport, then clears [pending] —
        // cannot leave an orphaned deferred. Sequence:
        //   close():  lock → closing=true → transport=null → pending.clear() → unlock
        //   here:     lock → if closing/no transport: throw → pending[id]=…  → unlock
        // Because close() clears pending AFTER nulling transport (both
        // under the lock), the worst we observe in the install branch
        // is the absence of a transport; we never install behind a
        // pending.clear().
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
        //
        // Race: between the connection-state read and `call()`, the
        // lifecycle coroutine can null out [transport] (mid-flush
        // transport drop) and `callInternal` throws [NotConnectedException].
        // Catch it and fall through to the queuing branch so we honour
        // the "held until next Connected" contract instead of dropping
        // the message silently.
        if (_connectionState.value is ConnectionState.Connected) {
            try {
                return call(method, params)
            } catch (_: NotConnectedException) {
                // Fall through to queue.
            }
        }
        val deferred = CompletableDeferred<JsonRpcResponse>()
        val message = QueuedMessage(
            id = UUID.randomUUID().toString(),
            method = method,
            params = params,
            enqueuedAtMs = nowMs(),
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
                // Persist BEFORE adding to the in-memory deque, both
                // under the same lock. If we add to the deque first and
                // release the lock before queueStore.add lands, a
                // QueueChanged dispatch can drain + remove the in-memory
                // entry before the disk write completes — leaving a
                // phantom on disk that gets replayed at next process
                // start. Ordering: persist → enqueue, atomically.
                runCatching { queueStore.add(message) }
                queued += item
                false
            }
        }
        if (sendSynchronously) {
            // Connected raced us; send directly. No need to persist —
            // the call is one round-trip away from a real response.
            try {
                return call(method, params)
            } catch (_: NotConnectedException) {
                // Same race as the outer fast path — fall through to
                // the persistent queue branch.
                synchronized(stateLock) {
                    runCatching { queueStore.add(message) }
                    queued += item
                }
            }
        }
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
                // Run the re-subscribe handshake BEFORE transitioning
                // to Connected. The user-visible promise of R-6a is
                // "reconnect handshake atomicity" — once observers see
                // Connected they should not receive a notification gap
                // that includes the window between welcome and
                // subscribe-ack. Awaiting subscribe here also serializes
                // flushQueue() after the resubscribe, so any queued
                // call that itself depends on subscription state hits
                // a fully-registered server.
                onConnected()
                _connectionState.value = ConnectionState.Connected
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
     * The re-subscribe RPC is awaited synchronously by the lifecycle
     * coroutine BEFORE the connection state transitions to Connected
     * and before [flushQueue] runs. Without this serialization the
     * server could dispatch a notification in the window between
     * welcome and subscribe-ack, leaving subscribers with a gap they
     * cannot detect from the Connected transition alone. A failure of
     * the subscribe call is non-fatal (callers see notifications resume
     * on the next successful reconnect), so we swallow exceptions and
     * still proceed to flushQueue.
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
        // Orchestrator: first walk the queue to drop anything past TTL,
        // then dispatch the survivors. Semantics are unchanged versus the
        // pre-refactor monolith — see [expireStaleEntries] and
        // [dispatchQueuedItems] for the two halves.
        val survivors = expireStaleEntries()
        if (survivors.isEmpty()) return
        dispatchQueuedItems(survivors)
    }

    /**
     * Walk [queued] under [stateLock], partitioning into still-fresh
     * survivors and TTL-expired entries. Expired entries are removed
     * from [queueStore] and bounced via [onMessageExpired] + their
     * deferred is completed with [QueueTtlException]. Returns the
     * survivors (in their original FIFO order) for [dispatchQueuedItems]
     * to send.
     */
    private fun expireStaleEntries(): List<QueuedCall> {
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
        return survivors
    }

    /**
     * Attempt the FIFO drain of [toSend] over the current [transport].
     * On transport loss mid-flush, the untouched suffix is re-enqueued at
     * the head of [queued] so FIFO order survives the next reconnect.
     * Returns true iff every item was dispatched (i.e. the transport
     * stayed alive through the whole loop and no per-item TTL fired).
     */
    private fun dispatchQueuedItems(toSend: List<QueuedCall>): Boolean {
        val activeScope = scope
        if (activeScope == null) {
            // Without a scope we can't fire — restore everything.
            synchronized(stateLock) { queued.addAll(toSend) }
            return false
        }
        var fullyDrained = true
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
                return false
            }
            val remainingTtl = item.ttlMs - (nowMs() - item.message.enqueuedAtMs)
            if (remainingTtl <= 0) {
                runCatching { queueStore.remove(item.message.id) }
                runCatching { onMessageExpired?.invoke(item.message) }
                item.deferred.completeExceptionally(QueueTtlException())
                fullyDrained = false
                continue
            }
            // Per-item launch — does NOT block the lifecycle loop on the
            // wire response. Audit Phase 3 M1 (sequential await) caused
            // deadlocks in the lifecycle channel: while flushQueue was
            // blocked on `call()` no other LifecycleEvent could be
            // processed, including TransportClosed. Reverted to per-item
            // launch; the FIFO-on-mid-flush-drop invariant remains a
            // documented gap (see .audit-backlog.md M1 + disabled T1).
            activeScope.launch {
                try {
                    val resp = call(item.message.method, item.message.params)
                    runCatching { queueStore.remove(item.message.id) }
                    item.deferred.complete(resp)
                } catch (t: Throwable) {
                    if (t is QueueTtlException) {
                        runCatching { queueStore.remove(item.message.id) }
                        item.deferred.completeExceptionally(t)
                    } else {
                        // Transient — re-enqueue this single item at the
                        // head. (FIFO across multiple concurrent failures
                        // is the open M1 gap.)
                        synchronized(stateLock) { queued.addFirst(item) }
                        events.trySend(LifecycleEvent.QueueChanged)
                    }
                }
            }
        }
        return fullyDrained
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
                // Fire the bounce callback directly — consistent with the
                // other two TTL-expiry paths (expireStaleEntries,
                // dispatchQueuedItems per-item) which also invoke it
                // synchronously. The callback contract is "may be called
                // from any coroutine; implementations must be thread-safe"
                // (see callback KDoc).
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
        val idElement = obj["id"]
        if (idElement != null) {
            // Response frame. Match against [pending]; if no caller is
            // waiting (typical case: the call was already cancelled or
            // timed out), drop silently — emitting it onto
            // [_notifications] would pollute the bounded SharedFlow
            // buffer (64 entries) and confuse subscribers that only
            // expect server-initiated frames.
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
        // No id at all — server-initiated notification.
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
