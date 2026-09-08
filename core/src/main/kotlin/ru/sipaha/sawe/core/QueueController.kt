package ru.sipaha.sawe.core

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement

/**
 * Owns the outbound queue half of [RemoteClient]'s state machine — the
 * in-memory FIFO of typed-but-unsent JSON-RPC calls, the disk-backed
 * [QueueStore] mirror, TTL bookkeeping, and the connected-edge flush.
 *
 * Extracted from [RemoteClient] in the M1 refactor: the host class is
 * now a thin facade over [QueueController] for the queue-shaped API
 * (`queueCall`, rehydrate-on-connect, close-time queue drain), while the
 * handshake + transport lifecycle stays in [RemoteClient].
 *
 * **Concurrency model:** every mutation to [queued] goes through
 * [stateLock]. [stateLock] is shared with [RemoteClient] so the
 * close-time edge (null transport, clear queue, mark closing) is one
 * atomic block from the caller's perspective.
 *
 * **Dispatch — M1 "accumulate-then-restore":**
 * Per-item flush is launched concurrently as `Deferred<DispatchResult>`
 * children of a single coordinator coroutine. On success the child
 * removes its own store entry; on transient failure it records its
 * original index. After all children settle (or the transport drops and
 * they cancel/fail in any order) the coordinator collects the
 * failed-at-index list, sorts ascending, and re-prepends in ONE
 * synchronized block. This preserves FIFO across concurrent
 * mid-dispatch failures — the open M1 gap that the previous per-item
 * `addFirst` could not guarantee.
 *
 * **Wire order** is enforced by a chain of gates ([dispatchQueuedItems]):
 * item *n* does not touch the socket until item *n-1* has. Concurrency is
 * for the *responses*, not for the sends — two messages typed offline in
 * a definite order must reach the server in that order, and relying on
 * the children being dispatched in creation order only holds while the
 * host scope happens to be single-threaded.
 */
