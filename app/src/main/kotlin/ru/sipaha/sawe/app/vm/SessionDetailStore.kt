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
import kotlinx.serialization.builtins.ListSerializer
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
import ru.sipaha.sawe.core.BackgroundAgentDto
import ru.sipaha.sawe.core.BackgroundShellDto
import ru.sipaha.sawe.core.ContentBlockDto
import ru.sipaha.sawe.core.EntryRoleDto
import ru.sipaha.sawe.core.EntrySummary
import ru.sipaha.sawe.core.QueuedBundleSummary
import ru.sipaha.sawe.core.SessionActiveSubagentsChangedPayload
import ru.sipaha.sawe.core.SessionBackgroundAgentsChangedPayload
import ru.sipaha.sawe.core.SessionBackgroundShellsChangedPayload
import ru.sipaha.sawe.core.SessionQueueChangedPayload
import ru.sipaha.sawe.core.SubagentDto
import ru.sipaha.sawe.core.SessionStateDto
import ru.sipaha.sawe.core.GetSessionChangesResult
import ru.sipaha.sawe.core.JsonRpc
import ru.sipaha.sawe.core.GetSessionResult
import ru.sipaha.sawe.core.MessageAppendedPayload
import ru.sipaha.sawe.core.QueueTtlException
import ru.sipaha.sawe.core.QueuedMessage
import ru.sipaha.sawe.core.RemoteClient
import ru.sipaha.sawe.core.ResetContextResult
import ru.sipaha.sawe.core.SupervisorStateDto
import ru.sipaha.sawe.core.SessionDeltaState
import ru.sipaha.sawe.core.StartCompactResult
import ru.sipaha.sawe.core.applySessionDelta
import ru.sipaha.sawe.core.isTailAnchoredWindow
import ru.sipaha.sawe.core.parseExpiredSendMessage
import ru.sipaha.sawe.core.reconcileOptimistic
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
 * Per-attachment grand timeout for deferred-send. `UploadManager` has
 * its own 30s per-ack timeout that flips to Paused (= "waiting for
 * server"), but Paused isn't terminal — without this outer guard a
 * permanently-stuck upload would hold the optimistic bubble in
 * "Uploading" state forever. Five minutes is generous enough that a
 * paused→resumed cycle (Doze wake / WiFi flap) finishes inside it;
 * beyond five minutes the user almost certainly wants a retry, not
 * an infinite spinner.
 */
private const val DEFERRED_UPLOAD_TIMEOUT_MS: Long = 5L * 60_000L

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

// Convergence poll (driven by `agent_session_dirty`): retry a failed/short poll
// with exponential backoff until the held cursor reaches the signalled seq.
private const val CONVERGENCE_MIN_BACKOFF_MS: Long = 150L
private const val CONVERGENCE_MAX_BACKOFF_MS: Long = 2_000L
// Bound the retry loop so a target that can never be reached (e.g. a seq from a
// since-rotated epoch the reset path didn't catch) can't spin forever — the
// next dirty / tail-resync / reconnect retries.
private const val CONVERGENCE_MAX_ATTEMPTS: Int = 15

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
 *     `_session.entries`/state, `_serverQueuedBundles`, `_activeSubagents`:
 *     [applyDeltaLocked] + [fetchFullSession]. Push handlers only
 *     [scheduleDeltaPoll].
 *  4. **Optimistic bubbles carry two ids** — a local stable id
 *     ([optimisticIdGen]) used by the cancel-by-id failure path and a
 *     wire-side `client_send_id` stamp ([optimisticClientSendIds])
 *     stamped onto the originating ContentBlock's
 *     `_meta.spk_client_send_id`. The pure `reconcileOptimistic`
 *     prefers id-based matching; falls back to content-match for legacy
 *     server entries (pre-rollout) or cross-client echoes that don't
 *     carry a csid.
 *  5. **The shared [lastSeen] index is the single source of truth** —
 *     don't store a sibling map; both this store and [SessionListStore]
 *     read/write through [LastSeenIndex].
 */
internal class SessionDetailStore(
    private val scope: CoroutineScope,
    private val context: ConnectionContext,
    private val draftRepository: DraftRepository,
    private val attachmentDraftRepository: AttachmentDraftRepository,
    private val uploadManager: UploadManager,
    private val lastSeen: LastSeenIndex,
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
     * In-flight Claude Code sub-agents (Task/Agent tool uses) for the
     * open session, in server-side insertion order. Mirrors
     * [GetSessionResult.activeSubagents] at cold-start, then live-updates
     * via the `agent_session_active_subagents_changed` notification.
     * Drives the `SubagentTabStrip` on the detail screen.
     */
    private val _activeSubagents = MutableStateFlow<List<SubagentDto>>(emptyList())
    val activeSubagents: StateFlow<List<SubagentDto>> = _activeSubagents.asStateFlow()

    /**
     * Currently-selected subagent tab id, or `null` for the implicit
     * "Main" tab. The chat list filters entries by
     * `entry.subagentId == selectedSubagent` (both null = Main view).
     * Auto-resets to null on session switch and snaps to the first
     * remaining subagent (or null) when the selected one disappears.
     */
    private val _selectedSubagent = MutableStateFlow<String?>(null)
    val selectedSubagent: StateFlow<String?> = _selectedSubagent.asStateFlow()

    /**
     * Background shells (`run_in_background` Bash tool uses) for the open
     * session, in server-side order. Seeded once on [openSession] via the
     * lite `get_session_background_shells` call (include_output=false),
     * then live-updated from the `agent_session_background_shells_changed`
     * notification (which already carries the full lite list — no refetch).
     * Drives the `BackgroundShellStrip` on the detail screen. Empty for
     * sessions that never launched one, or pre-feature servers.
     */
    private val _backgroundShells = MutableStateFlow<List<BackgroundShellDto>>(emptyList())
    val backgroundShells: StateFlow<List<BackgroundShellDto>> = _backgroundShells.asStateFlow()

    /**
     * Managed background agents for the open session, in server-side
     * order. Seeded once on [openSession] via `get_session_background_agents`
     * (no include flag), then live-updated from the
     * `agent_session_background_agents_changed` notification (which already
     * carries the full list — no refetch). Drives the `BackgroundAgentStrip`
     * on the detail screen. Empty for sessions that never launched one, or
     * pre-feature servers.
     */
    private val _backgroundAgents = MutableStateFlow<List<BackgroundAgentDto>>(emptyList())
    val backgroundAgents: StateFlow<List<BackgroundAgentDto>> = _backgroundAgents.asStateFlow()


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
     * The coalescing live delta poll for the open session. At most ONE
     * `get_session_changes` is in flight at a time; [scheduleDeltaPoll]
     * cancels+rearms this job on every trigger so a burst of push
     * notifications collapses into a single trailing-edge poll. Guarded
     * exactly like [tailResyncJob] — cancelled on session switch / close /
     * reset / server switch.
     */
    private var deltaPollJob: Job? = null

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
        deltaPollJob?.cancel()
        deltaPollJob = null
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
                lastSeen.clear()
                _session.value = UiData.Loading
                _optimisticEntries.value = emptyList()
                optimisticIds.clear()
                optimisticClientSendIds.clear()
                _serverQueuedBundles.value = emptyList()
                _activeSubagents.value = emptyList()
                _selectedSubagent.value = null
                _backgroundShells.value = emptyList()
                _backgroundAgents.value = emptyList()
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
        deltaPollJob?.cancel()
        deltaPollJob = null
    }

    /** Bounce-to-input recovery routed here from ConnectionManager. */
    fun handleExpiredMessage(message: QueuedMessage) {
        val parsed = parseExpiredSendMessage(message) ?: return
        val (sessionId, content) = parsed
        draftRepository.setBounced(sessionId, content)
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
        val cached = sessionHistoryRepository.load(sessionId)
        scope.launch {
            sessionMutex.withLock {
                openSessionId = sessionId
                // Reset the delta cursor for the new session; seeded
                // below from the cache (if present) or by the full load.
                openEpoch = 0
                openSeq = 0
                _isLoadingOlder.value = false
                _optimisticEntries.value = emptyList()
                optimisticIds.clear()
                optimisticClientSendIds.clear()
                // Drop the previous session's server-queue view; the
                // next delta / full load seeds from the new session's
                // `pendingBundles`.
                _serverQueuedBundles.value = emptyList()
                // Drop the previous session's subagent strip — the next
                // delta / full load seeds from `activeSubagents`. Reset
                // selection to Main so the new session opens unfiltered.
                _activeSubagents.value = emptyList()
                _selectedSubagent.value = null
                // Drop the previous session's background-shell strip; the
                // explicit fetch below (after the mutex) re-seeds it.
                _backgroundShells.value = emptyList()
                // Same for the background-agent strip.
                _backgroundAgents.value = emptyList()
                lastSeen.primeFromDisk(sessionId)
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
                for (p in pendingSendsRepository.list()) {
                    if (p.sessionId != sessionId) continue
                    if (p.attachments.isNotEmpty()) continue
                    if (p.csid in optimisticClientSendIds) continue
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
        // Seed the background-shell strip once with the lite list (no
        // output_tail — the strip never needs it). Subsequent mutations
        // ride the `agent_session_background_shells_changed` notification.
        scope.launch {
            val result = sessionList.loadBackgroundShells(sessionId, includeOutput = false)
            sessionMutex.withLock {
                // stale-write barrier (see class kdoc invariant 1)
                if (openSessionId != sessionId) return@withLock
                _backgroundShells.value = result.backgroundShells
            }
        }
        // Seed the background-agent strip once (no include flag — the DTO
        // already carries everything the strip + drill-in show). Subsequent
        // mutations ride the `agent_session_background_agents_changed`
        // notification.
        scope.launch {
            val result = sessionList.loadBackgroundAgents(sessionId)
            sessionMutex.withLock {
                if (openSessionId != sessionId) return@withLock
                _backgroundAgents.value = result.backgroundAgents
            }
        }
        sessionList.ensureNotificationsObserver()
        startTailResync(sessionId)
        // Opening a session over a silently-dead socket (a zombie left by a
        // Doze window or a server restart the client never detected — state
        // still reads "Connected", no banner) would let the initial
        // `fetchDeltaOrFull` above time out silently, leaving an empty
        // transcript and a stale subagent strip. Probe liveness in parallel:
        // a healthy socket answers in <8s (cheap no-op) and the fetch lands
        // normally; a dead one is force-reconnected, and `onReconnected`
        // re-runs the fetch via `resumeSession(openSessionId)`. Without this
        // the only recovery was the ~30–76s heartbeat watchdog or the user
        // sending a message (whose write trips broken-pipe detection sooner).
        scope.launch { context.probeLivenessNow() }
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
                if (openSessionId != sessionId) break
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

    fun closeSession() {
        tailResyncJob?.cancel()
        tailResyncJob = null
        deltaPollJob?.cancel()
        deltaPollJob = null
        scope.launch {
            sessionMutex.withLock {
                openSessionId = null
                openEpoch = 0
                openSeq = 0
                _session.value = UiData.Loading
                _isLoadingOlder.value = false
                _optimisticEntries.value = emptyList()
                optimisticIds.clear()
                optimisticClientSendIds.clear()
                _serverQueuedBundles.value = emptyList()
                _activeSubagents.value = emptyList()
                _selectedSubagent.value = null
                _backgroundShells.value = emptyList()
                _backgroundAgents.value = emptyList()
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
        // Keep the shared last-seen index current — the session-list unread
        // badge reads it. Everything else (the placeholder, the fast-pop,
        // the entry content) now arrives via the delta poll: the delta's
        // `changed_entries` always carries the full entry body, and
        // `applyDeltaLocked` pops optimistics via `reconcileOptimisticLocked`.
        lastSeen.recordIfNewer(payload.sessionId, payload.entryIndex)
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

    override fun onSessionDirty(sessionId: String, currentSeq: Long) {
        val openSid = openSessionId ?: return
        if (sessionId != openSid) return
        // Converge to the signalled seq: poll get_session_changes until the held
        // cursor reaches currentSeq, retrying failed polls. Unlike the plain
        // poke (scheduleDeltaPoll), this does not give up after one failed/short
        // poll — so an interrupted reply whose trailing append pokes were lost
        // is reliably healed by a single delivered dirty.
        scheduleDeltaPollToConvergence(openSid, currentSeq)
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
        // The delta's `active_subagents` section is the single writer of
        // `_activeSubagents` now — just trigger a poll; do NOT apply the
        // payload list directly (that would make the push a second writer).
        scheduleDeltaPoll(openSid)
    }

    override fun onBackgroundShellsChanged(payload: SessionBackgroundShellsChangedPayload) {
        val openSid = openSessionId ?: return
        if (payload.sessionId != openSid) return
        // The notification carries the FULL post-change lite list — mirror
        // it verbatim. Cross the mutex so the write can't race a concurrent
        // openSession seed / stale-write barrier on the same flow.
        scope.launch {
            sessionMutex.withLock {
                if (openSessionId != payload.sessionId) return@withLock
                _backgroundShells.value = payload.backgroundShells
            }
        }
    }

    override fun onBackgroundAgentsChanged(payload: SessionBackgroundAgentsChangedPayload) {
        val openSid = openSessionId ?: return
        if (payload.sessionId != openSid) return
        // The notification carries the FULL post-change list — mirror it
        // verbatim. Cross the mutex so the write can't race a concurrent
        // openSession seed / stale-write barrier on the same flow.
        scope.launch {
            sessionMutex.withLock {
                if (openSessionId != payload.sessionId) return@withLock
                _backgroundAgents.value = payload.backgroundAgents
            }
        }
    }

    /**
     * Fetch the full stdout tail for one background shell of the open
     * session (`include_output=true`). Returns the matching
     * [BackgroundShellDto] (carrying `output_tail`) or null when the
     * shell is gone / the fetch failed / the session was switched away.
     * Backs the drill-in sheet — kept off the live strip flow so the
     * strip never carries (and re-renders on) the heavy output text.
     */
    suspend fun loadBackgroundShellOutput(shellId: String): BackgroundShellDto? {
        val sessionId = openSessionId ?: return null
        val result = sessionList.loadBackgroundShells(sessionId, includeOutput = true)
        if (openSessionId != sessionId) return null
        return result.backgroundShells.firstOrNull { it.id == shellId }
    }

    /**
     * UI hook for the subagent tab strip. Validates that [id] either
     * is null (Main) or matches one of the currently-active subagents;
     * an unknown id is logged and silently rejected to null. Mutating
     * [_selectedSubagent] directly from outside the store is forbidden
     * — this entry point is the single seam.
     */
    fun selectSubagent(id: String?) {
        val target = if (id != null && _activeSubagents.value.none { it.id == id }) {
            android.util.Log.w(
                "SessionDetailStore",
                "selectSubagent($id) is not in active set — resetting to Main",
            )
            null
        } else {
            id
        }
        val changed = _selectedSubagent.value != target
        _selectedSubagent.value = target
        if (changed) {
            // Cancel any armed delta poll — it carries the old tab filter
            // and would apply stale entries under the new tab if it fired
            // after the switch.
            deltaPollJob?.cancel()
            deltaPollJob = null
            // Server-side per-tab filtering: the held entries are the PREVIOUS
            // tab's window, so the newly-selected tab's history isn't loaded
            // yet. Pull a fresh first page filtered to the new tab (a tab
            // switch is a user action — a fresh count=50 fetch is cheap and
            // correct, and avoids an empty tab when its entries sit outside
            // the old window).
            val sid = openSessionId ?: return
            val active = context.activeClient() ?: return
            scope.launch { fetchInitialPage(active, sid) }
        }
    }

    /**
     * The `subagent_filter` to send to `get_session` so the SERVER applies the
     * same per-tab membership rule the desktop uses (single source of truth)
     * and the windowed page is the SELECTED tab's entries — not a tail of all
     * entries that, once client-filtered, can leave Main empty when a subagent
     * dominates the tail. `null` tab (Main) → `"__main__"`; a subagent tab →
     * its id.
     */
    private fun currentSubagentFilter(): String = _selectedSubagent.value ?: "__main__"

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
    private suspend fun fetchDeltaOrFull(
        active: RemoteClient,
        sessionId: String,
        cached: CachedSessionHistory?,
    ) {
        if (cached == null || cached.entries.isEmpty()) {
            fetchFullSession(active, sessionId)
            return
        }
        val delta = runCatching {
            active.getSessionChanges(
                sessionId = sessionId,
                sinceSeq = cached.lastSeq,
                knownEpoch = cached.epoch,
                subagentFilter = currentSubagentFilter(),
            )
        }.getOrElse {
            applyFetchFailure(sessionId, it)
            return
        }
        if (delta.reset) {
            fetchFullSession(active, sessionId)
        } else {
            applyDeltaLocked(sessionId, delta)
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
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("include_full_content", true)
            put("include_images", true)
            put("subagent_filter", currentSubagentFilter())
            put("count", SESSION_PAGE_SIZE)
        }
        val result = runCatching { active.call("remote.solution_agent.get_session", params) }
            .mapCatching { resp -> resp.decodeResultOrThrow(GetSessionResult.serializer()) }
            .getOrElse {
                applyFetchFailure(sessionId, it)
                return
            }
        sessionMutex.withLock {
            // stale-write barrier (see class kdoc invariant 1)
            if (openSessionId != sessionId) return@withLock
            _session.value = UiData.Loaded(result)
            _serverQueuedBundles.value = result.pendingBundles
            applyActiveSubagentsLocked(result.activeSubagents)
            _isLoadingOlder.value = false
            reconcileOptimisticLocked(result.entries)
            result.entries.lastOrNull()?.takeIf { it.index >= 0 }?.let { newest ->
                lastSeen.recordIfNewer(sessionId, newest.index)
            }
            openEpoch = result.epoch
            openSeq = result.currentSeq
        }
        persistCache(sessionId, result, result.entries, result.totalCount)
    }

    private suspend fun fetchInitialPage(active: RemoteClient, sessionId: String) {
        fetchFullSession(active, sessionId)
    }

    /**
     * The SINGLE delta-applier publish seam. Together with [fetchFullSession]
     * it is the only writer of `_session.entries`/state,
     * `_serverQueuedBundles`, and `_activeSubagents`. Holds [sessionMutex] +
     * the `openSessionId == sessionId` stale-write barrier on every publish.
     *
     * Precondition: [delta.reset] is false (caller routes a reset delta to
     * [fetchFullSession]). When the held `_session` is not yet `Loaded`, or
     * the post-apply window falls short of the newest entry (the delta missed
     * newer entries), this triggers a full [fetchFullSession] instead of
     * publishing a half-applied state.
     */
    private suspend fun applyDeltaLocked(sessionId: String, delta: GetSessionChangesResult) {
        var needsFullLoad = false
        var snapshotForCache: GetSessionResult? = null
        sessionMutex.withLock {
            // stale-write barrier (see class kdoc invariant 1)
            if (openSessionId != sessionId) return@withLock
            val current = _session.value as? UiData.Loaded ?: run {
                // No loaded base to apply onto — fall back to a full load.
                needsFullLoad = true
                return@withLock
            }
            val deltaState = SessionDeltaState(
                entries = current.value.entries,
                totalCount = current.value.totalCount,
                state = current.value.state,
                pendingBundles = _serverQueuedBundles.value,
                activeSubagents = _activeSubagents.value,
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
            val updated = current.value.copy(
                entries = next.entries,
                totalCount = next.totalCount,
                state = next.state,
                epoch = delta.epoch,
                currentSeq = delta.currentSeq,
            )
            _session.value = UiData.Loaded(updated)
            _serverQueuedBundles.value = next.pendingBundles
            applyActiveSubagentsLocked(next.activeSubagents)
            reconcileOptimisticLocked(next.entries)
            // Bundle-only optimistic handoff: a send that landed in a server
            // bundle (but whose merged user entry hasn't materialised in
            // `changed_entries` yet) still pops, mirroring the old
            // `onSessionQueueChanged` behaviour.
            reconcilePendingBundleOptimisticsLocked(next.pendingBundles)
            next.entries.lastOrNull()?.takeIf { it.index >= 0 }?.let { newest ->
                lastSeen.recordIfNewer(sessionId, newest.index)
            }
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
            persistCache(sessionId, it, it.entries, it.totalCount)
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
    ) {
        // The disk cache is rendered instantly on reopen, and a session always
        // reopens on Main (openSession resets selectedSubagent to null). With
        // server-side per-tab filtering, caching a subagent tab's window would
        // flash the wrong transcript on Main (and re-introduce the empty-Main
        // symptom this fix removes), so only persist while viewing Main.
        // Subagent tabs re-fetch on selection, so they need no disk cache.
        if (_selectedSubagent.value != null) return
        val lastIdx = entries.mapNotNull { it.index.takeIf { i -> i >= 0 } }.maxOrNull()
        sessionHistoryRepository.save(
            CachedSessionHistory(
                sessionId = sessionId,
                solutionId = fetched.solutionId,
                agentId = fetched.agentId,
                entries = entries,
                lastIndex = lastIdx,
                totalCountAtLastWrite = newTotalCount,
                // Stamp the held delta cursor (set under the lock by the
                // single writer before this off-lock persist) so the next
                // open can drive a `get_session_changes` poll from it.
                epoch = openEpoch,
                lastSeq = openSeq,
            ),
        )
    }

    /**
     * Trailing-edge debounced live delta poll. At most ONE
     * `get_session_changes` is in flight per open session: every trigger
     * cancels+rearms [deltaPollJob], so a burst of push notifications
     * collapses into a single poll after [DELTA_POLL_DEBOUNCE_MS] of quiet.
     *
     * When it fires (still the open session): poll `get_session_changes`
     * against the held [openSeq]/[openEpoch]. `reset == true` → full
     * `get_session` via [fetchFullSession]; otherwise apply via
     * [applyDeltaLocked] (the single writer). A failed poll is recoverable
     * and silent — the slow tail-resync fallback / the next notification
     * re-triggers.
     */
    private fun scheduleDeltaPoll(sessionId: String) {
        if (openSessionId != sessionId) return
        deltaPollJob?.cancel()
        deltaPollJob = scope.launch {
            delay(DELTA_POLL_DEBOUNCE_MS)
            if (openSessionId != sessionId) return@launch
            val active = context.activeClient() ?: return@launch
            // Snapshot the cursor under the lock so we don't race a
            // concurrent applyDeltaLocked / fetchFullSession advancing it.
            val (sinceSeq, knownEpoch) = sessionMutex.withLock {
                if (openSessionId != sessionId) return@launch
                openSeq to openEpoch
            }
            val delta = runCatching {
                active.getSessionChanges(
                    sessionId = sessionId,
                    sinceSeq = sinceSeq,
                    knownEpoch = knownEpoch,
                    subagentFilter = currentSubagentFilter(),
                )
            }.getOrNull() ?: return@launch
            if (openSessionId != sessionId) return@launch
            if (delta.reset) {
                fetchFullSession(active, sessionId)
            } else {
                applyDeltaLocked(sessionId, delta)
                // Server paginated — drain the next page from the advanced
                // cursor (the convergence path loops on its own; this keeps the
                // plain poke path catching up too).
                if (delta.hasMore && openSessionId == sessionId) {
                    scheduleDeltaPoll(sessionId)
                }
            }
        }
    }

    /**
     * Poll `get_session_changes` until the held cursor ([openSeq]) reaches
     * [targetSeq], retrying failed polls with bounded exponential backoff.
     * Triggered by the server's `agent_session_dirty` signal.
     *
     * Unlike [scheduleDeltaPoll] (one shot, silent give-up on failure / short
     * read), this CONVERGES: it does not stop while the held cursor is below the
     * signalled seq and a client is connected. That is what heals an interrupted
     * reply whose trailing append pokes were lost on a flaky link — a single
     * delivered dirty drives the view all the way to the server's `current_seq`.
     *
     * Shares [deltaPollJob] (cancel + re-arm), so a newer poke / dirty
     * supersedes an in-flight convergence run; a dirty with a higher target
     * simply restarts the loop against the new target.
     */
    private fun scheduleDeltaPollToConvergence(sessionId: String, targetSeq: Long) {
        if (openSessionId != sessionId) return
        deltaPollJob?.cancel()
        deltaPollJob = scope.launch {
            delay(DELTA_POLL_DEBOUNCE_MS)
            var backoffMs = CONVERGENCE_MIN_BACKOFF_MS
            // Bounds only CONSECUTIVE failures — a successful page (progress)
            // resets it, so a far-behind session can drain arbitrarily many
            // `has_more` pages while a dead link still gives up after the cap.
            var failures = 0
            while (failures < CONVERGENCE_MAX_ATTEMPTS) {
                if (openSessionId != sessionId) return@launch
                val active = context.activeClient() ?: return@launch
                val (sinceSeq, knownEpoch) = sessionMutex.withLock {
                    if (openSessionId != sessionId) return@launch
                    openSeq to openEpoch
                }
                if (sinceSeq >= targetSeq) return@launch // converged
                val delta = runCatching {
                    active.getSessionChanges(
                        sessionId = sessionId,
                        sinceSeq = sinceSeq,
                        knownEpoch = knownEpoch,
                        subagentFilter = currentSubagentFilter(),
                    )
                }.getOrNull()
                if (delta == null) {
                    // Poll failed (transport / reconnect window) — back off and
                    // retry rather than stranding the view.
                    failures++
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(CONVERGENCE_MAX_BACKOFF_MS)
                    continue
                }
                if (openSessionId != sessionId) return@launch
                if (delta.reset) {
                    // Epoch rotated: a full reload snaps the cursor to the live
                    // currentSeq, which is convergence by definition.
                    fetchFullSession(active, sessionId)
                    return@launch
                }
                applyDeltaLocked(sessionId, delta)
                // Progress: reset the failure budget + backoff. The next
                // iteration re-reads [openSeq] and returns once it reaches
                // [targetSeq]; a paginated catch-up (`has_more`) loops here page
                // by page until the held cursor reaches the target.
                failures = 0
                backoffMs = CONVERGENCE_MIN_BACKOFF_MS
            }
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
            put("include_images", true)
            put("subagent_filter", currentSubagentFilter())
            put("before_index", oldestIndex)
            put("count", SESSION_PAGE_SIZE)
        }
        scope.launch {
            val outcome = runCatching { active.call("remote.solution_agent.get_session", params) }
                .mapCatching { resp -> resp.decodeResultOrThrow(GetSessionResult.serializer()) }
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
                    }
                    .onFailure {
                        _isLoadingOlder.value = false
                        context.emitError("Couldn't load older messages: ${it.message ?: "?"}")
                    }
            }
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
     * Seed [_activeSubagents] from a fresh `GetSessionResult` and snap
     * [_selectedSubagent] back to a valid value if the previously-selected
     * subagent disappeared. MUST be called while holding [sessionMutex] —
     * mirrors the notification path's auto-snap so cold-start /
     * full-refresh and live updates converge on the same invariant.
     */
    private fun applyActiveSubagentsLocked(activeSubagents: List<SubagentDto>) {
        _activeSubagents.value = activeSubagents
        val current = _selectedSubagent.value
        if (current != null && activeSubagents.none { it.id == current }) {
            _selectedSubagent.value = activeSubagents.firstOrNull()?.id
        }
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
        pendingSendsRepository.saveOrUpdate(
            PersistedPendingSend(
                csid = clientSendId,
                localId = localId,
                sessionId = sessionId,
                // Persist the FULL typed body so rehydration on next
                // [openSession] re-shows the complete message, not the
                // truncated `buildBlocksPreview` clamp.
                text = fullMarkdown.takeIf { it.isNotEmpty() } ?: preview,
                attachments = emptyList(),
            ),
        )
        scope.launch {
            sessionMutex.withLock {
                _optimisticEntries.value = _optimisticEntries.value + optimistic
                optimisticIds.add(localId)
                optimisticClientSendIds.add(clientSendId)
            }
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
            runCatching {
                active.queueCall("remote.solution_agent.send_message_blocks", params)
            }
                .mapCatching { resp ->
                    val err = resp.error
                    if (err != null) error(err.message)
                    val toolErr = resp.toolError()
                    if (toolErr != null) error(toolErr)
                }
                .onSuccess {
                    // The server received the message; the disk marker is
                    // no longer needed (the optimistic itself is popped by
                    // the server echo via `client_send_id`). Removing it
                    // here keeps the repository scoped to genuinely-unsent
                    // messages.
                    pendingSendsRepository.remove(clientSendId)
                }
                .onFailure {
                    pendingSendsRepository.remove(clientSendId)
                    removeOptimisticById(localId)
                    val msg = when (it) {
                        is QueueTtlException ->
                            "send timed out — the editor was offline for too long"
                        is RemoteClient.ClosedException ->
                            "send cancelled — connection closed"
                        else -> it.message ?: "send failed"
                    }
                    context.emitError(msg)
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
        // Persist BEFORE spawning so a force-kill between the spawn
        // and the first await survives — the cold-start
        // resumeDeferredSendsFromDisk path will pick the record up
        // and re-spawn an identical waiter coroutine.
        pendingSendsRepository.saveOrUpdate(
            PersistedPendingSend(
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
            ),
        )
        runDeferredSend(
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
     */
    fun resumeDeferredSendsFromDisk(
        stateFlowOf: (localKey: String) -> StateFlow<UploadManager.State>?,
        forgetUpload: (localKey: String) -> Unit,
    ) {
        val persisted = pendingSendsRepository.list()
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
    ) {
        inflightDeferred[send.csid] = send
        scope.launch {
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
                        send,
                        failureReason =
                            "`${u.displayName}` upload state lost — re-attach to retry",
                        forgetUpload = forgetUpload,
                    )
                    return@launch
                }
                val perAttachmentTotal = totalBytesFromState(flow.value)
                // Per-attachment grand timeout safety net. The chunk
                // loop in UploadManager has a 30s per-ack timeout that
                // transitions to Paused — but Paused isn't terminal,
                // so without this withTimeout the deferred coroutine
                // would wait forever on a permanently-stuck upload.
                // 5 minutes is generous: a 5 MB file on bad LTE
                // typically completes within 60-90s; 5 min lets
                // a paused→resumed cycle settle.
                val terminal = kotlinx.coroutines.withTimeoutOrNull(DEFERRED_UPLOAD_TIMEOUT_MS) {
                    flow
                        .onEach { state ->
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
                        }
                        .first { state ->
                            state is UploadManager.State.Done ||
                                state is UploadManager.State.Failed
                        }
                }
                if (terminal == null) {
                    cleanupDeferred(
                        send,
                        failureReason =
                            "`${u.displayName}` upload stalled — try again (no progress within ${DEFERRED_UPLOAD_TIMEOUT_MS / 60_000}m)",
                        forgetUpload = forgetUpload,
                    )
                    return@launch
                }
                when (terminal) {
                    is UploadManager.State.Failed -> {
                        cleanupDeferred(
                            send,
                            failureReason =
                                "`${u.displayName}` failed to upload: ${terminal.reason}",
                            forgetUpload = forgetUpload,
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
                            send,
                            failureReason = "`${u.displayName}` upload ended in unexpected state",
                            forgetUpload = forgetUpload,
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
            // Fire-and-forget once uploads have all reached terminal.
            // Server-side `send_message_blocks` queues+merges into
            // `pending_messages` when the session is Running, so we
            // don't need a client-side gate here either — same model
            // as the text-only [sendMessageBlocks] path.
            runCatching {
                active.queueCall("remote.solution_agent.send_message_blocks", params)
            }
                .mapCatching { resp ->
                    val err = resp.error
                    if (err != null) error(err.message)
                    val toolErr = resp.toolError()
                    if (toolErr != null) error(toolErr)
                }
                .fold(
                    onSuccess = {
                        cleanupDeferred(send, failureReason = null, forgetUpload = forgetUpload)
                    },
                    onFailure = {
                        val msg = when (it) {
                            is QueueTtlException ->
                                "send timed out — the editor was offline for too long"
                            is RemoteClient.ClosedException ->
                                "send cancelled — connection closed"
                            else -> it.message ?: "send failed"
                        }
                        cleanupDeferred(send, failureReason = msg, forgetUpload = forgetUpload)
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

    private suspend fun cleanupDeferred(
        send: InflightDeferredSend,
        failureReason: String?,
        forgetUpload: (localKey: String) -> Unit,
    ) {
        inflightDeferred.remove(send.csid)
        pendingSendsRepository.remove(send.csid)
        // Release the upload bookkeeping for every attachment regardless
        // of outcome:
        //   - success → server consumed the bytes on resolve; the local
        //     StateFlow + InFlightUploadsRepository disk record are
        //     dead weight that would otherwise leak until server switch
        //   - failure (upload failed OR queueCall failed AFTER uploads
        //     completed) → the bubble drops and the user re-attaches if
        //     they want to retry; there's no path that re-uses the
        //     existing localKey
        // Mirrors the all-Done path in ComposeBar.onClick which calls
        // onForgetUpload(localKey) right after onSend(...).
        for (att in send.attachments) {
            runCatching { forgetUpload(att.localKey) }
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
            // Pop the optimistic bubble (if visible) and surface the
            // reason. After this the user sees the message disappear
            // and a snackbar with the failure reason.
            removeOptimisticById(send.localId)
            context.emitError(failureReason)
        }
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
        (_session.value as? UiData.Loaded)?.let { loaded ->
            _session.value = UiData.Loaded(loaded.value.withOptimisticStopping())
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
                .onFailure { context.emitError("cancel failed: ${it.message ?: "?"}") }
            _cancelInFlight.value = false
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
                .onFailure { context.emitError("authorize failed: ${it.message ?: "?"}") }
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
            runCatching { active.call("remote.solution_agent.cancel_turn", params) }
                .mapCatching { resp ->
                    val err = resp.error
                    if (err != null) error(err.message)
                    val toolErr = resp.toolError()
                    if (toolErr != null) error(toolErr)
                }
                .onFailure { context.emitError("flush failed: ${it.message ?: "?"}") }
            _cancelInFlight.value = false
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
                .onFailure { context.emitError("Reset failed: ${it.message ?: "?"}") }
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
                .onFailure { context.emitError("Compact failed: ${it.message ?: "?"}") }
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
                .onFailure { context.emitError("Supervisor load failed: ${it.message ?: "?"}") }
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
                .onFailure { context.emitError("Supervisor prompt update failed: ${it.message ?: "?"}") }
        }
    }

    // ---- Draft seed methods (R-6c-multi) ----

    suspend fun loadDraftSeed(sessionId: String): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        val bounced = draftRepository.bouncedFor(sessionId)
        if (bounced != null) bounced to true
        else draftRepository.load(sessionId) to false
    }

    suspend fun saveDraft(sessionId: String, text: String) = withContext(Dispatchers.IO) {
        draftRepository.save(sessionId, text)
    }

    /**
     * Synchronous flush of the draft text. Called from the chat detail
     * screen's `DisposableEffect.onDispose` so a back-press inside the
     * 500 ms debounce window of the live `saveDraft` writer doesn't drop
     * the trailing keystrokes. The underlying [DraftRepository.save] is
     * `SharedPreferences.edit().apply()` — already non-blocking — so
     * calling it on the main thread is fine.
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
