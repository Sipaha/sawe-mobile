package ru.sipaha.sawe.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
     * Optional hook invoked when a queued message is **abandoned** — it
     * will never be delivered, by this client or any future one, and its
     * [QueueStore] record has been removed. The handler receives the
     * persisted [QueuedMessage] (NOT the in-memory wrapper) so it can
     * route the payload back to the user via the `:app` draft repository.
     *
     * Fires exactly once per message, and only for:
     *  - TTL expiry (the message sat undeliverable for its whole TTL);
     *  - a payload the wire cannot carry ([FrameTooLargeException]);
     *  - a message the server kept refusing across
     *    [QueueController.MAX_DISPATCH_ATTEMPTS] live send attempts.
     *
     * On [close] it fires only for messages that were already on the
     * socket, whose records are dropped because replaying them could
     * duplicate a message the server already has. Messages still parked
     * in the queue are handed to the next client instead, records intact,
     * and do NOT bounce. See [close].
     *
     * May be called from any coroutine context. Implementations must
     * be thread-safe and should not block — schedule disk I/O on a
     * separate dispatcher if needed.
     */
    private val onMessageExpired: ((QueuedMessage) -> Unit)? = null,
    /**
     * "May this queue record, written to a socket by a PREVIOUS process
     * and restored from [queueStore] on this one's start, go out again?"
     *
     * Consulted only for records that were rehydrated from disk AND carry a
     * [QueuedSendAttempt]; anything this process queued itself, and anything
     * that never reached a transport, is dispatched without asking. A
     * `false` verdict abandons the record: its store entry is dropped and
     * [onMessageExpired] fires, so `:app` bounces the text to the composer.
     *
     * The predicate receives the persisted message and the [ServerFeatures]
     * of the connection that would carry the replay. It must be pure and
     * cheap — it is called on the flush path, once per gated item.
     *
     * `null` (the default) means "always allow", which is the unconditional
     * replay `:cli`, `:core` tests and any non-Android consumer have always
     * had. `:app` passes `canReplayRehydratedSend`.
     */
    private val replayGate: ((QueuedMessage, ServerFeatures) -> Boolean)? = null,
) {
    constructor(
        url: PairingUrl,
        httpClientBuilder: OkHttpClient.Builder = OkHttpRemoteTransportFactory.defaultBuilder(url),
        queueStore: QueueStore = InMemoryQueueStore(),
        onMessageExpired: ((QueuedMessage) -> Unit)? = null,
        replayGate: ((QueuedMessage, ServerFeatures) -> Boolean)? = null,
    ) : this(
        url = url,
        transportFactory = OkHttpRemoteTransportFactory { _ -> httpClientBuilder },
        queueStore = queueStore,
        onMessageExpired = onMessageExpired,
        replayGate = replayGate,
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

    private val _serverFeatures = MutableStateFlow(ServerFeatures.NONE)

    /**
     * What the peer on the CURRENT socket advertised in its
     * `remote.editor.capabilities` response — the negotiated set that gates
     * every additive wire parameter. See [ServerFeatures].
     *
     * Starts at [ServerFeatures.NONE], is filled in by
     * [publishServerFeatures] once the capabilities probe answers, and is
     * reset to [ServerFeatures.NONE] on EVERY transition out of
     * [ConnectionState.Connected] — reconnect, terminal failure and
     * [close] alike. That reset is the whole safety story: a reconnect that
     * lands on a downgraded desktop must not inherit the previous socket's
     * flags, because the server's params structs are `deny_unknown_fields`
     * and the first poll carrying an inherited parameter would come back a
     * hard error rather than degrade.
     */
    val serverFeatures: StateFlow<ServerFeatures> = _serverFeatures.asStateFlow()

    /**
     * Event kinds the caller has asked to receive. Mutated under [stateLock]
     * to keep [subscribe]/[unsubscribe] linearizable, and replayed on every
     * successful reconnect handshake.
     */
    private val activeSubscriptions = mutableSetOf<String>()

    /**
     * Kinds the caller asked the proxy to stop forwarding on this
     * connection — the last [subscribe] call's `suppressKinds`. Replayed
     * with [activeSubscriptions] on reconnect, and gated the same way as on
     * a direct call, so a reconnect onto a server without
     * [WireFeature.QUIET_MESSAGE_APPENDED] simply sends the plain `kinds`.
     */
    private val activeSuppressKinds = mutableSetOf<String>()
    private val stateLock = Any()

    /**
     * Publish [next] and, unless it is [ConnectionState.Connected], drop the
     * negotiated feature set.
     *
     * Every write to [_connectionState] goes through here so the reset
     * cannot be forgotten on one of the several paths that leave a live
     * connection (graceful close, transient drop, terminal failure, loop
     * exit). [stateLock] is reentrant, so the call sites that already hold
     * it to serialise against a concurrent [close] keep working.
     */
    private fun publishConnectionState(next: ConnectionState) {
        synchronized(stateLock) {
            if (next !is ConnectionState.Connected) {
                _serverFeatures.value = ServerFeatures.NONE
            }
            _connectionState.value = next
        }
    }

    /**
     * Record what the peer advertised on the current connection, from the
     * caller's `remote.editor.capabilities` response
     * (`CapabilitiesDto.toServerFeatures()`), or [ServerFeatures.NONE] when
     * the probe failed.
     *
     * **Ignored unless the connection is [ConnectionState.Connected].** The
     * probe is an ordinary call, so its answer can land after the socket it
     * was asked on already died; applying it then would re-arm gated
     * parameters for a connection that has not negotiated anything, which is
     * exactly what the reset in [publishConnectionState] exists to prevent.
     */
    fun publishServerFeatures(features: ServerFeatures) {
        synchronized(stateLock) {
            if (_connectionState.value !is ConnectionState.Connected) return
            _serverFeatures.value = features
        }
    }

    /**
     * The feature set a cross-process replay verdict should be made
     * against — waiting, briefly, for the capabilities probe if it hasn't
     * answered yet.
     *
     * [onConnected] flushes the queue immediately after the `subscribe`
     * replay, but the capabilities probe is an ordinary `:app`-driven call
     * that has normally NOT come back at that point. Reading
     * [serverFeatures] synchronously there would see [ServerFeatures.NONE]
     * for every flush on a fresh socket and deny every replay — i.e. bounce
     * the user's queued text on a peer that would happily have absorbed it.
     *
     * Bounded by [QueueController.REPLAY_GATE_TIMEOUT_MS]; on timeout the
     * caller gets whatever is negotiated by then (typically [NONE], which
     * denies). Called at most once per flush, and only when the flush
     * actually holds a record that needs a verdict — nothing else in the
     * client may block on the feature gate.
     */
    private suspend fun awaitDedupeFeatures(): ServerFeatures {
        _serverFeatures.value.takeIf { it.has(WireFeature.CSID_DEDUPE) }?.let { return it }
        return withTimeoutOrNull(QueueController.REPLAY_GATE_TIMEOUT_MS) {
            serverFeatures.first { it.has(WireFeature.CSID_DEDUPE) }
        } ?: _serverFeatures.value
    }

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
     * [nowMs] reading when the current transport last reached
     * `Connected`. Read by [forceReconnect]'s grace window to refuse
     * tearing down a connection that just established, and by the loop's
     * stability check. 0 until the first successful handshake.
     *
     * Deliberately the injected clock rather than the wall clock: the two
     * windows that consume it must be measured on the same timeline as
     * each other and as the backoff.
     */
    @Volatile private var lastConnectedAtMs: Long = 0L

    /**
     * Current transport (null while reconnecting). Written only by the
     * lifecycle coroutine and [close]; read from arbitrary coroutines
     * via [callInternal] — @Volatile so non-Connected reads see the
     * most recent write.
     */
    @Volatile private var transport: RemoteTransport? = null

    /**
     * Negotiated outbound wire-compression dictionary id, or
     * [COMPRESSION_DISABLED] when the server didn't accept compression (older
     * server, or our advertisement was ignored). Set from the `welcome` frame
     * and reset on each fresh handshake. Read on the send path in
     * [callInternal]; @Volatile so post-handshake sends observe it.
     */
    @Volatile private var negotiatedOutboundDict: Int = COMPRESSION_DISABLED

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
     *
     * A failure is reported as [Result.failure]; cancellation of the
     * calling coroutine is NOT — it propagates, so a caller cancelled
     * mid-handshake unwinds instead of continuing with a `Result` that
     * looks like a connection error.
     */
    suspend fun connect(scope: CoroutineScope? = null): Result<Unit> = try {
        Result.success(connectInternal(scope))
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Result.failure(t)
    }

    private suspend fun connectInternal(scope: CoroutineScope?) {
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
            callRpc = { method, params, onSent -> callInternal(method, params, onSent) },
            events = events,
            connectionState = _connectionState,
            currentFeatures = { _serverFeatures.value },
            awaitDedupeFeatures = ::awaitDedupeFeatures,
            replayGate = replayGate,
        )
        queueController = controller
        // Rehydrate from disk before the lifecycle loop boots; expired
        // entries are bounced by the first [flushQueue] on Connected.
        controller.rehydrate()
        val gate = CompletableDeferred<Unit>()
        // Serialize start with [close] under [stateLock] so a close()
        // racing past our CAS observes the lifecycleJob it must cancel.
        synchronized(stateLock) {
            if (closing) throw ClosedException.NotQueued()
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
     * Also releases a ladder parked by [setReconnectPaused], and grants
     * it [WAKE_ATTEMPT_BURST] dials before it parks again — a background
     * network-change edge has to be able to reconnect without waiting for
     * the user to open the app.
     *
     * Safe to call from any coroutine / thread.
     */
    fun wakeReconnect() {
        if (_connectionState.value is ConnectionState.Reconnecting) {
            wakeRequests.trySend(Unit)
        }
    }

    /**
     * Whether the reconnect ladder is parked. See [setReconnectPaused].
     */
    @Volatile private var reconnectPaused = false

    /**
     * Park (or release) the reconnect ladder.
     *
     * While paused, the lifecycle loop waits indefinitely instead of
     * counting down a backoff and dialling. A phone whose screen has been
     * off for an hour otherwise spends that hour bringing the radio up
     * every 30 seconds to retry a server it cannot reach — the single
     * most expensive thing this client does while nobody is looking at it.
     *
     * Deliberately narrow:
     *  - It only affects the **backoff step**. An established connection
     *    is untouched (a paused client that is `Connected` stays
     *    `Connected` and keeps receiving notifications), an in-flight
     *    handshake runs to completion, and the very first connect of a
     *    [connect] call dials immediately — that one is user-initiated.
     *  - The durable queue, the subscription set and [connectionState]
     *    are not modified. Pausing loses nothing; it only postpones.
     *
     * Releasing is prompt: `setReconnectPaused(false)` wakes the parked
     * loop and retries at once rather than serving out a backoff that
     * elapsed while the screen was off, and leaves it unparked.
     *
     * [wakeReconnect] also releases the park, but only for a burst of
     * [WAKE_ATTEMPT_BURST] dials (on the normal backoff schedule) before
     * it parks again — `paused` itself stays set. That is what lets a
     * background network-change edge actually recover while a still-dark
     * screen keeps costing nothing: an event-driven retry gets through, a
     * periodic one does not. A single dial would not be enough, because
     * an interface that has just come up frequently fails the first
     * attempt on a route or DNS lookup that is seconds from working.
     *
     * Safe to call from any thread, and idempotent.
     *
     * Note that while parked the state stays
     * [ConnectionState.Reconnecting] with the `nextRetryMs` that *would*
     * have applied. Introducing a distinct state would buy nothing: the
     * banner is only on screen in the foreground, which is exactly when
     * the caller has unpaused us.
     */
    fun setReconnectPaused(paused: Boolean) {
        reconnectPaused = paused
        // Releasing must wake the park. Poking unconditionally is safe:
        // the channel is CONFLATED, and a poke observed while paused is
        // also a legitimate "retry now".
        if (!paused) wakeRequests.trySend(Unit)
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
     *
     * Also no-ops when the current connection is younger than
     * [FORCE_RECONNECT_GRACE_MS]. A genuine zombie socket has been
     * `Connected` for a long time (it died mid-session); a connection
     * that JUST established and is already being asked to tear down is
     * the signature of a reconnect storm — an on-resume / on-open
     * liveness probe (or the heartbeat) firing against a socket that
     * hasn't had a chance to prove itself, whose `capabilities` ping
     * raced the handshake. Refusing to tear down a fresh connection
     * breaks that feedback loop: the probe that misjudged a 1s-old
     * socket as dead can no longer kick it, so the connection survives
     * and steady-state liveness detection (the 30s heartbeat) takes over.
     *
     * @param force skip that grace window.
     *
     * The grace exists to damp *inferences* — a probe that timed out, a
     * heartbeat that missed twice — which are guesses about a socket that
     * may well be fine. Pass `force = true` only when the caller has
     * direct evidence that the socket is already dead rather than a guess
     * that it might be: an Android `ConnectivityManager` default-network
     * change (Wi-Fi → LTE handover) is the motivating case, because the
     * old socket is bound to an interface that no longer carries traffic
     * and no amount of waiting will revive it. Such a signal fires once
     * per real handover, so it cannot produce the storm the grace guards
     * against — whereas a connection younger than the grace window is
     * exactly the one a handover is most likely to have just killed,
     * which is why waiting out the ping timeout there is the wrong
     * answer. Do NOT pass `force = true` from anything periodic.
     */
    fun forceReconnect(
        reason: String = "heartbeat watchdog: server unresponsive",
        force: Boolean = false,
    ) {
        if (_connectionState.value !is ConnectionState.Connected) return
        val age = nowMs() - lastConnectedAtMs
        if (!force && age in 0 until FORCE_RECONNECT_GRACE_MS) {
            // Fresh connection — refuse to tear it down (storm guard, see kdoc).
            return
        }
        val toClose: RemoteTransport?
        synchronized(stateLock) {
            if (closing) return
            toClose = transport
            transport = null
        }
        toClose?.close()
        events.trySend(
            LifecycleEvent.TransportClosed(
                ConnectFailure.Unreachable(reason),
                // Tagged with the socket we just killed so the lifecycle
                // can tell this apart from a drop of whatever transport is
                // live by the time it reads the event.
                transport = toClose,
            ),
        )
    }

    /** Whether a programmatic close has been requested (lifecycle ends). */
    @Volatile private var closing = false

    /**
     * Stop this client. **A handoff, not a cancellation of the user's
     * messages.**
     *
     * A [RemoteClient] is single-shot and per-server: `:app` closes one
     * and builds a fresh one whenever the server's address, secret or
     * fingerprint is edited, when the user switches servers, and when the
     * ViewModel is cleared. None of those mean "throw away what the user
     * typed", so:
     *
     *  - Every persisted [QueuedMessage] stays in [queueStore]. The store
     *    is keyed per server, so the next [RemoteClient] for the same
     *    server rehydrates and sends them. Wiping a pairing is a separate,
     *    explicit operation (`clear()` / `removeForServer`).
     *  - In-memory callers are released immediately rather than left
     *    parked on a deferred nobody will ever complete. Which exception
     *    they get depends on whether anything was written for them, and
     *    the two are not interchangeable:
     *      - still parked in the queue → [ClosedException.StillQueued]:
     *        nothing was sent, the record is kept, the next client sends
     *        it. Do NOT bounce it to the draft and do NOT re-send it.
     *      - a frame that reached the transport — written, or still inside
     *        the write when the close landed — or a plain [call] in flight
     *        → [TransportLostException]: delivery is unknown. For a queue
     *        item the record is dropped and [onMessageExpired] fires,
     *        because keeping it would let the next client post a message
     *        the server may already have. A flush being in progress is NOT
     *        by itself enough to land here: an item can be claimed for a
     *        flush and closed before anything was written for it, and that
     *        one is still queued — see [FrameState].
     *  - [onMessageExpired] therefore fires ONLY for that second group.
     *    Messages still parked in the queue are not lost and must not be
     *    handed back — bouncing them would let the user send them twice.
     *
     * Serializes with `connect` and `callInternal` through [stateLock]:
     * `callInternal` installs its pending entry inside the same lock and
     * re-checks `closing` + `transport` AFTER the install, which closes
     * the install-after-clear window. [connectGuard] and [closing] are
     * NOT reset — the instance stays closed forever.
     */
    fun close() {
        val toClose: RemoteTransport?
        val pendingSnapshot: List<CompletableDeferred<JsonRpcResponse>>
        val queuedSnapshot: QueueController.CloseDrain?
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
            queuedSnapshot = queueController?.drainOnClose()
            gate = firstConnect
            job = lifecycleJob
            lifecycleJob = null
        }
        events.trySend(LifecycleEvent.UserClose)
        // Release a lifecycle loop parked by [setReconnectPaused]: it
        // re-reads `closing` the moment it wakes, so the park can never
        // outlive the client even if the job cancellation below is slow
        // to be delivered.
        wakeRequests.trySend(Unit)
        // Requests already handed to the socket: delivery is unknown.
        // Complete rather than cancel — the caller's coroutine is not the
        // thing being cancelled, and a CancellationException here would
        // be swallowed by every `runCatching` on the way out.
        pendingSnapshot.forEach { it.completeExceptionally(TransportLostException()) }
        // Never written: honestly still queued, and the next client sends it.
        queuedSnapshot?.parked?.forEach {
            it.deferred.completeExceptionally(ClosedException.StillQueued(it.message.id))
        }
        // Already on the socket: we cannot know whether the server applied
        // it, so we can neither promise delivery nor let the next client
        // replay it blindly. Same treatment as any other frame lost
        // in flight — drop the record, hand the text back.
        queuedSnapshot?.onTheWire?.forEach {
            queueController?.abandonOnClose(it)
            it.deferred.completeExceptionally(TransportLostException())
        }
        gate?.takeIf { !it.isCompleted }?.completeExceptionally(ClosedException.NotQueued())
        toClose?.close()
        job?.cancel()
        // Under the lock so it cannot be overwritten by a handshake that
        // is publishing Connected at this instant: that publish takes the
        // same lock and skips once `closing` is set, so whichever wins the
        // lock, Disconnected is the last word.
        synchronized(stateLock) {
            publishConnectionState(ConnectionState.Disconnected)
        }
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

    /**
     * Test seam for [callInternal]'s `onSent` contract.
     *
     * That callback is what tells [QueueController] whether a frame left
     * the process, and therefore what a `close()` mid-flush owes the user,
     * but no public entry point passes one: [call] and the [queueCall]
     * fast path both hand it `null`, and the queue's own dispatch only
     * reaches two of its three exit paths in a test that can also observe
     * them. Exercising it directly is the only way to pin "invoked exactly
     * once, with the right verdict, on every path".
     */
    internal suspend fun callForTest(
        method: String,
        params: JsonElement? = null,
        onSent: ((written: Boolean) -> Unit)? = null,
    ): JsonRpcResponse = callInternal(method, params, onSent)

    /**
     * @param onSent invoked exactly once on every exit path, before this
     *   call suspends waiting for the response. `written` is true only
     *   when the transport ACCEPTED the frame; it is false when nothing
     *   was written at all — the pre-flight not-connected bail, a
     *   [sendMaybeCompressed] that threw, or a transport that refused the
     *   frame ([FrameRefusedException]).
     *
     *   "Accepted" means the bytes left this process, not that the peer
     *   received them: that is already the line this client draws between
     *   [FrameRefusedException] (provably not sent) and
     *   [TransportLostException] (ambiguous), and it is the same line
     *   here. [QueueController] uses the callback both to chain concurrent
     *   queue dispatches into wire order and to record each item's
     *   `FrameState`, which decides what a close-time drain owes the user.
     */
    private suspend fun callInternal(
        method: String,
        params: JsonElement?,
        onSent: ((written: Boolean) -> Unit)? = null,
    ): JsonRpcResponse {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonRpcResponse>()
        // Install pending under [stateLock] and re-check `closing`/
        // `transport` so a concurrent close() (which locks, sets
        // closing=true, nulls transport, then clears pending) cannot
        // leave an orphaned deferred behind a pending.clear().
        val active: RemoteTransport = try {
            synchronized(stateLock) {
                if (closing) throw NotConnectedException(lastConnectFailure)
                val tx = transport ?: throw NotConnectedException(lastConnectFailure)
                pending[id] = deferred
                tx
            }
        } catch (t: Throwable) {
            // Nothing was built, let alone written.
            onSent?.invoke(false)
            throw t
        }
        val req = JsonRpc.encodeRequest(JsonRpcRequest(method = method, params = params, id = id))
        return suspendCancellableCoroutine { cont ->
            // The single resume path. Every failure below completes the
            // DEFERRED and lets this handler resume `cont` exactly once —
            // resuming `cont` directly would double-resume whenever a
            // concurrent [failPendingOnDisconnect] has already completed
            // the deferred (the socket fails its writer before it
            // delivers `onFailure`, so "send refused" and "connection
            // lost" routinely race), replacing the real reason with an
            // "Already resumed" IllegalStateException.
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
                sendMaybeCompressed(active, req)
            } catch (t: Throwable) {
                // Threw on the way to the socket (over-cap frame, writer
                // error): the bytes provably did not leave this process.
                onSent?.invoke(false)
                pending.remove(id)?.completeExceptionally(t)
                return@suspendCancellableCoroutine
            }
            // `sent` is the transport's own accept/refuse verdict, and it
            // is the only path on which anything can have gone out.
            onSent?.invoke(sent)
            if (!sent) {
                pending.remove(id)?.completeExceptionally(FrameRefusedException())
            }
        }
    }

    /**
     * Send a JSON-RPC text frame, transparently compressing it to a binary
     * frame when the server negotiated wire compression in its `welcome` and
     * the payload is large enough to benefit. Falls back to a plain TEXT
     * frame otherwise, so a frame is never inflated and a peer that didn't
     * negotiate compression only ever receives text.
     *
     * Throws [FrameTooLargeException] instead of sending a frame the
     * server's reader would reject at the framing layer — see that type
     * for why such a frame is a connection-killer rather than a failed
     * request. The cap is applied to what actually goes on the wire, so a
     * large payload that compresses under the limit still sends.
     */
    private fun sendMaybeCompressed(tx: RemoteTransport, text: String): Boolean {
        val dict = negotiatedOutboundDict
        if (dict != COMPRESSION_DISABLED) {
            val frame = WireCompression.compressIfWorthwhile(text, dict)
            if (frame != null) {
                if (frame.size > MAX_OUTBOUND_FRAME_BYTES) throw FrameTooLargeException(frame.size)
                return tx.send(frame)
            }
        }
        // A UTF-8 char is at most 3 bytes (a surrogate pair is 2 chars ×
        // 2 bytes), so anything whose 3× upper bound fits is safe without
        // counting; only strings that could plausibly straddle the limit
        // pay for the exact count.
        if (text.length.toLong() * 3L > MAX_OUTBOUND_FRAME_BYTES) {
            val size = WireCompression.utf8Size(text)
            if (size > MAX_OUTBOUND_FRAME_BYTES) throw FrameTooLargeException(size)
        }
        return tx.send(text)
    }

    /**
     * Bytes the live transport has accepted but not yet written to the
     * socket, or 0 when disconnected.
     *
     * See [RemoteTransport.queuedBytes]: the WebSocket writer is strictly
     * FIFO, so bulk senders must read this before queuing the next chunk
     * or they push the heartbeat and every chat RPC behind minutes of
     * upload payload.
     */
    fun queuedBytes(): Long = transport?.queuedBytes() ?: 0L

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
     * **Failure modes** (each one means something different to the caller):
     *  - [NotConnectedException] — before [connect]; nothing was queued.
     *  - [QueueTtlException] — the TTL ran out undelivered; the record is
     *    gone and [onMessageExpired] has already fired.
     *  - [ClosedException.StillQueued] — the client was closed; the
     *    message is still on disk and the next client sends it.
     *  - [TransportLostException] — it was on the wire when the socket
     *    died; delivery unknown, nothing persisted.
     *  - [FrameTooLargeException] — too big for our own wire cap; never sent.
     *  - [MessageRejectedException] — the server discarded it (1009
     *    "message too big"); not delivered, already bounced.
     *
     * @param messageId identity of the persisted [QueuedMessage]. Pass the
     *   caller's own client-send-id to be able to [cancelQueued] this
     *   message later and to correlate it with a local record across a
     *   process restart; it must be unique, since the store is keyed on
     *   it. Defaults to a fresh UUID, which is fine for a call the caller
     *   will never need to name again.
     *
     * Delegated to [QueueController].
     */
    suspend fun queueCall(
        method: String,
        params: JsonElement? = null,
        ttlMs: Long = DEFAULT_QUEUE_TTL_MS,
        messageId: String? = null,
    ): JsonRpcResponse {
        val controller = queueController ?: throw NotConnectedException(lastConnectFailure)
        return controller.queueCall(method, params, ttlMs, messageId)
    }

    /**
     * Cancel a message the user typed while offline, before it is sent.
     *
     * Returns **true** only if nothing had been written for the message
     * and it has now been taken back: its [QueueStore] record is deleted,
     * the [queueCall] that is waiting on it fails with
     * [QueueCancelledException], and the messages queued around it keep
     * their order and their place in line. That covers a message still
     * parked in the queue AND one a flush has claimed but not yet written
     * a frame for — the latter is not a corner case: a flush claims every
     * item up front and can then sit on the cross-process replay verdict
     * for up to `QueueController.REPLAY_GATE_TIMEOUT_MS`, which is
     * precisely when a user watching a stuck send taps cancel. The race
     * with the dispatch coroutine is settled under the client's state
     * lock, so a message is either withdrawn or written, never both.
     *
     * Returns **false** when the message is unknown or **not
     * withdrawable** — the frame is being written, was written, or was
     * refused and is on its way back to the queue. The caller must treat
     * that as **too late — it may already have been delivered.** A frame
     * on the wire may have been read and applied by the server before the
     * socket reported anything back, and the server's
     * `spk_client_send_id` de-duplication makes a replay free, not a
     * delivery reversible. Reporting "cancelled" for such a message would
     * be exactly the lie this API exists to prevent, so the UI must leave
     * the message on screen and let the server's transcript settle it.
     * False negatives are safe here, false positives are not — a refused
     * frame, for instance, provably never went out, but it belongs to the
     * flush that is restoring it and only becomes cancellable again once
     * it is back in the queue.
     *
     * [onMessageExpired] does NOT fire — that callback means "this was
     * lost, hand the text back to the user", and the user is the one who
     * just discarded it.
     *
     * Safe to call from any thread. Returns false before [connect].
     *
     * @param id the [QueuedMessage.id] of the message to cancel.
     */
    fun cancelQueued(id: String): Boolean = queueController?.cancelQueued(id) ?: false

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

    /**
     * Add [kinds] to the active subscription set and notify the server.
     *
     * [suppressKinds] names kinds the proxy should stop FORWARDING on this
     * connection even though they stay subscribed — the
     * `agent_session_message_appended` double-shipping that duplicates
     * `agent_session_dirty` is the motivating case. It REPLACES whatever a
     * previous [subscribe] asked to suppress (it is one per-connection set,
     * not a cumulative one) and is recorded for the reconnect replay.
     *
     * Suppression is deliberately expressed as "keep subscribing, ask for
     * less": the kinds stay in `kinds`, so a server that does not honour it
     * — or the moment right after a reconnect, before the capabilities
     * probe answers — simply delivers everything, exactly as today.
     *
     * The `suppress_kinds` key is emitted only when the peer advertised
     * [WireFeature.QUIET_MESSAGE_APPENDED] on the CURRENT connection; see
     * [serverFeatures]. Because that is only known after the capabilities
     * probe, the reconnect replay in [onConnected] runs too early to carry
     * it — the caller re-subscribes once the probe answers if it wants
     * suppression back.
     */
    suspend fun subscribe(
        kinds: List<String>,
        suppressKinds: List<String> = emptyList(),
    ): JsonRpcResponse {
        val gatedSuppress = synchronized(stateLock) {
            activeSubscriptions += kinds
            activeSuppressKinds.clear()
            activeSuppressKinds += suppressKinds
            gatedSuppressKindsLocked()
        }
        return call("remote.editor.subscribe", subscribeParams(kinds, gatedSuppress))
    }

    /**
     * The suppression set as it may go on the wire right now: empty unless
     * the peer advertised [WireFeature.QUIET_MESSAGE_APPENDED]. Call under
     * [stateLock].
     */
    private fun gatedSuppressKindsLocked(): List<String> =
        if (_serverFeatures.value.has(WireFeature.QUIET_MESSAGE_APPENDED)) {
            activeSuppressKinds.toList()
        } else {
            emptyList()
        }

    /**
     * `editor.subscribe` params. `suppress_kinds` is omitted entirely when
     * empty: the server's params struct is `deny_unknown_fields`, so an
     * unrecognised key is a failed call rather than an ignored one, and
     * omission is what makes old-server compatibility provable by
     * inspection.
     */
    private fun subscribeParams(kinds: List<String>, suppressKinds: List<String>): JsonObject =
        buildJsonObject {
            put("kinds", JsonArray(kinds.map { JsonPrimitive(it) }))
            if (suppressKinds.isNotEmpty()) {
                put("suppress_kinds", JsonArray(suppressKinds.map { JsonPrimitive(it) }))
            }
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
     *
     * [index] is **stream-local**, so [streamId] selects the space it is
     * read in — the same contract, and the same encoding, as
     * [getSessionChanges]. It is sent only when non-null: the server's
     * params struct is `deny_unknown_fields` with
     * `skip_serializing_if = "Option::is_none"`, so an explicit JSON null
     * would be rejected where an omitted key is accepted. Passing null
     * therefore behaves exactly as before this parameter existed — the
     * server defaults to the Main stream.
     */
    suspend fun getSessionEntry(
        sessionId: String,
        index: Int,
        streamId: StreamIdDto? = null,
        includeImages: Boolean = true,
    ): GetSessionEntryResult {
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("index", index)
            put("include_images", includeImages)
            if (streamId != null) {
                put("stream_id", JsonRpc.json.encodeToJsonElement(StreamIdDto.serializer(), streamId))
            }
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
     * Convenience helper around `remote.solution_agent.get_session_changes`.
     *
     * Poll this instead of `get_session` to fetch only what changed since
     * [sinceSeq]. Pass [knownEpoch] from the last [GetSessionResult] so
     * the server can detect a state reset and return [GetSessionChangesResult.reset]
     * = true, signalling that the client must fall back to a full `get_session`.
     *
     * [streamId] selects which transcript stream to diff (wire schema v3).
     * It is sent only when non-null — the server uses `deny_unknown_fields`
     * + `skip_serializing_if`, so sending JSON null would fail. Pass null to
     * default to the Main stream (server default when `stream_id` is omitted).
     *
     * [knownEntries] and [omitPreviewWhenMarkdown] are the two additive,
     * NEGOTIATED parameters. Both are dropped silently unless the peer
     * advertised the matching token on the CURRENT connection
     * ([serverFeatures]); see the gate inside. That check is here rather
     * than at the call site on purpose — the server's params struct is
     * `deny_unknown_fields`, so one un-negotiated key does not degrade, it
     * turns the poll into a `-32602` error. A caller therefore cannot break
     * an old server by passing these; the worst it can do is waste the work
     * of building them.
     *
     * @param knownEntries bodies this client already holds, offered so the
     *   server can reply with only the appended tail — build it with
     *   [buildKnownEntries]. `null` and empty are the SAME request (no key
     *   on the wire, whole bodies back); an empty list is "I hold nothing
     *   worth diffing", never "delta everything".
     * @param omitPreviewWhenMarkdown ask the server to drop
     *   [EntrySummary.preview] on non-user entries whose body it is already
     *   sending. Only `true` is ever put on the wire.
     */
    suspend fun getSessionChanges(
        sessionId: String,
        sinceSeq: Long,
        knownEpoch: Long,
        streamId: StreamIdDto? = null,
        includeImages: Boolean = true,
        knownEntries: List<KnownEntryDto>? = null,
        omitPreviewWhenMarkdown: Boolean = false,
    ): GetSessionChangesResult {
        val features = _serverFeatures.value
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("since_seq", sinceSeq)
            put("known_epoch", knownEpoch)
            put("include_images", includeImages)
            if (streamId != null) {
                put("stream_id", JsonRpc.json.encodeToJsonElement(StreamIdDto.serializer(), streamId))
            }
            if (!knownEntries.isNullOrEmpty() && features.has(WireFeature.ENTRY_BODY_DELTA)) {
                put(
                    "known_entries",
                    JsonRpc.json.encodeToJsonElement(
                        ListSerializer(KnownEntryDto.serializer()),
                        knownEntries,
                    ),
                )
            }
            if (omitPreviewWhenMarkdown) {
                putOmitPreviewWhenMarkdown(features)
            }
        }
        val response = call("remote.solution_agent.get_session_changes", params)
        val err = response.error
        if (err != null) {
            error("get_session_changes failed: ${err.message}")
        }
        val result = response.structuredContent()
            ?: error("get_session_changes returned no structuredContent")
        return JsonRpc.json.decodeFromJsonElement(GetSessionChangesResult.serializer(), result)
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
    suspend fun addMember(solutionId: Long, catalogId: Long): AddMemberResult {
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
    suspend fun addEmptyMember(solutionId: Long, name: String): AddEmptyMemberResult {
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
    suspend fun removeMember(solutionId: Long, catalogId: Long) {
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
    suspend fun catalogRemove(catalogId: Long) {
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
        /**
         * Consecutive TLS-pin mismatches since the last successful pinned
         * handshake. See [shouldEscalatePinMismatch].
         */
        var pinMismatches = 0
        /**
         * Dials still owed to the most recent wake while
         * [reconnectPaused]. See [WAKE_ATTEMPT_BURST].
         */
        var wakeBudget = 0
        // Drain any pre-loop wake pokes so a wakeReconnect() that fires
        // before the loop is observing the channel doesn't short-circuit
        // the very first connect (attempt==0 already runs immediately).
        while (wakeRequests.tryReceive().isSuccess) { /* drain */ }
        // scope is non-null and active inside the lifecycle loop; the close()
        // path nulls scope via teardown, but `closing=true` is the actual
        // exit signal that this coroutine observes first.
        while (!closing) {
            if (attempt == 0) {
                publishConnectionState(ConnectionState.Connecting)
            } else {
                val delayMs = retryDelayMs(attempt)
                // Discard pokes that arrived before this wait began. A
                // wake fired in the sliver between the previous timer
                // expiring and the state leaving Reconnecting is about a
                // backoff that has already elapsed; buffering it on the
                // CONFLATED channel made the NEXT backoff fire instantly,
                // spending an extra handshake for nothing.
                while (wakeRequests.tryReceive().isSuccess) { /* drain */ }
                publishConnectionState(
                    ConnectionState.Reconnecting(attempt, delayMs, lastConnectFailure),
                )
                // Race the backoff timer against a wakeReconnect() poke.
                // Receiving null = timer fired naturally; receiving Unit =
                // wake fired, reset the attempt counter so a subsequent
                // failure restarts the 1s → 2s → … schedule rather than
                // pinning at the 30s cap forever.
                //
                // While [setReconnectPaused] holds us AND the last wake's
                // budget is spent, skip the timer and park on the channel
                // instead: no wake-up, no radio, no dial until somebody
                // asks for one. Re-checked after a natural timer expiry so
                // a pause that arrived mid-wait takes effect before we
                // spend the attempt, not after.
                val parked = reconnectPaused && wakeBudget <= 0
                val woken: Boolean = if (parked) {
                    wakeRequests.receive()
                    true
                } else {
                    val timerWoken = withTimeoutOrNull(delayMs) { wakeRequests.receive() } != null
                    if (!timerWoken && reconnectPaused && wakeBudget <= 0 && !closing) {
                        wakeRequests.receive()
                        true
                    } else {
                        timerWoken
                    }
                }
                if (closing) break
                if (woken) {
                    // Either an explicit wake or a release from the park:
                    // retry now, and restart the ladder rather than serving
                    // out a backoff that elapsed while we were parked.
                    attempt = 0
                    // Grant this wake a small run of attempts. One dial is
                    // not a reconnect: a network-change edge fires while
                    // the interface is still settling, so the first dial
                    // routinely fails on a DNS or route that is seconds
                    // from working. Re-parking on that failure would hold
                    // the user's queue until the next edge.
                    wakeBudget = WAKE_ATTEMPT_BURST
                }
                publishConnectionState(ConnectionState.Connecting)
            }
            val outcome = runOneAttempt()
            if (wakeBudget > 0) wakeBudget--
            when (outcome) {
                AttemptOutcome.Connected -> {
                    pinMismatches = 0
                    lastConnectFailure = null
                    val connectedAtMs = nowMs()
                    // Wait for a close/failure event before looping.
                    val end = awaitDisconnect(transport)
                    if (closing || end is DisconnectReason.UserClose) break
                    if (end is DisconnectReason.Terminal) {
                        lastConnectFailure = end.failure
                        if (end.failure is ConnectFailure.TlsPinMismatch) {
                            pinMismatches++
                            if (!shouldEscalatePinMismatch(pinMismatches)) {
                                // Same policy as the handshake path — a
                                // pin failure is a pin failure whether it
                                // surfaces during the handshake or on an
                                // established socket, and the client has
                                // no way to tell a swapped certificate
                                // from a network that started
                                // intercepting TLS mid-session.
                                attempt = attempt + 1
                                continue
                            }
                        }
                        publishConnectionState(ConnectionState.FailedTerminal(end.failure))
                        firstConnect?.takeIf { !it.isCompleted }
                            ?.completeExceptionally(ConnectException(end.failure))
                        return
                    }
                    // Transient — loop carrying the cause. Restarting the
                    // backoff ladder is right for a connection that did
                    // real work and then dropped, but a peer that hangs up
                    // immediately after every handshake would otherwise pin
                    // us to a 1s connect → TLS → HMAC → drop cycle forever
                    // (two devices sharing a client name evict each other
                    // exactly like this). Only a connection that stayed up
                    // long enough to be useful earns the reset.
                    lastConnectFailure = (end as DisconnectReason.Transient).failure
                    attempt = if (nowMs() - connectedAtMs >= MIN_STABLE_CONNECTION_MS) {
                        1
                    } else {
                        attempt + 1
                    }
                }
                is AttemptOutcome.TerminalFailure -> {
                    lastConnectFailure = outcome.failure
                    if (outcome.failure is ConnectFailure.TlsPinMismatch) {
                        pinMismatches++
                        if (!shouldEscalatePinMismatch(pinMismatches)) {
                            // Keep retrying (slowly, and without ever
                            // relaxing the pin) — the banner still shows
                            // the mismatch, so the user is not misled.
                            firstConnect?.takeIf { !it.isCompleted }
                                ?.completeExceptionally(ConnectException(outcome.failure))
                            attempt = if (attempt == 0) 1 else attempt + 1
                            continue
                        }
                    }
                    publishConnectionState(ConnectionState.FailedTerminal(outcome.failure))
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
        publishConnectionState(ConnectionState.Disconnected)
    }

    /**
     * Backoff for the next attempt, with a floor for the pin-mismatch
     * retries described in [shouldEscalatePinMismatch] — those are waiting
     * on a human (log into the captive portal), not on a network blip, so
     * hammering the endpoint on the normal 1s ladder is pure battery.
     */
    private fun retryDelayMs(attempt: Int): Long {
        val base = backoff.nextDelayMs(attempt)
        return if (lastConnectFailure is ConnectFailure.TlsPinMismatch) {
            maxOf(base, PIN_MISMATCH_RETRY_DELAY_MS)
        } else {
            base
        }
    }

    /**
     * Whether a TLS-pin mismatch should end the lifecycle in
     * [ConnectionState.FailedTerminal] ("re-pair required") rather than
     * being retried.
     *
     * The pin is never relaxed either way — a mismatching peer is refused
     * by the TrustManager, so no application data ever reaches it, and the
     * banner names the mismatch throughout. What the retry buys is a way
     * out of the far more common false positive: a TLS-terminating captive
     * portal (hotel / airport DNAT-all on a custom port) presents its own
     * certificate and is indistinguishable from an attacker from here.
     * Failing terminally on the first occurrence left the client stuck
     * until the server was removed and re-paired, even after the user had
     * logged into the portal and the real server was reachable again.
     *
     * The rule is a plain count: allow [PIN_MISMATCH_RETRY_LIMIT]
     * mismatches (spaced by [PIN_MISMATCH_RETRY_DELAY_MS]), then escalate.
     * The counter resets ONLY on a successful pinned handshake, so a peer
     * that alternates between a pin mismatch and other errors still
     * escalates rather than retrying forever.
     *
     * It deliberately does NOT special-case "this client has connected
     * successfully before". That looks like evidence the certificate
     * changed under a working connection, but a mobile client cannot
     * distinguish that from "same server, different network, and this one
     * intercepts TLS" — which is the entire scenario the retry exists for,
     * and which by definition happens to a client that was working a
     * moment ago. Conditioning on it made the retry apply only to a cold
     * start, i.e. almost never.
     */
    private fun shouldEscalatePinMismatch(consecutive: Int): Boolean =
        consecutive >= PIN_MISMATCH_RETRY_LIMIT

    /**
     * Drive one transport from connect → handshake → ready or → failure.
     * Returns once the handshake completes, fails, or the transport hangs
     * up before reaching Established.
     */
    private suspend fun runOneAttempt(): AttemptOutcome {
        val handshake = CompletableDeferred<AttemptOutcome>()
        // Completes when the WebSocket opens (HTTP 101) — or earlier, if
        // the attempt fails before it ever opens. Splits the attempt into
        // its two very differently-sized phases: see [runOneAttempt]'s
        // timeout handling below.
        val opened = CompletableDeferred<Unit>()
        val stage = HandshakeStageRef()
        // Handshake-private transport ref. The HandshakeListener uses
        // THIS to send the HMAC response (it can't read the shared
        // `transport` field because we deliberately don't publish to
        // it until handshake completes — see below). After Established
        // the listener stops using this and the shared field takes
        // over for app-side RPC traffic.
        val handshakeTransportRef =
            AtomicReference<RemoteTransport?>(null)
        // [boundTx] mirrors the per-attempt transport but is NEVER cleared
        // — it stays set for the listener's lifetime so [HandshakeListener.isStale]
        // can detect when a later attempt has displaced this socket and
        // silently drop the late `onClosed` / `onFailure` events the OkHttp
        // listener still fires from the dying socket. See the listener's
        // `boundTx` kdoc for the full race.
        val boundTx = AtomicReference<RemoteTransport?>(null)
        val listener = HandshakeListener(handshake, opened, stage, handshakeTransportRef, boundTx)
        val tx = transportFactory.connect(url, listener)
        handshakeTransportRef.set(tx)
        boundTx.set(tx)
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
            // Two budgets, because the two phases are not comparable.
            //
            // Phase 1 (pre-open): DNS + TCP + TLS 1.3 + HTTP upgrade, plus
            // the server's accept-rate sleep — 3-4 round trips of someone
            // else's protocol before ours starts. On a roaming link that
            // is tens of seconds. It gets [PRE_OPEN_TIMEOUT_MS], matching
            // the OkHttp `callTimeout` that bounds the same phase.
            //
            // Phase 2 (post-open): challenge → response → welcome, one
            // round trip against a server that gives itself 10s per stage.
            // It gets [HANDSHAKE_TIMEOUT_MS], counted from the HTTP 101 —
            // so a slow link no longer spends the handshake budget on the
            // TLS handshake and reports "not spk-editor" for a perfectly
            // healthy server.
            //
            // `opened` is also completed by every pre-open failure path, so
            // an attempt that dies before the upgrade is reported straight
            // away instead of waiting out phase 1.
            val outcome = if (withTimeoutOrNull(ConnectFailure.PRE_OPEN_TIMEOUT_MS) {
                    opened.await()
                } == null
            ) {
                // Nothing answered — the socket (and the OkHttp call
                // behind it) has to be cancelled, not closed: there is no
                // writer yet to carry a Close frame.
                tx.cancel()
                AttemptOutcome.TransientFailure(
                    ConnectFailure.Unreachable(
                        "Couldn't establish a connection within " +
                            "${ConnectFailure.PRE_OPEN_TIMEOUT_MS / 1000}s — " +
                            "server unreachable or the network is very slow.",
                    ),
                )
            } else {
                withTimeoutOrNull(ConnectFailure.HANDSHAKE_TIMEOUT_MS) {
                    handshake.await()
                } ?: AttemptOutcome.TransientFailure(
                    ConnectFailure.HandshakeTimeout(ConnectFailure.HANDSHAKE_TIMEOUT_MS)
                ).also { tx.cancel() }
            }
            if (outcome is AttemptOutcome.Connected) {
                // Handshake passed → it's safe to publish. Order is
                // important: `transport` first so `onConnected`'s queue
                // flush + resubscribe (which both call through
                // `callInternal`) see a non-null transport.
                //
                // Under [stateLock], and refusing to publish once
                // [close] has been entered: close snapshots `transport`
                // under the same lock, so an unsynchronized publish here
                // could hand an authenticated socket to a client that had
                // already decided it owns nothing — leaving the socket
                // open on the server and the state machine reporting
                // Connected for a client that is gone.
                val published = synchronized(stateLock) {
                    if (closing) {
                        false
                    } else {
                        transport = tx
                        true
                    }
                }
                if (!published) {
                    tx.cancel()
                    handshakeTransportRef.set(null)
                    return AttemptOutcome.TransientFailure(
                        ConnectFailure.Unreachable("client closed during the handshake"),
                    )
                }
                handshakeTransportRef.set(null)
                // Re-subscribe BEFORE transitioning to Connected — R-6a
                // reconnect-handshake atomicity. Also serializes the
                // queue flush after resubscribe so queued calls hit a
                // fully-registered server.
                onConnected()
                lastConnectedAtMs = nowMs()
                // Same guard again: `onConnected` suspends, so a close
                // can land inside it. Publishing Connected afterwards
                // would overwrite the Disconnected that close() set.
                synchronized(stateLock) {
                    if (!closing) publishConnectionState(ConnectionState.Connected)
                }
                firstConnect?.takeIf { !it.isCompleted }?.complete(Unit)
            } else {
                tx.close()
                handshakeTransportRef.set(null)
                // `transport` was never published this attempt — nothing
                // to clear on the shared field.
            }
            outcome
        } catch (c: CancellationException) {
            // The lifecycle coroutine itself is going away (close()); the
            // socket must not outlive it, and the cancellation must not be
            // reported as a connection failure.
            tx.cancel()
            handshakeTransportRef.set(null)
            throw c
        } catch (t: Throwable) {
            tx.cancel()
            handshakeTransportRef.set(null)
            AttemptOutcome.TransientFailure(ConnectFailure.classify(t))
        }
    }

    /**
     * Wait for a Close / Failure event belonging to [expected] — the
     * transport this pass of the lifecycle owns.
     *
     * Events carry the transport they came from because two of them can
     * legitimately be in flight for the same socket: a natural
     * `TransportFailure` posted from the OkHttp reader thread and the
     * synthetic `TransportClosed` the heartbeat watchdog posts from
     * [forceReconnect] a moment later. Consuming the first tears the
     * socket down and reconnects; without an identity check the second
     * would then be read as the *new* socket dying, dropping every
     * in-flight request and reconnecting again.
     *
     * A null [expected] (the watchdog already cleared the field) accepts
     * anything — there is no newer socket to protect.
     */
    private suspend fun awaitDisconnect(expected: RemoteTransport?): DisconnectReason {
        for (event in events) {
            when (event) {
                LifecycleEvent.UserClose -> return DisconnectReason.UserClose
                is LifecycleEvent.TransportClosed -> {
                    if (isStaleEvent(expected, event.transport)) continue
                    abandonTransport()
                    failPendingOnDisconnect(event.failure)
                    return DisconnectReason.Transient(event.failure)
                }
                is LifecycleEvent.TransportFailure -> {
                    if (isStaleEvent(expected, event.transport)) continue
                    abandonTransport()
                    failPendingOnDisconnect(event.failure)
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

    private fun isStaleEvent(expected: RemoteTransport?, from: RemoteTransport?): Boolean =
        expected != null && from != null && from !== expected

    /**
     * Drop the current transport and close it.
     *
     * The close matters even when the event that brought us here was a
     * server-side close (it is a no-op then): on the watchdog path the
     * socket is perfectly healthy and merely unresponsive at the
     * application level, and just forgetting the reference left it open
     * with no Close frame — the server kept the session until its own idle
     * timeout while OkHttp's ping kept it alive from our side.
     */
    private fun abandonTransport() {
        val toClose: RemoteTransport?
        synchronized(stateLock) {
            toClose = transport
            transport = null
        }
        toClose?.close()
    }

    /**
     * Re-subscribe + flush queued calls after a successful handshake.
     * Subscribe is awaited before the Connected transition (R-6a
     * atomicity); a subscribe error is non-fatal — we still flush.
     *
     * The replay uses [RESUBSCRIBE_TIMEOUT_MS] rather than the 30s default
     * because the lifecycle coroutine is blocked here: it is not yet in
     * `awaitDisconnect`, so a socket that dies during the replay is not
     * noticed until this call returns. (The listener also fails pending
     * requests directly for exactly this window — see [HandshakeListener]
     * — so the common case resolves immediately and this bound is only the
     * backstop for a peer that accepts the frame and then goes quiet.)
     */
    private suspend fun onConnected() {
        val (kinds, suppressKinds) = synchronized(stateLock) {
            activeSubscriptions.toList() to gatedSuppressKindsLocked()
        }
        if (kinds.isNotEmpty()) {
            try {
                call(
                    "remote.editor.subscribe",
                    subscribeParams(kinds, suppressKinds),
                    timeoutMs = RESUBSCRIBE_TIMEOUT_MS,
                )
            } catch (_: TimeoutCancellationException) {
                // Our own timeout, not a cancellation of this coroutine.
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                // Non-fatal: the server may reject or drop the replay, but
                // the connection itself is up and the queue still flushes.
            }
        }
        queueController?.flushQueue()
    }

    /**
     * Fail every request already on the wire.
     *
     * They normally get [TransportLostException], not a generic error: the
     * frames were written, so whether the server applied them is
     * unknowable from here, and the caller must be able to tell that apart
     * from "the server said no" (retryable / reportable) and from
     * [NotConnectedException] ("never left us — safe to queue").
     *
     * When the peer named the message as the problem
     * ([ConnectFailure.rejectsMessage], i.e. a 1009 close) the ambiguity
     * is gone — the server discarded the frame and said so — and callers
     * get [MessageRejectedException] instead, which the queue treats as
     * permanent rather than retrying into the same wall.
     *
     * Idempotent: `pending` is cleared, so the second call for the same
     * disconnect (listener first, then the lifecycle loop) does nothing.
     */
    private fun failPendingOnDisconnect(failure: ConnectFailure) {
        val cause: Throwable = if (failure.rejectsMessage) {
            MessageRejectedException(failure)
        } else {
            TransportLostException(failure)
        }
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
        /**
         * Completed on [onOpen] — and by every path that ends the attempt
         * before it opens, so the pre-open phase never outlives the
         * attempt it is timing.
         */
        private val opened: CompletableDeferred<Unit>,
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
        /**
         * Identity reference to the transport this listener was bound
         * to. Stays set for the listener's lifetime — used by
         * `onClosed` / `onFailure` to detect when a later attempt has
         * displaced this transport and silently drop the late events
         * the OkHttp listener still fires from the dying socket.
         *
         * Without this, the sequence ([forceReconnect] → new socket
         * established → old socket's close handshake finishes →
         * `onClosed(1001 "evicted by new connection")`) would push a
         * stale `TransportClosed` into [events] AFTER the lifecycle
         * loop had already settled on the new transport. The loop
         * would interpret the stale event as the current transport
         * dying and reconnect again, causing a self-reinforcing loop
         * with the heartbeat watchdog re-firing every 30s.
         */
        private val boundTx: AtomicReference<RemoteTransport?>,
    ) : RemoteTransportListener {

        /**
         * `true` once the listener's transport has been displaced or
         * abandoned by a newer attempt. Computed lazily on each event
         * by comparing [boundTx] against the shared [transport] field.
         */
        private val isStale: Boolean
            get() {
                val mine = boundTx.get() ?: return false
                // A null published field means "no socket is current" —
                // either this attempt has authenticated but the lifecycle
                // coroutine has not been resumed to publish it yet, or the
                // previous socket was just abandoned. Neither makes THIS
                // socket stale, and treating them as stale dropped the
                // only notification we would ever get: a server that sends
                // `welcome` and closes in the same breath (a peer evicting
                // a duplicate client name, or a drop landing in that
                // window) left the loop waiting forever on an event that
                // had already been thrown away, reporting Connected on a
                // dead socket.
                //
                // Posting the event is safe now that the loop filters by
                // identity: [awaitDisconnect] compares against the socket
                // it actually owns and discards anything older.
                val published = this@RemoteClient.transport ?: return false
                return published !== mine
            }

        override fun onOpen() {
            opened.complete(Unit)
        }

        override fun onBinary(bytes: ByteArray) {
            // R-2 handshake is text/JSON only — binary before Established
            // means the peer speaks a different protocol.
            if (ref.stage != HandshakeStage.Established) {
                completeTransient(
                    ConnectFailure.ProtocolError(
                        "unexpected binary frame during handshake — peer isn't spk-editor"
                    )
                )
                return
            }
            // Post-Established: a binary frame is a compressed JSON-RPC message
            // (uploads only flow client→server, so we never receive those).
            if (WireCompression.isCompressed(bytes)) {
                val text = runCatching { WireCompression.decompress(bytes) }.getOrNull()
                if (text != null) dispatchJsonRpc(text)
                // A decode failure can't be wire corruption (TLS guarantees
                // integrity) — it would be a codec bug. Drop rather than tear
                // the connection down over one frame.
                return
            }
            // Unknown binary frame — drop silently (forward-compat).
        }

        override fun onText(text: String) {
            when (ref.stage) {
                HandshakeStage.AwaitingNonce -> {
                    val challengeBytes = parseChallengeFrame(text)
                    if (challengeBytes == null) {
                        // Transient, like the unexpected-binary case in
                        // [onBinary]: [ConnectFailure.ProtocolError] is a
                        // retryable classification, and delivering it
                        // terminally froze the client on what is usually a
                        // transient peer problem (a mid-restart server, a
                        // proxy injecting a frame). A peer that genuinely
                        // doesn't speak this protocol just keeps failing,
                        // with the reason on the banner.
                        completeTransient(
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
                    // Fresh handshake — clear any prior negotiation until the
                    // welcome re-confirms it.
                    negotiatedOutboundDict = COMPRESSION_DISABLED
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
                            negotiatedOutboundDict =
                                obj?.let { parseNegotiatedDict(it) } ?: COMPRESSION_DISABLED
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
                if (isStale) return
                releaseCallers(failure)
                events.trySend(
                    LifecycleEvent.TransportFailure(
                        failure,
                        terminal = isTerminal,
                        transport = boundTx.get(),
                    ),
                )
            } else if (!handshake.isCompleted) {
                handshake.complete(
                    if (isTerminal) AttemptOutcome.TerminalFailure(failure)
                    else AttemptOutcome.TransientFailure(failure)
                )
                opened.complete(Unit)
            }
        }

        override fun onClosed(code: Int, reason: String) {
            val failure = ConnectFailure.ServerClosed(code, reason)
            val isTerminal = !failure.isRetryable
            if (ref.stage == HandshakeStage.Established) {
                if (isStale) return
                releaseCallers(failure)
                events.trySend(LifecycleEvent.TransportClosed(failure, transport = boundTx.get()))
            } else if (!handshake.isCompleted) {
                handshake.complete(
                    if (isTerminal) AttemptOutcome.TerminalFailure(failure)
                    else AttemptOutcome.TransientFailure(failure)
                )
                opened.complete(Unit)
            }
        }

        /**
         * Fail in-flight requests straight from the transport callback,
         * without waiting for the lifecycle coroutine to dequeue the
         * event.
         *
         * The loop is not always sitting in `awaitDisconnect`: right after
         * a handshake it is inside `onConnected`, awaiting the
         * subscription replay. A socket that dies in that window leaves
         * every application request — and the replay itself — parked until
         * their own timeouts, turning a one-second reconnect into tens of
         * seconds of a UI that has nothing to show. `pending` is
         * concurrent and `failPendingOnDisconnect` is idempotent, so doing
         * it here as well as in the loop is safe.
         */
        private fun releaseCallers(failure: ConnectFailure) {
            failPendingOnDisconnect(failure)
        }

        private fun completeTerminal(failure: ConnectFailure) {
            if (!handshake.isCompleted) handshake.complete(AttemptOutcome.TerminalFailure(failure))
            opened.complete(Unit)
        }
        private fun completeTransient(failure: ConnectFailure) {
            if (!handshake.isCompleted) handshake.complete(AttemptOutcome.TransientFailure(failure))
            opened.complete(Unit)
        }
    }

    /**
     * Read negotiated compression from a `welcome` frame
     * (`{"type":"welcome",...,"compress":"deflate","dict":N}`). Returns the
     * dictionary id to use for outbound frames, or [COMPRESSION_DISABLED] when
     * the server offered no compression — or a codec / dictionary this client
     * doesn't implement (in which case we stay on plain text rather than risk a
     * frame we can't produce or the peer can't read).
     */
    private fun parseNegotiatedDict(welcome: JsonObject): Int {
        val codec = welcome["compress"]?.jsonPrimitive?.contentOrNull
            ?: return COMPRESSION_DISABLED
        if (codec != WireCompression.CODEC_DEFLATE) return COMPRESSION_DISABLED
        val dict = welcome["dict"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: WIRE_DICT_NONE
        return if (dict == WIRE_DICT_PROTO_V1) WIRE_DICT_PROTO_V1 else WIRE_DICT_NONE
    }

    /**
     * Route one inbound frame. Runs on the transport's reader thread, so
     * it must never throw: an exception here escapes into OkHttp's read
     * loop, which reports it as a transport failure and reconnects the
     * whole session over a single malformed frame. Anything unrecognised
     * — not an object, an `id` that isn't a JSON number, a body that
     * doesn't decode — is dropped instead.
     */
    private fun dispatchJsonRpc(text: String) {
        val parsed = runCatching { JsonRpc.json.parseToJsonElement(text) }.getOrNull() ?: return
        val obj = parsed as? JsonObject ?: return
        val idElement = obj["id"]
        if (idElement != null) {
            // Response frame. Match [pending]; if no waiter (call was
            // cancelled or timed out), drop silently — routing it to
            // [_notifications] would pollute the bounded buffer and
            // mislead subscribers expecting server-initiated frames.
            // A JSON-RPC parse-error reply carries `"id": null`, which has
            // no waiter to match either.
            val id = (idElement as? JsonPrimitive)
                ?.let { runCatching { it.long }.getOrNull() }
                ?: return
            val deferred = pending.remove(id) ?: return
            // Decode from the element we already parsed — the frame can be
            // hundreds of KB and this runs on the reader thread, where a
            // second parse delays every frame behind it.
            val response = runCatching {
                JsonRpc.json.decodeFromJsonElement(JsonRpcResponse.serializer(), parsed)
            }
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
        /**
         * Wire-compression codecs this client understands. The server echoes
         * the one it picked in its `welcome` (`compress`/`dict`). Older servers
         * ignore this field, so compression stays off against them.
         */
        val compress: List<String> = listOf(WireCompression.CODEC_DEFLATE),
        /** Highest preset-dictionary id this client implements. */
        val dict: Int = WIRE_DICT_PROTO_V1,
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
     * Raised to callers still waiting on [queueCall] when the client is
     * [close]d.
     *
     * The two variants exist because the recovery is opposite, and the
     * caller cannot work it out from a message string.
     */
    sealed class ClosedException(message: String) : RuntimeException(message) {
        /**
         * **Not delivered, still queued on disk.**
         *
         * The [QueuedMessage] is intact in the [QueueStore] for this
         * server; the next [RemoteClient] built for the same server
         * rehydrates it and sends it. The caller must NOT bounce the text
         * back to the draft (the user would then send it twice) and must
         * NOT re-issue the call. Clearing an optimistic "sending" bubble
         * is fine — it comes back from the server's transcript once the
         * message lands.
         *
         * [messageId] is the [QueuedMessage.id] still on disk.
         */
        class StillQueued(val messageId: String) :
            ClosedException("client closed before flush — still queued for delivery")

        /**
         * **Dropped; nothing was persisted.**
         *
         * Raised when the client was already closed (or closed before the
         * call could be queued at all), so no record of it exists
         * anywhere. Whatever the user typed exists only in the caller's
         * memory: bounce it back to the draft.
         */
        class NotQueued :
            ClosedException("client closed — the call was never queued")
    }

    companion object {
        /**
         * Default TTL for [queueCall] — **24 hours** (R-6d). Sized for
         * a metro / flight / overnight outage; stale-send risk is
         * mitigated by the `:app` bounce-to-input recovery path.
         */
        const val DEFAULT_QUEUE_TTL_MS: Long = 24L * 60L * 60L * 1_000L

        /** [negotiatedOutboundDict] sentinel: server didn't accept compression. */
        private const val COMPRESSION_DISABLED: Int = -1

        /**
         * Grace window after a connection reaches `Connected` during which
         * [forceReconnect] is a no-op. Breaks reconnect storms caused by a
         * liveness probe (or the heartbeat) misjudging a just-established
         * socket as dead. Comfortably longer than a handshake + a probe's
         * `capabilities` round-trip, far shorter than the age of any real
         * mid-session zombie socket.
         */
        const val FORCE_RECONNECT_GRACE_MS: Long = 12_000L

        /**
         * Default per-call timeout for [call]. Bounds RPC duration so a
         * stuck server doesn't pin the UI spinner forever. 30s suits
         * chat-shaped ops (longest realistic: `create_session` spawning
         * an agent subprocess) and lets users notice + retry.
         */
        const val DEFAULT_CALL_TIMEOUT_MS: Long = 30_000L

        /**
         * Timeout for the post-handshake subscription replay. Short
         * because the lifecycle coroutine cannot process transport events
         * while it waits — see [onConnected].
         */
        const val RESUBSCRIBE_TIMEOUT_MS: Long = 10_000L

        /**
         * How long a connection must survive to earn a backoff reset.
         * Below this it counts as flapping and the ladder keeps climbing.
         * Long enough that any connection doing real work qualifies, short
         * enough that a genuine mid-session drop still restarts at 1s.
         */
        const val MIN_STABLE_CONNECTION_MS: Long = 5_000L

        /**
         * TLS-pin mismatches tolerated before the client gives up and
         * demands a re-pair. See [shouldEscalatePinMismatch].
         */
        const val PIN_MISMATCH_RETRY_LIMIT: Int = 3

        /** Floor on the retry delay while a pin mismatch is the last failure. */
        const val PIN_MISMATCH_RETRY_DELAY_MS: Long = 60_000L

        /**
         * Dials a [wakeReconnect] buys a ladder parked by
         * [setReconnectPaused] before it parks again. Enough to ride out
         * an interface that is still settling when the network-change
         * event fires; small enough that a burst of spurious events can't
         * turn into a background reconnect loop.
         */
        const val WAKE_ATTEMPT_BURST: Int = 3
    }
}
