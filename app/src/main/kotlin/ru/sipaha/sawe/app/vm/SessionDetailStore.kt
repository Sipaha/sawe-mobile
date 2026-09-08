package ru.sipaha.sawe.app.vm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import android.net.Uri
import ru.sipaha.sawe.app.data.AttachmentDraftRepository
import ru.sipaha.sawe.app.data.AttachmentRef
import ru.sipaha.sawe.app.data.CachedSessionHistory
import ru.sipaha.sawe.app.data.DraftRepository
import ru.sipaha.sawe.app.data.PendingSendsRepository
import ru.sipaha.sawe.app.data.PersistedPendingAttachment
import ru.sipaha.sawe.app.data.PersistedPendingSend
import ru.sipaha.sawe.app.data.SessionHistoryRepository
import ru.sipaha.sawe.core.AgentSessionContextResetPayload
import ru.sipaha.sawe.core.ContentBlockDto
import ru.sipaha.sawe.core.Digests
import ru.sipaha.sawe.core.EntryImage
import ru.sipaha.sawe.core.EntryRoleDto
import ru.sipaha.sawe.core.EntrySummary
import ru.sipaha.sawe.core.FrameRefusedException
import ru.sipaha.sawe.core.FrameTooLargeException
import ru.sipaha.sawe.core.MessageRejectedException
import ru.sipaha.sawe.core.TransportLostException
import ru.sipaha.sawe.core.QueuedBundleSummary
import ru.sipaha.sawe.core.SessionActiveSubagentsChangedPayload
import ru.sipaha.sawe.core.SessionQueueChangedPayload
import ru.sipaha.sawe.core.StreamDto
import ru.sipaha.sawe.core.StreamIdDto
import ru.sipaha.sawe.core.SessionStateDto
import ru.sipaha.sawe.core.GetSessionChangesResult
import ru.sipaha.sawe.core.JsonRpc
import ru.sipaha.sawe.core.JsonRpcResponse
import ru.sipaha.sawe.core.KnownEntryDto
import ru.sipaha.sawe.core.GetSessionResult
import ru.sipaha.sawe.core.MessageAppendedPayload
import ru.sipaha.sawe.core.QueueCancelledException
import ru.sipaha.sawe.core.QueueTtlException
import ru.sipaha.sawe.core.QueuedMessage
import ru.sipaha.sawe.core.RemoteClient
import ru.sipaha.sawe.core.ResetContextResult
import ru.sipaha.sawe.core.SupervisorStateDto
import ru.sipaha.sawe.core.SessionDeltaState
import ru.sipaha.sawe.core.SendDeliveryDto
import ru.sipaha.sawe.core.SendMessageBlocksResult
import ru.sipaha.sawe.core.ServerFeatures
import ru.sipaha.sawe.core.StartCompactResult
import ru.sipaha.sawe.core.WireFeature
import ru.sipaha.sawe.core.applySessionDelta
import ru.sipaha.sawe.core.buildKnownEntries
import ru.sipaha.sawe.core.BodyRehydration
import ru.sipaha.sawe.core.isTailAnchoredWindow
import ru.sipaha.sawe.core.parseExpiredSendMessage
import ru.sipaha.sawe.core.putOmitPreviewWhenMarkdown
import ru.sipaha.sawe.core.reconcileOptimistic
import ru.sipaha.sawe.core.rehydrateEntryBodies
import ru.sipaha.sawe.core.stampClientSendId
import ru.sipaha.sawe.core.withOptimisticStopping
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/** Page size for [SessionDetailStore.openSession] / [SessionDetailStore.loadOlder]. */
private const val SESSION_PAGE_SIZE = 50

/**
 * Cadence of the tail-resync poll — the safety net for a lost (and
 * unsequenced) `agent_session_message_appended` on a live socket. Short
 * enough that a stranded reply surfaces within a few seconds; runs while
 * the agent produces and for a brief trailing window after it stops, so a
 * long-idle session eventually costs nothing.
 */
private const val TAIL_RESYNC_INTERVAL_MS: Long = 4_000L

/**
 * How many resync ticks to keep running after the agent stops producing.
 * Covers the stranded-final-message case (the lost notification that has
 * no follow-up): ~[TAIL_RESYNC_TRAILING_TICKS] × [TAIL_RESYNC_INTERVAL_MS]
 * of catch-up after Idle, then the poller goes quiet.
 */
private const val TAIL_RESYNC_TRAILING_TICKS: Int = 3

/**
 * Per-attachment STALL timeout for deferred-send: how long the upload may go
 * without a single additional byte being acked before we give up on it.
 * `UploadManager` has its own 30 s per-ack timeout that flips to Paused
 * (= "waiting for server"), but Paused isn't terminal — without this outer
 * guard a permanently-stuck upload would hold the optimistic bubble in
 * "Uploading" forever.
 *
 * Measured from the LAST progress, not from the start of the wait: a 40 MB
 * attachment on a slow link makes steady progress for far longer than five
 * minutes, and a wall-clock deadline killed exactly those healthy transfers
 * (and with them, in the old code, the user's text). Five minutes of complete
 * silence, by contrast, means the transfer really is dead.
 */
private const val DEFERRED_UPLOAD_STALL_TIMEOUT_MS: Long = 5L * 60_000L

/**
 * How often the deferred-send waiter re-evaluates the stall deadline while an
 * upload is quiet. Bounds the latency of noticing a stall without polling
 * hard; progress itself arrives as a `StateFlow` emission, not on this tick.
 */
private const val DEFERRED_UPLOAD_PROGRESS_TICK_MS: Long = 1_000L

/**
 * Trailing-edge debounce window for the live delta poll
 * ([SessionDetailStore.scheduleDeltaPoll]). Push notifications no longer
 * write session state directly — they only TRIGGER a poll, and a burst of
 * notifications (e.g. ~5 `message_appended`/s while the agent streams a
 * long reply, plus an interleaved state/queue change) collapses into ONE
 * `get_session_changes` round-trip per quiet window. Short enough that a
 * streaming reply still feels live; long enough that a tight burst doesn't
 * fan out into N redundant requests (the very fan-out this phase removes).
 */
private const val DELTA_POLL_DEBOUNCE_MS: Long = 200L

// Retry budget for the delta poll loop. ONLY a failed poll (transport error /
// reconnect window) consumes it; a poll that came back resets it, so a
// far-behind session can drain arbitrarily many `has_more` pages while a dead
// link still gives up instead of spinning — the next notification /
// tail-resync / reconnect retries.
private const val POLL_MIN_BACKOFF_MS: Long = 150L
private const val POLL_MAX_BACKOFF_MS: Long = 2_000L
private const val POLL_MAX_FAILURES: Int = 15

/**
 * Floor delay between two SUCCESSFUL iterations of the delta poll loop
 * ([SessionDetailStore.runDeltaPollLoop]). The loop re-polls when the server
 * paginated (`has_more`) or when a push arrived while the previous poll was on
 * the wire; without a floor those iterations run back-to-back at 1/RTT — on a
 * 60 ms LAN that is ~15 `get_session_changes`/s, each carrying the full body of
 * every entry the agent touched. The floor caps a re-poll burst at ~5 req/s
 * while still draining a paginated catch-up quickly.
 */
private const val POLL_FLOOR_DELAY_MS: Long = 200L

/**
 * How long an ambiguous send waits for the socket to come back and
 * re-negotiate before its text is bounced to the composer.
 *
 * The §4.6 replay may only go out on a connection that proves it is the same
 * editor process the send was dispatched to, and that proof arrives with the
 * capabilities probe on the next `Connected` edge. Waiting for it is what
 * makes the replay safe; waiting for it *indefinitely* would leave the user
 * with a bubble that says "Sending" for as long as they are offline, which is
 * strictly worse than the pre-feature behaviour of handing the text straight
 * back. Sized to cover a lift / lock-screen blip and the reconnect ladder's
 * first couple of rungs, not an evening without signal.
 */
private const val REPLAY_RENEGOTIATE_TIMEOUT_MS: Long = 30_000L

/**
 * How many user entries the image backfill may request in one pass before
 * yielding.
 *
 * The wire carries no per-entry image hint, so every user entry in the window
 * has to be asked about individually — and the desktop dispatches requests
 * inline and strictly in order, so a burst of them head-of-line blocks the
 * delta poll behind it. Draining a 50-entry window's ~20 user entries in one
 * go was up to 20 serial round-trips before the chat could sync again. A small
 * batch, newest first, gets the photos the user is actually looking at while
 * leaving the connection responsive; the rest follow on later passes.
 */
private const val IMAGE_BACKFILL_BATCH: Int = 4

/** Pause between backfill passes, so the delta poll gets the wire back. */
private const val IMAGE_BACKFILL_BATCH_DELAY_MS: Long = 750L

/**
 * How many times one entry's image fetch may fail before it is retired for the
 * rest of this open. Without a cap a permanently-failing entry would be
 * retried by every pass forever; with it, the next `openSession` still gives it
 * a fresh chance.
 */
private const val IMAGE_FETCH_MAX_ATTEMPTS: Int = 3

/**
 * Cadence of the SLOW belt-and-suspenders safety-net poll for the
 * currently-open session detail. The push path (`agent_session_dirty` →
 * convergence) plus the short trailing [TAIL_RESYNC_INTERVAL_MS] window cover
 * the common cases, but a session that has sat Idle for a while (its trailing
 * budget spent) does NOT poll at all — so if EVERY push for the final turn was
 * dropped, the open conversation would strand until the user interacts. This
 * far-apart tick re-polls the OPEN session roughly once a minute so a missed
 * push self-heals within ~[SAFETY_NET_POLL_INTERVAL_MS] without a user send.
 *
 * Deliberately coarse: it only fires when no live/convergence delta poll is
 * already in flight (it coalesces with the push path rather than fighting it),
 * and a lightweight `get_session_changes` from the held cursor is cheap when
 * nothing changed (empty `changed_entries`).
 */
private const val SAFETY_NET_POLL_INTERVAL_MS: Long = 60_000L

/**
 * Pure coalescing decision for the periodic [SAFETY_NET_POLL_INTERVAL_MS]
 * safety-net tick, extracted so it is unit-testable without standing up the
 * whole store. A tick should trigger a delta poll ONLY when:
 *   - the detail for [tickSessionId] is still the open one (`openSessionId`),
 *     so we never poll a closed / switched-away session, and
 *   - no live/convergence delta poll is already in flight — if one is, the
 *     push path is already converging and the tick must be a no-op so it can't
 *     double-apply or cancel an in-progress convergence loop.
 */
internal fun shouldSafetyNetPoll(
    tickSessionId: String,
    openSessionId: String?,
    deltaPollInFlight: Boolean,
): Boolean = tickSessionId == openSessionId && !deltaPollInFlight

/**
 * Pure convergence rule for the delta poll loop, extracted so it is
 * unit-testable without standing up the whole store.
 *
 * A poll that came back with `has_more == false` is CAUGHT UP: the server
 * computed the page from `entry.mod_seq > since_seq` over the selected stream
 * and, having nothing left over, handed out that stream's own `seq` as
 * `current_seq` — a re-poll from there provably returns nothing. The small
 * sections (`state`, `pending_bundles`, `streams`) ride every response
 * unconditionally, so they are current too.
 *
 * The loop therefore runs again only when
 *  - the server paginated ([hasMore]) — there is a next page from the advanced
 *    cursor, or
 *  - a push arrived while the poll was on the wire ([repollRequested]) — that
 *    change is by definition newer than the response we just applied.
 *
 * What it deliberately does NOT do is chase the `current_seq` carried by
 * `agent_session_dirty`. That value is the SESSION-GLOBAL `change_seq`, which
 * a per-stream cursor can be permanently below (the last dirty of every turn
 * carries `Main.seq + 1` because the `→Idle` transition bumps `change_seq`
 * with no entry behind it) — comparing the two made the target unreachable and
 * span the loop at 1/RTT for as long as the chat stayed open.
 */
internal fun shouldRepollAfterSuccess(hasMore: Boolean, repollRequested: Boolean): Boolean =
    hasMore || repollRequested

/**
 * What a freshly-arrived delta can be applied AS, once its append-only entry
 * bodies have been spliced back onto the held ones.
 */
internal sealed interface DeltaApplyPlan {
    /**
     * Apply [delta] — every entry in it now carries a whole `markdown` again
     * (or none at all, for a body-less page). Identical to the incoming delta
     * when the server sent no tails.
     */
    data class Apply(val delta: GetSessionChangesResult) : DeltaApplyPlan

    /**
     * The delta cannot be spliced onto what is held — a protocol violation,
     * or a base body this client no longer has. [reason] is for the log.
     *
     * The caller must full-reload and publish NOTHING from this delta: a
     * partially-spliced window is a silently corrupted transcript, which is
     * strictly worse than one extra `get_session`.
     */
    data class FullReload(val reason: String) : DeltaApplyPlan
}

/**
 * Decide how to apply [delta] against the currently [held] window, given the
 * digests this client [offered] on the poll that produced it.
 *
 * ### The invariant this enforces, and why it needs enforcing
 *
 * A splice is only sound if the bytes it appends to are the bytes the server
 * verified. Those are two different reads of `_session.value.entries`
 * separated by a full round trip: the digests go out from a snapshot taken at
 * the top of the poll iteration, the splice runs against whatever is held
 * when the response lands. **No writer may change an existing entry's
 * `markdown` in between** — and nothing about the wire can notice if one
 * does, because the server always sets `markdown_len == prefix_len +
 * tail.length`, so the length check in [rehydrateEntryBodies] passes for ANY
 * base of the right length. A stale or foreign base of equal length would be
 * spliced silently and written to `_session`, to the disk cache, and into the
 * next poll's digest.
 *
 * So the invariant is re-checked here rather than merely documented: for every
 * entry the server says it spliced, the held prefix is re-digested and
 * compared against the digest we actually sent for that index. One SHA-256 per
 * spliced entry — the same order of cost [buildKnownEntries] already pays —
 * and it converts an undetectable corruption into the full reload that already
 * exists. A new writer that breaks the invariant now trips
 * [DeltaApplyPlan.FullReload] instead of corrupting the transcript.
 *
 * It also closes two gaps in the wire itself:
 *  - a `markdown_prefix_len` for an index this client never offered is a
 *    protocol violation (§2.5: the server may only splice what the caller
 *    described), and would otherwise be spliced against an unverified base;
 *  - a `markdown_tail` with no `markdown_prefix_len` beside it is checked
 *    here for the whole page. [rehydrateEntryBodies] short-circuits when NO
 *    entry on the page carries a `prefix_len`, so on such a page it would let
 *    the orphan tails through and the entries would reach the UI with
 *    `markdown == null`, i.e. as empty bubbles.
 *
 * [offered] empty means "we asked for no deltas" — the legacy path, and the
 * common one. Then any `markdown_prefix_len` at all is unsolicited.
 */
internal fun planDeltaApply(
    held: List<EntrySummary>,
    delta: GetSessionChangesResult,
    offered: List<KnownEntryDto> = emptyList(),
): DeltaApplyPlan {
    verifySpliceBases(held, delta, offered)?.let { return DeltaApplyPlan.FullReload(it) }
    return when (val rehydrated = rehydrateEntryBodies(held, delta)) {
        is BodyRehydration.Ok -> DeltaApplyPlan.Apply(rehydrated.delta)
        is BodyRehydration.Broken -> DeltaApplyPlan.FullReload(rehydrated.reason)
    }
}

/**
 * Re-prove that every base [delta] was spliced against is still the base this
 * client described in [offered]. Returns the reason to full-reload, or `null`
 * when the page is safe to splice.
 *
 * Cheap exit first: a page carrying neither a `prefix_len` nor a `tail`
 * anywhere is the ordinary whole-body response and costs one scan.
 */
private fun verifySpliceBases(
    held: List<EntrySummary>,
    delta: GetSessionChangesResult,
    offered: List<KnownEntryDto>,
): String? {
    if (delta.changedEntries.none { it.markdownPrefixLen != null || it.markdownTail != null }) {
        return null
    }
    val offeredByIndex = offered.associateBy { it.index }
    val heldByIndex = held.associateBy { it.index }
    for (entry in delta.changedEntries) {
        val prefixLen = entry.markdownPrefixLen
        if (prefixLen == null) {
            // A tail with nothing saying where it goes. Caught here because
            // `rehydrateEntryBodies` would not look at this page at all if no
            // OTHER entry on it carried a `prefix_len`.
            if (entry.markdownTail != null) return "tail without prefix_len at ${entry.index}"
            continue
        }
        val offer = offeredByIndex[entry.index]
            ?: return "unsolicited prefix_len at ${entry.index}"
        if (offer.markdownLen != prefixLen) {
            // The server echoes the caller's own `markdown_len` back as
            // `markdown_prefix_len` (§2.4). A different value means it
            // verified something other than what we described.
            return "prefix_len ${prefixLen} does not echo offer ${offer.markdownLen} at ${entry.index}"
        }
        val base = heldByIndex[entry.index]?.markdown
            ?: return "no held body for ${entry.index}"
        val baseBytes = base.toByteArray(Charsets.UTF_8)
        if (prefixLen < 0L || prefixLen > baseBytes.size) {
            return "prefix_len out of range at ${entry.index}"
        }
        if (Digests.bodyDigest(baseBytes.copyOf(prefixLen.toInt())) != offer.markdownHash) {
            // The held body changed under us between the offer and the
            // splice. The wire cannot detect this; only this check can.
            return "base changed under index ${entry.index} since the digest was offered"
        }
    }
    return null
}

/**
 * Admission gate for the single-flight delta poll.
 *
 * `running` and `repollRequested` are a check-then-act pair and must be read
 * and written together, which is the whole reason this is a class rather than
 * two fields. A poke that arrives while a poll is on the wire is recorded
 * ([admit] returning false) instead of cancelling it — cancelling was N-29(b),
 * where the server executed and transmitted every cancelled response anyway
 * and the cursor never advanced. But the poll also has to be able to *stop*,
 * and the instant between "the loop decided to return" and "the coroutine
 * completed" is a window in which the poke would be handed to a loop that will
 * never read it again. `Job.isActive` cannot close that window — a job stays
 * active throughout it — and marking the flag `@Volatile` fixes visibility,
 * not the race.
 *
 * So the loop publishes its stop decision through [continueOrFinish], under the
 * same lock that [admit] tests. Every poke then has exactly two possible
 * outcomes: it is picked up by the running loop, or it starts a new one.
 */
internal class DeltaPollGate {

    private val lock = Any()
    private var running = false
    private var repollRequested = false

    /** True while a loop is running and still willing to consume a request. */
    fun isRunning(): Boolean = synchronized(lock) { running }

    /**
     * Ask to poll. Returns true when the caller must start the loop, false
     * when a running loop has taken ownership of the request.
     */
    fun admit(): Boolean = synchronized(lock) {
        if (running) {
            repollRequested = true
            false
        } else {
            running = true
            repollRequested = false
            true
        }
    }

    /**
     * End-of-iteration decision. Consumes any pending request and, when the
     * answer is "stop", marks the gate closed in the same critical section so
     * a concurrent [admit] starts a fresh loop rather than filing a request
     * nobody will read.
     *
     * @return true to run another iteration.
     */
    fun continueOrFinish(hasMore: Boolean): Boolean = synchronized(lock) {
        val requested = repollRequested
        repollRequested = false
        val more = shouldRepollAfterSuccess(hasMore = hasMore, repollRequested = requested)
        if (!more) running = false
        more
    }

    /**
     * Abnormal exit — the loop returned without going through
     * [continueOrFinish] (session switch, no client, failure budget spent,
     * cancellation). Closes the gate and reports whether a request came in
     * that nobody has served.
     *
     * @return true when the caller must re-arm.
     */
    fun finishAndShouldRearm(): Boolean = synchronized(lock) {
        if (!running) return@synchronized false
        running = false
        repollRequested.also { repollRequested = false }
    }

    /**
     * Hard close for the cancel paths. Called BEFORE the job is cancelled so
     * its `finally` cannot re-arm a poll for a session or stream we are
     * abandoning.
     */
    fun reset() = synchronized(lock) {
        running = false
        repollRequested = false
    }
}

/**
 * Recover the `spk_client_send_id` a queued `send_message_blocks` was stamped
 * with, from `params.blocks[0]._meta.spk_client_send_id`.
 *
 * The queue's terminal paths (TTL expiry, poison-pill abandon) hand back a
 * [QueuedMessage], not a csid — but the pending-send marker that mirrors it on
 * disk is keyed BY csid. Without this the marker outlived every terminal path
 * and `openSession` re-materialised it as a permanent phantom "Sending" bubble
 * (N-03).
 *
 * Returns null for the legacy text-only `send_message` method (never stamped)
 * and for any payload shape that doesn't carry the stamp.
 */
internal fun parseQueuedClientSendId(message: QueuedMessage): Long? =
    clientSendIdInSendParams(message.params)

/**
 * The `spk_client_send_id` a `send_message_blocks` params object carries on
 * the wire, or `null` if it carries none.
 *
 * Reads the stamp back out of the serialised payload rather than trusting the
 * caller's local variable, because it is the payload the server sees that
 * decides whether the id is claimed at all. A send that reaches the server
 * unstamped is answered `delivery: accepted` and is NOT entered into the
 * dedupe table — so "the response said accepted" is not evidence the table
 * holds anything, and replaying such a send would post it twice.
 */
internal fun clientSendIdInSendParams(params: kotlinx.serialization.json.JsonElement?): Long? {
    val obj = params as? JsonObject ?: return null
    val blocks = obj["blocks"] as? JsonArray ?: return null
    val first = blocks.firstOrNull() as? JsonObject ?: return null
    val meta = first["_meta"] as? JsonObject ?: return null
    return (meta["spk_client_send_id"] as? JsonPrimitive)?.content?.toLongOrNull()
}

/**
 * Whether a persisted pending-send marker may still be shown as a "Sending"
 * bubble when its session is opened.
 *
 * A marker is only meaningful while the send it names can still happen. It can
 * still happen while the message sits in the durable offline queue
 * ([queuedCsids]) or while a send coroutine in THIS process owns it
 * ([inFlightCsids] — a message that has not reached the queue yet has no entry
 * there). Anything else is an orphan left behind by a process death: the
 * coroutine that would have removed it is gone, so re-materialising it painted
 * a bubble that no echo could ever resolve and that came back on every open.
 *
 * [queuedCsids] is null when nothing has published the queue contents yet, in
 * which case we keep every marker — guessing wrong here erases a message the
 * user is still waiting on.
 */
internal fun shouldMaterialiseMarker(
    csid: Long,
    queuedCsids: Set<Long>?,
    inFlightCsids: Set<Long>,
): Boolean = queuedCsids == null || csid in queuedCsids || csid in inFlightCsids

/**
 * What the send-failure path owes the user for a given [throwable].
 *
 * Splitting bounce-vs-clear out of the call site is what makes "no user text
 * is ever lost, and no text is ever handed back twice" checkable: see
 * [classifySendFailure].
 */
internal enum class SendFailureAction {
    /**
     * Return the typed text to the compose draft — nothing was delivered and
     * nobody else has handed it back.
     */
    Bounce,

    /**
     * Drop the optimistic bubble and the disk marker, but do NOT touch the
     * draft: either the text was already bounced by the queue's own
     * `onMessageExpired`, or bouncing would race a delivery that is still
     * going to happen.
     */
    ClearOnly,