internal class QueueController(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val queueStore: QueueStore,
    private val onMessageExpired: ((QueuedMessage) -> Unit)?,
    private val stateLock: Any,
    /** Accessor for the current live transport (null while reconnecting). */
    private val transportAccessor: () -> RemoteTransport?,
    /**
     * RPC call hook — invokes the host's `call(method, params, onSent)`
     * (no default timeout). Re-uses the host's `pending` map and
     * `transport` so the queue can't see a different wire than direct
     * callers. `onSent` fires exactly once per call, before the response
     * is awaited, with `written = true` only when the transport ACCEPTED
     * the frame — see `RemoteClient.callInternal` for the three paths and
     * for why "accepted" is the right line to draw.
     */
    private val callRpc: suspend (
        method: String,
        params: JsonElement?,
        onSent: ((written: Boolean) -> Unit)?,
    ) -> JsonRpcResponse,
    /** Events channel — pushes [LifecycleEvent.QueueChanged] when items enqueue. */
    private val events: Channel<LifecycleEvent>,
    /** Read-only connection state — short-circuits `queueCall` while Connected. */
    private val connectionState: StateFlow<ConnectionState>,
    /**
     * Non-blocking read of what the CURRENT socket has negotiated.
     *
     * Used only for the write-ahead provenance stamp in [dispatchOne], and
     * deliberately non-blocking: the stamp is a best-effort record of the
     * peer a frame is about to be written to, and nothing may wait for it.
     * If the capabilities probe has not answered yet this reads
     * [ServerFeatures.NONE], the stamp records `instanceId = null`, and a
     * later gate reads that as "not provably replayable" — the conservative
     * direction.
     */
    private val currentFeatures: () -> ServerFeatures = { ServerFeatures.NONE },
    /**
     * Bounded wait for a feature set that actually advertises
     * [WireFeature.CSID_DEDUPE], used ONLY to reach a gate verdict.
     *
     * [flushQueue] runs on the `Connected` edge, right after `subscribe`,
     * while the capabilities probe is an ordinary `:app`-driven call whose
     * answer has normally NOT landed yet. Reading [currentFeatures]
     * synchronously there would see [ServerFeatures.NONE] and deny every
     * replay. Called at most once per flush — see [dispatchQueuedItems].
     */
    private val awaitDedupeFeatures: suspend () -> ServerFeatures = { ServerFeatures.NONE },
    /**
     * "May this record, restored from disk by a LATER process than the one
     * that wrote its frame, go out again?" — supplied by `:app`, which owns
     * the csid-dedupe policy (`canReplayRehydratedSend`).
     *
     * Pure predicate over the persisted [QueuedMessage] and the feature set
     * of the connection that would carry the replay. `null` means "allow",
     * preserving the unconditional replay `:cli`, `:core` tests and any
     * other non-Android consumer have always had.
     */
    private val replayGate: ((QueuedMessage, ServerFeatures) -> Boolean)? = null,
) {
    /**
     * Outbound queue — in-memory wrappers pairing each persisted
     * [QueuedMessage] with its caller-side [CompletableDeferred]. The
     * authoritative ordering and survival across restarts comes from
     * [queueStore]; this deque is just a fast lookup for the in-flight
     * coroutines awaiting their response.
     */
    private val queued = ArrayDeque<QueuedCall>()

    /**
     * Items handed to [dispatchQueuedItems] and not yet settled.
     *
     * They are deliberately absent from [queued] while in flight (so a
     * concurrent flush can't send them twice), which used to make them
     * invisible to [drainOnClose] as well: closing the client cancelled
     * their underlying RPC but completed nobody, leaving the caller parked
     * on a deferred for the rest of its 24-hour TTL — and then, hours
     * later, bouncing a message the next client had long since delivered.
     * Guarded by [stateLock], like [queued].
     *
     * Membership here means "claimed for a flush", NOT "written": items
     * are claimed by [expireStaleEntries] before the coordinator has put
     * anything on a socket. What was actually written is
     * [QueuedCall.frameState]. A claim whose frame was never attempted is
     * still the user's to withdraw — [cancelQueued] takes it straight out
     * of this set.
     */
    private val inFlight = LinkedHashSet<QueuedCall>()

    /**
     * Read every previously-persisted [QueuedMessage] from [queueStore]
     * into [queued], wrapping each in a fresh [CompletableDeferred].
     *
     * **Bounce semantics for orphaned deferreds:** an entry restored
     * from disk has no caller awaiting its [CompletableDeferred] — the
     * coroutine that originally called `queueCall` died with the
     * previous process. We still complete the deferred on success /
     * failure so the bookkeeping is symmetric, but no one will observe
     * the result. The user-visible recovery path is [onMessageExpired]
     * — that's where the `:app` layer plumbs the bounced text back into
     * the draft repository for retry.
     *
     * Everything restored here is marked [QueuedCall.rehydrated], which is
     * what arms the cross-process replay gate in [dispatchOne]. Items this
     * process queued itself are never gated — see that method.
     */
    fun rehydrate() {
        val persisted = queueStore.loadAll()
        if (persisted.isEmpty()) return
        synchronized(stateLock) {
            for (msg in persisted.sortedBy { it.enqueuedAtMs }) {
                queued += QueuedCall(
                    message = msg,
                    deferred = CompletableDeferred(),
                    ttlMs = RemoteClient.DEFAULT_QUEUE_TTL_MS,
                    rehydrated = true,
                )
            }
        }
    }

    /**
     * Deferreds of the items currently parked in [queued], in FIFO order.
     *
     * Test seam. A rehydrated entry's deferred is orphaned by construction
     * — the coroutine that would await it died with the previous process —
     * so this is the only way to observe how the queue settles one. Every
     * production caller already holds the deferred it cares about, from
     * [queueCall].
     */
    internal fun parkedDeferredsForTest(): List<Deferred<JsonRpcResponse>> =
        synchronized(stateLock) { queued.map { it.deferred } }

    /**
     * Snapshot every item with a caller still waiting, split by whether
     * anything has been written for it. Caller MUST hold [stateLock] when
     * calling (or accept a slightly stale view). Clears the in-memory
     * collections as a side effect.
     *
     * The split is decided by [QueuedCall.frameState], NOT by which
     * collection an item happens to sit in: [expireStaleEntries] claims
     * every survivor into [inFlight] before the dispatch coordinator has
     * written a byte, so set membership alone would report messages that
     * never touched a socket as delivery-unknown and bounce them into the
     * composer while dropping their records.
     *
     * The two halves owe the user opposite things, and only [parked] can
     * honestly be called "not sent" — see [RemoteClient.close].
     */
    fun drainOnClose(): CloseDrain {
        // Caller is responsible for [stateLock] ordering — see
        // [RemoteClient.close]. We do NOT acquire it here because the
        // close path needs the same lock for transport=null, pending
        // clear, and queue drain to be one atomic block.
        val onTheWire = ArrayList<QueuedCall>(inFlight.size)
        val unwritten = ArrayList<QueuedCall>(inFlight.size)
        for (item in inFlight) {
            when (item.frameState) {
                FrameState.WRITTEN, FrameState.IN_PROGRESS -> onTheWire += item
                FrameState.NOT_ATTEMPTED, FrameState.REFUSED -> unwritten += item
            }
        }
        // Unwritten in-flight items were drained from the HEAD of [queued],
        // so they go back in front of whatever queued up behind them —
        // otherwise the next client sends the user's messages out of order.
        val drain = CloseDrain(parked = unwritten + queued, onTheWire = onTheWire)
        queued.clear()
        inFlight.clear()
        return drain
    }

    /**
     * The two halves of a close-time drain.
     *
     * @property parked provably nothing was written for them: either still
     *   parked in the queue, or claimed for a flush that never reached
     *   their frame ([FrameState.NOT_ATTEMPTED]) or whose frame the
     *   transport refused ([FrameState.REFUSED]). Their [QueueStore]
     *   records are kept for the next client, which will send them, in
     *   this order.
     * @property onTheWire the transport took their frame
     *   ([FrameState.WRITTEN]) or was still inside the write when the
     *   close landed ([FrameState.IN_PROGRESS]); whether the server
     *   applied them is unknowable. Re-sending them from disk would post a
     *   duplicate, so their records are dropped and the text goes back to
     *   the user instead.
     */
    internal data class CloseDrain(
        val parked: List<QueuedCall>,
        val onTheWire: List<QueuedCall>,
    )

    /**
     * Drop the persisted record for an item that was on the wire when the
     * client was closed, and hand its payload back through
     * [onMessageExpired].
     *
     * The bounce is what keeps a rehydrated entry — whose original caller
     * died with a previous process — from vanishing silently once its disk
     * record is gone.
     */
    fun abandonOnClose(item: QueuedCall) {
        runCatching { queueStore.remove(item.message.id) }
        runCatching { onMessageExpired?.invoke(item.message) }
    }

    /**
     * True when this call may skip the queue entirely and go straight to
     * the wire. Caller must hold [stateLock].
     *
     * Being connected is not sufficient: a backlog that is queued or
     * mid-flush has to reach the server first. The flush is dispatched
     * from a launched coroutine, and `Connected` is published before it
     * has necessarily put anything on the socket, so a send issued in that
     * gap would otherwise overtake messages the user typed earlier — the
     * exact FIFO inversion the dispatch gate chain exists to prevent.
     * Joining the tail costs one extra `QueueChanged` flush.
     */
    private fun canSendImmediatelyLocked(): Boolean =
        connectionState.value is ConnectionState.Connected &&
            queued.isEmpty() &&
            inFlight.isEmpty()

    /**
     * Body of [RemoteClient.queueCall]. Splits cleanly into fast path
     * (connected, nothing ahead of us → call directly) and slow path
     * (offline or queued behind others → persist + enqueue + await TTL).
     */
    suspend fun queueCall(
        method: String,
        params: JsonElement?,
        ttlMs: Long,
        messageId: String? = null,
    ): JsonRpcResponse {
        // Fast path: if we're connected, hand off to `call` directly. The
        // TTL only applies while the call is *queued* — once the wire
        // delivers it, the server's per-method timeout is what we trust.
        //
        // Race: between the connection-state read and `call()`, the
        // lifecycle coroutine can null out transport (mid-flush
        // transport drop) and `callInternal` throws [NotConnectedException];
        // or the socket is already failing and the transport refuses the
        // frame ([FrameRefusedException]). Both mean the bytes provably
        // never left this process, so we fall through to the queuing
        // branch and honour the "held until next Connected" contract
        // instead of reporting a send that never happened as an error.
        //
        // A [TransportLostException] is deliberately NOT caught here: that
        // frame *was* written, and re-queueing it would post the message
        // twice as soon as the server received the first copy (there is no
        // server-side de-duplication by `spk_client_send_id` yet).
        if (synchronized(stateLock) { canSendImmediatelyLocked() }) {
            try {
                return callRpc(method, params, null)
            } catch (_: NotConnectedException) {
                // Fall through to queue.
            } catch (_: FrameRefusedException) {
                // Fall through to queue.
            }
        }
        val deferred = CompletableDeferred<JsonRpcResponse>()
        val message = QueuedMessage(
            id = messageId ?: UUID.randomUUID().toString(),
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
            if (canSendImmediatelyLocked()) {
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
                return callRpc(method, params, null)
            } catch (t: Throwable) {
                if (t !is NotConnectedException && t !is FrameRefusedException) throw t
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

    /**
     * Send every still-fresh queued item; TTL-expire the stale ones.
     * On transport loss mid-flush, items not yet dispatched are re-enqueued
     * at the head of the deque so FIFO order survives across reconnects.
     *
     * **Persistence:** expired entries are removed from [queueStore] and
     * [onMessageExpired] fires before the deferred fails so the `:app`
     * bounce-to-input path sees the payload exactly once.
     */
    fun flushQueue() {
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
            // Claim the survivors here rather than in [dispatchQueuedItems]:
            // draining `queued` and marking them in flight has to be one
            // atomic step, or a [cancelQueued] landing in between would see
            // an item that is in neither collection and could not tell
            // "about to be sent" from "unknown id".
            inFlight.addAll(sv)
            sv to ex
        }
        for (item in expired) {
            abandon(item, QueueTtlException())
        }
        return survivors
    }

    /**
     * Concurrent FIFO drain with M1 accumulate-then-restore.
     *
     * Launches a single coordinator coroutine on [scope]. The coordinator
     * launches per-item dispatches as `Deferred<DispatchResult>` children
     * and awaits all of them via [awaitAll]. Each child:
     *
     *   - on success: removes its [queueStore] entry, completes its
     *     deferred with the response, returns [DispatchResult.Sent].
     *   - on [QueueTtlException]: removes its store entry, fires
     *     [onMessageExpired], completes the deferred with the exception,
     *     returns [DispatchResult.Expired] (no re-enqueue).
     *   - on a server-side rejection ([MessageRejectedException], i.e. a
     *     1009 close): returns [DispatchResult.Rejected] and lets the
     *     coordinator bounce the earliest such item — see below.
     *   - on a permanently undeliverable payload ([FrameTooLargeException],
     *     or [MAX_DISPATCH_ATTEMPTS] live send attempts that all failed):
     *     same treatment as expiry — store entry removed,
     *     [onMessageExpired] fired, deferred failed. Retrying it forever
     *     would keep tearing the connection down (a frame above the
     *     server's read limit is a poison pill) or keep the queue head
     *     blocked for the whole 24h TTL.
     *   - on any other failure (transient — NotConnectedException, refused
     *     frame, IO error): does NOT immediately re-enqueue. Returns
     *     [DispatchResult.Failed(originalIndex)] so the coordinator can
     *     restore FIFO order.
     *   - if the user withdrew the item ([cancelQueued] won the
     *     [tryBeginWrite] transition): sends nothing, touches nothing —
     *     the cancel already dropped the record and failed the deferred —
     *     and returns [DispatchResult.Cancelled] for the coordinator to
     *     drop. It still opens its gate, so the rest of the flush is not
     *     stranded behind a message that is no longer going anywhere.
     *
     * After [awaitAll] resolves the coordinator sorts the failed indices
     * ascending and re-prepends those items in ONE synchronized block.
     * If new items arrived in [queued] while we were in flight, they keep
     * their relative position AFTER the restored prefix — same semantics
     * as the previous mid-flush re-enqueue.
     *
     * If transport drops mid-flush, the children's `callRpc` invocations
     * throw [NotConnectedException] (or similar transient) — the
     * coordinator collects those and re-prepends in original order, just
     * as if every child had failed sequentially. No deadlock vs the
     * lifecycle channel: the lifecycle coroutine never awaits the
     * coordinator; it's a fire-and-forget launch on [scope].
     *
     * **Wire order.** Each child waits on the gate its predecessor opens
     * once that predecessor's frame has been handed to the transport, so
     * the sends happen in queue order no matter which thread runs which
     * child. The gate opens before the response is awaited, so a slow
     * server delays nothing behind it, and it opens on every exit path
     * (failure, expiry, cancellation) so one stuck item cannot strand the
     * rest of the flush.
     *
     * **Replay gate, once per flush.** If anything in [toSend] was restored
     * from disk AND already carries a [QueuedSendAttempt], the coordinator
     * resolves [awaitDedupeFeatures] ONE time and passes the answer to every
     * child. Asking per item would cost N × [REPLAY_GATE_TIMEOUT_MS] against
     * a peer that advertises no dedupe at all. A flush with nothing to gate
     * never calls it, so an ordinary flush cannot stall behind a wait it has
     * no use for.
     *
     * The gate verdict is the ONLY consumer of that bounded wait. The
     * write-ahead provenance stamp reads [currentFeatures] instead — see
     * [dispatchOne].
     */
    private fun dispatchQueuedItems(toSend: List<QueuedCall>) {
        if (toSend.isEmpty()) return
        // [toSend] is already claimed into [inFlight] by [expireStaleEntries].
        // The coordinator owns the failed-index aggregation and the
        // single restore block. It runs on [scope]; the lifecycle
        // coroutine returns from flushQueue immediately and stays
        // available to consume LifecycleEvents (including a mid-flush
        // TransportClosed). This is the deadlock-free shape — Phase 3
        // M1 (sequential await on lifecycle) is forbidden, see the
        // backlog note in the host class.
        scope.launch {
            // Resolve the gate verdict's feature set before any child runs,
            // and only when some child can actually use it.
            val needsGate = toSend.any { it.rehydrated && it.message.attempt != null }
            val gateFeatures = if (needsGate) awaitDedupeFeatures() else ServerFeatures.NONE
            // gates[i] opens when item i-1 has been put on the wire;
            // gates[0] is open from the start.
            val gates = List(toSend.size) { CompletableDeferred<Unit>() }
            gates[0].complete(Unit)
            val children: List<Deferred<DispatchResult>> = toSend.mapIndexed { index, item ->
                async {
                    val openNext = { gates.getOrNull(index + 1)?.complete(Unit); Unit }
                    try {
                        gates[index].await()
                        dispatchOne(item, index, gateFeatures, openNext)
                    } finally {
                        // Note: the item stays in [inFlight] until the
                        // coordinator settles it below. Removing it here
                        // opened a window in which it was in neither
                        // collection, so a close() landing there released
                        // nobody and left the caller parked for its TTL.
                        openNext()
                    }
                }
            }
            // awaitAll: every child has its own try/catch and returns
            // a DispatchResult, so this never throws even if individual
            // RPCs failed. The only escape is coordinator cancellation
            // (scope cancel = close()) — in that case let the
            // CancellationException propagate; the lock-protected
            // queue state is already consistent because each child
            // either ran to completion (returned a result) or was
            // cancelled before mutating anything.
            val results = try {
                children.awaitAll()
            } catch (t: Throwable) {
                // A child rethrowing CancellationException (close() cancelled
                // its RPC) lands here. The items are close()'s to settle, but
                // if we were cancelled for any other reason they must not be
                // left claimed forever.
                synchronized(stateLock) { inFlight.removeAll(toSend.toSet()) }
                throw t
            }
            // A server-side message rejection (1009) kills the socket at
            // the frame the server choked on, so it read everything before
            // that frame and nothing after it. Only the earliest rejected
            // item can be the offender; the ones behind it were never
            // looked at and deserve a normal retry.
            //
            // This is a backstop, not a live path, and it is meant to stay
            // that way: [MAX_OUTBOUND_FRAME_BYTES] (900 KiB) sits below the
            // desktop's 1 MiB read limit, so a frame this client is willing
            // to send is one the server is willing to read, and the local
            // [FrameTooLargeException] fires first. Do not delete it as
            // dead code — it is what stands between us and an unexplained
            // reconnect loop if the two limits ever drift apart (a server
            // that lowers its cap, a build with a raised client cap, or a
            // proxy in the path with its own smaller one), and it is the
            // only place that decides WHICH of several in-flight messages
            // the rejection belongs to.
            val rejected = results.filterIsInstance<DispatchResult.Rejected>()
                .sortedBy { it.originalIndex }
            rejected.firstOrNull()?.let { abandon(toSend[it.originalIndex], it.cause) }
            val failed = results.mapNotNull { (it as? DispatchResult.Failed)?.originalIndex } +
                rejected.drop(1).map { it.originalIndex }
            // Sort ascending so the prefix we restore preserves FIFO
            // order: lower originalIndex was enqueued earlier and
            // must come out earlier on the next flush. Un-claiming and
            // restoring in ONE block so an item is never momentarily
            // invisible to [drainOnClose] or [cancelQueued].
            //
            // [QueuedCall.cancelled] is read under the same [stateLock]
            // that sets it, so this cannot resurrect a withdrawn message:
            // either the cancel got there first and we skip the item, or
            // we restore it to [queued] first and the cancel finds it on
            // the ordinary parked path. A cancelled item CAN reach here —
            // the transport-null fail-fast at the top of [dispatchOne]
            // reports [DispatchResult.Failed] before anything is written.
            synchronized(stateLock) {
                inFlight.removeAll(toSend.toSet())
                val restored = failed.sorted().map { toSend[it] }.filterNot { it.cancelled }
                if (restored.isNotEmpty()) {
                    val combined = ArrayDeque<QueuedCall>(restored.size + queued.size)
                    combined.addAll(restored)
                    combined.addAll(queued)
                    queued.clear()
                    queued.addAll(combined)
                }
            }
            if (failed.isEmpty()) return@launch
            // Wake the lifecycle coroutine — it'll re-flush the
            // restored items on the next Connected edge.
            events.trySend(LifecycleEvent.QueueChanged)
        }
    }

    /**
     * Send one queued item. See [dispatchQueuedItems] for the contract of
     * the returned [DispatchResult] and for [openNext].
     *
     * **Frame state.** [QueuedCall.frameState] moves to
     * [FrameState.IN_PROGRESS] immediately before the call — the same "we
     * are about to write" point as [stampAttempt] — and then to
     * [FrameState.WRITTEN] or [FrameState.REFUSED] from the `onSent`
     * callback, according to whether the transport accepted the frame. It
     * is what [drainOnClose] classifies by; nothing else may infer
     * "was written" from set membership.
     *
     * **The write is claimed, not assumed.** That first move is
     * [tryBeginWrite], a [stateLock]-guarded transition this child has to
     * WIN before it may write anything: the same lock is what
     * [cancelQueued] takes to withdraw an unwritten claim, so between the
     * two exactly one happens. Losing it means the user cancelled while
     * this child was parked on its gate — return
     * [DispatchResult.Cancelled] and touch nothing else.
     *
     * **Cross-process replay gate.** An item that this process restored from
     * disk AND that already carries a [QueuedSendAttempt] describes a frame
     * some EARLIER process wrote. Whether the desktop applied it is
     * unknowable from here, so it may only go out again when [replayGate]
     * can prove the repeat would be absorbed — same editor process, still
     * inside its `spk_client_send_id` dedupe window. Otherwise it is
     * [abandon]ed, i.e. the record is dropped and the text bounces to the
     * composer, which is the established restart-time recovery
     * ([abandonOnClose] does exactly this).
     *
     * **Only rehydrated items are gated, deliberately.** An item queued by
     * THIS process whose frame was written and whose socket then died is
     * restored and retried unconditionally — see the [TransportLostException]
     * branch below ("bouncing on an ambiguous delivery is worse than
     * retrying") and its `:app` caller `dispatchSendWithRetry`, which owns
     * that ambiguity while the user is still watching the bubble. Across a
     * restart there is no caller left to own it, and the bubble is gone too;
     * that is the whole difference between the two halves.
     *
     * **Write-ahead provenance.** The attempt is stamped onto the record
     * and persisted BEFORE the frame goes out, so a kill in the window
     * between the write and the response cannot lose the fact that bytes
     * left. The stamp reads [currentFeatures] (non-blocking) rather than the
     * gate's bounded wait: it must name the peer the frame is ACTUALLY
     * written to, and it must not stall a flush. Before the capabilities
     * probe answers that yields `instanceId = null`, which a later gate
     * reads as "not provably replayable" — the conservative direction.
     * Re-stamping is skipped while the instance is unchanged, because
     * `EncryptedQueueStore.add` rewrites the whole encrypted blob and a
     * reconnect-retry loop would otherwise churn the disk for no new fact.
     */
    private suspend fun dispatchOne(
        item: QueuedCall,
        index: Int,
        gateFeatures: ServerFeatures,
        openNext: () -> Unit,
    ): DispatchResult {
        // Cheap early-out for the common shape: the user cancelled while
        // this child was parked on its gate (or on the coordinator's
        // bounded feature wait). Not the barrier — [tryBeginWrite] below
        // is — but it keeps a withdrawn item out of the [abandon] paths
        // ahead, which would bounce its text back into the composer for a
        // message the user deliberately threw away.
        if (item.cancelled) return DispatchResult.Cancelled
        if (transportAccessor() == null) {
            // Wire is already gone before this child even started —
            // fail-fast as transient, without counting an attempt (no
            // frame was built, let alone refused). Restore at [index].
            return DispatchResult.Failed(index)
        }
        if (item.rehydrated &&
            item.message.attempt != null &&
            replayGate?.invoke(item.message, gateFeatures) == false
        ) {
            abandon(item, ReplayNotSafeException())
            return DispatchResult.Expired
        }
        val remainingTtl = item.ttlMs - (nowMs() - item.message.enqueuedAtMs)
        if (remainingTtl <= 0) {
            abandon(item, QueueTtlException())
            return DispatchResult.Expired
        }
        // Win the item before touching disk or socket. Deliberately BEFORE
        // [stampAttempt]: a cancel drops the [QueueStore] record outside
        // the lock, so a stamp racing it would re-add the row it just
        // deleted and the next process would replay a message the user
        // withdrew.
        if (!tryBeginWrite(item)) return DispatchResult.Cancelled
        stampAttempt(item)
        return try {
            val resp = callRpc(item.message.method, item.message.params) { written ->
                item.frameState = if (written) FrameState.WRITTEN else FrameState.REFUSED
                openNext()
            }
            runCatching { queueStore.remove(item.message.id) }
            item.deferred.complete(resp)
            DispatchResult.Sent
        } catch (c: CancellationException) {
            // The controller is going away (close() cancels the scope, or
            // close() cancelled the underlying RPC). Do NOT translate that
            // into a per-item failure: the restore path would push the
            // item back into a dead deque and its caller would wait out
            // the full TTL before bouncing a message the next client is
            // about to deliver. [RemoteClient.close] releases the caller
            // and leaves the store entry for that next client.
            throw c
        } catch (t: Throwable) {
            when {
                // The payload itself is impossible to send — no number of
                // reconnects changes that.
                t is FrameTooLargeException -> {
                    abandon(item, t)
                    DispatchResult.Expired
                }
                // The server named the message as the problem. Permanent
                // for this payload — the coordinator decides which of the
                // rejected items was actually the offender.
                t is MessageRejectedException -> DispatchResult.Rejected(index, t)
                // Never reached the wire and there is nothing to retry
                // against — the flush already lost its transport. Costs no
                // attempt, and cannot spin: the re-flush only happens on
                // the next Connected edge.
                t is NotConnectedException -> DispatchResult.Failed(index)
                // The frame WAS written and the socket died before the
                // reply. Delivery is unknown, which is precisely why this
                // must not count towards [MAX_DISPATCH_ATTEMPTS]: that cap
                // ends in [abandon], i.e. "this was never sent, here is
                // your text back", and saying that about a message the
                // server may well have applied is how the user is led into
                // posting it twice. A flaky link drops every item of a
                // flush at once, so counting it would reach the cap in
                // five ordinary reconnects. The TTL is what bounds this
                // case; retrying is safe because the caller re-queues the
                // same persisted message, not a new one.
                t is TransportLostException -> DispatchResult.Failed(index)
                // Everything else — a transport that refuses the frame
                // while still reporting itself live, or an unrecognised
                // error — burns an attempt. Refusal is the case the cap
                // actually exists for: it is not followed by a transport
                // loss that would clear the deque, so restore →
                // QueueChanged → re-flush spins while still Connected.
                else -> {
                    item.failedAttempts++
                    if (item.failedAttempts >= MAX_DISPATCH_ATTEMPTS) {
                        abandon(item, t)
                        DispatchResult.Expired
                    } else {
                        // Transient — do NOT re-enqueue here. The
                        // coordinator restores all failed items in one
                        // synchronized block, sorted by index, so
                        // concurrent reverse-order failures do not invert
                        // FIFO order.
                        DispatchResult.Failed(index)
                    }
                }
            }
        }
    }

    /**
     * The one transition between "the user may still take this back" and
     * "a frame is going out for it". Returns true iff [dispatchOne] may
     * proceed to write.
     *
     * Both halves happen under [stateLock], which is what makes the race
     * with [cancelQueued] decidable instead of hoped-for: a cancel can only
     * take an item that is [FrameState.NOT_ATTEMPTED] and not already
     * withdrawn, and it marks it withdrawn under this same lock. So either
     * the cancel got here first and this returns false (nothing is written,
     * ever), or this moves the item to [FrameState.IN_PROGRESS] first and
     * every later cancel is refused. Never both, never neither.
     *
     * The move is unconditional on the previous [FrameState] rather than a
     * literal `NOT_ATTEMPTED → IN_PROGRESS` compare-and-set: an item whose
     * earlier attempt was [FrameState.REFUSED] is restored to [queued] by
     * the coordinator and gets a fresh dispatch, which is a real write.
     */
    private fun tryBeginWrite(item: QueuedCall): Boolean = synchronized(stateLock) {
        if (item.cancelled) {
            false
        } else {
            item.frameState = FrameState.IN_PROGRESS
            true
        }
    }

    /**
     * Record, on disk, that a frame for [item] is about to be written and
     * to which editor process. See the write-ahead paragraph in
     * [dispatchOne] for why this happens before the send and why it is
     * skipped when the instance is unchanged.
     */
    private fun stampAttempt(item: QueuedCall) {
        val live = currentFeatures()
        val instance = live.serverInstanceId.takeIf { live.has(WireFeature.CSID_DEDUPE) }
        val prior = item.message.attempt
        if (prior != null && prior.instanceId == instance) return
        val stamped = item.message.copy(attempt = QueuedSendAttempt(nowMs(), instance))
        item.message = stamped
        runCatching { queueStore.add(stamped) }
    }

    /**
     * Give up on [item] for good: drop its store record, hand the payload
     * to [onMessageExpired] so the `:app` layer can put the text back in
     * front of the user, and fail its caller with [cause].
     */
    private fun abandon(item: QueuedCall, cause: Throwable) {
        runCatching { queueStore.remove(item.message.id) }
        runCatching { onMessageExpired?.invoke(item.message) }
        item.deferred.completeExceptionally(cause)
    }

    /**
     * Withdraw a queued message on the user's behalf, if nothing has been
     * written for it.
     *
     * Returns true iff the message was taken back before any frame went
     * out. That covers two states, and the second one is the point:
     *
     *  1. still parked in [queued] — never claimed by a flush;
     *  2. claimed into [inFlight] but [FrameState.NOT_ATTEMPTED] — a flush
     *     drained it from the deque and it is sitting on its gate, or on
     *     the coordinator's [REPLAY_GATE_TIMEOUT_MS] feature wait, with
     *     nothing written for it. [expireStaleEntries] claims every
     *     survivor before the coordinator writes a byte, so this window is
     *     up to 30 seconds wide — exactly the interval in which a user
     *     watching a stuck send reaches for cancel.
     *
     * Returns false for [FrameState.IN_PROGRESS], [FrameState.WRITTEN] and
     * [FrameState.REFUSED] claims, and for unknown ids. So **false means
     * "not withdrawable", not "too late"** — a REFUSED item provably never
     * went out, but it is the coordinator's to restore to [queued], and it
     * becomes cancellable again on the ordinary parked path once it is
     * back. See [RemoteClient.cancelQueued] for the caller-facing contract.
     *
     * Racing the dispatch coroutine is decided, not hoped for: taking an
     * [inFlight] claim and marking it withdrawn happens in the same
     * [stateLock] block that [tryBeginWrite] must win before it writes.
     */
    fun cancelQueued(id: String): Boolean {
        val item = synchronized(stateLock) {
            // Removal preserves the order of the rest — ArrayDeque.remove
            // is a positional splice, not a reorder.
            val parked = queued.firstOrNull { it.message.id == id }
            if (parked != null) {
                queued.remove(parked)
                parked.cancelled = true
                parked
            } else {
                // Membership of [inFlight] means "claimed by a flush", and
                // a claim is only the user's to take back while its frame
                // state proves nothing left this process. Anything else —
                // mid-write, written, or refused-and-awaiting-restore — is
                // settled by the dispatch coroutine, not here.
                val claimed = inFlight.firstOrNull {
                    it.message.id == id &&
                        it.frameState == FrameState.NOT_ATTEMPTED &&
                        !it.cancelled
                } ?: return false
                // Both in one locked step: the flag is what makes the
                // dispatch child stand down, and dropping it from
                // [inFlight] here is what keeps [drainOnClose] from seeing
                // a message that no longer exists. The coordinator's
                // closing `inFlight.removeAll(toSend)` is then a no-op for
                // it, and its restore path skips it by the same flag.
                claimed.cancelled = true
                inFlight.remove(claimed)
                claimed
            }
        }
        runCatching { queueStore.remove(item.message.id) }
        // Deliberately NOT [abandon]: [onMessageExpired] means "this was
        // lost, give the text back to the user", and the user is the one
        // who just threw it away. Bouncing it would put the text they
        // cancelled straight back into the composer.
        item.deferred.completeExceptionally(QueueCancelledException())
        return true
    }

    /**
     * Await the per-item TTL on the caller side of [queueCall]. If TTL
     * fires while the item is still in [queued] (not yet dispatched),
     * remove it from disk and bounce via [onMessageExpired]. If the
     * item was already handed off to the wire, leave the in-flight
     * pending entry to be resolved by `dispatchJsonRpc` (or fail via
     * `failPendingOnDisconnect`) — its disk entry is cleaned up there.
     */
    suspend fun awaitWithTtl(item: QueuedCall): JsonRpcResponse {
        return try {
            withTimeout(item.ttlMs) { item.deferred.await() }
        } catch (t: TimeoutCancellationException) {
            val removed = synchronized(stateLock) { queued.remove(item) }
            if (removed) {
                runCatching { queueStore.remove(item.message.id) }
                // Fire the bounce callback directly — consistent with the
                // other two TTL-expiry paths (expireStaleEntries,
                // dispatchQueuedItems per-item) which also invoke it
                // synchronously. The callback contract is "may be called
                // from any coroutine; implementations must be thread-safe"
                // (see callback KDoc on [RemoteClient.onMessageExpired]).
                runCatching { onMessageExpired?.invoke(item.message) }
            }
            if (!item.deferred.isCompleted) {
                item.deferred.completeExceptionally(QueueTtlException())
            }
            throw QueueTtlException()
        }
    }

    /** Per-item outcome reported back to the dispatch coordinator. */
    private sealed interface DispatchResult {
        data object Sent : DispatchResult
        data object Expired : DispatchResult
        data class Failed(val originalIndex: Int) : DispatchResult

        /**
         * The user withdrew this item before its frame was written, and
         * [cancelQueued] has already dropped the record, un-claimed it and
         * failed its caller with [QueueCancelledException].
         *
         * Deliberately NOT [Expired]: that one means "abandoned, the
         * bounce fired, the text is back in the composer", which is the
         * opposite of what a cancel owes the user. The coordinator drops
         * this one — no restore, no bounce, no second completion.
         */
        data object Cancelled : DispatchResult

        /**
         * The server refused this item's frame outright. Settled by the
         * coordinator rather than the child, because only the coordinator
         * can see which of several rejected items was first on the wire
         * and therefore the one the server actually choked on.
         */
        data class Rejected(
            val originalIndex: Int,
            val cause: MessageRejectedException,
        ) : DispatchResult
    }

    companion object {
        /**
         * Send attempts one queued item gets before it is treated as
         * undeliverable and bounced back to the user.
         *
         * Counts only failures that prove the frame did NOT go out and
         * that will keep repeating — a transport refusing the frame while
         * reporting itself live, or an error we don't recognise. Being
         * offline costs nothing, and neither does losing the socket
         * mid-flight: see the [TransportLostException] branch in
         * [dispatchOne] for why bouncing on an ambiguous delivery is worse
         * than retrying it.
         */
        const val MAX_DISPATCH_ATTEMPTS: Int = 5

        /**
         * How long a flush may wait for the capabilities probe to answer
         * before ruling on a cross-process replay.
         *
         * The same bound as `:app`'s `REPLAY_RENEGOTIATE_TIMEOUT_MS`, and
         * for the same reason: the two paths decide the identical question
         * ("is the desktop still the process whose dedupe table holds this
         * claim?") and must not disagree about how long that is worth
         * waiting for. On timeout the flush rules on whatever has been
         * negotiated so far, which for an un-negotiated connection means a
         * refusal.
         */
        const val REPLAY_GATE_TIMEOUT_MS: Long = 30_000L
    }
}

/**
 * In-memory wrapper around a persisted [QueuedMessage] holding its
 * caller-side [CompletableDeferred] + the TTL chosen by the caller.
 * The [message] is the disk-canonical view; [deferred] is process-local
 * and re-created (orphaned) on rehydrate.
 *
 * **Deliberately not a `data class`, and [message] is deliberately a
 * `var`.** The write-ahead attempt stamp rewrites [message] mid-dispatch,
 * while the very same wrapper is a member of [QueueController]'s deque and
 * of its `inFlight` [LinkedHashSet], both of which look items up by value.
 * A generated `hashCode` over a mutable field would move the item to a
 * different bucket the moment it is stamped, so it could never be removed
 * from the set again and the caller would sit on its deferred for the whole
 * TTL. Identity equality is also all the lookups need: every one of them
 * passes back the very instance it was handed.
 *
 * @property rehydrated true iff this wrapper was restored from
 *   [QueueStore] by [QueueController.rehydrate], i.e. a PREVIOUS process
 *   queued it. Arms the cross-process replay gate — see
 *   [QueueController.dispatchOne].
 */
internal class QueuedCall(
    @Volatile var message: QueuedMessage,
    val deferred: CompletableDeferred<JsonRpcResponse>,
    val ttlMs: Long,
    val rehydrated: Boolean = false,
) {
    /**
     * Live send attempts that came back failed. Deliberately outside the
     * constructor so it stays out of `equals`/`hashCode` — the deque and
     * the in-flight set look items up by value.
     *
     * See [QueueController.MAX_DISPATCH_ATTEMPTS].
     */
    @Volatile
    var failedAttempts: Int = 0

    /**
     * How far this item's frame got on the CURRENT dispatch. Deliberately
     * outside the constructor, for the same reason as [failedAttempts].
     *
     * See [FrameState]; written by [QueueController.dispatchOne] and by
     * the `onSent` callback it hands to the host's RPC, read by
     * [QueueController.drainOnClose].
     */
    @Volatile
    var frameState: FrameState = FrameState.NOT_ATTEMPTED

    /**
     * The user took this message back before anything was written for it
     * ([QueueController.cancelQueued]). Terminal: a cancelled wrapper is
     * out of [QueueController]'s collections for good, its store record is
     * gone and its deferred is failed with [QueueCancelledException].
     * Outside the constructor for the same reason as [failedAttempts].
     *
     * Written and read under `stateLock` — that is what decides the race
     * with the dispatch coroutine (see [QueueController.tryBeginWrite])
     * and what keeps the coordinator's restore path from resurrecting a
     * withdrawn message. `@Volatile` on top of that only for the one
     * lock-free read, the early-out at the top of `dispatchOne`, which
     * must not act on a stale `false`.
     */
    @Volatile
    var cancelled: Boolean = false
}

/**
 * What a [QueuedCall]'s dispatch has actually done to the socket.
 *
 * The queue used to infer this from set membership — "in `inFlight`" was
 * read as "sent" — but items are claimed into that set before the
 * coordinator writes anything, so a `close()` landing mid-flush reported
 * never-written messages as delivery-unknown: their records were dropped
 * and their text bounced to the composer instead of being left for the
 * next client. This enum is that missing fact.
 *
 * [IN_PROGRESS] is the load-bearing state. `RemoteClient.close()` nulls
 * `transport` under `stateLock` and `callInternal` re-checks it under the
 * same lock, so no NEW frame can go out once [QueueController.drainOnClose]
 * has run — but a write that entered `sendMaybeCompressed` before that lock
 * was taken is still running, and its outcome is genuinely unknown. It is
 * therefore classified as on-the-wire: bouncing text the server may never
 * have seen is recoverable (the user gets it back in the composer), while
 * replaying a frame the server did apply posts the message twice, and there
 * is no undo for that.
 */
internal enum class FrameState {
    /** No frame was built for this item on this dispatch. */
    NOT_ATTEMPTED,

    /** Inside the write; accepted-or-refused is not yet known. */
    IN_PROGRESS,

    /** The transport refused the frame — provably nothing went out. */
    REFUSED,

    /**
     * The transport accepted the frame, i.e. the bytes left this process.
     * Not a claim that the peer received or applied it — that is exactly
     * the ambiguity [TransportLostException] exists for.
     */
    WRITTEN,
}

/**
 * Lifecycle events the [RemoteClient]'s loop coroutine consumes.
 *
 * Promoted to a top-level `internal` sealed interface in the M1 refactor
 * so [QueueController] can emit [QueueChanged] from a different file.
 * Sealed-subtype rule: every variant must live in the same package +
 * module as the parent, which holds here.
 */
internal sealed interface LifecycleEvent {
    data object UserClose : LifecycleEvent

    /**
     * @property transport the socket the event describes, or null when
     *   the poster can't identify it. The lifecycle loop uses it to
     *   discard events belonging to a socket it has already replaced —
     *   see [RemoteClient.awaitDisconnect].
     */
    data class TransportClosed(
        val failure: ConnectFailure,
        val transport: RemoteTransport? = null,
    ) : LifecycleEvent

    /** @property transport see [TransportClosed.transport]. */
    data class TransportFailure(
        val failure: ConnectFailure,
        val terminal: Boolean,
        val transport: RemoteTransport? = null,
    ) : LifecycleEvent

    data object QueueChanged : LifecycleEvent
}

/** Surface marker for TTL-expired queue items. */
class QueueTtlException : RuntimeException("queued call timed out")

/**
 * Raised to the caller of [RemoteClient.queueCall] when the user cancelled
 * the message before it was sent, via [RemoteClient.cancelQueued].
 *
 * Distinct from the other queue failures because the recovery is "do
 * nothing": the text was discarded on purpose, so unlike a bounce it must
 * not reappear in the composer, and unlike [TransportLostException] there
 * is no ambiguity about delivery — nothing was written.
 */
class QueueCancelledException : RuntimeException("queued call cancelled by the user")

/**
 * Raised for a queue record that a PREVIOUS process already put on a
 * socket, when no live connection can prove the desktop would absorb the
 * repeat.
 *
 * The desktop de-duplicates by `spk_client_send_id`, but its table is
 * in-memory and per-process: a replay is only free while the peer is the
 * same editor process ([WireFeature.CSID_DEDUPE] +
 * `ServerFeatures.serverInstanceId`) and the claim is still inside
 * `csid_dedupe_window_ms`. Fail either half and re-sending would post the
 * user's message twice, so the text goes back to the composer instead —
 * the same recovery as every other queue abandon.
 *
 * Never raised for a record that was never handed to a transport: that one
 * is a first delivery, and replaying it is the whole point of the durable
 * offline queue.
 */
class ReplayNotSafeException : RuntimeException(
    "queued call was already written to a previous editor process and cannot be safely replayed",
)