    /**
     * Leave everything in place. The message is still on disk in the offline
     * queue and the NEXT client for this server will send it; clearing the
     * marker would erase the bubble for a message that is about to arrive,
     * and bouncing would let the user send it twice.
     */
    KeepQueued,

    /**
     * Re-park the IDENTICAL call — same queue id, same
     * `spk_client_send_id` — and let it go out again. The bubble, the draft
     * and the disk marker are all untouched.
     *
     * Only ever chosen when the peer proved it de-duplicates by
     * `spk_client_send_id` and is still the same editor process
     * ([canRequeueAmbiguousSend]); a replay that the server has already
     * applied comes back as `delivery: duplicate` and enqueues nothing. Any
     * doubt about either fact degrades to [Bounce], which is what every
     * ambiguous send did before the feature existed.
     */
    Requeue,
}

/**
 * The four facts §4.6 of the wire spec needs before an ambiguous send may be
 * replayed instead of bounced to the composer.
 *
 * All of them are about the *server's* dedupe table, which is in-memory and
 * per-process: it exists only while a peer advertises
 * [WireFeature.CSID_DEDUPE], it is emptied by a desktop restart (signalled by
 * a fresh `server_instance_id`), and an entry ages out of it after
 * `csid_dedupe_window_ms`.
 *
 * @property featuresAtDispatch what the peer advertised on the connection
 *   this send was handed to. NOT the live set — by the time a transport loss
 *   is observed the live set has already been reset to
 *   [ServerFeatures.NONE], and reading that would make the retry
 *   unreachable.
 * @property currentServerInstanceId the `server_instance_id` of the
 *   connection that would actually carry the replay, resolved by the caller
 *   (which may have to wait for the socket to come back and re-negotiate).
 *   `null` means "no negotiated connection to replay on", which is a refusal.
 * @property dispatchedAtMs wall clock when the send went out.
 * @property nowMs wall clock at the decision.
 * @property requeuesUsed how many replays this send has already had.
 * @property stampedOnTheWire whether the serialised params actually carry a
 *   `_meta.spk_client_send_id`. The server claims ids, not calls: an
 *   unstamped send is answered `delivery: accepted` and entered into nothing,
 *   so the response alone is never evidence that a replay would be
 *   recognised. Read back off the payload by [clientSendIdInSendParams].
 */
internal data class SendRetryContext(
    val featuresAtDispatch: ServerFeatures = ServerFeatures.NONE,
    val currentServerInstanceId: String? = null,
    val dispatchedAtMs: Long = 0L,
    val nowMs: Long = 0L,
    val requeuesUsed: Int = 0,
    val stampedOnTheWire: Boolean = false,
) {
    companion object {
        /**
         * Context that can never permit a replay. The default for every
         * caller that isn't making the §4.6 decision, so "no context" reads
         * as "behave exactly as before this feature existed".
         */
        val NONE = SendRetryContext()

        /**
         * Ceiling on replays of one message.
         *
         * The window check alone would already terminate the loop, but only
         * after up to 24 h of a bubble that says "Sending". A payload the
         * desktop chokes on every time (it dies mid-RTT, we replay, it dies
         * again) has to end up back in the composer where the user can see
         * and edit it, and three round trips is enough to ride out a flapping
         * link without becoming that.
         */
        const val MAX_REQUEUES: Int = 3
    }
}

/**
 * Whether an ambiguous send may be replayed under [ctx] — all four §4.6
 * conditions, none of them defaulted to "yes".
 *
 * Every `return false` here is a degradation to today's behaviour
 * ([SendFailureAction.Bounce]): the text goes back to the composer and the
 * user decides. That is the safe side, because the failure mode on the other
 * side is a message the user sees twice in their transcript.
 *
 * Deliberately does NOT consult [SendMessageBlocksResult.delivery]. It is not
 * available where it matters — the decision is made on a failure, where there
 * is no result to read — and it would not be evidence if it were: the server
 * claims ids, so `accepted` for an unstamped send claims nothing, and both
 * `null` (server predates dedupe) and [SendDeliveryDto.Unknown] (server newer
 * than this build) say only "do not rely on dedupe".
 */
internal fun canRequeueAmbiguousSend(ctx: SendRetryContext): Boolean {
    // (0) there is an id for the server to have claimed in the first place.
    if (!ctx.stampedOnTheWire) return false
    val dispatch = ctx.featuresAtDispatch
    // (1) the peer we sent to advertised csid dedupe…
    if (!dispatch.has(WireFeature.CSID_DEDUPE)) return false
    // …and named the process holding the table.
    val dispatchInstance = dispatch.serverInstanceId ?: return false
    // (2) the connection that would carry the replay is that same process.
    if (ctx.currentServerInstanceId != dispatchInstance) return false
    // (3) the claim has not aged out of the server's window.
    val window = dispatch.csidDedupeWindowMs ?: return false
    val age = ctx.nowMs - ctx.dispatchedAtMs
    if (age < 0L || age >= window) return false
    return ctx.requeuesUsed < SendRetryContext.MAX_REQUEUES
}

/**
 * Whether a queue record restored from disk by THIS process may go back on
 * the wire — `QueueController`'s cross-process replay gate.
 *
 * The in-process replay ([canRequeueAmbiguousSend]) and this one answer the
 * same question about the same server-side table, so this is deliberately a
 * thin adapter onto that policy rather than a second copy of it: reconstruct
 * the dispatch-time context from what the queue record persisted, then defer.
 * The two paths cannot drift.
 *
 * **A record with no [QueuedMessage.attempt] is always replayable.** Nothing
 * was ever written for it — it is a message the user typed while offline and
 * the process died before a socket existed. Sending it now is a FIRST
 * delivery, not a repeat, and letting it through is the entire point of the
 * durable offline queue. Refusing here would bounce typed-offline text to the
 * composer on every cold start.
 *
 * @param live what the connection that would carry the replay negotiated.
 * @param nowMs injectable clock; wall clock by default, because the dedupe
 *   window is measured against the server's own wall clock.
 */
internal fun canReplayRehydratedSend(
    message: QueuedMessage,
    live: ServerFeatures,
    nowMs: Long = System.currentTimeMillis(),
): Boolean {
    val attempt = message.attempt ?: return true
    // `instanceId` is null when the dispatch-time peer advertised no dedupe
    // (or had not answered its capabilities probe yet). Nothing to match
    // against, so there is no proof and no reconstruction worth building.
    val dispatchInstance = attempt.instanceId ?: return false
    return canRequeueAmbiguousSend(
        SendRetryContext(
            // A faithful reconstruction of the dispatch-time feature set from
            // the two facts the record kept. The token is implied: the queue
            // only ever stamps a non-null instance id when the peer it was
            // writing to advertised CSID_DEDUPE. The window is read off the
            // LIVE peer, which is sound precisely because the instance check
            // below has to pass first — same process, therefore the same
            // configured window.
            featuresAtDispatch = ServerFeatures(
                tokens = setOf(WireFeature.CSID_DEDUPE),
                serverInstanceId = dispatchInstance,
                csidDedupeWindowMs = live.csidDedupeWindowMs,
            ),
            // A peer that does not advertise dedupe holds no table, so its
            // instance id must not be allowed to match the reconstruction
            // even if the desktop process happens to be the same one.
            currentServerInstanceId = live.serverInstanceId
                .takeIf { live.has(WireFeature.CSID_DEDUPE) },
            dispatchedAtMs = attempt.atMs,
            nowMs = nowMs,
            // Each process start gets one shot. The replay budget exists to
            // bound a flapping link inside a single send coroutine; here the
            // dedupe window is the real bound, and there is no coroutine and
            // no "Sending" bubble left over from the previous process to
            // protect the user from.
            requeuesUsed = 0,
            stampedOnTheWire = parseQueuedClientSendId(message) != null,
        ),
    )
}

/**
 * A `send_message_blocks` that came back with a JSON-RPC `error` envelope
 * rather than a result — a desktop-proxy failure, or the proxy's own 30 s
 * `CALL_TIMEOUT`.
 *
 * Deliberately its own type rather than a bare `IllegalStateException`,
 * because the recovery is the same as [TransportLostException] and the
 * opposite of a tool-level refusal: a timed-out proxy call may well have been
 * executed by the editor and simply not answered in time, so the send must not
 * be treated as "definitely didn't happen". [raw] is kept for logs; it is
 * internal wording and is never shown to the user.
 */
internal class SendEnvelopeException(val raw: String) :
    RuntimeException("send failed on the editor link: $raw")

/**
 * The `send_message_blocks` tool itself refused the call (a `toolError` in an
 * otherwise well-formed response) — e.g. the session no longer exists.
 *
 * Unambiguous, unlike [SendEnvelopeException]: the tool ran and declined, so
 * nothing was enqueued and retrying the identical payload cannot help. [detail]
 * comes from the tool and is short and user-facing.
 */
internal class SendRejectedByServerException(val detail: String) :
    RuntimeException(detail)

/**
 * Whether a failed deferred send should KEEP its uploaded attachments staged
 * for a retry, or release them.
 *
 * The uploads are already finished at this point, so the question is only
 * whether the handles still refer to anything. The server consumes an upload
 * record in `resolve_upload_handles`, which runs *inside* a SUCCESSFUL
 * `send_message_blocks` — so on a failed send the tmp file is still there for
 * its full TTL and the staged local copy is still on disk.
 *
 *  - Definitively refused (the tool declined, the payload is too big, the
 *    server rejected the frame) or long expired → release. Retrying that exact
 *    payload cannot work, and the server's four per-session upload slots
 *    should not be held for an hour by a send that will never happen.
 *  - Everything else — the socket died mid-RTT, the client was closed, the
 *    proxy timed out, an unrecognised failure → keep. The user attached a
 *    4 MB photo on LTE; making them re-pick and re-upload it because the
 *    response was lost is the exact loss N-04 was about, and on a link flaky
 *    enough to lose a response it is a loop.
 */
internal fun shouldKeepAttachmentsOnSendFailure(throwable: Throwable): Boolean = when (throwable) {
    is SendRejectedByServerException -> false
    is MessageRejectedException -> false
    is FrameTooLargeException -> false
    // 24 h in the queue; the server's upload slot expired many hours ago.
    is QueueTtlException -> false
    else -> true
}

/**
 * Decide what a failed `send_message_blocks` owes the user.
 *
 * [alreadyBouncedByQueue] is true when the queue already fired
 * `onMessageExpired` for this csid (TTL expiry, poison-pill abandon, 1009
 * rejection) — the text is in the bounce slot already and a second
 * `setBounced` would duplicate it in the composer, because
 * `DraftRepository.setBounced` appends rather than replaces.
 */
internal fun classifySendFailure(
    throwable: Throwable,
    alreadyBouncedByQueue: Boolean,
    retry: SendRetryContext = SendRetryContext.NONE,
): SendFailureAction = when {
    // The user withdrew this message themselves. The cancel path has already
    // decided what happens to the text; bouncing here would append a second
    // copy of it to the composer.
    throwable is QueueCancelledException -> SendFailureAction.ClearOnly
    // The queue owns the text now — it is either already bounced or about to be.
    alreadyBouncedByQueue -> SendFailureAction.ClearOnly
    throwable is QueueTtlException -> SendFailureAction.ClearOnly
    throwable is RemoteClient.ClosedException.StillQueued -> SendFailureAction.KeepQueued
    // Dropped without a trace: the text exists only in our memory.
    throwable is RemoteClient.ClosedException.NotQueued -> SendFailureAction.Bounce
    // Never reached the wire (client-side cap, refused frame) or was named by
    // the server as undeliverable (1009). Definitely not applied, and this
    // payload can never succeed — hand the text back so it can be trimmed.
    throwable is FrameTooLargeException -> SendFailureAction.Bounce
    throwable is MessageRejectedException -> SendFailureAction.Bounce
    throwable is FrameRefusedException -> SendFailureAction.Bounce
    // On the wire when the socket died: delivery UNKNOWN. Replayable only
    // against a peer that de-duplicates by `spk_client_send_id` and is still
    // the same editor process; otherwise the text goes back to the composer,
    // because posting it twice is worse than making the user press Send again.
    throwable is TransportLostException -> requeueOrBounce(retry)
    // The tool ran and declined — nothing was enqueued.
    throwable is SendRejectedByServerException -> SendFailureAction.Bounce
    // An error envelope from the editor link, including the proxy's 30 s
    // timeout: same unknown-delivery shape as a lost transport.
    throwable is SendEnvelopeException -> requeueOrBounce(retry)
    else -> SendFailureAction.Bounce
}

/** The §4.6 branch: replay when [canRequeueAmbiguousSend], else today's bounce. */
private fun requeueOrBounce(retry: SendRetryContext): SendFailureAction =
    if (canRequeueAmbiguousSend(retry)) SendFailureAction.Requeue else SendFailureAction.Bounce

/**
 * Read a `send_message_blocks` response, turning the two failure shapes into
 * the typed exceptions the failure policy keys off and everything else into
 * the tool's own structured result.
 *
 * A `delivery: duplicate` is a **success**: the server recognised the
 * `spk_client_send_id` of a message it had already accepted and did nothing,
 * which is precisely the outcome a replay is supposed to have. The caller
 * retires the disk marker and keeps the optimistic bubble — the first copy's
 * transcript echo pops it by csid, exactly as it would have without the
 * replay.
 *
 * The `delivery` field has THREE non-`Duplicate` readings, and none of them
 * may be collapsed into one another:
 *
 *  - [SendDeliveryDto.Accepted] — this call enqueued the message.
 *  - `null` — a missing or undecodable `structuredContent`: this server
 *    predates the field entirely.
 *  - [SendDeliveryDto.Unknown] — a verdict newer than this build. The enum's
 *    lenient serializer exists precisely so this DEGRADES: an unrecognised
 *    string used to abort the decode of the whole result, which the send path
 *    reported as a failed send, turning a message the server had accepted into
 *    an error toast and a bounced draft.
 *
 * All three are successes and none of them is evidence that the server holds
 * a dedupe claim for this csid — which is why the §4.6 replay decision
 * ([canRequeueAmbiguousSend]) is derived from the negotiated feature set and
 * the payload's own stamp, and never from `delivery`.
 */
internal fun interpretSendResponse(resp: JsonRpcResponse): SendMessageBlocksResult {
    resp.error?.let { throw SendEnvelopeException(it.message) }
    resp.toolError()?.let { throw SendRejectedByServerException(it) }
    val structured = resp.structuredContent() ?: return SendMessageBlocksResult()
    return runCatching {
        JsonRpc.json.decodeFromJsonElement(SendMessageBlocksResult.serializer(), structured)
    }.getOrDefault(SendMessageBlocksResult())
}

/**
 * User-facing explanation for a failed send, paired with [classifySendFailure].
 *
 * The send path suppresses the notice entirely for a [QueueCancelledException]
 * — there is no point telling the user they did the thing they just did — so
 * that branch exists only as a safe fallback for any future caller that does
 * surface it.
 */
internal fun sendFailureMessage(throwable: Throwable): String = when (throwable) {
    is QueueCancelledException -> "send cancelled"
    is QueueTtlException -> "send timed out — the editor was offline for too long"
    is RemoteClient.ClosedException.StillQueued -> "still queued — it'll send on the next connection"
    is RemoteClient.ClosedException.NotQueued -> "send cancelled — connection closed"
    is FrameTooLargeException, is MessageRejectedException ->
        "message is too large to send — shorten it or drop an attachment"
    // Delivery is genuinely unknown on both: the frame was on the wire, or the
    // editor answered with an error envelope — including its proxy's 30 s
    // timeout, by which point the send may already have been applied. Same
    // advice, and never the raw internal string: "opening local MCP proxy: …"
    // means nothing to the user and leaks implementation detail into a
    // snackbar.
    is TransportLostException, is SendEnvelopeException ->
        "connection lost while sending — check the chat before sending it again"
    // The tool's own wording ("session not found", …) is short and meaningful.
    is SendRejectedByServerException -> "couldn't send: ${throwable.detail}"
    else -> throwable.message ?: "send failed"
}

/**
 * Entries in [entries] that must have their inline images fetched separately.
 *
 * The transcript is pulled with `include_images = false` on every path, so
 * `EntrySummary.images` is `null` ("not requested") rather than `[]` ("none").
 * Only USER entries can carry inline base64 — the server flattens assistant /
 * tool-call image chunks to `spk-image://N` markdown at conversion time and
 * discards the blocks — so those are the only ones worth a per-entry fetch.
 *
 * [EntrySummary.imageCount] then narrows that to the entries that actually
 * have something to fetch, and its three states are all load-bearing:
 *
 *  - **a positive count** — probe; there are that many images to pull.
 *  - **`0`** — skip. The server has told us this entry has nothing inlineable,
 *    which is the overwhelming majority of user messages. Without this the
 *    client cannot tell a text-only message from one whose images were merely
 *    omitted, so every user entry costs a round trip on every open — a few
 *    hundred of them on a long session, on the very path N-30 exists to make
 *    cheap.
 *  - **`null`** — probe, exactly as before the field existed. `null` means
 *    "unknown", not "none": every currently-shipped desktop omits the field,
 *    and reading that as zero would silently switch the backfill off against
 *    all of them. This is why the DTO is nullable rather than defaulted to 0.
 *
 * The count survives the disk cache — `stripImages` drops the blobs but keeps
 * the count — so a cache hit still knows which entries are worth fetching
 * without having stored a single byte of image data.
 *
 * [resolved] holds the indices already claimed for the open session (fetched,
 * in flight, or retired), so an entry is fetched at most once per open even
 * though the server re-sends it on every `mod_seq` bump.
 */
internal fun entriesNeedingImageFetch(
    entries: List<EntrySummary>,
    resolved: Set<Int>,
): List<Int> = entries
    .filter {
        it.role == EntryRoleDto.User &&
            it.index >= 0 &&
            it.images == null &&
            it.imageCount != 0
    }
    .map { it.index }
    .filterNot { it in resolved }
    .distinct()

/**
 * Carry already-known images across a delta apply.
 *
 * A delta re-sends an entry in full whenever its `mod_seq` moves, and it is
 * fetched WITHOUT images — so the merged window would drop image payloads the
 * previous window already had, and the lazy fetch would re-download them. This
 * copies them forward: for every merged entry that carries no image section,
 * the same index's images from [previous] (when it had any) win.
 */
internal fun carryOverImages(
    previous: List<EntrySummary>,
    merged: List<EntrySummary>,
): List<EntrySummary> {
    if (previous.isEmpty()) return merged
    val known = previous.asSequence()
        .filter { it.index >= 0 && it.images != null }
        .associate { it.index to it.images }
    if (known.isEmpty()) return merged
    return merged.map { entry ->
        if (entry.images != null) entry else known[entry.index]?.let { entry.copy(images = it) } ?: entry
    }
}

/**
 * Combine the on-disk [draft] with a [bounced] message for the same session.
 *
 * A bounce and a draft are both text the user typed and has not sent, so
 * neither may win outright. The seed used to be the bounce alone, which meant
 * a message that TTL-expired overnight overwrote whatever the user had started
 * typing in the morning the moment they re-entered the chat — the newer text
 * was the one destroyed. Appending keeps both, with the bounced (older,
 * complete) message after the in-progress draft so the caret context the user
 * left behind stays at the top.
 */
internal fun mergeDraftSeed(draft: String, bounced: String): String = when {
    bounced.isBlank() -> draft
    draft.isBlank() -> bounced
    draft.contains(bounced) -> draft
    else -> "$draft\n\n$bounced"
}

/**
 * A message the outbound queue handed back, addressed to the session it was
 * typed in.
 *
 * Emitted on [SessionDetailStore.bouncedDrafts] so a chat that is ALREADY open
 * gets the text back the moment the queue gives up on it, instead of only on
 * the next [SessionDetailStore.loadDraftSeed] (i.e. the next time the screen is
 * opened). The disk bounce slot is written either way — the flow is the live
 * shortcut, not the source of truth.
 */
data class BouncedDraft(val sessionId: String, val text: String)

/**
 * The durable-queue id an outbound send is filed under.
 *
 * `RemoteClient.queueCall` mints a random UUID unless the caller names the
 * entry, and an id nobody can reproduce is an id nobody can cancel. Deriving it
 * from the `client_send_id` — the one handle the bubble, the pending-send
 * marker and the wire payload all already share — is what lets a cancel tap
 * name the exact queue entry behind the bubble, including after a process
 * restart, where the only surviving link between the two is that id.
 *
 * The `csid-` prefix keeps the value out of the UUID shape used by entries
 * queued by earlier builds, so the two namespaces can never collide.
 */
internal fun queueIdForClientSendId(csid: Long): String = "csid-$csid"

/**
 * The text a withdrawn send owes the composer, or null when there is nothing
 * to hand back.
 *
 * Read from the pending-send [markers] rather than the queued payload because
 * the marker holds the FULL typed body (the payload's first text block is the
 * same string, but an attachment-only send has no text block at all) and
 * because it is the record that survives the process death this has to work
 * across. A missing marker, or one with a blank body, means the cancel simply
 * has no text to restore — an attachment-only send — not that it should be
 * refused.
 */
internal fun withdrawnSendDraft(
    markers: List<PersistedPendingSend>,
    csid: Long,
): BouncedDraft? {
    val marker = markers.firstOrNull { it.csid == csid } ?: return null
    val text = marker.text?.takeIf { it.isNotBlank() } ?: return null
    return BouncedDraft(sessionId = marker.sessionId, text = text)
}

/**
 * What the user is told when a cancel arrives for a message that is no longer
 * withdrawable — its frame is being written, was written, or is on its way
 * back to the queue after a refusal.
 *
 * Deliberately does not claim the message was sent — nobody knows. It says the
 * cancel did not happen and points at the place where the truth will show up,
 * which is the transcript. Still the right words after `cancelQueued` learned
 * to take back claims whose frame never went out: what is left behind that
 * `false` is exactly the set of messages that are moving.
 */
internal const val CANCEL_TOO_LATE_MESSAGE: String =
    "Too late to cancel — it's already on its way. Check the chat before resending."

/**
 * Bookkeeping for the per-entry image backfill.
 *
 * The wire carries no "this entry has images" hint, so the client has to ask
 * per user entry — which makes *what has already been asked* the whole of the
 * correctness problem. Two properties matter and both live here rather than in
 * the coroutine that does the fetching:
 *
 *  1. **Nothing is ever claimed speculatively.** An index is claimed
 *     immediately before its own request and released the moment that request
 *     fails, so abandoning a batch half-way (session closed, tab switched,
 *     transport gone) cannot leave the untried remainder marked as done. The
 *     earlier version claimed the whole batch up front, so one failed probe on
 *     a flaky link made every photo behind it unreachable until the chat was
 *     closed and reopened.
 *  2. **A permanently failing entry stops costing round-trips.** After
 *     [maxAttempts] failures the index is retired for this open; the next
 *     `openSession` clears the tracker and it gets a fresh chance.
 *
 * Not thread-safe: the store touches it only from its own scope.
 */
internal class ImageBackfillTracker(private val maxAttempts: Int = IMAGE_FETCH_MAX_ATTEMPTS) {

    private val claimed = mutableSetOf<Int>()
    private val failures = mutableMapOf<Int, Int>()

    /** Indices that must not be requested again — resolved, in flight, or retired. */
    fun claimedIndices(): Set<Int> = claimed

    /**
     * Take exclusive ownership of [index] for one request. Returns false when
     * somebody else already holds it, in which case the caller must skip it.
     */
    fun claim(index: Int): Boolean = claimed.add(index)

    /** The request answered — keep the claim so the entry is never re-fetched. */
    fun settle(index: Int) {
        failures.remove(index)
    }

    /**
     * The request failed. Releases the claim so a later batch retries, until
     * [maxAttempts] is reached — past which the claim is kept and the entry is
     * retired for this open. Returns true when a retry is still allowed.
     */
    fun fail(index: Int): Boolean {
        val attempts = (failures[index] ?: 0) + 1
        failures[index] = attempts
        if (attempts >= maxAttempts) return false
        claimed.remove(index)
        return true
    }

    /**
     * Give the claim back untouched — the request was never made (no client,
     * session closed, tab switched). Costs no attempt: nothing was tried.
     */
    fun release(index: Int) {
        claimed.remove(index)
    }

    fun clear() {
        claimed.clear()
        failures.clear()
    }
}

/** Slot [images] into the entry at [index], leaving every other entry alone. */
internal fun withEntryImages(
    entries: List<EntrySummary>,
    index: Int,
    images: List<EntryImage>,
): List<EntrySummary> = entries.map {
    if (it.index == index) it.copy(images = images) else it
}

/**
 * Progress watermark for one attachment of a deferred send: the highest byte
 * count the server has acked so far and when it was observed.
 */
internal data class UploadProgressWatermark(
    val sentBytes: Long = -1L,
    val lastProgressAtMs: Long,
)

/**
 * Advance [previous] if [sentBytes] is genuinely new progress. A repeated or
 * lower count (a Paused state reports the last confirmed offset, a Failed one
 * reports zero) leaves the watermark — and therefore the stall deadline —
 * untouched.
 */
internal fun trackUploadProgress(
    previous: UploadProgressWatermark,
    sentBytes: Long,
    nowMs: Long,
): UploadProgressWatermark =
    if (sentBytes > previous.sentBytes) {
        UploadProgressWatermark(sentBytes = sentBytes, lastProgressAtMs = nowMs)
    } else {
        previous
    }

/**
 * True when nothing has moved for [stallTimeoutMs]. This is the whole
 * difference between "abandon a dead upload" and "abandon a slow one": a
 * transfer that keeps acking bytes never trips it, however long it runs.
 */
internal fun isUploadStalled(
    watermark: UploadProgressWatermark,
    nowMs: Long,
    stallTimeoutMs: Long,
): Boolean = nowMs - watermark.lastProgressAtMs >= stallTimeoutMs

private fun sentBytesFromState(state: UploadManager.State): Long = when (state) {
    is UploadManager.State.Queued -> 0L
    is UploadManager.State.Uploading -> state.sent
    is UploadManager.State.Paused -> state.sent
    is UploadManager.State.Done -> Long.MAX_VALUE
    is UploadManager.State.Failed -> 0L
}

private fun totalBytesFromState(state: UploadManager.State): Long = when (state) {
    is UploadManager.State.Queued -> state.total
    is UploadManager.State.Uploading -> state.total
    is UploadManager.State.Paused -> state.total
    is UploadManager.State.Done -> 0L // handle is opaque — caller has size hint elsewhere
    is UploadManager.State.Failed -> 0L
}

/**
 * Per-send upload progress badge model.
 *
 * Granularity is BYTES (across all attachments for one send) so the
 * bubble can show real-time progress within a single attachment —
 * "Uploading 1.2 / 4.8 MB" — instead of just an attachment count
 * ("0/1") that stays static while a 5 MB image is being chunked.
 *
 * [status] disambiguates "actively uploading" vs "stalled" (e.g.
 * waiting on a server ack, no chunks moving). The bubble UI shows a
 * different icon/text for each.
 */
data class PendingUploadProgress(
    val sentBytes: Long,
    val totalBytes: Long,
    val status: Status = Status.Uploading,
) {
    enum class Status {
        /** Chunks are moving (or the loop is mid-init). */
        Uploading,
        /** Connection dropped / ack timed out — waiting to retry. */
        Paused,
    }
}

/**
 * One attachment scheduled for a deferred send — the upload may still
 * be in flight or already Done. [SessionDetailStore.sendMessageBlocksDeferred]
 * resolves each [localKey] via the upload manager and turns the result
 * into a `ResourceLink` block with `displayName` as `name`.
 */
data class DeferredUpload(
    val localKey: String,
    val displayName: String,
    val mime: String,
)

/**
 * Currently-open session detail — transcript, optimistic bubbles, draft
 * seeds, send / cancel / resume / pagination. The "detail seam" half of
 * the previous god-object `SessionStore`.
 *
 * ### Invariants
 *
 *  1. **`openSessionId` is `@Volatile` + every `_session` write is
 *     guarded by a stale-write barrier** — any coroutine that resolves a
 *     network result MUST re-check `openSessionId == sessionId` immediately
 *     before writing `_session.value`, otherwise a late delivery for a
 *     just-closed session can resurrect the previous transcript on top
 *     of the new session's `Loading`.
 *  2. **`detailSubscribed` is owned by [SessionListStore]** — the single
 *     notification collector lives there; this store consumes routed
 *     events via the [DetailNotificationRouter] hook implemented below.
 *  3. **Read-modify-write mutations on `_session.value` are serialised
 *     under [sessionMutex]** — multiple coroutines (the delta poll,
 *     fetchFullSession, loadOlder) compete for the same flow; without the
 *     mutex a stale snapshot can be re-published on top of a newer one.
 *     After the delta-sync rewrite there is exactly ONE writer of
 *     `_session.entries`/state, `_serverQueuedBundles`, `_streams`:
 *     [applyDeltaLocked] + [fetchFullSession]. Push handlers only
 *     [scheduleDeltaPoll]. The one write outside the mutex is [openSession]'s
 *     synchronous `Loading` publish, which exists so the chat surface has a
 *     state to paint while the cache read happens on IO; it is immediately
 *     followed by a locked publish of the cached (or Loading) window, so a
 *     late unlocked delivery for the PREVIOUS session cannot outlive it.
 *  4. **Optimistic bubbles carry two ids** — a local stable id
 *     ([optimisticIdGen]) used by the cancel-by-id failure path and a
 *     wire-side `client_send_id` stamp ([optimisticClientSendIds])
 *     stamped onto the originating ContentBlock's
 *     `_meta.spk_client_send_id`. The pure `reconcileOptimistic`
 *     prefers id-based matching; falls back to content-match for legacy
 *     server entries (pre-rollout) or cross-client echoes that don't
 *     carry a csid.
 *  5. **Outbound session RPCs reach the wire in tap order.** The server
 *     applies `send_message_blocks` / `cancel_turn` in the order it reads
 *     them, and `RemoteClient.callInternal` writes the frame inside its
 *     `suspendCancellableCoroutine` before awaiting anything — so wire order
 *     is exactly the order coroutines reach `call` / `queueCall`. The store's
 *     scope is `Dispatchers.Main.immediate`, which runs a `launch` body
 *     undispatched, so that order is the caller's order **as long as nothing
 *     between `scope.launch` and the call suspends**. Any `withContext(IO)`,
 *     any contended `Mutex.withLock`, any `delay` placed there re-orders the
 *     user's messages against each other and lets Stop / "send queued now"
 *     overtake the send they were pressed for. Work that must happen first
 *     (the durable marker) runs on the caller's thread; work that merely
 *     needs to happen (the optimistic bubble) rides its own coroutine.
 *
 *     The deferred (attachment) send is the one exception, and only because
 *     it has no tap order to preserve: it dispatches when its uploads finish,
 *     so its position on the wire is already decided by upload timing rather
 *     than by when Send was pressed.
 */
internal class SessionDetailStore(
    private val scope: CoroutineScope,
    private val context: ConnectionContext,
    private val draftRepository: DraftRepository,
    private val attachmentDraftRepository: AttachmentDraftRepository,
    private val uploadManager: UploadManager,
    private val sessionList: SessionListStore,
    private val sessionHistoryRepository: SessionHistoryRepository,
    private val pendingSendsRepository: PendingSendsRepository,
) : DetailNotificationRouter {

    /**
     * Sessions whose `start_compact` returned `queued=true` and are
     * waiting for the matching `agent_session_created` notification
     * (with `parent_session_id` set to one of these) to arrive — at
     * which point the parent's history cache is evicted.
     *
     * Thread-safe set wrapping a synchronised map; reads + mutations
     * cross the notification observer thread + the RPC continuation
     * thread.
     */
    private val pendingCompactSourceIds: MutableSet<String> =
        Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    private val _session = MutableStateFlow<UiData<GetSessionResult>>(UiData.Loading)
    val session: StateFlow<UiData<GetSessionResult>> = _session.asStateFlow()

    private val _isLoadingOlder = MutableStateFlow(false)
    val isLoadingOlder: StateFlow<Boolean> = _isLoadingOlder.asStateFlow()

    private val _optimisticEntries = MutableStateFlow<List<EntrySummary>>(emptyList())
    val optimisticEntries: StateFlow<List<EntrySummary>> = _optimisticEntries.asStateFlow()

    /**
     * Stable per-optimistic-bubble id paired with each entry in
     * [_optimisticEntries] by list index. Source-of-truth for FIFO
     * reconciliation; we expose only [_optimisticEntries] to the UI.
     * Both lists are mutated together under [sessionMutex].
     */
    private val optimisticIds: MutableList<Long> = mutableListOf()
    private val optimisticIdGen = AtomicLong(0L)

    /**
     * Monotonic generator for [client_send_id] stamps. Initialised lazily
     * on first use to `System.currentTimeMillis()` so the values are both
     * monotonically increasing within a process AND ordered across app
     * restarts (the next session's start value sits above every id this
     * one issued, assuming wall-clock didn't move backward). Eliminates
     * the same-millisecond collision risk of using `currentTimeMillis()`
     * directly: a queue-flush-on-reconnect coinciding with a fresh user
     * send in the same ms would have stamped identical ids; with the
     * counter every send gets a strictly distinct value.
     */
    private val clientSendIdGen = AtomicLong(System.currentTimeMillis())

    /**
     * Per-optimistic-bubble `client_send_id` stamp paired with
     * [optimisticIds] by list index. `null` slot when the bubble wasn't
     * stamped (legacy [sendMessage] path — text-only — or a producer that
     * opts out). Mutated together with the entry / id lists under
     * [sessionMutex].
     *
     * **Role of the stamp.** When the user fires a send the producer
     * generates a monotonic id, stamps it onto the first ContentBlock's
     * `_meta.spk_client_send_id`, and records it here. The server echoes
     * the value back on the resulting `EntrySummary.clientSendId` and on
     * the matching `agent_session_message_appended` notification — both
     * paths in `reconcileOptimisticLocked` and [onMessageAppended] use
     * the id to pop the optimistic bubble unambiguously. Replaces the
     * fragile `(role, preview)` content-match that broke for long
     * messages truncated server-side to ~200 chars (and the
     * `optimisticBlocksFlags` parallel hack added for #11 to work
     * around the same shape mismatch on multi-block sends).
     */
    private val optimisticClientSendIds: MutableList<Long?> = mutableListOf()

    /**
     * Per-optimistic-bubble upload progress keyed by `client_send_id`.
     * Populated by [sendMessageBlocksDeferred] when a Send was fired
     * while attachments were still uploading; updated as each upload
     * reaches a terminal state; removed when the deferred send finally
     * fires (or fails). The UI surfaces this as a "Загружается N/M
     * вложений…" sub-label inside the user bubble plus a cloud-upload
     * status icon — see [userBubbleStatusFor].
     */
    private val _pendingUploadProgress =
        MutableStateFlow<Map<Long, PendingUploadProgress>>(emptyMap())
    val pendingUploadProgress: StateFlow<Map<Long, PendingUploadProgress>> =
        _pendingUploadProgress.asStateFlow()

    /**
     * Process-lifetime registry of in-flight deferred sends keyed by
     * `client_send_id`. Holds the full send payload so:
     *   - if the user navigates AWAY from the originating session
     *     mid-send and back, [openSession] can rehydrate the optimistic
     *     bubble from this map (the previous openSession cleared
     *     `_optimisticEntries`, but the runtime waiter coroutine is
     *     still alive and tracked here);
     *   - on cold start, [resumeDeferredSendsFromDisk] revives one
     *     coroutine per persisted entry and seeds this map so the
     *     same rehydrate path runs when the user eventually opens
     *     the matching session.
     *
     * Entries are removed in [cleanupDeferred] (success / failure
     * terminals). Thread-safe map because reads happen on the UI
     * thread via [openSession] and writes happen on the deferred-send
     * coroutine + disk-resume path.
     */
    private val inflightDeferred = java.util.concurrent.ConcurrentHashMap<Long, InflightDeferredSend>()

    private data class InflightDeferredSend(
        val csid: Long,
        val localId: Long,
        val sessionId: String,
        val text: String?,
        val attachments: List<DeferredUpload>,
    )

    private val _cancelInFlight = MutableStateFlow(false)
    val cancelInFlight: StateFlow<Boolean> = _cancelInFlight.asStateFlow()

    /**
     * Session id we owe a `cancel_turn` to, or `null` if no cancel is
     * pending. Set by [cancelTurn] when the user taps Stop; cleared by
     * [flushPendingCancel] once the RPC settles successfully. While set,
     * any reconnect-resume path re-fires the RPC — the server-side
     * `cancel_turn` is idempotent (a repeat in `Stopping`/`Idle` is a
     * safe no-op, see commit f5fb202892), so resend is safe.
     *
     * We hold the TARGET session id rather than a bare `Boolean` so a
     * navigation away from the originating session before the cancel
     * lands doesn't accidentally cancel the NEW session's turn on
     * reconnect.
     */
    private val _pendingCancel = MutableStateFlow<String?>(null)
    val pendingCancel: StateFlow<String?> = _pendingCancel.asStateFlow()

    /**
     * Server-broadcast `pending_messages` view for the open session.
     * One [QueuedBundleSummary] per bundle the server is holding while
     * the agent finishes its current turn. Mobile renders each as a
     * Queued bubble alongside the regular transcript — single
     * mechanism whether the queued bundle was enqueued from this
     * device, from a paired desktop, or from another mobile.
     *
     * Live updates ride the `agent_session_queue_changed`
     * notification; cold-start seed comes from
     * `GetSessionResult.pendingBundles`. Cleared on
     * [closeSession] / session switch so a stale broadcast from the
     * previous session can't leak into the new one.
     */
    private val _serverQueuedBundles = MutableStateFlow<List<QueuedBundleSummary>>(emptyList())
    val serverQueuedBundles: StateFlow<List<QueuedBundleSummary>> =
        _serverQueuedBundles.asStateFlow()

    /**
     * All transcript streams for the open session (wire schema v3), Main
     * first. Mirrors [GetSessionResult.streams] at cold-start, then
     * unconditionally replaced from each delta's `streams` (the server sends
     * the full list every poll). Drives the `SubagentTabStrip` on the detail
     * screen.
     */
    private val _streams = MutableStateFlow<List<StreamDto>>(emptyList())
    val streams: StateFlow<List<StreamDto>> = _streams.asStateFlow()

    /**
     * Currently-selected stream, defaulting to [StreamIdDto.Main]. The
     * server scopes the fetched entries to this stream (via the `stream_id`
     * request param), so the chat list renders them as-is. Auto-resets to
     * Main on session switch and snaps to the first remaining stream (Main
     * is always present) when the selected one disappears.
     */
    private val _selectedStream = MutableStateFlow<StreamIdDto>(StreamIdDto.Main)
    val selectedStream: StateFlow<StreamIdDto> = _selectedStream.asStateFlow()

    /**
     * One-shot signal emitted after a successful Reset context. Carries the
     * new session id the server minted; the UI collector hops navigation
     * onto the fresh session so the open chat surface stops pointing at
     * the now-closed source session.
     *
     * Channel-backed (not a replay-less SharedFlow): a `MutableSharedFlow`
     * with `replay = 0` DROPS an emission that lands while no collector is
     * subscribed — which happens whenever the user rotates / backgrounds
     * the app while a `reset_context` / compact RPC is in flight. The
     * dropped switch left the user stranded on the now-evicted source
     * session. A `Channel` buffers the value until the next collector
     * attaches and delivers it exactly once (no redelivery on
     * recomposition, unlike `replay = 1`). Single-consumer by
     * construction — the only collector is the chat screen's
     * `LaunchedEffect`.
     */
    private val _resetSwitch = Channel<String>(Channel.BUFFERED)
    val resetSwitch: Flow<String> = _resetSwitch.receiveAsFlow()

    @Volatile
    var openSessionId: String? = null

    /**
     * Periodic tail-resync while the open session's agent is actively
     * producing (Running / Stopping). The `agent_session_message_appended`
     * stream is best-effort and NOT sequenced, so a notification lost on a
     * live socket with no follow-up (the lost one was the last) would
     * never be re-fetched until the next reconnect / resume / send — the
     * stranded final-message-in-Idle bug. This poller closes that gap by
     * pulling an `after_index` diff while producing and for a short
     * trailing window after the agent stops (see [startTailResync]).
     */
    private var tailResyncJob: Job? = null
        private set

    /**
     * Trailing-edge debounce timer for the live delta poll. Cancelled and
     * re-armed on every trigger, so a burst of push notifications collapses
     * into a single [DELTA_POLL_DEBOUNCE_MS] window. This is the ONLY thing a
     * poke cancels — see [deltaPollJob].
     */
    private var deltaDebounceJob: Job? = null

    /**
     * The single-flight delta poll runner for the open session. At most ONE
     * `get_session_changes` is in flight at a time, and an in-flight poll is
     * NEVER cancelled by a new poke: the server executes and transmits a
     * cancelled response anyway (the JSON-RPC cancel is client-local), so
     * cancel-and-rearm under a stream of pokes meant no poll ever completed
     * while the wire carried every one of them. A poke that lands mid-poll
     * instead records the request on [pollGate] and the loop runs one more
     * iteration.
     *
     * Genuinely cancelled only when the poll's result would be wrong or
     * unwanted: session switch / close / reset / server switch / stream switch.
     */
    private var deltaPollJob: Job? = null

    /**
     * Single-flight admission for the delta poll. See [DeltaPollGate]: a poke
     * that lands while a poll is on the wire is recorded rather than
     * cancelling it, and the loop's stop decision is published through the
     * same lock so no poke can fall between the two.
     */
    private val pollGate = DeltaPollGate()

    /**
     * Far-apart belt-and-suspenders poll for the OPEN session detail only —
     * scoped to exactly the same lifecycle as [tailResyncJob] (started in
     * [openSession], cancelled on switch / [closeSession] / [reset] /
     * [beforeServerSwitch]). Runs roughly once per [SAFETY_NET_POLL_INTERVAL_MS]
     * and, when no live/convergence delta poll is already in flight
     * ([shouldSafetyNetPoll]), triggers one lightweight delta poll so a missed
     * `agent_session_dirty`/append push self-heals the open conversation without
     * a user send. Never polls a closed / background session.
     */
    private var safetyNetPollJob: Job? = null

    /**
     * Per-open-session delta cursor. Seeded from the disk cache on open,
     * from `get_session`'s `epoch`/`currentSeq` on every full load, and
     * from each applied delta's `epoch`/`currentSeq`. Reset on every
     * session switch / [reset] / [closeSession]. Mutated ONLY under
     * [sessionMutex] (read by the poll trigger while building the
     * `get_session_changes` request).
     */
    private var openEpoch: Long = 0
    private var openSeq: Long = 0

    /**
     * Which entries of the open session have had their inline images asked
     * about. Every transcript path pulls entries WITHOUT images (a 5 MB photo
     * is ~6.7 MB of base64 on every re-send of the entry), so this is what
     * keeps the per-entry fetch a once-per-entry cost instead of a per-poll
     * one. Reset with the rest of the open-session cursor state.
     */
    private val imageBackfill = ImageBackfillTracker()

    /**
     * The in-progress image backfill pass, if any. One at a time: the passes
     * re-read the live window each round, so a second concurrent runner would
     * only duplicate requests.
     */
    private var imageBackfillJob: Job? = null

    /**
     * The initial load (`fetchDeltaOrFull`) for the open session while it is
     * still in flight. `openSession` fires it and, a moment later, fires a
     * liveness probe whose `resumeSession` used to schedule a SECOND identical
     * full load — on a slow link the user paid for the same multi-megabyte
     * `get_session` twice. Any poll trigger that lands while this job is alive
     * is dropped: the initial load lands on the live cursor by definition.
     */
    private var initialLoadJob: Job? = null

    /**
     * csids the offline queue has already bounced into the draft via
     * `onMessageExpired` ([handleExpiredMessage]). The queue fires that
     * callback BEFORE completing the caller's deferred, so by the time the
     * send coroutine sees the failure this set already names it — and
     * [classifySendFailure] can suppress the second `setBounced` that would
     * otherwise duplicate the user's text in the composer.
     */
    private val bouncedByQueueCsids: MutableSet<Long> =
        Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /**
     * csids of plain text sends whose `queueCall` coroutine is still alive in
     * THIS process. Their marker has no queue entry yet (the message may not
     * have reached [RemoteClient.queueCall]'s offline branch), so the
     * queue-membership test in [openSession] must not treat them as orphans.
     * The attachment-carrying equivalent is [inflightDeferred].
     */
    private val sentCsidsInFlight: MutableSet<Long> =
        Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /**
     * Snapshot of the `spk_client_send_id`s still parked in the per-server
     * offline queue, or null while nothing has wired it.
     *
     * A pending-send marker means "this send has not reached the server". After
     * a process death the coroutine that would remove it is gone, so the only
     * remaining proof that a marker is still live is a matching entry in the
     * durable queue — which lives in `ConnectionManager`'s `EncryptedQueueStore`,
     * not here. When the hook is unset the store keeps the pre-N-03 behaviour
     * (materialise every marker) rather than guessing, because guessing wrong
     * erases a message the user is still waiting on.
     */
    internal var queuedCsidsProvider: (suspend () -> Set<Long>)? = null

    /**
     * Withdraw the queue entry with the given persisted id, returning true only
     * if it was still parked and is now gone for good.
     *
     * False means the entry had already been handed to the transport (or is
     * unknown): delivery is possible, so the caller must NOT tell the user the
     * message was taken back. Wired to `RemoteClient.cancelQueued` (which does
     * a synchronous encrypted-prefs write, hence the suspend signature — the
     * implementation hops to IO); unset while no server is bound, in which case
     * there is no queue to cancel from either.
     */
    internal var cancelQueuedCallProvider: (suspend (queueId: String) -> Boolean)? = null

    /**
     * csids the user withdrew from the offline queue via [cancelQueuedSend].
     *
     * The withdraw completes the queued call exceptionally, so the still-alive
     * send coroutine is about to observe a failure for a message that did not
     * fail — it was cancelled on purpose, and the cancel path owns its text.
     * Membership suppresses that path's error toast; the double-bounce is
     * suppressed by [classifySendFailure]'s `QueueCancelledException` branch.
     *
     * An entry is consumed by the failure path it was written for. The one that
     * outlives its reader is a cancel of a bubble rehydrated from a previous
     * process, whose send coroutine died with that process — a handful of longs
     * per app run, which is why this is a plain set and not a timed cache.
     */
    /**
     * Text the outbound queue handed back for a session that is open RIGHT NOW.
     *
     * `onMessageExpired` (and [cancelQueuedSend]) write the text to
     * `DraftRepository`'s bounce slot, which the chat screen only reads in
     * [loadDraftSeed] — i.e. on open. A bounce that lands while the user is
     * sitting in the chat was therefore invisible until they navigated away and
     * back (N-07). This delivers it to the live composer instead.
     *
     * Channel-backed for the same reason as [resetSwitch]: a `SharedFlow` with
     * no replay drops an emission that arrives while the screen is briefly
     * detached (rotation, backgrounding). The collector merges the payload with
     * [mergeDraftSeed] and then calls [consumeBounce], so the disk slot is
     * cleared exactly once and a redelivery cannot duplicate the text.
     */
    private val _bouncedDrafts = Channel<BouncedDraft>(Channel.BUFFERED)
    val bouncedDrafts: Flow<BouncedDraft> = _bouncedDrafts.receiveAsFlow()

    /**
     * Serialises read-modify-write mutations on [_session]. See class
     * KDoc invariant 3.
     */
    private val sessionMutex = Mutex()


    init {
        // Wire the routing hook so the list-side notification collector
        // can forward detail-shaped events through this store.
        sessionList.detailNotificationRouter = this
    }

    /** Tear-down hook called from coordinator on server switch / disconnect. */
    fun reset() {
        tailResyncJob?.cancel()
        tailResyncJob = null
        initialLoadJob?.cancel()
        initialLoadJob = null
        cancelDeltaPoll()
        safetyNetPollJob?.cancel()
        safetyNetPollJob = null
        _isLoadingOlder.value = false
        // Detail-state mutations MUST run under `sessionMutex` — otherwise
        // an in-flight `withLock` block that already cleared its stale-write
        // barrier check (e.g. fetchSession onSuccess) could publish AFTER
        // this clear and resurrect the previous server's session state.
        // See class KDoc invariant 3 + closeSession() (mirrors this pattern).
        scope.launch {
            sessionMutex.withLock {
                openSessionId = null
                openEpoch = 0
                openSeq = 0
                imageBackfill.clear()
                _session.value = UiData.Loading
                _optimisticEntries.value = emptyList()
                optimisticIds.clear()
                optimisticClientSendIds.clear()
                _serverQueuedBundles.value = emptyList()
                _streams.value = emptyList()
                _selectedStream.value = StreamIdDto.Main
            }
        }
    }

    /**
     * Lightweight pre-switch reset that drops just the open-session
     * markers — called BEFORE `tearDownConnection` so the connection
     * observer doesn't try to resume against the old `openSessionId`
     * mid-teardown.
     */
    fun beforeServerSwitch() {
        // Don't cancel the list-owned consolidated observer here —
        // [reset] (driven by onTearDown) does that. Only drop the
        // open-session marker so resumeSession in onReconnected sees null.
        openSessionId = null
        tailResyncJob?.cancel()
        tailResyncJob = null
        initialLoadJob?.cancel()
        initialLoadJob = null
        cancelDeltaPoll()
        safetyNetPollJob?.cancel()
        safetyNetPollJob = null
    }

    /**
     * Cancel both halves of the delta poll machinery and drop the pending
     * re-poll request. Only for transitions where an in-flight poll's result
     * would be WRONG (session switch / close / reset / server switch / stream
     * switch) — never for an ordinary poke, which must not cancel a poll the
     * server is already executing.
     */
    private fun cancelDeltaPoll() {
        // The image backfill shares this lifecycle exactly: its results are
        // only valid for the session + stream that were selected when it
        // started, and each of these call sites is changing one of those.
        imageBackfillJob?.cancel()
        imageBackfillJob = null
        deltaDebounceJob?.cancel()
        deltaDebounceJob = null
        // Close the gate BEFORE cancelling, so the job's `finally` cannot
        // re-arm a poll for a session/stream we are abandoning.
        pollGate.reset()
        deltaPollJob?.cancel()
        deltaPollJob = null
    }

    /**
     * Bounce-to-input recovery routed here from ConnectionManager — the
     * queue's `onMessageExpired`, fired once per abandoned message (TTL
     * expiry, poison-pill payload, server 1009 rejection).
     *
     * **Everything here is synchronous on the calling thread, deliberately.**
     * `QueueController.abandon` deletes the queue record BEFORE invoking this
     * callback, so from that moment the user's text exists only in memory
     * until `setBounced` puts it on disk. Moving the write to another
     * dispatcher widens that window from zero to a scheduling delay, and a
     * process death inside it loses the message outright — the exact loss the
     * bounce exists to prevent. What is being avoided is one AES encrypt of
     * one small record per abandoned message (both writes are `apply()`-backed
     * single keys, not file rewrites), which is not worth a durability hole.
     * The publish must also follow the write: the live consumer clears the
     * slot when it takes the text, so a write landing after that clear would
     * re-seed the same message on the next open.
     *
     * Besides returning the text to the draft this retires the message's
     * pending-send marker: the queue record is gone, so nothing else would
     * ever remove it and `openSession` would re-materialise it as a
     * never-resolving "Sending" bubble on every open, forever. The csid is
     * also remembered so the send coroutine that is about to observe the same
     * failure clears its bubble WITHOUT bouncing the text a second time.
     *
     * When the bounce belongs to the session the user is looking at, it is also
     * published on [bouncedDrafts] so the open composer gets the text back now
     * rather than on the next open (N-07).
     */
    fun handleExpiredMessage(message: QueuedMessage) {
        parseQueuedClientSendId(message)?.let { csid ->
            bouncedByQueueCsids.add(csid)
            pendingSendsRepository.remove(csid)
        }
        val parsed = parseExpiredSendMessage(message) ?: return
        val (sessionId, content) = parsed
        draftRepository.setBounced(sessionId, content)
        publishBounce(sessionId, content)
    }

    /**
     * Offer [text] to the live composer when [sessionId] is the open session.
     *
     * Deliberately conditional: a bounce for a session nobody is looking at has
     * nothing to render into, and buffering it would hand it to whichever chat
     * the user opens next. That case is already covered — the text is in the
     * durable bounce slot and [loadDraftSeed] picks it up on open.
     */
    private fun publishBounce(sessionId: String, text: String) {
        if (text.isBlank() || openSessionId != sessionId) return
        _bouncedDrafts.trySend(BouncedDraft(sessionId, text))
    }

    /**
     * Retire the durable bounce slot for [sessionId] after the live composer
     * has taken the text.
     *
     * Called by the chat screen once [bouncedDrafts] has been merged into the
     * visible draft. Ordered deliberately AFTER the merge: if the process dies
     * in between, the slot survives and [loadDraftSeed] re-seeds on the next
     * open — and [mergeDraftSeed] is idempotent, so a draft that already
     * contains the text absorbs the repeat instead of duplicating it. Clearing
     * first would trade a harmless repeat for a lost message.
     */
    suspend fun consumeBounce(sessionId: String) {
        withContext(Dispatchers.IO) { draftRepository.bouncedFor(sessionId) }
    }

    /**
     * Take back a message parked in the durable offline queue (N-07).
     *
     * A send made while the wire is down can sit in the queue for its whole
     * 24-hour TTL, and until now the only thing the user could do about it was
     * wait. This withdraws it: the text goes back into the composer to be
     * edited or dropped, the optimistic bubble disappears and both the queue
     * record and the pending-send marker are retired.
     *
     * **What it refuses to do.** A queue item whose frame was written — or is
     * being written right now — may have been delivered, and nothing can
     * un-deliver it: the server's `spk_client_send_id` de-duplication makes a
     * REPLAY free, not a delivery reversible. So it cannot be recalled and we
     * cannot know whether it arrived. `RemoteClient.cancelQueued` withdraws
     * anything nothing was written for — still parked in the queue, or
     * claimed by a flush that has not reached its frame yet, which is the
     * state a stuck send sits in for up to half a minute while the queue
     * waits on a cross-process replay verdict — and answers false for the
     * rest. False therefore means "not withdrawable", not "gone from the
     * queue"; it surfaces as "already on its way", nothing is mutated and the
     * bubble stays on screen for the server transcript to settle. Refusing is
     * the honest answer: telling the user a message is gone and having it
     * appear in the chat a minute later is worse than not offering the cancel
     * at all.
     *
     * **Ordering.** The withdraw happens before the text is restored, because
     * it is the only step that can fail; restoring first and then failing would
     * leave the text in the composer AND in the queue, i.e. set the user up to
     * send it twice. The pending-send marker — which also holds the full body —
     * is read before the withdraw and deleted last, so the window between the
     * withdraw and the `setBounced` still has the text on disk.
     */
    fun cancelQueuedSend(csid: Long) {
        scope.launch { cancelQueuedSendInternal(csid) }
    }

    /** Body of [cancelQueuedSend]; returns whether the send was withdrawn. */
    internal suspend fun cancelQueuedSendInternal(csid: Long): Boolean {
        // Read the text BEFORE anything is destroyed. `withdrawnSendDraft`
        // returns null only for a send that has no body to give back (an
        // attachment-only one), which is still worth cancelling.
        val restore = withdrawnSendDraft(pendingSendsRepository.listOnIo(), csid)
        val withdraw = cancelQueuedCallProvider
        if (withdraw == null) {
            context.emitError(CANCEL_TOO_LATE_MESSAGE)
            return false
        }
        // No pre-claim by csid. The withdraw completes the parked call with
        // QueueCancelledException, and THAT is what the send coroutine keys
        // off — so it can never mistake an unrelated failure arriving in the
        // same window (a socket close racing the tap gives
        // ClosedException.StillQueued for the same csid) for the user's own
        // cancel and swallow its notice.
        val withdrawn = runCatching { withdraw(queueIdForClientSendId(csid)) }
            .getOrDefault(false)
        if (!withdrawn) {
            context.emitError(CANCEL_TOO_LATE_MESSAGE)
            return false
        }
        // From here the message provably never reaches the server, so every
        // remaining step is about giving the user their text back.
        if (restore != null) {
            withContext(Dispatchers.IO) {
                draftRepository.setBounced(restore.sessionId, restore.text)
            }
            publishBounce(restore.sessionId, restore.text)
        }
        removeOptimisticByCsid(csid)
        pendingSendsRepository.remove(csid)
        return true
    }

    fun openSession(sessionId: String) {
        val active = context.activeClient()
        if (active == null) {
            _session.value = UiData.Error(context.notConnectedMessage())
            return
        }
        // Sequence the openSessionId update + Loading reset together to
        // block a late delivery for the previous session from landing
        // between them (audit Fix M). The mutex covers both reads inside
        // the observer (when it checks `openSessionId == sessionId`) and
        // writes here.
        //
        // Publish `Loading` synchronously so the chat surface has a state to
        // render while the cache read (AES-GCM decrypt + JSON decode of up to
        // 50 entries) happens on IO instead of on the navigation thread.
        _session.value = UiData.Loading
        cancelDeltaPoll()
        initialLoadJob?.cancel()
        initialLoadJob = scope.launch {
            val cached = sessionHistoryRepository.loadOnIo(sessionId)
            val markers = pendingSendsRepository.listOnIo()
            val queuedCsids = queuedCsidsProvider?.let { runCatching { it() }.getOrNull() }
            sessionMutex.withLock {
                openSessionId = sessionId
                // Reset the delta cursor for the new session; seeded
                // below from the cache (if present) or by the full load.
                openEpoch = 0
                openSeq = 0
                imageBackfill.clear()
                _isLoadingOlder.value = false
                _optimisticEntries.value = emptyList()
                optimisticIds.clear()
                optimisticClientSendIds.clear()
                // Drop the previous session's server-queue view; the
                // next delta / full load seeds from the new session's
                // `pendingBundles`.
                _serverQueuedBundles.value = emptyList()
                // Drop the previous session's stream strip — the next
                // delta / full load seeds from `streams`. Reset selection
                // to Main so the new session opens on the main thread.
                _streams.value = emptyList()
                _selectedStream.value = StreamIdDto.Main
                // Re-materialise optimistic state for every deferred
                // send whose waiter coroutine is still alive for THIS
                // session (the user may have navigated away and back,
                // or this is a cold-start session-open after
                // [resumeDeferredSendsFromDisk] revived background
                // waiters). The runtime registry [inflightDeferred] is
                // the source of truth — disk is just the cold-start
                // seed.
                for (s in inflightDeferred.values) {
                    if (s.sessionId != sessionId) continue
                    if (s.csid in optimisticClientSendIds) continue
                    val preview = buildDeferredPreview(s)
                    _optimisticEntries.value = _optimisticEntries.value +
                        EntrySummary(role = EntryRoleDto.User, preview = preview, clientSendId = s.csid)
                    optimisticIds.add(s.localId)
                    optimisticClientSendIds.add(s.csid)
                    // Initial render: zero bytes, unknown total.
                    // The waiter coroutine overwrites this with real
                    // sent/total values on its next StateFlow tick
                    // (typically within tens of ms — the StateFlow
                    // always has a non-null current value).
                    _pendingUploadProgress.value = _pendingUploadProgress.value +
                        (s.csid to PendingUploadProgress(
                            sentBytes = 0L,
                            totalBytes = 1L,
                            status = PendingUploadProgress.Status.Uploading,
                        ))
                }
                // Re-materialise plain (no-attachment) text sends that are
                // still in flight — `sendMessageBlocks` persisted a marker
                // for each, removed only once the server received the
                // message. These have no upload progress, so the status
                // row computes to Sending/Queued. Skip ones already
                // present (deferred loop above, or a still-live producer
                // coroutine that re-added before we got the lock) and
                // ones with attachments (owned by the deferred machinery).
                //
                // A marker is only shown while the send it names can still
                // happen: it is either owned by a live coroutine in this
                // process ([inflightDeferred] / [sentCsidsInFlight]) or still
                // parked in the durable queue. A marker that is neither is an
                // orphan left by a process death, and re-showing it painted a
                // permanent "Sending" bubble that no echo would ever resolve.
                for (p in markers) {
                    if (p.sessionId != sessionId) continue
                    if (p.attachments.isNotEmpty()) continue
                    if (p.csid in optimisticClientSendIds) continue
                    if (!shouldMaterialiseMarker(
                            csid = p.csid,
                            queuedCsids = queuedCsids,
                            inFlightCsids = sentCsidsInFlight,
                        )
                    ) {
                        continue
                    }
                    val body = p.text.orEmpty()
                    _optimisticEntries.value = _optimisticEntries.value +
                        EntrySummary(
                            role = EntryRoleDto.User,
                            preview = body,
                            // Mirror `sendMessageBlocks` — rendering off
                            // `markdown` keeps the bubble identical to a
                            // freshly-sent one (the latter sets `preview`
                            // to the truncated stub).
                            markdown = body.takeIf { it.isNotEmpty() },
                            clientSendId = p.csid,
                        )
                    optimisticIds.add(p.localId)
                    optimisticClientSendIds.add(p.csid)
                }
                if (cached != null && cached.entries.isNotEmpty()) {
                    // Render the cached transcript immediately so the
                    // chat surface is interactive while the delta fetch
                    // is in flight. We synthesise a minimal
                    // GetSessionResult carrying the cached delta cursor
                    // (`epoch`/`currentSeq`) so the held `_session` value
                    // agrees with [openEpoch]/[openSeq]; missing fields
                    // (state, title, timestamps) are overwritten by the
                    // first successful delta / full fetch.
                    openEpoch = cached.epoch
                    openSeq = cached.lastSeq
                    _session.value = UiData.Loaded(
                        GetSessionResult(
                            id = cached.sessionId,
                            solutionId = cached.solutionId,
                            agentId = cached.agentId,
                            title = "",
                            state = SessionStateDto.Idle,
                            createdAt = 0L,
                            lastActivityAt = 0L,
                            epoch = cached.epoch,
                            currentSeq = cached.lastSeq,
                            entries = cached.entries,
                            totalCount = cached.totalCountAtLastWrite,
                        ),
                    )
                } else {
                    _session.value = UiData.Loading
                }
            }
            fetchDeltaOrFull(active, sessionId, cached)
        }
        sessionList.loadChildren(sessionId)
        sessionList.ensureNotificationsObserver()
        startTailResync(sessionId)
        startSafetyNetPoll(sessionId)
        // Opening a session over a silently-dead socket (a zombie left by a
        // Doze / VPN window or a server restart the client never detected —
        // state still reads "Connected", no banner) would let the initial
        // `fetchDeltaOrFull` above time out silently, leaving the stale cache
        // placeholder (last-seen intermediate step + a hardcoded `Idle`). The
        // observed symptom: the chat only refreshes once the user SENDS a
        // message (whose socket write trips broken-pipe → reconnect → catch-up).
        // Close that gap deterministically on entry:
        //   - a DEAD wire → `probeLivenessNow` force-reconnects (or, if not
        //     Connected, kicks the backoff), and `onReconnected` re-runs the
        //     catch-up via `resumeSession` — the same proven path a send trips;
        //   - a LIVE wire → still schedule ONE `resumeSession` delta poll: the
        //     initial `fetchDeltaOrFull` may have failed silently over a
        //     slow/pinched (not fully dead) socket, and `resumeSession` is
        //     idempotent + coalesced, so this is a cheap guaranteed reconcile
        //     rather than sitting on the placeholder until the next send.
        scope.launch {
            if (context.probeLivenessNow()) {
                // Wait for the open's own load before reconciling, so this
                // never becomes a second concurrent `get_session` for the same
                // page (the poll trigger joins it too, but `resumeSession`
                // bails early if `openSessionId` isn't set yet).
                initialLoadJob?.join()
                resumeSession(sessionId)
            }
        }
    }

    /**
     * Start (or restart) the SLOW tail-resync fallback poller for
     * [sessionId]. The live delta poll ([scheduleDeltaPoll]) is the primary
     * sync path — driven by push notifications; this fallback exists ONLY as
     * the safety net for a LOST (and unsequenced) notification on a live
     * socket. It triggers a delta poll while the agent is producing (Running
     * / Stopping) AND for a short TRAILING window after it stops.
     *
     * The trailing window is the whole point: mid-turn losses self-heal (the
     * next notification triggers a poll), but the FINAL message of a turn has
     * no follow-up — if its notification (or the Idle-transition
     * notification) is lost, the reply strands until the user sends again.
     * Keeping a few ticks of resync running after the agent goes Idle catches
     * that last message; the delta poll always pulls the full changed entries
     * and the freshest session state, so a missed Idle transition is
     * recovered here too. Once the trailing budget is spent on a quiet
     * session, the poller idles (no traffic).
     */
    private fun startTailResync(sessionId: String) {
        tailResyncJob?.cancel()
        tailResyncJob = scope.launch {
            var trailing = 0
            while (true) {
                delay(TAIL_RESYNC_INTERVAL_MS)
                // `continue`, not `break`: `openSession` now sets
                // `openSessionId` inside its IO-backed initial load, so on a
                // cold first open (three encrypted-prefs opens, each a Tink
                // keyset unwrap) this tick can fire BEFORE the id is seated.
                // Exiting the loop there killed the poller for the whole
                // session. Every path that genuinely ends the session cancels
                // this job outright, so skipping a tick is the only thing this
                // check needs to do.
                if (openSessionId != sessionId) continue
                val producing = when ((_session.value as? UiData.Loaded)?.value?.state) {
                    is SessionStateDto.Running, SessionStateDto.Stopping -> true
                    else -> false
                }
                when {
                    producing -> {
                        trailing = TAIL_RESYNC_TRAILING_TICKS
                        scheduleDeltaPoll(sessionId)
                    }
                    trailing > 0 -> {
                        // Agent just stopped — keep polling briefly to
                        // recover a stranded final message / Idle transition.
                        trailing--
                        scheduleDeltaPoll(sessionId)
                    }
                }
            }
        }
    }

    /**
     * Start (or restart) the far-apart safety-net poll for [sessionId].
     * Scoped strictly to the open session detail: every tick re-checks
     * `openSessionId == sessionId` (so a switch/close stops it even before the
     * job is cancelled) and only triggers a poll when [shouldSafetyNetPoll]
     * agrees — i.e. no live/convergence delta poll is already in flight. That
     * coalescing makes the tick a pure no-op whenever the push path is already
     * converging, so it can never double-apply or cancel an in-progress
     * convergence loop. The triggered [scheduleDeltaPoll] is the same
     * lightweight delta-from-cursor the dirty path drives; from a caught-up
     * cursor it is a cheap empty `get_session_changes`.
     */
    private fun startSafetyNetPoll(sessionId: String) {
        safetyNetPollJob?.cancel()
        safetyNetPollJob = scope.launch {
            while (true) {
                delay(SAFETY_NET_POLL_INTERVAL_MS)
                // See [startTailResync]: skip the tick, don't end the loop —
                // this is the last-resort sync path and it must not be able to
                // die on a slow open. Cancellation is what stops it.
                if (openSessionId != sessionId) continue
                // The gate, not `Job.isActive`: a job stays active through the
                // window between the loop deciding to stop and the coroutine
                // completing, and the gate is what the loop actually publishes
                // its stop decision through.
                val inFlight = pollGate.isRunning() ||
                    deltaDebounceJob?.isActive == true ||
                    initialLoadJob?.isActive == true
                if (shouldSafetyNetPoll(sessionId, openSessionId, inFlight)) {
                    scheduleDeltaPoll(sessionId)
                }
            }
        }
    }

    fun closeSession() {
        tailResyncJob?.cancel()
        tailResyncJob = null
        cancelDeltaPoll()
        safetyNetPollJob?.cancel()
        safetyNetPollJob = null
        initialLoadJob?.cancel()
        initialLoadJob = null
        scope.launch {
            sessionMutex.withLock {
                openSessionId = null
                openEpoch = 0
                openSeq = 0
                imageBackfill.clear()
                _session.value = UiData.Loading
                _isLoadingOlder.value = false
                _optimisticEntries.value = emptyList()
                optimisticIds.clear()
                optimisticClientSendIds.clear()
                _serverQueuedBundles.value = emptyList()
                _streams.value = emptyList()
                _selectedStream.value = StreamIdDto.Main
            }
        }
    }

    // -------------------------------------------------------------------------
    // DetailNotificationRouter — invoked by the list store's collector.
    // -------------------------------------------------------------------------

    override fun onChildSessionCreated(parentSessionId: String) {
        // Compact two-phase eviction: if [parentSessionId] is one of our
        // pending compact sources, the new child IS the compacted-into
        // session; the old (parent) session is now closable and its
        // cache should go. Idempotent eviction is fine.
        if (pendingCompactSourceIds.remove(parentSessionId)) {
            sessionHistoryRepository.evict(parentSessionId)
        }
        val openSid = openSessionId ?: return
        if (parentSessionId == openSid) {
            sessionList.loadChildren(parentSessionId)
        }
    }

    override fun onMessageAppended(payload: MessageAppendedPayload) {
        val openSid = openSessionId ?: return
        if (payload.sessionId != openSid) return
        // The entry itself (the placeholder, the fast-pop, the content) all
        // arrives via the delta poll: the delta's `changed_entries` always
        // carries the full entry body, and `applyDeltaLocked` pops optimistics
        // via `reconcileOptimisticLocked`.
        scheduleDeltaPoll(openSid)
    }

    override fun onMessageAppendedFallback() {
        val openSid = openSessionId ?: return
        scheduleDeltaPoll(openSid)
    }

    override fun onSessionStateOrTitleChanged(notifSessionId: String?) {
        val openSid = openSessionId ?: return
        if (notifSessionId != null && notifSessionId != openSid) return
        scheduleDeltaPoll(openSid)
    }

    override fun onSessionDirty(sessionId: String) {
        val openSid = openSessionId ?: return
        if (sessionId != openSid) return
        // Same single-flight poll every other trigger uses. It already
        // converges: it drains `has_more` pages and retries failed polls with
        // backoff, and stops on the first response the server certifies as
        // caught up for the selected stream.
        //
        // It deliberately does NOT chase the `current_seq` the payload carries.
        // That is the session-GLOBAL `change_seq`, while our cursor is
        // per-stream — and the last dirty of every turn carries `Main.seq + 1`
        // because the `→Idle` transition bumps `change_seq` with no entry
        // behind it. Polling until a per-stream cursor reached a global seq was
        // therefore an unreachable target that re-polled with no delay for as
        // long as the chat stayed open.
        scheduleDeltaPoll(openSid)
    }

    override fun onSessionContextReset(payload: AgentSessionContextResetPayload) {
        val openSid = openSessionId ?: return
        if (payload.sessionId != openSid) return
        scope.launch {
            sessionMutex.withLock {
                // stale-write barrier (see class kdoc invariant 1)
                if (openSessionId != payload.sessionId) return@withLock
                // Drop optimistic bubbles tied to the now-wiped context.
                _optimisticEntries.value = emptyList()
                optimisticIds.clear()
                optimisticClientSendIds.clear()
                // Flip to Loading so the chat shows a clean refresh state
                // rather than briefly painting the old entries during the
                // poll round-trip.
                _session.value = UiData.Loading
            }
            // The delta poll detects the epoch change (our held `openEpoch`
            // no longer matches the server's), gets `reset:true`, and falls
            // back to a full `get_session` — a fresh transcript page (likely
            // empty) replaces the stale one.
            scheduleDeltaPoll(payload.sessionId)
        }
    }

    override fun onActiveSubagentsChanged(payload: SessionActiveSubagentsChangedPayload) {
        val openSid = openSessionId ?: return
        if (payload.sessionId != openSid) return
        // Kept as a dirty-poke: the server still emits this notification, but
        // the delta's `streams` section is the single writer of `_streams`
        // now — just trigger a poll; do NOT apply the payload list directly
        // (that would make the push a second writer).
        scheduleDeltaPoll(openSid)
    }

    /**
     * UI hook for the stream tab strip. Validates that [id] matches one of
     * the currently-known streams (by id); an unknown id is logged and
     * silently snapped back to [StreamIdDto.Main] (always present). Mutating
     * [_selectedStream] directly from outside the store is forbidden — this
     * entry point is the single seam.
     */
    fun selectStream(id: StreamIdDto) {
        val target = if (id != StreamIdDto.Main && _streams.value.none { it.id == id }) {
            android.util.Log.w(
                "SessionDetailStore",
                "selectStream($id) is not in the stream set — resetting to Main",
            )
            StreamIdDto.Main
        } else {
            id
        }
        val changed = _selectedStream.value != target
        _selectedStream.value = target
        if (changed) {
            // Cancel any armed / in-flight delta poll — it carries the old
            // stream id and would apply stale entries under the new stream if
            // it landed after the switch. This is one of the few places where
            // cancelling an in-flight poll is the RIGHT thing: its result is
            // wrong, not merely late.
            cancelDeltaPoll()
            // Entry indices are STREAM-LOCAL, so the resolved-images set for
            // the previous tab means nothing here.
            imageBackfill.clear()
            // Server-side per-stream scoping: the held entries are the
            // PREVIOUS stream's window, so the newly-selected stream's
            // history isn't loaded yet. Pull a fresh first page scoped to
            // the new stream (a tab switch is a user action — a fresh
            // count=50 fetch is cheap and correct). Tracked as the initial
            // load so a rapid double-tap replaces the previous tab's fetch
            // instead of racing it, and so a poll trigger waits for it.
            val sid = openSessionId ?: return
            val active = context.activeClient() ?: return
            initialLoadJob?.cancel()
            initialLoadJob = scope.launch { fetchInitialPage(active, sid) }
        }
    }

    override fun onSessionQueueChanged(payload: SessionQueueChangedPayload) {
        val openSid = openSessionId ?: return
        if (payload.sessionId != openSid) return
        // The delta's `pending_bundles` section is the single writer of
        // `_serverQueuedBundles` now — just trigger a poll; do NOT assign
        // the payload bundles directly. The optimistic-bubble pop that this
        // handler used to do (drop the local optimistic once its csid lands
        // in a server bundle) now happens inside `applyDeltaLocked` via
        // `reconcileOptimisticLocked` against the delta's `changed_entries`
        // (the merged user entry carries the rolled-up csids) — see
        // `reconcilePendingBundleOptimisticsLocked` for the bundle-only case
        // where the merged entry hasn't materialised yet.
        scheduleDeltaPoll(openSid)
    }

    // -------------------------------------------------------------------------
    // Network ops
    // -------------------------------------------------------------------------

    /**
     * Cache-first open path. The cache (if any) has already been rendered by
     * [openSession]; this resolves the live state:
     *
     *  - **cache present** (delta cursor known) → poll `get_session_changes`
     *    against the held [openEpoch]/[openSeq]. `reset == true` (the cached
     *    epoch is stale — a compact / reset happened while we were away) →
     *    full `get_session` via [fetchFullSession]; otherwise apply the delta
     *    via [applyDeltaLocked].
     *  - **no cache** → full `get_session` via [fetchFullSession].
     */
    /**
     * Dispatch one `send_message_blocks` for [csid], replaying it while §4.6
     * says the replay is provably free.
     *
     * Returns the TERMINAL outcome: a decoded [SendMessageBlocksResult] (with
     * `delivery == Duplicate` counting as success), or the last failure. The
     * caller then classifies that failure with [SendRetryContext.NONE], so it
     * can only ever see `Bounce` / `ClearOnly` / `KeepQueued` — the replay
     * branch is consumed here and never leaks out as an action the cleanup
     * paths would have to know about.
     *
     * Every replay re-parks the SAME call: same queue id ([queueIdForClientSendId]),
     * same params, therefore the same `spk_client_send_id`. Nothing about the
     * optimistic bubble, the draft or the disk marker moves in between, so a
     * replay is invisible to the user except as a send that eventually lands.
     *
     * @param alreadyBounced re-read on each attempt — the queue can hand the
     *   text back underneath us (TTL expiry, 1009), and after that a replay
     *   would deliver a message the user already has back in their composer.
     */
    private suspend fun dispatchSendWithRetry(
        active: RemoteClient,
        csid: Long,
        params: JsonObject,
        alreadyBounced: () -> Boolean,
    ): Result<SendMessageBlocksResult> {
        // Read back off the payload, not off the caller's local: the server
        // claims the ids it actually receives, so this is the only honest
        // answer to "would a replay be recognised?".
        val stampedOnTheWire = clientSendIdInSendParams(params) == csid
        var requeuesUsed = 0
        while (true) {
            val featuresAtDispatch = active.serverFeatures.value
            val dispatchedAtMs = System.currentTimeMillis()
            val outcome = runCatching {
                // The queue entry is filed under the client-send-id rather
                // than a fresh UUID so the bubble's cancel affordance can
                // name it later — including in a process that did not queue
                // it. See [queueIdForClientSendId]. Re-adding the same id
                // REPLACES the record, so a replay can never leave two.
                active.queueCall(
                    "remote.solution_agent.send_message_blocks",
                    params,
                    messageId = queueIdForClientSendId(csid),
                )
            }.mapCatching { interpretSendResponse(it) }
            val failure = outcome.exceptionOrNull() ?: return outcome
            if (failure is kotlinx.coroutines.CancellationException) return outcome
            // Cheap pre-check: assume the connection comes back as the same
            // editor process and ask whether this failure would be replayed
            // at all. Everything except that assumption is decided here — the
            // token, the window, the budget, the ambiguity of the failure and
            // whether the queue has already handed the text back. Only if all
            // of it says "replay" is it worth paying for the real answer,
            // which may mean waiting for a reconnect.
            val ifSameProcess = SendRetryContext(
                featuresAtDispatch = featuresAtDispatch,
                currentServerInstanceId = featuresAtDispatch.serverInstanceId,
                dispatchedAtMs = dispatchedAtMs,
                nowMs = System.currentTimeMillis(),
                requeuesUsed = requeuesUsed,
                stampedOnTheWire = stampedOnTheWire,
            )
            if (classifySendFailure(failure, alreadyBounced(), ifSameProcess) != SendFailureAction.Requeue) {
                return outcome
            }
            val live = try {
                negotiatedInstanceIdForReplay(active)
            } catch (c: kotlinx.coroutines.CancellationException) {
                // The scope is going away. Report it AS the failure so the
                // caller's own cancellation check rethrows instead of running
                // its cleanup (bounce, marker delete) on a dying coroutine.
                return Result.failure(c)
            }
            val action = classifySendFailure(
                failure,
                alreadyBounced(),
                ifSameProcess.copy(currentServerInstanceId = live, nowMs = System.currentTimeMillis()),
            )
            if (action != SendFailureAction.Requeue) return outcome
            requeuesUsed++
            android.util.Log.i(
                "SessionDetailStore",
                "replaying ambiguous send csid=$csid (attempt ${requeuesUsed + 1}) — " +
                    "server de-duplicates by client_send_id",
            )
        }
    }

    /**
     * The `server_instance_id` of the connection a replay would go out on, or
     * `null` if there isn't one worth waiting for.
     *
     * A `SendEnvelopeException` arrives on a live socket, so the answer is
     * already in hand. A `TransportLostException` does not: the socket is
     * gone and [RemoteClient.serverFeatures] has been reset, so the only
     * honest answer is the one the NEXT connection negotiates — which is also
     * the connection that would carry the replay, and therefore exactly the
     * process whose dedupe table has to still hold the claim.
     *
     * The wait is bounded. Past [REPLAY_RENEGOTIATE_TIMEOUT_MS] the user has
     * been staring at a "Sending" bubble long enough; returning `null` bounces
     * the text to the composer, which is what an ambiguous send has always
     * done and costs nothing but a second Send tap.
     */
    private suspend fun negotiatedInstanceIdForReplay(active: RemoteClient): String? {
        active.serverFeatures.value.takeIf { it.has(WireFeature.CSID_DEDUPE) }
            ?.let { return it.serverInstanceId }
        return withTimeoutOrNull(REPLAY_RENEGOTIATE_TIMEOUT_MS) {
            active.serverFeatures.first { it.has(WireFeature.CSID_DEDUPE) }
        }?.serverInstanceId
    }

    /**
     * Per-entry body digests for the bodies in [held], or `null` when this
     * connection can't use them.
     *
     * Null (not an empty list) is the "don't send the key" signal, matching
     * `RemoteClient.getSessionChanges`' own contract: absent and empty are
     * the same request, and only one of them should ever be built.
     *
     * The SHA-256s run on [Dispatchers.Default] — this is called from the
     * poll coroutine, which for `viewModelScope` is Main, and hashing up to
     * `KNOWN_ENTRIES_MAX` transcript bodies has no business there.
     */
    private suspend fun knownEntriesFor(
        active: RemoteClient,
        held: List<EntrySummary>,
    ): List<KnownEntryDto>? {
        if (held.isEmpty()) return null
        if (!active.serverFeatures.value.has(WireFeature.ENTRY_BODY_DELTA)) return null
        return withContext(Dispatchers.Default) { buildKnownEntries(held) }
            .takeIf { it.isNotEmpty() }
    }

    private suspend fun fetchDeltaOrFull(
        active: RemoteClient,
        sessionId: String,
        cached: CachedSessionHistory?,
    ) {
        if (cached == null || cached.entries.isEmpty()) {
            fetchFullSession(active, sessionId)
            return
        }
        // Kept, not just sent: `applyDeltaLocked` re-proves the response's
        // splice bases against these exact digests.
        val offered = knownEntriesFor(active, cached.entries).orEmpty()
        val delta = runCatching {
            active.getSessionChanges(
                sessionId = sessionId,
                sinceSeq = cached.lastSeq,
                knownEpoch = cached.epoch,
                streamId = _selectedStream.value,
                // Images ride the per-entry [backfillEntryImages] path, never
                // the transcript path — see [fetchFullSession].
                includeImages = false,
                knownEntries = offered.takeIf { it.isNotEmpty() },
                omitPreviewWhenMarkdown = active.serverFeatures.value
                    .has(WireFeature.OMIT_PREVIEW),
            )
        }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            applyFetchFailure(sessionId, it)
            return
        }
        if (delta.reset) {
            fetchFullSession(active, sessionId)
        } else {
            applyDeltaLocked(sessionId, delta, offered)
        }
    }

    /**
     * Full `get_session` load — the single writer for the no-cache open,
     * reset / context-clear, tab switch, and the gap / short-window
     * fallback. Publishes entries / bundles / subagents under the mutex,
     * reconciles optimistics, adopts the server's delta cursor
     * ([epoch]/[currentSeq]) into [openEpoch]/[openSeq], and persists the
     * cache stamped with that cursor.
     */
    private suspend fun fetchFullSession(active: RemoteClient, sessionId: String) {
        val requestedStream = _selectedStream.value
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("include_full_content", true)
            // Images are pulled per entry by [backfillEntryImages] instead of
            // inline here. A page of 50 entries with a handful of photos was
            // several megabytes of base64 on ONE response — past the 30 s call
            // timeout on a slow link, which made the session simply refuse to
            // open (the retry re-downloaded the same megabytes). Without them
            // the transcript lands in one small response and the photos stream
            // in afterwards, each with its own timeout budget.
            put("include_images", false)
            put("stream_id", JsonRpc.json.encodeToJsonElement(StreamIdDto.serializer(), requestedStream))
            put("count", SESSION_PAGE_SIZE)
            // Every entry on this page carries a body (include_full_content),
            // so its `preview` is a duplicate of the first ~200 characters of
            // that body. Ask the server to drop it where it can — the key is
            // emitted only when the peer advertised WireFeature.OMIT_PREVIEW,
            // and user entries keep their preview regardless (reconcile keys
            // off it).
            putOmitPreviewWhenMarkdown(active.serverFeatures.value)
        }
        val result = runCatching { active.call("remote.solution_agent.get_session", params) }
            .mapCatching { resp -> resp.decodeResultOrThrow(GetSessionResult.serializer()) }
            .getOrElse {
                // A cancelled load is a REPLACED load: `openSession` and
                // `selectStream` both cancel `initialLoadJob` for the same
                // session, so swallowing this painted
                // `UiData.Error("StandaloneCoroutine was cancelled")` over the
                // chat until the replacement landed — and left it there if the
                // replacement failed too.
                if (it is kotlinx.coroutines.CancellationException) throw it
                applyFetchFailure(sessionId, it)
                return
            }
        var published: GetSessionResult? = null
        sessionMutex.withLock {
            // stale-write barrier (see class kdoc invariant 1)
            if (openSessionId != sessionId) return@withLock
            // Stream barrier: the user may have tapped a different tab while
            // this page was on the wire. `get_session` doesn't echo back which
            // stream it answered for, so the request's own selection is the
            // only ground truth — publishing it under the new tab would show
            // one stream's transcript inside another's until the next poll.
            if (_selectedStream.value != requestedStream) return@withLock
            // The response carries no images (see the request above); anything
            // already resolved for these indices is still valid, so keep it
            // rather than making the user's photos blink out and re-download.
            val previous = (_session.value as? UiData.Loaded)?.value?.entries.orEmpty()
            val entries = carryOverImages(previous, result.entries)
            val merged = result.copy(entries = entries)
            _session.value = UiData.Loaded(merged)
            _serverQueuedBundles.value = result.pendingBundles
            applyStreamsLocked(result.streams)
            _isLoadingOlder.value = false
            reconcileOptimisticLocked(entries)
            openEpoch = result.epoch
            openSeq = result.currentSeq
            published = merged
        }
        val snapshot = published ?: return
        persistCache(
            sessionId = sessionId,
            fetched = snapshot,
            entries = snapshot.entries,
            newTotalCount = snapshot.totalCount,
            epoch = result.epoch,
            lastSeq = result.currentSeq,
        )
        backfillEntryImages(sessionId, snapshot.entries)
    }

    private suspend fun fetchInitialPage(active: RemoteClient, sessionId: String) {
        fetchFullSession(active, sessionId)
    }

    /**
     * The SINGLE delta-applier publish seam. Together with [fetchFullSession]
     * it is the only writer of `_session.entries`/state,
     * `_serverQueuedBundles`, and `_streams`. Holds [sessionMutex] +
     * the `openSessionId == sessionId` stale-write barrier on every publish.
     *
     * Precondition: [incoming].reset is false (caller routes a reset delta to
     * [fetchFullSession]). When the held `_session` is not yet `Loaded`, when
     * an append-only entry body can't be spliced onto the held one
     * ([planDeltaApply]), or when the post-apply window falls short of the
     * newest entry (the delta missed newer entries), this triggers a full
     * [fetchFullSession] instead of publishing a half-applied state.
     */
    private suspend fun applyDeltaLocked(
        sessionId: String,
        incoming: GetSessionChangesResult,
        offered: List<KnownEntryDto> = emptyList(),
    ) {
        var needsFullLoad = false
        var snapshotForCache: GetSessionResult? = null
        var delta = incoming
        sessionMutex.withLock {
            // stale-write barrier (see class kdoc invariant 1)
            if (openSessionId != sessionId) return@withLock
            val current = _session.value as? UiData.Loaded ?: run {
                // No loaded base to apply onto — fall back to a full load.
                needsFullLoad = true
                return@withLock
            }
            // Splice append-only entry bodies back to whole ones BEFORE any
            // merge sees them (WireFeature.ENTRY_BODY_DELTA). Done inside the
            // mutex so the base it diffs against is the same window the merge
            // is about to run on; done first so nothing downstream ever
            // observes an entry carrying a tail instead of a body.
            //
            // [offered] is what the poll that produced this delta actually
            // described. Passing it makes the splice self-verifying — see
            // [planDeltaApply] for the invariant it re-proves and why the
            // wire cannot prove it for us.
            //
            // Note this runs BEFORE the selected-stream guard below, so a
            // splice can in principle be computed against a window belonging
            // to a stream the user has since switched away from. That is
            // harmless only because the guard sets `needsFullLoad` and
            // returns before anything is published — nothing spliced ever
            // escapes this block. Do not move a publish above that guard.
            when (val plan = planDeltaApply(current.value.entries, incoming, offered)) {
                is DeltaApplyPlan.Apply -> delta = plan.delta
                is DeltaApplyPlan.FullReload -> {
                    android.util.Log.w(
                        "SessionDetailStore",
                        "entry-body delta unusable (${plan.reason}) — full reload",
                    )
                    needsFullLoad = true
                    return@withLock
                }
            }
            val deltaState = SessionDeltaState(
                entries = current.value.entries,
                totalCount = current.value.totalCount,
                state = current.value.state,
                pendingBundles = _serverQueuedBundles.value,
                streams = _streams.value,
                currentSeq = openSeq,
            )
            val next = applySessionDelta(deltaState, delta)
            // Tail-anchored guard: if the post-apply window no longer reaches
            // the newest entry, the delta missed newer entries (e.g. a gap
            // the cursor couldn't bridge) — do NOT publish the half-applied
            // state; full-reload instead. EXCEPT when `delta.hasMore`: then the
            // short window is the server INTENTIONALLY paginating, not a gap —
            // apply this page and let the caller keep polling from the advanced
            // cursor. Without this exception a far-behind catch-up would fall
            // back to a full "big bang" load, defeating pagination.
            if (!delta.hasMore && !isTailAnchoredWindow(next.entries, next.totalCount)) {
                needsFullLoad = true
                return@withLock
            }
            // Reconcile the stream mirror BEFORE publishing the merged window.
            // If the selected stream vanished from the delta's stream list (a
            // teammate stream auto-closed server-side), applyStreamsLocked
            // snaps the selection; and if this delta was scoped to a stream we
            // are no longer on, its entries are for the wrong stream. In either
            // case the merged window is stale — force a clean full refetch of
            // the now-selected stream instead of publishing it (deterministic,
            // vs. relying on the next poll's shrink-to-0 to self-heal).
            val snapped = applyStreamsLocked(next.streams)
            if (snapped || delta.selectedStreamId != _selectedStream.value) {
                needsFullLoad = true
                return@withLock
            }
            // The delta is fetched without images, and it re-sends a changed
            // entry whole — so a user entry whose images we already resolved
            // would come back stripped. Carry them forward instead of
            // re-downloading them on every `mod_seq` bump.
            val mergedEntries = carryOverImages(current.value.entries, next.entries)
            val updated = current.value.copy(
                entries = mergedEntries,
                totalCount = next.totalCount,
                state = next.state,
                epoch = delta.epoch,
                currentSeq = delta.currentSeq,
            )
            _session.value = UiData.Loaded(updated)
            _serverQueuedBundles.value = next.pendingBundles
            reconcileOptimisticLocked(mergedEntries)
            // Bundle-only optimistic handoff: a send that landed in a server
            // bundle (but whose merged user entry hasn't materialised in
            // `changed_entries` yet) still pops, mirroring the old
            // `onSessionQueueChanged` behaviour.
            reconcilePendingBundleOptimisticsLocked(next.pendingBundles)
            openEpoch = delta.epoch
            openSeq = delta.currentSeq
            snapshotForCache = updated
        }
        if (needsFullLoad) {
            val active = context.activeClient() ?: return
            fetchFullSession(active, sessionId)
            return
        }
        snapshotForCache?.let {
            persistCache(
                sessionId = sessionId,
                fetched = it,
                entries = it.entries,
                newTotalCount = it.totalCount,
                epoch = delta.epoch,
                lastSeq = delta.currentSeq,
            )
            backfillEntryImages(sessionId, it.entries)
        }
    }

    /**
     * Kick a bounded, resumable pass that fetches the inline images of USER
     * entries in the open window and slots them into the held transcript.
     *
     * This is the other half of pulling every transcript path with
     * `include_images = false`. The wire has no "this entry has N images" hint,
     * so a user entry is asked about once and the answer — images or none — is
     * remembered by [imageBackfill] for as long as the session stays open. A
     * probe for a text-only entry is a small round-trip; a probe for a photo
     * costs what the old inline path cost, except it is paid once instead of on
     * every re-send of the entry, off the critical path of opening the chat,
     * and inside its own call timeout.
     *
     * Only USER entries are worth a probe, on any stream: assistant and
     * tool-call images are flattened to `spk-image://N` markdown server-side
     * and the raw bytes are not retained, so there is nothing to re-inline.
     *
     * [EntrySummary.index] is stream-local, so the fetch carries the stream the
     * window was served for and the result is published only while that stream
     * is still selected — a tab switch mid-fetch must not slot one stream's
     * images into another's entry at the same index.
     */
    private fun backfillEntryImages(sessionId: String, entries: List<EntrySummary>) {
        if (entriesNeedingImageFetch(entries, imageBackfill.claimedIndices()).isEmpty()) return
        if (imageBackfillJob?.isActive == true) return
        val requestedStream = _selectedStream.value
        imageBackfillJob = scope.launch { runImageBackfill(sessionId, requestedStream) }
    }

    /**
     * Drain the outstanding image fetches for [sessionId] in batches of
     * [IMAGE_BACKFILL_BATCH], newest entries first, pausing
     * [IMAGE_BACKFILL_BATCH_DELAY_MS] between passes.
     *
     * Bounded, because the desktop dispatches requests inline and in order:
     * firing every outstanding probe at once head-of-line blocks the delta poll
     * behind it for the whole burst. Newest-first, because those are the
     * entries on screen.
     *
     * Resumable, because each index is claimed immediately before its own
     * request and released the moment it fails. Nothing is claimed
     * speculatively, so abandoning a pass — session closed, tab switched,
     * transport momentarily gone — cannot mark the untried remainder as done.
     * The previous version claimed the whole batch up front, so a single failed
     * probe on a flaky link made every photo behind it unreachable until the
     * chat was closed and reopened.
     *
     * The window is re-read every pass rather than captured, so entries that
     * arrive mid-drain are picked up without waiting for the next poll.
     */
    private suspend fun runImageBackfill(sessionId: String, requestedStream: StreamIdDto) {
        while (true) {
            if (openSessionId != sessionId) return
            if (_selectedStream.value != requestedStream) return
            val window = (_session.value as? UiData.Loaded)?.value?.entries.orEmpty()
            val wanted = entriesNeedingImageFetch(window, imageBackfill.claimedIndices())
                .takeLast(IMAGE_BACKFILL_BATCH)
            if (wanted.isEmpty()) return
            var progressed = false
            for (index in wanted) {
                if (openSessionId != sessionId) return
                if (_selectedStream.value != requestedStream) return
                val active = context.activeClient() ?: return
                // Claimed for the duration of THIS request only.
                if (!imageBackfill.claim(index)) continue
                val fetched = runCatching {
                    active.getSessionEntry(
                        sessionId = sessionId,
                        index = index,
                        streamId = requestedStream,
                        includeImages = true,
                    )
                }.getOrElse {
                    if (it is kotlinx.coroutines.CancellationException) {
                        // Nothing was tried to completion — hand the claim back
                        // untouched so the next open/pass retries it.
                        imageBackfill.release(index)
                        throw it
                    }
                    // A transport blip must not make this photo unreachable for
                    // the rest of the open; give the claim back until the
                    // attempt budget runs out.
                    imageBackfill.fail(index)
                    continue
                }
                imageBackfill.settle(index)
                progressed = true
                val images = fetched.entry.images ?: emptyList()
                if (images.isEmpty()) continue
                sessionMutex.withLock {
                    if (openSessionId != sessionId) return@withLock
                    if (_selectedStream.value != requestedStream) return@withLock
                    val loaded = _session.value as? UiData.Loaded ?: return@withLock
                    _session.value = UiData.Loaded(
                        loaded.value.copy(
                            entries = withEntryImages(loaded.value.entries, index, images),
                        ),
                    )
                }
            }
            // A pass in which every probe failed means the wire is unhappy;
            // stop rather than spin. The next applied delta re-triggers.
            if (!progressed) return
            delay(IMAGE_BACKFILL_BATCH_DELAY_MS)
        }
    }

    /**
     * Drop optimistic bubbles whose `client_send_id` has landed in a server
     * pending bundle. The server's `pending_messages` is the single source of
     * truth for queued state: it MERGES sends from every client into shared
     * bundles (mobile + desktop typing into the same busy session collapse
     * into one growing bundle, and a desktop EDIT rewrites that bundle's
     * preview in place). Once a csid lands in a server bundle we drop the
     * local optimistic and let the synthetic bundle bubble represent it —
     * keeping the local optimistic instead would show stale mobile-only text
     * after a desktop edit and render a cross-client merge as two competing
     * bubbles.
     *
     * MUST hold [sessionMutex] — mutates the three optimistic lists in
     * lock-step. Folded into [applyDeltaLocked] so the delta's
     * `pending_bundles` section stays the single writer for this transition.
     */
    private fun reconcilePendingBundleOptimisticsLocked(
        bundles: List<QueuedBundleSummary>,
    ) {
        val csidsInBundles: Set<Long> = bundles.flatMap { it.csids }.toHashSet()
        if (csidsInBundles.isEmpty()) return
        val current = _optimisticEntries.value
        val keptIndices = current.indices.filter { i ->
            current[i].clientSendId !in csidsInBundles
        }
        if (keptIndices.size == current.size) return
        _optimisticEntries.value = keptIndices.map { current[it] }
        val keptIds = keptIndices.mapNotNull { optimisticIds.getOrNull(it) }
        val keptCsids = keptIndices.mapNotNull { optimisticClientSendIds.getOrNull(it) }
        optimisticIds.clear()
        optimisticIds.addAll(keptIds)
        optimisticClientSendIds.clear()
        optimisticClientSendIds.addAll(keptCsids)
        // The disk marker (offline-send rehydrate) is no longer needed once
        // the server has the message in a bundle.
        for (csid in csidsInBundles) pendingSendsRepository.remove(csid)
    }

    private fun applyFetchFailure(sessionId: String, throwable: Throwable) {
        // Never render a coroutine's cancellation as a fetch error. Callers
        // rethrow it, but this is the single seam that writes the failure into
        // user-visible state, so it refuses the whole class rather than
        // trusting every caller.
        if (throwable is kotlinx.coroutines.CancellationException) return
        scope.launch {
            sessionMutex.withLock {
                if (openSessionId != sessionId) return@withLock
                if (_session.value !is UiData.Loaded) {
                    _session.value = UiData.Error(throwable.message ?: "unknown error")
                }
            }
        }
    }

    private fun persistCache(
        sessionId: String,
        fetched: GetSessionResult,
        entries: List<EntrySummary>,
        newTotalCount: Int,
        epoch: Long,
        lastSeq: Long,
    ) {
        // The disk cache is rendered instantly on reopen, and a session always
        // reopens on Main (openSession resets selectedStream to Main). With
        // server-side per-stream scoping, caching a non-Main stream's window
        // would flash the wrong transcript on Main, so only persist while
        // viewing Main. Other streams re-fetch on selection, so they need no
        // disk cache.
        if (_selectedStream.value != StreamIdDto.Main) return
        val lastIdx = entries.mapNotNull { it.index.takeIf { i -> i >= 0 } }.maxOrNull()
        sessionHistoryRepository.save(
            CachedSessionHistory(
                sessionId = sessionId,
                solutionId = fetched.solutionId,
                agentId = fetched.agentId,
                // Never cache image payloads. A lazily back-filled photo is
                // several MB of base64 that would be re-serialised, re-encrypted
                // and rewritten on every applied delta; the repository would
                // then throw it away anyway (it strips anything over 4 KB).
                // Dropping it here also makes the reopened window ask for its
                // images again through the per-entry path rather than rendering
                // a half-stripped one.
                //
                // `imageCount` deliberately rides along: it costs a few bytes
                // and it is what lets a cache hit go straight to the entries
                // that actually have images, instead of probing every user
                // message in the restored window.
                entries = entries.map { if (it.images == null) it else it.copy(images = null) },
                lastIndex = lastIdx,
                totalCountAtLastWrite = newTotalCount,
                // The cursor is CAPTURED inside the writer's `withLock` and
                // passed in, not re-read here. Reading `openEpoch`/`openSeq`
                // off-lock let a session switch land in between and stamp this
                // session's cache with another session's cursor — after which
                // the next open polls from above its own entries, gets an
                // empty delta and shows a stale window.
                epoch = epoch,
                lastSeq = lastSeq,
            ),
        )
    }

    /**
     * Trailing-edge debounced trigger for the live delta poll — the single
     * entry point every push handler, the tail-resync and the safety-net tick
     * use.
     *
     * Two separate jobs, deliberately:
     *  - [deltaDebounceJob] is the 200 ms quiet-window timer. A burst of pushes
     *    cancels and re-arms it, so the burst collapses into ONE poll.
     *  - [deltaPollJob] is the poll itself, and a trigger NEVER cancels it. The
     *    JSON-RPC cancel is client-local: the server still executes the request
     *    and still writes the whole response onto a serial connection, so
     *    cancel-and-rearm under a stream of pushes paid for every response and
     *    applied none of them — the cursor never advanced and the next poll
     *    re-fetched the same (growing) page. A trigger that lands mid-poll sets
     *    [repollRequested] instead, and the loop runs one more iteration from
     *    the advanced cursor.
     *
     * The trigger also waits out [initialLoadJob]: a poll fired while the
     * open's `get_session` is still in flight would either duplicate that
     * multi-megabyte load or apply a delta onto a base that is about to be
     * replaced.
     */
    private fun scheduleDeltaPoll(sessionId: String) {
        if (openSessionId != sessionId) return
        deltaDebounceJob?.cancel()
        deltaDebounceJob = scope.launch {
            delay(DELTA_POLL_DEBOUNCE_MS)
            initialLoadJob?.join()
            if (openSessionId != sessionId) return@launch
            requestDeltaPoll(sessionId)
        }
    }

    /**
     * Single-flight admission for [runDeltaPollLoop]. A request that arrives
     * while a poll is already running is recorded on [pollGate] rather than
     * starting a second one or cancelling the first; see [DeltaPollGate] for
     * why that has to be one critical section with the loop's stop decision.
     */
    private fun requestDeltaPoll(sessionId: String) {
        if (openSessionId != sessionId) return
        if (!pollGate.admit()) return
        deltaPollJob = scope.launch {
            try {
                runDeltaPollLoop(sessionId)
            } finally {
                // Covers every exit the loop's own tail does not: session
                // switch, no client, an exhausted failure budget, cancellation.
                if (pollGate.finishAndShouldRearm() && openSessionId == sessionId) {
                    requestDeltaPoll(sessionId)
                }
            }
        }
    }

    /**
     * Poll `get_session_changes` from the held [openSeq]/[openEpoch] until the
     * selected stream is caught up.
     *
     * Termination is [shouldRepollAfterSuccess]: a response with
     * `has_more == false` IS convergence — the server derived the page from
     * `mod_seq > since_seq` over the selected stream, found nothing left over,
     * and handed back that stream's own `seq`. It keeps going only for the
     * next page of a paginated catch-up or for a push that landed while the
     * request was on the wire, and puts [POLL_FLOOR_DELAY_MS] between
     * successful iterations so a busy session cannot drive the loop at 1/RTT.
     *
     * A FAILED poll (transport error / reconnect window) is retried with
     * bounded exponential backoff — that is what heals an interrupted reply
     * whose trailing pokes were lost on a flaky link. Failures are consecutive:
     * any response resets the budget.
     *
     * `reset == true` means the epoch rotated; a full [fetchFullSession] snaps
     * the cursor to the live state, which is convergence by definition.
     *
     * ### Cross-file invariant: held bodies are frozen across a poll
     *
     * Each iteration snapshots the window, digests it into `known_entries`,
     * and a full round trip later splices the server's tails onto whatever is
     * held then. **No writer may change an existing entry's `markdown`
     * between those two moments.** Adding `images`, prepending an older page,
     * or replacing the whole window are all fine; rewriting a body in place
     * is not, and neither the wire nor the length check in
     * [rehydrateEntryBodies] can detect it (the server always sets
     * `markdown_len == prefix_len + tail.length`, so any base of the right
     * length passes).
     *
     * Every writer today already respects it — the image backfill touches
     * only `images`, `loadOlder` only prepends absent indices, and every path
     * that replaces the window (`openSession`, `selectStream`, a context
     * reset) cancels this loop first. Rather than leave that resting on seven
     * call sites in one file agreeing forever, [planDeltaApply] re-digests
     * each splice base against the offer this loop actually sent, so a future
     * writer that breaks the rule gets a redundant full reload instead of a
     * silently corrupted transcript.
     */
    private suspend fun runDeltaPollLoop(sessionId: String) {
        var failures = 0
        var backoffMs = POLL_MIN_BACKOFF_MS
        while (failures < POLL_MAX_FAILURES) {
            if (openSessionId != sessionId) return
            val active = context.activeClient() ?: return
            // Snapshot the cursor under the lock so we don't race a
            // concurrent applyDeltaLocked / fetchFullSession advancing it.
            // Snapshot the cursor AND the window the digests are computed over
            // in the same critical section, so the `known_entries` we send can
            // never describe a window a concurrent apply has already replaced.
            val (sinceSeq, knownEpoch, held) = sessionMutex.withLock {
                if (openSessionId != sessionId) return
                Triple(openSeq, openEpoch, (_session.value as? UiData.Loaded)?.value?.entries.orEmpty())
            }
            val offered = knownEntriesFor(active, held).orEmpty()
            val delta = runCatching {
                active.getSessionChanges(
                    sessionId = sessionId,
                    sinceSeq = sinceSeq,
                    knownEpoch = knownEpoch,
                    streamId = _selectedStream.value,
                    // The transcript never carries base64 on this path — a
                    // changed entry is re-sent whole on every `mod_seq` bump,
                    // so inlining its images meant re-downloading them for the
                    // life of the session. `backfillEntryImages` fetches them
                    // once, per entry, out of band.
                    includeImages = false,
                    // Digest the bodies we already hold so a growing assistant
                    // reply comes back as its tail rather than whole. Absent
                    // the feature this is empty and the request is
                    // byte-identical to the pre-negotiation one. The list is
                    // KEPT: `applyDeltaLocked` re-proves the response's splice
                    // bases against these exact digests, which is the only
                    // thing that can notice a writer having rewritten a held
                    // body while this request was on the wire.
                    knownEntries = offered.takeIf { it.isNotEmpty() },
                    omitPreviewWhenMarkdown = active.serverFeatures.value
                        .has(WireFeature.OMIT_PREVIEW),
                )
            }
                // A cancelled poll means the session closed / the stream
                // switched — unwind instead of burning a retry attempt on it.
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull()
            if (delta == null) {
                failures++
                if (failures >= POLL_MAX_FAILURES) return
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(POLL_MAX_BACKOFF_MS)
                continue
            }
            failures = 0
            backoffMs = POLL_MIN_BACKOFF_MS
            if (openSessionId != sessionId) return
            if (delta.reset) {
                fetchFullSession(active, sessionId)
                return
            }
            applyDeltaLocked(sessionId, delta, offered)
            // Consumes any pending poke and publishes the stop decision in ONE
            // critical section, so a poke landing here either gets picked up by
            // this iteration or finds the gate closed and starts a fresh loop.
            // There is no third outcome in which it is dropped.
            if (!pollGate.continueOrFinish(hasMore = delta.hasMore)) return
            delay(POLL_FLOOR_DELAY_MS)
        }
    }

    fun loadOlder(sessionId: String) {
        if (openSessionId != sessionId) return
        if (_isLoadingOlder.value) return
        val active = context.activeClient() ?: return
        val current = _session.value as? UiData.Loaded ?: return
        val oldest = current.value.entries.firstOrNull() ?: return
        val oldestIndex = oldest.index
        if (oldestIndex <= 0) return
        _isLoadingOlder.value = true
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("include_full_content", true)
            // Same rule as [fetchFullSession]: pages carry no base64, images
            // arrive per entry via [backfillEntryImages].
            put("include_images", false)
            put("stream_id", JsonRpc.json.encodeToJsonElement(StreamIdDto.serializer(), _selectedStream.value))
            put("before_index", oldestIndex)
            put("count", SESSION_PAGE_SIZE)
            // Same reasoning as [fetchFullSession]: this page is bodies, so
            // the previews beside them are pure duplication.
            putOmitPreviewWhenMarkdown(active.serverFeatures.value)
        }
        scope.launch {
            val outcome = runCatching { active.call("remote.solution_agent.get_session", params) }
                .mapCatching { resp -> resp.decodeResultOrThrow(GetSessionResult.serializer()) }
            var olderPage: List<EntrySummary> = emptyList()
            sessionMutex.withLock {
                if (openSessionId != sessionId) {
                    _isLoadingOlder.value = false
                    return@withLock
                }
                outcome
                    .onSuccess { result ->
                        val latest = _session.value as? UiData.Loaded
                        if (latest == null) {
                            _isLoadingOlder.value = false
                            return@onSuccess
                        }
                        val existingIndices = latest.value.entries.mapNotNull {
                            it.index.takeIf { i -> i >= 0 }
                        }.toHashSet()
                        val older = result.entries.filterNot {
                            it.index >= 0 && existingIndices.contains(it.index)
                        }
                        val merged = older + latest.value.entries
                        val newTotal = maxOf(latest.value.totalCount, result.totalCount)
                        _session.value = UiData.Loaded(
                            latest.value.copy(entries = merged, totalCount = newTotal),
                        )
                        _isLoadingOlder.value = false
                        olderPage = older
                    }
                    .onFailure {
                        _isLoadingOlder.value = false
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        context.emitError("Couldn't load older messages: ${it.message ?: "?"}")
                    }
            }
            backfillEntryImages(sessionId, olderPage)
        }
    }

    /**
     * Reconnect / foreground-resume entry point (called externally by
     * `MainViewModel`). Flushes a deferred cancel queued while offline
     * (`cancel_turn` is server-side idempotent, so a flush against an
     * already-Stopping/Idle session is a safe no-op), then kicks the live
     * delta poll — which pulls everything new (full changed-entry bodies +
     * the freshest session state) since the held cursor, or full-reloads on
     * an epoch mismatch. The old `after_index` get_session merge + gap logic
     * + placeholder healing are subsumed by the delta poll.
     */
    fun resumeSession(sessionId: String) {
        if (openSessionId != sessionId) return
        flushPendingCancel()
        scheduleDeltaPoll(sessionId)
    }

    /**
     * Replace [_streams] from a fresh `GetSessionResult` / delta and snap
     * [_selectedStream] back to a valid value if the previously-selected
     * stream disappeared. MUST be called while holding [sessionMutex] — the
     * delta's `streams` is the single writer, and this keeps cold-start /
     * full-refresh and live updates converging on the same invariant.
     *
     * The snap target is `streams.firstOrNull()?.id ?: Main` — the server
     * always sends Main first, so this lands on Main whenever the selected
     * non-Main stream is gone.
     *
     * Returns `true` when the selection was SNAPPED (the previously-selected
     * stream is no longer present — e.g. a teammate stream auto-closed
     * server-side and dropped out of the descriptor list). The delta caller
     * uses this to force a clean full refetch of the now-selected stream
     * instead of publishing the merged (stale-stream) window.
     * [fetchFullSession] ignores the return — it's already a full load.
     */
    private fun applyStreamsLocked(streams: List<StreamDto>): Boolean {
        _streams.value = streams
        val current = _selectedStream.value
        if (streams.none { it.id == current }) {
            _selectedStream.value = streams.firstOrNull()?.id ?: StreamIdDto.Main
            return true
        }
        return false
    }

    private fun reconcileOptimisticLocked(serverEntries: List<EntrySummary>) {
        if (_optimisticEntries.value.isEmpty()) return
        val priorEntries = _optimisticEntries.value
        val priorIds = optimisticIds.toList()
        val priorCsids = optimisticClientSendIds.toList()
        val (keptEntries, keptIds, keptCsids) = reconcileOptimistic(
            optimistic = priorEntries,
            optimisticIds = priorIds,
            optimisticClientSendIds = priorCsids,
            serverEntries = serverEntries,
        )
        _optimisticEntries.value = keptEntries
        optimisticIds.clear()
        optimisticIds.addAll(keptIds)
        optimisticClientSendIds.clear()
        optimisticClientSendIds.addAll(keptCsids)
        // Drop disk markers for every csid that just reconciled away (its
        // server entry landed) — otherwise a plain send delivered while the
        // app was backgrounded (no live echo observed) would leave a stale
        // marker that rehydrates a phantom bubble on the next open.
        val droppedCsids = priorCsids.filterNotNull().toSet() - keptCsids.filterNotNull().toSet()
        for (csid in droppedCsids) pendingSendsRepository.remove(csid)
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        // Route text-only sends through `sendMessageBlocks` so the
        // payload carries a `_meta.spk_client_send_id` stamp the
        // server echoes back on the user entry. The legacy
        // `send_message` RPC (raw `content: String`, no _meta seam)
        // produced no csid → reconcile fell through to a content-
        // match path that briefly let the local optimistic and the
        // server echo coexist as two visible bubbles. Diagnosed
        // 2026-05-20 via a "duplicate bubble that resolves a beat
        // later" report from a plain text send to an Idle agent.
        sendMessageBlocks(listOf(ContentBlockDto.Text(text)))
    }

    /**
     * Multi-block variant of [sendMessage] backing the mobile attach flow.
     * The block list is encoded as `Vec<acp::ContentBlock>` on the wire
     * and dispatched via `remote.solution_agent.send_message_blocks`.
     *
     * The optimistic bubble carries a flattened text preview (text blocks
     * concatenated, plus `[image]` / `[file]` annotations for non-text
     * payloads) so the chat surface shows immediate feedback. We stamp
     * `_meta.spk_client_send_id` onto the first block before send and
     * record the same id in [optimisticClientSendIds] — the server echoes
     * it back on the resulting user entry, letting
     * [reconcileOptimisticLocked] dedupe by id rather than a fragile
     * preview-content match (which the server-side ACP rendering of a
     * blocks send doesn't honour anyway). Failure removes the bubble
     * synchronously and surfaces the reason via [ConnectionContext.emitError].
     */
    fun sendMessageBlocks(blocks: List<ContentBlockDto>) {
        if (blocks.isEmpty()) return
        val active = context.activeClient() ?: return
        val sessionId = openSessionId ?: return
        val preview = buildBlocksPreview(blocks)
        // Full user-typed body for the optimistic bubble. UserBubble
        // renders `markdown ?: preview` — populating `markdown` here lets
        // the bubble show the COMPLETE multi-line message immediately
        // instead of `buildBlocksPreview`'s first-line + 200-char clamp.
        // Without this, long sends flashed as a truncated stub until the
        // delta poll's `changed_entries` back-filled the real body.
        val fullMarkdown = buildBlocksFullText(blocks)
        val localId = optimisticIdGen.incrementAndGet()
        val clientSendId = clientSendIdGen.incrementAndGet()
        val optimistic = EntrySummary(
            role = EntryRoleDto.User,
            preview = preview,
            markdown = fullMarkdown.takeIf { it.isNotEmpty() },
            clientSendId = clientSendId,
        )
        val stamped = stampClientSendId(blocks, clientSendId)
        // Persist a marker so the optimistic bubble survives navigation
        // away-and-back while the send is still in flight (e.g. the wire
        // is down and `queueCall` is parked in the offline queue). Without
        // this, [openSession] clears `_optimisticEntries` on re-entry and
        // the in-memory-only bubble vanishes even though the message is
        // still queued to send — the "message with the clock icon
        // disappeared when I left and came back" bug. Empty `attachments`
        // distinguishes a plain text send from a deferred-upload send;
        // [openSession] rehydrates the former, [resumeDeferredSendsFromDisk]
        // skips it (the offline queue already owns the actual RPC replay,
        // so reviving a zero-upload deferred waiter would double-send).
        val bouncePayload = fullMarkdown.takeIf { it.isNotEmpty() } ?: preview
        sentCsidsInFlight.add(clientSendId)
        // Written on the CALLER's thread, deliberately. See the ordering
        // invariant in the class KDoc: anything that suspends between the tap
        // and `queueCall` lets a later tap overtake this one on the wire, and
        // the server applies messages in the order it reads them. The write is
        // `apply()`-backed and the prefs file is warmed at ViewModel
        // construction (`warmUpEncryptedPrefs`), so what runs here is an AES
        // encrypt of one small record, not a Keystore open.
        pendingSendsRepository.saveOrUpdate(
            PersistedPendingSend(
                csid = clientSendId,
                localId = localId,
                sessionId = sessionId,
                // Persist the FULL typed body so rehydration on next
                // [openSession] re-shows the complete message, not the
                // truncated `buildBlocksPreview` clamp.
                text = bouncePayload,
                attachments = emptyList(),
            ),
        )
        // The optimistic bubble rides its OWN coroutine: `sessionMutex` can be
        // held by an in-flight delta apply, and waiting for it here would put a
        // suspension point in front of the wire write.
        scope.launch {
            sessionMutex.withLock {
                _optimisticEntries.value = _optimisticEntries.value + optimistic
                optimisticIds.add(localId)
                optimisticClientSendIds.add(clientSendId)
            }
        }
        scope.launch {
            val blocksJson = JsonRpc.json.encodeToJsonElement(
                ListSerializer(ContentBlockDto.serializer()),
                stamped,
            )
            val params = buildJsonObject {
                put("session_id", sessionId)
                put("blocks", blocksJson)
            }
            // Fire-and-forget: the server's `send_message_blocks`
            // (store/queue.rs) already does the "if Running, queue +
            // merge into pending_messages" half. Each press from the
            // mobile becomes its own immediate queueCall; if the
            // session is mid-turn the server appends the bundle to
            // `pending_messages` (separated by `\n\n`) and flushes
            // everything as one merged user entry once the turn
            // settles. The mobile shows a Queued badge per optimistic
            // bubble while the session is Running, derived from the
            // bubble being optimistic + the live session state.
            //
            // Typed outcome, so the failure policy can tell "the editor link
            // failed, delivery unknown" from "the tool declined" — and so an
            // ambiguous failure can be replayed against a peer that
            // de-duplicates by `spk_client_send_id`.
            val outcome = dispatchSendWithRetry(
                active = active,
                csid = clientSendId,
                params = params,
                alreadyBounced = { clientSendId in bouncedByQueueCsids },
            )
            sentCsidsInFlight.remove(clientSendId)
            outcome
                .onSuccess { result ->
                    // Every branch below is a SUCCESS. The server received
                    // the message, or recognised it as one it had already
                    // received — from here those are the same thing, and
                    // neither may surface as an error: the copy in the
                    // transcript is what pops the optimistic bubble, by csid.
                    //
                    // Written as an exhaustive `when` rather than an equality
                    // check so a fourth verdict cannot be added upstream
                    // without this site failing to compile. Only `Duplicate`
                    // is allowed to mean "de-duplicated"; `Unknown` is a
                    // server newer than this build and must land with the
                    // conservative majority.
                    when (result.delivery) {
                        SendDeliveryDto.Duplicate -> android.util.Log.i(
                            "SessionDetailStore",
                            "send csid=$clientSendId was already accepted — " +
                                "server de-duplicated the replay",
                        )
                        SendDeliveryDto.Accepted,
                        SendDeliveryDto.Unknown,
                        null -> Unit
                    }
                    // The disk marker is no longer needed either way — the
                    // optimistic bubble is popped by the server echo via
                    // `client_send_id`.
                    pendingSendsRepository.remove(clientSendId)
                }
                .onFailure { failure ->
                    // A cancelled scope (ViewModel teardown, session switch)
                    // is NOT a send failure: swallowing it here deleted the
                    // marker and the bubble for a message the durable queue
                    // was still holding.
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    // The user withdrew this send themselves — the cancel path
                    // owns the text and the notice, so this one stays quiet.
                    val userCancelled = failure is QueueCancelledException
                    when (classifySendFailure(failure, clientSendId in bouncedByQueueCsids)) {
                        // Unreachable: the replay branch is consumed inside
                        // [dispatchSendWithRetry], which only ever returns a
                        // TERMINAL outcome. Folded onto KeepQueued rather
                        // than left to a wildcard so adding a fifth action
                        // still fails the compile here.
                        SendFailureAction.Requeue,
                        SendFailureAction.KeepQueued -> {
                            // Still on disk for the next client — leave the
                            // marker and the bubble exactly where they are.
                        }
                        SendFailureAction.ClearOnly -> {
                            pendingSendsRepository.remove(clientSendId)
                            removeOptimisticById(localId)
                        }
                        SendFailureAction.Bounce -> {
                            // The user's text is returned to the composer
                            // before the bubble disappears, so no send can
                            // ever leave them with an empty field and nothing
                            // to show for it.
                            draftRepository.setBounced(sessionId, bouncePayload)
                            pendingSendsRepository.remove(clientSendId)
                            removeOptimisticById(localId)
                        }
                    }
                    bouncedByQueueCsids.remove(clientSendId)
                    if (!userCancelled) context.emitError(sendFailureMessage(failure))
                }
        }
    }

    /**
     * Fire a multi-block send whose attachments may not be uploaded
     * yet. Creates the optimistic bubble immediately so the user sees
     * their message land in the chat with a "Uploading N/M" badge,
     * then awaits each upload to a terminal state, swaps in the
     * `spk-upload://<id>` handles, and finally dispatches
     * `send_message_blocks` over the wire.
     *
     * Failure modes:
     *   - any upload reaches a `Failed` terminal → drop the bubble +
     *     surface the upload's reason via [ConnectionContext.emitError].
     *   - the eventual `queueCall` fails → drop the bubble + surface
     *     the queue / server reason, same as [sendMessageBlocks].
     *
     * The `awaitTerminal` callback is the seam to
     * [UploadManager.awaitTerminal]; it returns the resolved
     * `spk-upload://<id>` handle on success or `null` on failure
     * (matching the upload manager's contract).
     */
    fun sendMessageBlocksDeferred(
        textBlock: ContentBlockDto.Text?,
        uploads: List<DeferredUpload>,
        stateFlowOf: (localKey: String) -> StateFlow<UploadManager.State>?,
        forgetUpload: (localKey: String) -> Unit,
    ) {
        if (textBlock == null && uploads.isEmpty()) return
        val sessionId = openSessionId ?: return
        val localId = optimisticIdGen.incrementAndGet()
        val clientSendId = clientSendIdGen.incrementAndGet()
        // Persist BEFORE the first await so a force-kill during the uploads
        // survives — the cold-start resumeDeferredSendsFromDisk path picks the
        // record up and re-spawns an identical waiter coroutine. The write
        // itself goes to IO: it opens an encrypted prefs file, which
        // unwraps a Tink keyset through the Android Keystore and would
        // otherwise block the tap handler.
        val record = PersistedPendingSend(
            csid = clientSendId,
            localId = localId,
            sessionId = sessionId,
            text = textBlock?.text,
            attachments = uploads.map {
                PersistedPendingAttachment(
                    localKey = it.localKey,
                    displayName = it.displayName,
                    mime = it.mime,
                )
            },
        )
        runDeferredSend(
            persist = record,
            send = InflightDeferredSend(
                csid = clientSendId,
                localId = localId,
                sessionId = sessionId,
                text = textBlock?.text,
                attachments = uploads,
            ),
            seedOptimistic = true,
            stateFlowOf = stateFlowOf,
            forgetUpload = forgetUpload,
        )
    }

    /**
     * Cold-start recovery: revives a waiter coroutine for every
     * pending send that was on disk when the process died. Called
     * from the coordinator's `onClientBound` AFTER the upload manager
     * has rehydrated its per-upload state — otherwise [awaitTerminal]
     * for a persisted `localKey` would resolve null before the upload
     * coroutine had a chance to register its StateFlow.
     *
     * The UI side does NOT seed an optimistic entry here: the user
     * may not even be on the matching session yet. When they DO open
     * it, [openSession] reads [inflightDeferred] and re-creates the
     * bubble from the held metadata.
     *
     * The revived waiters are launched without awaiting each other, and that
     * is deliberate rather than an ordering oversight: each one blocks on its
     * own attachments reaching a terminal state before it dispatches, so wire
     * order follows upload-completion order no matter what launch order was.
     * Serialising the launches would only mean the first stalled upload held
     * every other resumed send hostage.
     */
    fun resumeDeferredSendsFromDisk(
        stateFlowOf: (localKey: String) -> StateFlow<UploadManager.State>?,
        forgetUpload: (localKey: String) -> Unit,
    ) {
        scope.launch { resumeDeferredSendsFromDiskOnIo(stateFlowOf, forgetUpload) }
    }

    private suspend fun resumeDeferredSendsFromDiskOnIo(
        stateFlowOf: (localKey: String) -> StateFlow<UploadManager.State>?,
        forgetUpload: (localKey: String) -> Unit,
    ) {
        // Sweep orphaned markers first, so a phantom left by a process death
        // is gone before anything can re-materialise it. A marker survives the
        // sweep only while it is backed by a queue entry (the durable replay
        // that will actually deliver it) or by a live send coroutine in this
        // process; everything else can never resolve.
        val queuedCsids = queuedCsidsProvider?.let { runCatching { it() }.getOrNull() }
        if (queuedCsids != null) {
            runCatching {
                pendingSendsRepository.gcOrphansOnIo(
                    liveCsids = queuedCsids,
                    inFlightCsids = inflightDeferred.keys.toSet() + sentCsidsInFlight,
                )
            }
        }
        val persisted = pendingSendsRepository.listOnIo()
        for (p in persisted) {
            if (inflightDeferred.containsKey(p.csid)) continue
            // Plain text sends (no attachments) are NOT revived as
            // deferred waiters: the offline queue (`queueCall` →
            // EncryptedQueueStore) already owns their RPC replay on
            // reconnect. Reviving a zero-upload deferred waiter would
            // fire a SECOND queueCall → duplicate send. They're kept on
            // disk only so [openSession] can re-show the optimistic
            // bubble; the marker is cleared when the optimistic pops
            // (echo / reconcile) or the send succeeds / fails.
            if (p.attachments.isEmpty()) continue
            runDeferredSend(
                send = InflightDeferredSend(
                    csid = p.csid,
                    localId = p.localId,
                    sessionId = p.sessionId,
                    text = p.text,
                    attachments = p.attachments.map {
                        DeferredUpload(
                            localKey = it.localKey,
                            displayName = it.displayName,
                            mime = it.mime,
                        )
                    },
                ),
                seedOptimistic = false,
                stateFlowOf = stateFlowOf,
                forgetUpload = forgetUpload,
            )
        }
    }

    private fun runDeferredSend(
        send: InflightDeferredSend,
        seedOptimistic: Boolean,
        stateFlowOf: (localKey: String) -> StateFlow<UploadManager.State>?,
        forgetUpload: (localKey: String) -> Unit,
        persist: PersistedPendingSend? = null,
    ) {
        inflightDeferred[send.csid] = send
        scope.launch {
            // Written here rather than by the caller so it cannot race this
            // coroutine's own `remove` on an early terminal.
            if (persist != null) pendingSendsRepository.saveOrUpdateOnIo(persist)
            if (seedOptimistic && openSessionId == send.sessionId) {
                seedOptimisticForDeferred(send)
            }
            // Per-attachment byte-level progress observation. The
            // bubble's "Uploading X / Y MB" badge updates on every
            // upstream ack; on Paused (no chunks moving, e.g. ack
            // timeout, ws drop) the badge flips to the Paused
            // variant so the user can tell stuck-vs-slow.
            //
            // `bytesDoneFromPrior` accumulates the COMPLETED
            // attachments' totalSize, so byte progress on the
            // currently-being-uploaded attachment lifts the bar
            // monotonically across all N attachments.
            val handles = ArrayList<String>(send.attachments.size)
            var bytesDoneFromPrior = 0L
            var totalBytesAcrossAll = 0L
            for (u in send.attachments) {
                val flow = stateFlowOf(u.localKey)
                if (flow != null) {
                    totalBytesAcrossAll += totalBytesFromState(flow.value)
                }
            }
            for ((idx, u) in send.attachments.withIndex()) {
                val flow = stateFlowOf(u.localKey)
                if (flow == null) {
                    cleanupDeferred(
                        send = send,
                        failureReason =
                            "`${u.displayName}` upload state lost — re-attach to retry",
                        forgetUpload = forgetUpload,
                        stateFlowOf = stateFlowOf,
                    )
                    return@launch
                }
                val perAttachmentTotal = totalBytesFromState(flow.value)
                // Wait for this attachment to reach a terminal state, giving
                // up only when it goes SILENT for
                // [DEFERRED_UPLOAD_STALL_TIMEOUT_MS]. The chunk loop in
                // UploadManager has a 30s per-ack timeout that transitions to
                // Paused — but Paused isn't terminal, so without an outer
                // guard the coroutine would wait forever on a permanently
                // stuck upload; and a wall-clock guard would instead kill a
                // large attachment that is uploading perfectly well.
                var watermark = UploadProgressWatermark(
                    lastProgressAtMs = System.currentTimeMillis(),
                )
                var terminal: UploadManager.State? = null
                while (true) {
                    val state = flow.value
                    val (sent, status) = when (state) {
                        is UploadManager.State.Queued ->
                            0L to PendingUploadProgress.Status.Uploading
                        is UploadManager.State.Uploading ->
                            state.sent to PendingUploadProgress.Status.Uploading
                        is UploadManager.State.Paused ->
                            state.sent to PendingUploadProgress.Status.Paused
                        is UploadManager.State.Done ->
                            perAttachmentTotal to PendingUploadProgress.Status.Uploading
                        is UploadManager.State.Failed ->
                            0L to PendingUploadProgress.Status.Uploading
                    }
                    if (openSessionId == send.sessionId) {
                        sessionMutex.withLock {
                            val map = _pendingUploadProgress.value.toMutableMap()
                            map[send.csid] = PendingUploadProgress(
                                sentBytes = bytesDoneFromPrior + sent,
                                totalBytes = totalBytesAcrossAll.coerceAtLeast(1L),
                                status = status,
                            )
                            _pendingUploadProgress.value = map
                        }
                    }
                    if (state is UploadManager.State.Done || state is UploadManager.State.Failed) {
                        terminal = state
                        break
                    }
                    val now = System.currentTimeMillis()
                    watermark = trackUploadProgress(watermark, sentBytesFromState(state), now)
                    if (isUploadStalled(watermark, now, DEFERRED_UPLOAD_STALL_TIMEOUT_MS)) break
                    kotlinx.coroutines.withTimeoutOrNull(DEFERRED_UPLOAD_PROGRESS_TICK_MS) {
                        flow.first { it != state }
                    }
                }
                if (terminal == null) {
                    cleanupDeferred(
                        send = send,
                        failureReason =
                            "`${u.displayName}` upload stalled — try again (no progress for ${DEFERRED_UPLOAD_STALL_TIMEOUT_MS / 60_000}m)",
                        forgetUpload = forgetUpload,
                        stateFlowOf = stateFlowOf,
                    )
                    return@launch
                }
                when (terminal) {
                    is UploadManager.State.Failed -> {
                        cleanupDeferred(
                            send = send,
                            failureReason =
                                "`${u.displayName}` failed to upload: ${terminal.reason}",
                            forgetUpload = forgetUpload,
                            stateFlowOf = stateFlowOf,
                        )
                        return@launch
                    }
                    is UploadManager.State.Done -> {
                        handles += terminal.handle
                        bytesDoneFromPrior += perAttachmentTotal
                    }
                    else -> {
                        // Defensive: `first` returns only on Done/Failed.
                        cleanupDeferred(
                            send = send,
                            failureReason = "`${u.displayName}` upload ended in unexpected state",
                            forgetUpload = forgetUpload,
                            stateFlowOf = stateFlowOf,
                        )
                        return@launch
                    }
                }
                // Index variable is unused after Done; suppress.
                @Suppress("UNUSED_VARIABLE")
                val _idx = idx
            }
            val finalBlocks = buildList<ContentBlockDto> {
                if (send.text != null) add(ContentBlockDto.Text(send.text))
                for ((idx, u) in send.attachments.withIndex()) {
                    add(
                        ContentBlockDto.ResourceLink(
                            name = u.displayName,
                            uri = handles[idx],
                        ),
                    )
                }
            }
            val stamped = stampClientSendId(finalBlocks, send.csid)
            // Clear the upload-progress badge BEFORE the network call —
            // the bubble transitions to "Sending" (clock icon) for the
            // RTT window between queueCall enqueue and server echo.
            sessionMutex.withLock {
                val map = _pendingUploadProgress.value
                if (send.csid in map) _pendingUploadProgress.value = map - send.csid
            }
            val blocksJson = JsonRpc.json.encodeToJsonElement(
                ListSerializer(ContentBlockDto.serializer()),
                stamped,
            )
            val params = buildJsonObject {
                put("session_id", send.sessionId)
                put("blocks", blocksJson)
            }
            val active = context.activeClient()
            if (active == null) {
                // No client right now (rare — we shouldn't reach here
                // unless the connection dropped between Done and the
                // fire). Keep the disk record so a future
                // resumeDeferredSendsFromDisk picks it up; drop the
                // in-memory runtime so the next resume can re-spawn.
                inflightDeferred.remove(send.csid)
                return@launch
            }
            // Every upload is Done, so the RPC no longer depends on any
            // upload state: rewrite the disk record WITHOUT its attachments so
            // it reads exactly like a plain text send. A process death from
            // here on then resumes through the durable queue (which owns the
            // RPC replay) instead of `resumeDeferredSendsFromDisk` re-spawning
            // a waiter for handles the server already consumed — the path that
            // delivered the message AND told the user the upload had expired.
            pendingSendsRepository.saveOrUpdateOnIo(
                PersistedPendingSend(
                    csid = send.csid,
                    localId = send.localId,
                    sessionId = send.sessionId,
                    text = send.text,
                    attachments = emptyList(),
                ),
            )
            // Fire-and-forget once uploads have all reached terminal.
            // Server-side `send_message_blocks` queues+merges into
            // `pending_messages` when the session is Running, so we
            // don't need a client-side gate here either — same model
            // as the text-only [sendMessageBlocks] path.
            // Typed outcome, so the failure policy can tell "the editor link
            // failed, delivery unknown" from "the tool declined"; ambiguous
            // failures are replayed in place when the peer de-duplicates by
            // `spk_client_send_id`, so a lost response no longer costs the
            // user their uploaded attachments.
            val outcome = dispatchSendWithRetry(
                active = active,
                csid = send.csid,
                params = params,
                alreadyBounced = { send.csid in bouncedByQueueCsids },
            )
            outcome.fold(
                onSuccess = {
                    cleanupDeferred(
                        send = send,
                        failureReason = null,
                        forgetUpload = forgetUpload,
                        stateFlowOf = stateFlowOf,
                    )
                },
                onFailure = { failure ->
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    // The user withdrew this send themselves; the cancel path
                    // owns the text and the notice.
                    val userCancelled = failure is QueueCancelledException
                    val action = classifySendFailure(failure, send.csid in bouncedByQueueCsids)
                    bouncedByQueueCsids.remove(send.csid)
                    if (action == SendFailureAction.KeepQueued) {
                        // The bytes are uploaded and the call is still on disk
                        // for the next client. Drop only the process-local
                        // runtime; the bubble is re-materialised from the
                        // marker on the next open.
                        inflightDeferred.remove(send.csid)
                        return@fold
                    }
                    cleanupDeferred(
                        send = send,
                        failureReason = sendFailureMessage(failure),
                        forgetUpload = forgetUpload,
                        stateFlowOf = stateFlowOf,
                        bounceText = action == SendFailureAction.Bounce,
                        // The handles are only spent if the send SUCCEEDED —
                        // the server resolves them inside `send_message_blocks`.
                        // A send that failed leaves the tmp file on the server
                        // and the staged copy on disk, so a lost response must
                        // not cost the user their 4 MB photo as well as their
                        // text. Only a definitive refusal releases them.
                        keepAttachments = shouldKeepAttachmentsOnSendFailure(failure),
                        notifyUser = !userCancelled,
                    )
                },
            )
        }
    }

    private suspend fun seedOptimisticForDeferred(send: InflightDeferredSend) {
        val preview = buildDeferredPreview(send)
        // The csid stamp is mandatory: the chat surface routes the
        // "Uploading …" badge off `entry.clientSendId == send.csid`
        // — without it userBubbleStatusFor falls through to "Sending"
        // and the whole upload-progress UI silently dies.
        val optimistic = EntrySummary(
            role = EntryRoleDto.User,
            preview = preview,
            // Show the full typed body immediately; the preview is the
            // first-line / 200-char clamp used elsewhere (session list).
            markdown = send.text?.takeIf { it.isNotEmpty() },
            clientSendId = send.csid,
        )
        sessionMutex.withLock {
            // Defensive: don't re-seed if the bubble is already present
            // (shouldn't happen — runDeferredSend is the only producer
            // — but the cost of an extra check is one indexOf).
            if (send.csid in optimisticClientSendIds) return@withLock
            _optimisticEntries.value = _optimisticEntries.value + optimistic
            optimisticIds.add(send.localId)
            optimisticClientSendIds.add(send.csid)
            // Initial placeholder — the runDeferredSend observer's
            // first StateFlow tick replaces this with real byte counts
            // typically within tens of ms.
            _pendingUploadProgress.value = _pendingUploadProgress.value +
                (send.csid to PendingUploadProgress(
                    sentBytes = 0L,
                    totalBytes = 1L,
                    status = PendingUploadProgress.Status.Uploading,
                ))
        }
    }

    private fun buildDeferredPreview(send: InflightDeferredSend): String {
        val parts = mutableListOf<String>()
        send.text?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { parts += it }
        for (u in send.attachments) {
            parts += if (u.mime.startsWith("image/")) "[image]" else "[file ${u.displayName}]"
        }
        val joined = parts.joinToString(" ")
        return if (joined.length > 200) joined.take(197) + "..." else joined
    }

    /**
     * Terminal for a deferred send — success or failure.
     *
     * On FAILURE nothing the user typed or picked is thrown away:
     *  - [bounceText] returns the message body to the compose draft (unless
     *    the queue already bounced it, which would duplicate it), and
     *  - [keepAttachments] leaves the uploads alone and re-writes their
     *    [AttachmentRef]s into the attachment draft, so the chips come back
     *    instead of the user having to find and re-pick the photos.
     *
     * That combination is the fix for the "Wi-Fi blinked mid-upload and my
     * paragraph plus the photo silently vanished" path: the old code called
     * `forgetUpload` on every attachment (dropping the StateFlow, the metadata
     * and the disk record) and surfaced nothing but a snackbar the connection
     * banner could swallow.
     *
     * On SUCCESS the handles are spent server-side, so the upload bookkeeping
     * really is dead weight and is released.
     *
     * [notifyUser] suppresses the snackbar for a terminal the user asked for:
     * a cancelled send still has to release its uploads and drop its bubble,
     * but reporting it back as an error would describe the user's own tap as a
     * failure.
     */
    private suspend fun cleanupDeferred(
        send: InflightDeferredSend,
        failureReason: String?,
        forgetUpload: (localKey: String) -> Unit,
        stateFlowOf: (localKey: String) -> StateFlow<UploadManager.State>?,
        bounceText: Boolean = failureReason != null,
        keepAttachments: Boolean = failureReason != null,
        notifyUser: Boolean = true,
    ) {
        inflightDeferred.remove(send.csid)
        pendingSendsRepository.remove(send.csid)
        when {
            keepAttachments -> {
                // Put the picks back where the compose bar looks for them, and
                // do NOT forget the uploads: `forget` also deletes the staged
                // local copy and the server-side slot, and a retry needs both —
                // resuming a partially-uploaded attachment is exactly what the
                // held slot is for.
                restoreAttachmentDraft(send, stateFlowOf)
            }
            failureReason != null -> {
                // The bytes are uploaded but the send failed, so the handles
                // will never be consumed. Release the server slot explicitly:
                // it is one of only four per session and the server otherwise
                // holds the tmp file for an hour.
                for (att in send.attachments) {
                    runCatching { uploadManager.forget(att.localKey, releaseServerSlot = true) }
                }
            }
            else -> {
                // Success: the server consumed the bytes on resolve, so the
                // local StateFlow + InFlightUploadsRepository disk record are
                // dead weight. No explicit abort — it would race the send that
                // is consuming the handle. Mirrors the all-Done path in
                // ComposeBar.onClick which calls onForgetUpload(localKey)
                // right after onSend(...).
                for (att in send.attachments) {
                    runCatching { forgetUpload(att.localKey) }
                }
            }
        }
        // Always clear the pending-upload badge entry. The success path
        // also nuked it just before queueCall to flip the bubble from
        // "Uploading" → "Sending", but a race with [openSession]
        // re-seeding `0/N` between that pre-call clear and this
        // post-terminal cleanup could leave a permanent dead entry
        // until the next reset. Idempotent — `- csid` on a missing key
        // is a no-op.
        sessionMutex.withLock {
            val map = _pendingUploadProgress.value
            if (send.csid in map) _pendingUploadProgress.value = map - send.csid
        }
        if (failureReason != null) {
            if (bounceText && send.csid !in bouncedByQueueCsids) {
                send.text?.takeIf { it.isNotBlank() }?.let {
                    draftRepository.setBounced(send.sessionId, it)
                }
            }
            bouncedByQueueCsids.remove(send.csid)
            // Pop the optimistic bubble (if visible) and surface the
            // reason. The text is back in the composer by now, so the
            // disappearing bubble is a move, not a loss.
            removeOptimisticById(send.localId)
            if (notifyUser) context.emitError(failureReason)
        }
    }

    /**
     * Re-stage the attachments of a failed deferred send so the compose bar
     * shows them again. Writes the on-disk [AttachmentRef] slot (which
     * [pickedAttachments] reads on a cold miss) and the in-memory mirror, and
     * keeps every upload registered — a `Failed` upload is retryable, and the
     * user picked these files once already.
     */
    private fun restoreAttachmentDraft(
        send: InflightDeferredSend,
        stateFlowOf: (localKey: String) -> StateFlow<UploadManager.State>?,
    ) {
        if (send.attachments.isEmpty()) return
        val refs = send.attachments.map { att ->
            AttachmentRef(
                localKey = att.localKey,
                displayName = att.displayName,
                mimeType = att.mime,
                sizeBytes = stateFlowOf(att.localKey)?.value?.let { totalBytesFromState(it) } ?: 0L,
            )
        }
        pickedAttachmentsBySession.remove(send.sessionId)
        runCatching { attachmentDraftRepository.save(send.sessionId, refs) }
    }

    /**
     * Drop one optimistic bubble matched by stable [localId]. Both
     * [optimisticIds] and [optimisticClientSendIds] stay paired by index
     * with [_optimisticEntries] because we always mutate the three lists
     * under [sessionMutex] together; the indexOf lookup here is therefore
     * referentially safe even after a reconcile.
     */
    private suspend fun removeOptimisticById(localId: Long) {
        sessionMutex.withLock {
            val idx = optimisticIds.indexOf(localId)
            if (idx < 0) return@withLock
            optimisticIds.removeAt(idx)
            if (idx < optimisticClientSendIds.size) {
                optimisticClientSendIds.removeAt(idx)
            }
            val list = _optimisticEntries.value.toMutableList()
            if (idx < list.size) {
                list.removeAt(idx)
                _optimisticEntries.value = list
            }
        }
    }

    /**
     * Drop the optimistic bubble carrying [csid], whichever local id it was
     * created under.
     *
     * The cancel path (N-07) only knows the `client_send_id` — it can be
     * cancelling a bubble this process never created, one re-materialised from
     * a pending-send marker after a restart, whose local id was minted by the
     * rehydrate. No-op when the bubble is not on screen (a different session is
     * open); the marker removal that follows keeps it from coming back.
     */
    private suspend fun removeOptimisticByCsid(csid: Long) {
        sessionMutex.withLock {
            val idx = optimisticClientSendIds.indexOf(csid)
            if (idx < 0) return@withLock
            optimisticClientSendIds.removeAt(idx)
            if (idx < optimisticIds.size) {
                optimisticIds.removeAt(idx)
            }
            val list = _optimisticEntries.value.toMutableList()
            if (idx < list.size) {
                list.removeAt(idx)
                _optimisticEntries.value = list
            }
        }
    }

    /**
     * Render a one-line preview of a [blocks] list for the optimistic
     * bubble. Text blocks' first line wins (truncated); image / file
     * blocks contribute a short bracketed annotation so the user
     * recognises what they just sent before the server echoes the full
     * rendering back.
     */
    /**
     * Concatenate every text block's body verbatim (separated by blank
     * lines) for use as the optimistic bubble's `markdown` payload. Image
     * and file blocks are intentionally skipped — the optimistic carries
     * no inline image bytes, and the rendered preview from the server
     * eventually slots them in via the next delta poll.
     */
    private fun buildBlocksFullText(blocks: List<ContentBlockDto>): String {
        val parts = mutableListOf<String>()
        for (block in blocks) {
            if (block is ContentBlockDto.Text) {
                val body = block.text.trimEnd()
                if (body.isNotEmpty()) parts += body
            }
        }
        return parts.joinToString("\n\n")
    }

    private fun buildBlocksPreview(blocks: List<ContentBlockDto>): String {
        val parts = mutableListOf<String>()
        for (block in blocks) {
            when (block) {
                is ContentBlockDto.Text -> {
                    val first = block.text.lineSequence().firstOrNull()?.trim().orEmpty()
                    if (first.isNotEmpty()) parts += first
                }
                is ContentBlockDto.Image -> parts += "[image ${block.mimeType}]"
                is ContentBlockDto.ResourceLink -> parts += "[link ${block.name}]"
                is ContentBlockDto.Audio -> parts += "[audio ${block.mimeType}]"
                is ContentBlockDto.Resource -> parts += "[resource]"
            }
        }
        val joined = parts.joinToString(" ")
        return if (joined.length > 200) joined.take(197) + "..." else joined
    }

    fun cancelTurn() {
        val sessionId = openSessionId ?: return
        // 1. Optimistic: flip the visible state to Stopping immediately,
        //    even if offline. The server's real Stopping/Idle push
        //    reconciles whichever GetSessionResult lands next.
        //
        //    Under [sessionMutex] like every other `_session` writer (class
        //    KDoc invariant 3): read-modify-write outside the lock only
        //    happened to be safe while every writer shared the main thread,
        //    and would silently start dropping applied entries the moment one
        //    of them moved off it.
        scope.launch {
            sessionMutex.withLock {
                if (openSessionId != sessionId) return@withLock
                (_session.value as? UiData.Loaded)?.let { loaded ->
                    _session.value = UiData.Loaded(loaded.value.withOptimisticStopping())
                }
            }
        }
        // 2. Mark pending and try to send now. Server-side cancel_turn is
        //    idempotent (a repeat in Stopping/Idle is a safe no-op), so a
        //    resend on reconnect is safe; [resumeSession] re-fires us.
        _pendingCancel.value = sessionId
        flushPendingCancel()
    }

    /**
     * Send the queued cancel RPC if one is pending AND we currently have
     * a live client AND no other cancel is already in flight. Early-
     * returns (idempotently) on any of those conditions; safe to call
     * from both [cancelTurn] and the reconnect-resume path.
     *
     * The pending flag carries the TARGET session id; if the user has
     * since navigated away to a DIFFERENT session, we stay pending and
     * leave the visible session untouched.
     */
    private fun flushPendingCancel() {
        val sessionId = _pendingCancel.value ?: return
        if (openSessionId != sessionId) return
        val active = context.activeClient() ?: return
        if (_cancelInFlight.value) return
        _cancelInFlight.value = true
        val params = buildJsonObject { put("session_id", sessionId) }
        scope.launch {
            // `finally`, because the failure handler now rethrows cancellation:
            // leaving the in-flight flag set would disable Stop for good.
            try {
                runCatching { active.call("remote.solution_agent.cancel_turn", params) }
                    .mapCatching { resp ->
                        val err = resp.error
                        if (err != null) error(err.message)
                        val toolErr = resp.toolError()
                        if (toolErr != null) error(toolErr)
                    }
                    .onSuccess {
                        // Only clear the pending flag if it still names the
                        // same session — guards against a fresh tap landing
                        // between the launch and the response.
                        if (_pendingCancel.value == sessionId) _pendingCancel.value = null
                    }
                    .onFailure {
                        // A cancelled scope is not an RPC failure — reporting
                        // "…: StandaloneCoroutine was cancelled" at the user on
                        // an ordinary session close is noise, not information.
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        context.emitError("cancel failed: ${it.message ?: "?"}")
                    }
            } finally {
                _cancelInFlight.value = false
            }
        }
    }

    /**
     * Answer a tool-call authorization prompt for the currently-open
     * session. The user tapped one of the option buttons surfaced on a
     * `WaitingForConfirmation` tool call; we echo the opaque
     * [optionId] back to the server, which resolves the outcome,
     * unblocks the turn, and re-broadcasts the tool-call entry with an
     * empty `options` list — so the buttons disappear on the next
     * update with no local optimistic state needed.
     */
    fun authorizeToolCall(toolCallId: String, optionId: String) {
        val active = context.activeClient() ?: return
        val sessionId = openSessionId ?: return
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("tool_call_id", toolCallId)
            put("option_id", optionId)
        }
        scope.launch {
            runCatching {
                active.call("remote.solution_agent.authorize_tool_call", params)
            }
                .mapCatching { resp ->
                    val err = resp.error
                    if (err != null) error(err.message)
                    val toolErr = resp.toolError()
                    if (toolErr != null) error(toolErr)
                }
                .onFailure {
                    // A cancelled scope is not an RPC failure — reporting
                    // "…: StandaloneCoroutine was cancelled" at the user on
                    // an ordinary session close is noise, not information.
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    context.emitError("authorize failed: ${it.message ?: "?"}")
                }
        }
    }

    /**
     * User-pressed "send queued now" button. Cancels the in-flight
     * agent turn with the server-side `flush_pending` flag set so the
     * accumulated `pending_messages` bundle gets flushed as a fresh
     * merged turn the moment the cancel settles, instead of being
     * dropped along with the cancelled turn.
     *
     * No client-side queue state to manage — all the waiting +
     * merging lives in `solution_agent::store::queue` (see
     * `interrupt_and_flush_pending`). The mobile just kicks the
     * server and lets the regular SessionStateChanged →
     * SessionMessageAppended notifications drive the UI update.
     */
    fun forceFlushQueue() {
        val active = context.activeClient() ?: return
        val sessionId = openSessionId ?: return
        if (_cancelInFlight.value) return
        _cancelInFlight.value = true
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("flush_pending", true)
        }
        scope.launch {
            try {
                runCatching { active.call("remote.solution_agent.cancel_turn", params) }
                    .mapCatching { resp ->
                        val err = resp.error
                        if (err != null) error(err.message)
                        val toolErr = resp.toolError()
                        if (toolErr != null) error(toolErr)
                    }
                    .onFailure {
                        // A cancelled scope is not an RPC failure — reporting
                        // "…: StandaloneCoroutine was cancelled" at the user on
                        // an ordinary session close is noise, not information.
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        context.emitError("flush failed: ${it.message ?: "?"}")
                    }
            } finally {
                _cancelInFlight.value = false
            }
        }
    }

    /**
     * Wipe the conversation history of the currently-open session via
     * the server's `solution_agent.reset_context` MCP tool — the same
     * code path the desktop's `/clear` slash command takes. The
     * `SolutionSessionId` and the user-set title are preserved; only
     * the transcript + pending-message queue + token counter are
     * cleared. The server returns the SAME session id; we re-emit it
     * through [resetSwitch] so the chat surface re-attaches and reloads
     * the now-empty transcript (mirrors the previous `restart_agent`
     * flow's UI integration, just without minting a new id).
     *
     * History: prior to 2026-05-20 this called `restart_agent`, which
     * minted a fresh session id (and therefore dropped the user-set
     * title). `restart_agent` is the correct path when the agent process
     * is broken; `reset_context` is the correct path when the user just
     * wants a clean conversation — matching the desktop's
     * `Reset context` menu item and the `/clear` slash command.
     */
    fun resetContext() {
        val active = context.activeClient() ?: run {
            // Surface why nothing happened instead of silently swallowing
            // the menu tap — reset needs a live wire.
            context.emitError(context.notConnectedMessage())
            return
        }
        val sessionId = openSessionId ?: return
        val params = buildJsonObject { put("session_id", sessionId) }
        scope.launch {
            runCatching { active.call("remote.solution_agent.reset_context", params) }
                .mapCatching { resp -> resp.decodeResultOrThrow(ResetContextResult.serializer()) }
                .onSuccess {
                    // Evict the on-disk cache so the next attach refetches the
                    // (now-empty) transcript from the server rather than
                    // resurrecting the pre-clear history from disk. Done
                    // after the RPC succeeds so a failed reset leaves the
                    // cache intact.
                    sessionHistoryRepository.evict(sessionId)
                    _resetSwitch.trySend(it.sessionId)
                }
                .onFailure {
                    // A cancelled scope is not an RPC failure — reporting
                    // "…: StandaloneCoroutine was cancelled" at the user on
                    // an ordinary session close is noise, not information.
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    context.emitError("Reset failed: ${it.message ?: "?"}")
                }
        }
    }

    /**
     * Kick off the Compact context workflow on the currently-open
     * session. The server returns immediately with a [queued][StartCompactResult.queued]
     * flag — `false` means a precondition wasn't met (session busy /
     * context below 20% / cold session / not enough headroom); the
     * accompanying message surfaces via the shared error channel so the
     * snackbar tells the user why. On `queued = true` no immediate UI
     * swap happens: the agent picks up the compact prompt on its next
     * turn and the resulting new session lands via the standard
     * `agent_session_created` notification path.
     */
    fun compactContext() {
        val active = context.activeClient() ?: run {
            context.emitError(context.notConnectedMessage())
            return
        }
        val sessionId = openSessionId ?: return
        val params = buildJsonObject { put("session_id", sessionId) }
        scope.launch {
            runCatching { active.call("remote.solution_agent.start_compact", params) }
                .mapCatching { resp -> resp.decodeResultOrThrow(StartCompactResult.serializer()) }
                .onSuccess { outcome ->
                    if (!outcome.queued) {
                        val reason = outcome.message?.takeIf { it.isNotBlank() }
                            ?: "Compact declined"
                        context.emitError(reason)
                    } else {
                        // Two-phase eviction: compact doesn't mint the
                        // new session id synchronously. Park the source
                        // session id; when the matching
                        // `agent_session_created` arrives carrying it as
                        // parent_session_id, we evict the source cache
                        // (see [onChildSessionCreated]).
                        pendingCompactSourceIds.add(sessionId)
                    }
                }
                .onFailure {
                    // A cancelled scope is not an RPC failure — reporting
                    // "…: StandaloneCoroutine was cancelled" at the user on
                    // an ordinary session close is noise, not information.
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    context.emitError("Compact failed: ${it.message ?: "?"}")
                }
        }
    }

    // ---- Supervisor ----

    private val _supervisorState = MutableStateFlow<SupervisorStateDto?>(null)
    val supervisorState: StateFlow<SupervisorStateDto?> = _supervisorState.asStateFlow()

    /**
     * Fetch the supervisor state for [sessionId] from the server and
     * publish it via [supervisorState]. Mirrors the `resetContext` pattern:
     * a single `runCatching` → `decodeResultOrThrow` → `onSuccess` /
     * `onFailure` coroutine. The flow is set to `null` on failure so the UI
     * can surface an error state rather than showing stale data.
     */
    fun loadSupervisorState(sessionId: String) {
        val active = context.activeClient() ?: run {
            context.emitError(context.notConnectedMessage())
            return
        }
        val params = buildJsonObject { put("session_id", sessionId) }
        scope.launch {
            runCatching { active.call("remote.solution_agent.get_supervisor_state", params) }
                .mapCatching { resp -> resp.decodeResultOrThrow(SupervisorStateDto.serializer()) }
                .onSuccess { _supervisorState.value = it }
                .onFailure {
                    // A cancelled scope is not an RPC failure — reporting
                    // "…: StandaloneCoroutine was cancelled" at the user on
                    // an ordinary session close is noise, not information.
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    context.emitError("Supervisor load failed: ${it.message ?: "?"}")
                }
        }
    }

    /**
     * Toggle Supervisor enabled/disabled for [sessionId]. Optimistically
     * updates [supervisorState] then reloads the authoritative state from
     * the server on success, or rolls back on failure.
     */
    fun setSupervisorEnabled(sessionId: String, enabled: Boolean) {
        val active = context.activeClient() ?: run {
            context.emitError(context.notConnectedMessage())
            return
        }
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("enabled", enabled)
        }
        // Optimistic update so the switch feels instant.
        _supervisorState.value = _supervisorState.value?.copy(enabled = enabled)
        scope.launch {
            runCatching { active.call("remote.solution_agent.set_supervisor_enabled", params) }
                .onSuccess { loadSupervisorState(sessionId) }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    // Roll back the optimistic flip.
                    loadSupervisorState(sessionId)
                    context.emitError("Supervisor toggle failed: ${it.message ?: "?"}")
                }
        }
    }

    /**
     * Set a custom Supervisor instruction prompt for [sessionId]. A null or
     * blank [prompt] clears the custom prompt (the server interprets a null
     * value as "use the default"). Reloads authoritative state on completion.
     */
    fun setSupervisorPrompt(sessionId: String, prompt: String?) {
        val active = context.activeClient() ?: run {
            context.emitError(context.notConnectedMessage())
            return
        }
        val params = buildJsonObject {
            put("session_id", sessionId)
            if (prompt != null) put("prompt", prompt)
        }
        scope.launch {
            runCatching { active.call("remote.solution_agent.set_supervisor_prompt", params) }
                .onSuccess { loadSupervisorState(sessionId) }
                .onFailure {
                    // A cancelled scope is not an RPC failure — reporting
                    // "…: StandaloneCoroutine was cancelled" at the user on
                    // an ordinary session close is noise, not information.
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    context.emitError("Supervisor prompt update failed: ${it.message ?: "?"}")
                }
        }
    }

    // ---- Draft seed methods (R-6c-multi) ----

    suspend fun loadDraftSeed(sessionId: String): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        val bounced = draftRepository.bouncedFor(sessionId)
        val draft = draftRepository.load(sessionId)
        if (bounced == null) draft to false else mergeDraftSeed(draft, bounced) to true
    }

    suspend fun saveDraft(sessionId: String, text: String) = withContext(Dispatchers.IO) {
        draftRepository.save(sessionId, text)
    }

    /**
     * Synchronous flush of the draft text. Called from the chat detail
     * screen's `DisposableEffect.onDispose` so a back-press inside the
     * 500 ms debounce window of the live `saveDraft` writer doesn't drop
     * the trailing keystrokes. Still fine on Main now that the store is
     * encrypted: [DraftRepository.save] is one AES-GCM encrypt of one key
     * behind an `apply()` — the disk write is already off-thread, and the
     * expensive part (the Keystore keyset unwrap) was paid at ViewModel
     * construction by `warmUpEncryptedPrefs`. It runs at most once per exit
     * from a chat, not per keystroke.
     */
    fun flushDraft(sessionId: String, text: String) {
        draftRepository.save(sessionId, text)
    }

    fun clearDraft(sessionId: String) {
        draftRepository.clear(sessionId)
    }

    // ---- Per-session picked-attachment draft (survives screen remount) ----

    private val pickedAttachmentsBySession = mutableMapOf<String, List<PickedAttachment>>()

    /**
     * Read the picked-attachment list the user had assembled in the
     * compose bar for [sessionId] before the screen last left composition
     * (back-nav OR process death). The in-memory map is consulted first;
     * on a cold miss the on-disk [AttachmentDraftRepository] is read and
     * each persisted [AttachmentRef] is joined with the matching
     * [UploadManager.stateFlowOf] to recover the live progress flow.
     *
     * Refs whose `localKey` is no longer known to [UploadManager] (the
     * upload was cancelled / forgotten between runs) are silently dropped
     * from the restored list so the user doesn't see a chip stuck at
     * "Queued" forever.
     */
    fun pickedAttachments(sessionId: String): List<PickedAttachment> {
        val cached = pickedAttachmentsBySession[sessionId]
        if (cached != null) return cached
        val refs = attachmentDraftRepository.load(sessionId)
        if (refs.isEmpty()) return emptyList()
        val restored = refs.mapNotNull { ref ->
            val flow = uploadManager.stateFlowOf(ref.localKey) ?: return@mapNotNull null
            PickedAttachment(
                uri = Uri.EMPTY,
                displayName = ref.displayName,
                mimeType = ref.mimeType,
                sizeBytes = ref.sizeBytes,
                localKey = ref.localKey,
                uploadState = flow,
            )
        }
        pickedAttachmentsBySession[sessionId] = restored
        // If the persisted list shrank (some uploads vanished), rewrite
        // disk so the next cold start doesn't re-do the drop work.
        if (restored.size != refs.size) {
            persistAttachments(sessionId, restored)
        }
        return restored
    }

    /**
     * Stash the picked-attachment list for [sessionId]. Empty list removes
     * the entry so a long-lived `MainViewModel` with many sessions doesn't
     * leak references to stale [UploadManager.State] flows, AND wipes the
     * on-disk slot so a follow-up cold start doesn't restore a list the
     * user already cleared (send-success path).
     */
    fun setPickedAttachments(sessionId: String, attachments: List<PickedAttachment>) {
        if (attachments.isEmpty()) {
            pickedAttachmentsBySession.remove(sessionId)
        } else {
            pickedAttachmentsBySession[sessionId] = attachments
        }
        persistAttachments(sessionId, attachments)
    }

    private fun persistAttachments(sessionId: String, attachments: List<PickedAttachment>) {
        val refs = attachments.map {
            AttachmentRef(
                localKey = it.localKey,
                displayName = it.displayName,
                mimeType = it.mimeType,
                sizeBytes = it.sizeBytes,
            )
        }
        attachmentDraftRepository.save(sessionId, refs)
    }
}
