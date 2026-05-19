package ru.sipaha.spkremote.app.vm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.sipaha.spkremote.app.data.CachedSessionHistory
import ru.sipaha.spkremote.app.data.DraftRepository
import ru.sipaha.spkremote.app.data.PendingSendsRepository
import ru.sipaha.spkremote.app.data.PersistedPendingAttachment
import ru.sipaha.spkremote.app.data.PersistedPendingSend
import ru.sipaha.spkremote.app.data.SessionHistoryRepository
import ru.sipaha.spkremote.core.AppendedPlaceholderOutcome
import ru.sipaha.spkremote.core.ContentBlockDto
import ru.sipaha.spkremote.core.EntrySummary
import ru.sipaha.spkremote.core.QueuedBundleSummary
import ru.sipaha.spkremote.core.SessionQueueChangedPayload
import ru.sipaha.spkremote.core.JsonRpc
import ru.sipaha.spkremote.core.GetSessionResult
import ru.sipaha.spkremote.core.MergeOutcome
import ru.sipaha.spkremote.core.MessageAppendedPayload
import ru.sipaha.spkremote.core.QueueTtlException
import ru.sipaha.spkremote.core.QueuedMessage
import ru.sipaha.spkremote.core.RemoteClient
import ru.sipaha.spkremote.core.RestartAgentResult
import ru.sipaha.spkremote.core.StartCompactResult
import ru.sipaha.spkremote.core.applyAppendedPlaceholder
import ru.sipaha.spkremote.core.mergeSessionHistory
import ru.sipaha.spkremote.core.parseExpiredSendMessage
import ru.sipaha.spkremote.core.reconcileOptimistic
import ru.sipaha.spkremote.core.stampClientSendId
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/** Page size for [SessionDetailStore.openSession] / [SessionDetailStore.loadOlder]. */
private const val SESSION_PAGE_SIZE = 50

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
 *     under [sessionMutex]** — multiple coroutines (notification observer,
 *     fetchAndReplaceEntry, loadOlder, resumeSession) compete for the
 *     same flow; without the mutex a stale snapshot can be re-published
 *     on top of a newer one.
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
     * One-shot signal emitted after a successful Reset context. Carries the
     * new session id the server minted; the UI collector hops navigation
     * onto the fresh session so the open chat surface stops pointing at
     * the now-closed source session. Replay = 0 (a missed emission while
     * the screen is gone is fine — the user picks up the new session via
     * the list).
     */
    private val _resetSwitch = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val resetSwitch: SharedFlow<String> = _resetSwitch.asSharedFlow()

    @Volatile
    var openSessionId: String? = null
        private set

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
        _isLoadingOlder.value = false
        // Detail-state mutations MUST run under `sessionMutex` — otherwise
        // an in-flight `withLock` block that already cleared its stale-write
        // barrier check (e.g. fetchSession onSuccess) could publish AFTER
        // this clear and resurrect the previous server's session state.
        // See class KDoc invariant 3 + closeSession() (mirrors this pattern).
        scope.launch {
            sessionMutex.withLock {
                openSessionId = null
                lastSeen.clear()
                _session.value = UiData.Loading
                _optimisticEntries.value = emptyList()
                optimisticIds.clear()
                optimisticClientSendIds.clear()
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
                _isLoadingOlder.value = false
                _optimisticEntries.value = emptyList()
                optimisticIds.clear()
                optimisticClientSendIds.clear()
                // Drop the previous session's server-queue view; the
                // next fetchInitialOrDiff seeds from the new
                // session's `pendingBundles`.
                _serverQueuedBundles.value = emptyList()
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
                        EntrySummary(role = "user", preview = preview, clientSendId = s.csid)
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
                if (cached != null && cached.entries.isNotEmpty()) {
                    // Render the cached transcript immediately so the
                    // chat surface is interactive while the diff fetch
                    // is in flight. We synthesise a minimal
                    // GetSessionResult; missing fields (state, title,
                    // timestamps) are overwritten by the first
                    // successful diff / full fetch.
                    _session.value = UiData.Loaded(
                        GetSessionResult(
                            id = cached.sessionId,
                            solutionId = cached.solutionId,
                            agentId = cached.agentId,
                            title = "",
                            state = "Idle",
                            createdAt = 0L,
                            lastActivityAt = 0L,
                            entries = cached.entries,
                            totalCount = cached.totalCountAtLastWrite,
                        ),
                    )
                } else {
                    _session.value = UiData.Loading
                }
            }
            fetchInitialOrDiff(active, sessionId, cached)
        }
        sessionList.loadChildren(sessionId)
        sessionList.ensureNotificationsObserver()
    }

    fun closeSession() {
        scope.launch {
            sessionMutex.withLock {
                openSessionId = null
                _session.value = UiData.Loading
                _isLoadingOlder.value = false
                _optimisticEntries.value = emptyList()
                optimisticIds.clear()
                optimisticClientSendIds.clear()
                _serverQueuedBundles.value = emptyList()
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
        lastSeen.recordIfNewer(payload.sessionId, payload.entryIndex)
        val singularCsid = payload.clientSendId
        val csids: List<Long> = when {
            payload.clientSendIds.isNotEmpty() -> payload.clientSendIds
            singularCsid != null -> listOf(singularCsid)
            else -> emptyList()
        }
        scope.launch {
            sessionMutex.withLock {
                if (openSessionId != payload.sessionId) return@withLock
                // Fast-pop optimistic bubbles whose csid lands in the
                // notification. Done FIRST inside the mutex so the
                // subsequent placeholder add can't create a brief
                // duplicate-bubble window (the prior two-launch
                // arrangement made `pop` and `applyPlaceholder` race —
                // when the apply landed before the pop the chat
                // briefly showed both the local + server bubble).
                if (payload.role == "user" && csids.isNotEmpty()) {
                    for (c in csids) popOptimisticByClientSendIdLocked(c)
                }
                // Apply the placeholder ONLY for new entries (slot
                // doesn't exist yet). For updates to an existing entry
                // (the common case under throttled EntryUpdated
                // streaming — ~5 emits/sec while the agent writes a
                // long reply), skipping the placeholder lets the
                // already-rendered full markdown stay on screen while
                // [fetchAndReplaceEntry] races the new content in.
                // Without this guard the bubble collapsed to the
                // truncated preview between every two updates and the
                // `animateContentSize` tween produced a vertical
                // jitter the user noticed on 2026-05-20.
                val current = _session.value as? UiData.Loaded ?: return@withLock
                val entries = current.value.entries
                if (payload.entryIndex >= entries.size) {
                    when (val outcome = applyAppendedPlaceholder(entries, payload)) {
                        is AppendedPlaceholderOutcome.Replaced -> {
                            _session.value = UiData.Loaded(
                                current.value.copy(entries = outcome.entries),
                            )
                        }
                        AppendedPlaceholderOutcome.OutOfRange -> {
                            val active = context.activeClient() ?: return@withLock
                            scope.launch {
                                runCatching { fetchFullSession(active, payload.sessionId) }
                            }
                        }
                    }
                }
            }
            fetchAndReplaceEntry(openSid, payload.entryIndex)
        }
    }

    override fun onMessageAppendedFallback() {
        val openSid = openSessionId ?: return
        refreshSession(openSid)
    }

    override fun onSessionStateOrTitleChanged(notifSessionId: String?) {
        val openSid = openSessionId ?: return
        if (notifSessionId != null && notifSessionId != openSid) return
        refreshSession(openSid)
    }

    override fun onSessionQueueChanged(payload: SessionQueueChangedPayload) {
        val openSid = openSessionId ?: return
        if (payload.sessionId != openSid) return
        scope.launch {
            sessionMutex.withLock {
                if (openSessionId != payload.sessionId) return@withLock
                _serverQueuedBundles.value = payload.bundles
                // Every csid that landed in a server-broadcast bundle
                // is no longer a "client-only optimistic" — drop the
                // matching local bubble so the server's bundle takes
                // over the visual slot, matching the desktop's single-
                // ghost-bubble UX even when the bundle absorbed multiple
                // originating sends.
                val csidsFromBundles: Set<Long> =
                    payload.bundles.flatMap { it.csids }.toHashSet()
                if (csidsFromBundles.isNotEmpty()) {
                    val current = _optimisticEntries.value
                    val filtered = current.filter { entry ->
                        entry.clientSendId !in csidsFromBundles
                    }
                    if (filtered.size != current.size) {
                        _optimisticEntries.value = filtered
                        // Mirror the in-list shrink onto the parallel
                        // bookkeeping arrays so a future
                        // `reconcileOptimisticLocked` sees consistent
                        // state. iterate by index since we want the
                        // SAME positions we just dropped.
                        val keptIndices: List<Int> = current.indices.filter {
                            current[it].clientSendId !in csidsFromBundles
                        }
                        val keptIds = keptIndices.mapNotNull { optimisticIds.getOrNull(it) }
                        val keptCsids = keptIndices.mapNotNull { optimisticClientSendIds.getOrNull(it) }
                        optimisticIds.clear()
                        optimisticIds.addAll(keptIds)
                        optimisticClientSendIds.clear()
                        optimisticClientSendIds.addAll(keptCsids)
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Network ops
    // -------------------------------------------------------------------------

    private fun fetchAndReplaceEntry(sessionId: String, index: Int) {
        val active = context.activeClient() ?: return
        scope.launch {
            val result = runCatching {
                active.getSessionEntry(sessionId, index, includeImages = true)
            }.getOrNull() ?: return@launch
            var snapshotForCache: GetSessionResult? = null
            sessionMutex.withLock {
                // stale-write barrier (see class kdoc invariant 1)
                if (openSessionId != sessionId) return@withLock
                val current = _session.value as? UiData.Loaded ?: return@withLock
                val entries = current.value.entries
                val newEntries = when {
                    index < entries.size ->
                        entries.toMutableList().also { it[index] = result.entry }
                    index == entries.size -> entries + result.entry
                    else -> {
                        scope.launch {
                            runCatching { fetchFullSession(active, sessionId) }
                        }
                        return@withLock
                    }
                }
                val updated = current.value.copy(entries = newEntries)
                _session.value = UiData.Loaded(updated)
                reconcileOptimisticLocked(newEntries)
                snapshotForCache = updated
            }
            // Persist cache outside the mutex — repository write is
            // already debounced + IO-dispatched.
            snapshotForCache?.let {
                persistCache(sessionId, it, it.entries, it.totalCount)
            }
        }
    }

    private suspend fun fetchFullSession(active: RemoteClient, sessionId: String) {
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("include_full_content", true)
            put("include_images", true)
        }
        val result = runCatching { active.call("remote.solution_agent.get_session", params) }
            .mapCatching { resp -> resp.decodeResultOrThrow(GetSessionResult.serializer()) }
            .getOrNull() ?: return
        sessionMutex.withLock {
            // stale-write barrier (see class kdoc invariant 1)
            if (openSessionId != sessionId) return@withLock
            _session.value = UiData.Loaded(result)
            _serverQueuedBundles.value = result.pendingBundles
            reconcileOptimisticLocked(result.entries)
        }
        persistCache(sessionId, result, result.entries, result.totalCount)
    }

    private suspend fun fetchInitialPage(active: RemoteClient, sessionId: String) {
        fetchInitialOrDiff(active, sessionId, cached = null)
    }

    /**
     * Pull either a full initial page (no cache, or `after_index` cursor
     * absent) or a diff against [cached] via `after_index=cached.lastIndex`.
     * Resolves the [MergeOutcome] in-place: [MergeOutcome.FullReplace] +
     * [MergeOutcome.Appended] splice into `_session.value`;
     * [MergeOutcome.GapDetected] retries WITHOUT `after_index` and full-
     * replaces.
     */
    private suspend fun fetchInitialOrDiff(
        active: RemoteClient,
        sessionId: String,
        cached: CachedSessionHistory?,
    ) {
        val afterIndex: Int? = cached
            ?.takeIf { it.entries.isNotEmpty() }
            ?.lastIndex
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("include_full_content", true)
            put("include_images", true)
            if (afterIndex != null) {
                put("after_index", afterIndex)
            } else {
                put("count", SESSION_PAGE_SIZE)
            }
        }
        val outcome = runCatching { active.call("remote.solution_agent.get_session", params) }
            .mapCatching { resp -> resp.decodeResultOrThrow(GetSessionResult.serializer()) }
        outcome
            .onSuccess { fetched ->
                val merged = mergeSessionHistory(
                    cachedEntries = cached?.entries.orEmpty(),
                    cachedLastIndex = cached?.lastIndex,
                    cachedTotalCount = cached?.totalCountAtLastWrite ?: -1,
                    fetched = fetched,
                    afterIndexHint = afterIndex,
                )
                when (merged) {
                    is MergeOutcome.FullReplace ->
                        applyFullReplace(sessionId, fetched, merged.entries, merged.newTotalCount)
                    is MergeOutcome.Appended ->
                        applyAppended(sessionId, fetched, cached, merged.mergedEntries, merged.newTotalCount)
                    is MergeOutcome.GapDetected -> {
                        // Fall back to a full fetch — drop the after_index cursor.
                        val fullParams = buildJsonObject {
                            put("session_id", sessionId)
                            put("include_full_content", true)
                            put("include_images", true)
                            put("count", SESSION_PAGE_SIZE)
                        }
                        val fullOutcome = runCatching {
                            active.call("remote.solution_agent.get_session", fullParams)
                        }.mapCatching { resp ->
                            resp.decodeResultOrThrow(GetSessionResult.serializer())
                        }
                        fullOutcome
                            .onSuccess { full ->
                                applyFullReplace(sessionId, full, full.entries, full.totalCount)
                            }
                            .onFailure { applyFetchFailure(sessionId, it) }
                    }
                }
            }
            .onFailure { applyFetchFailure(sessionId, it) }
    }

    private suspend fun applyFullReplace(
        sessionId: String,
        fetched: GetSessionResult,
        entries: List<EntrySummary>,
        newTotalCount: Int,
    ) {
        sessionMutex.withLock {
            if (openSessionId != sessionId) return@withLock
            val result = fetched.copy(entries = entries, totalCount = newTotalCount)
            _session.value = UiData.Loaded(result)
            _serverQueuedBundles.value = fetched.pendingBundles
            _isLoadingOlder.value = false
            reconcileOptimisticLocked(entries)
            entries.lastOrNull()?.takeIf { it.index >= 0 }?.let { newest ->
                lastSeen.recordIfNewer(sessionId, newest.index)
            }
        }
        persistCache(sessionId, fetched, entries, newTotalCount)
    }

    private suspend fun applyAppended(
        sessionId: String,
        fetched: GetSessionResult,
        cached: CachedSessionHistory?,
        mergedEntries: List<EntrySummary>,
        newTotalCount: Int,
    ) {
        sessionMutex.withLock {
            if (openSessionId != sessionId) return@withLock
            val result = fetched.copy(entries = mergedEntries, totalCount = newTotalCount)
            _session.value = UiData.Loaded(result)
            _serverQueuedBundles.value = fetched.pendingBundles
            _isLoadingOlder.value = false
            reconcileOptimisticLocked(mergedEntries)
            mergedEntries.lastOrNull()?.takeIf { it.index >= 0 }?.let { newest ->
                lastSeen.recordIfNewer(sessionId, newest.index)
            }
        }
        // Fall back to the cached solutionId when the server omits it
        // (some legacy `after_index` responses leave it blank because the
        // session id alone is canonical for the routing layer).
        val solutionId = fetched.solutionId.ifEmpty { cached?.solutionId.orEmpty() }
        persistCache(
            sessionId,
            fetched.copy(solutionId = solutionId),
            mergedEntries,
            newTotalCount,
        )
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
        val lastIdx = entries.mapNotNull { it.index.takeIf { i -> i >= 0 } }.maxOrNull()
        sessionHistoryRepository.save(
            CachedSessionHistory(
                sessionId = sessionId,
                solutionId = fetched.solutionId,
                agentId = fetched.agentId,
                entries = entries,
                lastIndex = lastIdx,
                totalCountAtLastWrite = newTotalCount,
            ),
        )
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

    fun resumeSession(sessionId: String) {
        val active = context.activeClient() ?: return
        if (openSessionId != sessionId) return
        val current = _session.value
        val lastSeenIdx = lastSeen.getCached(sessionId) ?: lastSeen.readFromDisk(sessionId)
        if (lastSeenIdx == null || current !is UiData.Loaded) {
            scope.launch { fetchInitialPage(active, sessionId) }
            return
        }
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("include_full_content", true)
            put("include_images", true)
            put("after_index", lastSeenIdx)
        }
        scope.launch {
            val outcome = runCatching { active.call("remote.solution_agent.get_session", params) }
                .mapCatching { resp -> resp.decodeResultOrThrow(GetSessionResult.serializer()) }
            outcome.onSuccess { result ->
                // Fast-path: dedup first, then decide whether the
                // post-dedup merged size still falls short of the server's
                // totalCount (audit Fix R). The previous order compared
                // pre-dedup, which could spuriously trigger a full
                // refetch when the resume returned overlapping entries.
                var snapshotForCache: GetSessionResult? = null
                sessionMutex.withLock {
                    if (openSessionId != sessionId) return@withLock
                    val latest = _session.value as? UiData.Loaded ?: return@withLock
                    val existingIndices = latest.value.entries.mapNotNull {
                        it.index.takeIf { i -> i >= 0 }
                    }.toHashSet()
                    val fresh = result.entries.filterNot {
                        it.index >= 0 && existingIndices.contains(it.index)
                    }
                    val merged = if (fresh.isEmpty()) latest.value.entries else latest.value.entries + fresh
                    val mergedTotal = maxOf(latest.value.totalCount, result.totalCount)
                    if (result.totalCount >= 0 && merged.size < result.totalCount) {
                        // Truly fell short — kick a full refetch.
                        scope.launch { fetchInitialPage(active, sessionId) }
                        return@withLock
                    }
                    if (fresh.isEmpty()) {
                        if (result.totalCount >= 0 && result.totalCount != latest.value.totalCount) {
                            _session.value = UiData.Loaded(
                                latest.value.copy(totalCount = result.totalCount),
                            )
                        }
                        return@withLock
                    }
                    val updated = latest.value.copy(entries = merged, totalCount = mergedTotal)
                    _session.value = UiData.Loaded(updated)
                    reconcileOptimisticLocked(merged)
                    fresh.lastOrNull()?.takeIf { it.index >= 0 }?.let { newest ->
                        lastSeen.recordIfNewer(sessionId, newest.index)
                    }
                    snapshotForCache = updated
                }
                snapshotForCache?.let {
                    persistCache(sessionId, it, it.entries, it.totalCount)
                }
            }
            // Failed resume is recoverable — silent.
        }
    }

    private fun refreshSession(sessionId: String) {
        val active = context.activeClient() ?: return
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("include_full_content", true)
            put("include_images", true)
        }
        scope.launch {
            val outcome = runCatching { active.call("remote.solution_agent.get_session", params) }
                .mapCatching { resp -> resp.decodeResultOrThrow(GetSessionResult.serializer()) }
            var snapshotForCache: GetSessionResult? = null
            sessionMutex.withLock {
                // stale-write barrier (see class kdoc invariant 1)
                if (openSessionId != sessionId) return@withLock
                outcome
                    .onSuccess { result ->
                        _session.value = UiData.Loaded(result)
                        _serverQueuedBundles.value = result.pendingBundles
                        reconcileOptimisticLocked(result.entries)
                        snapshotForCache = result
                    }
                    .onFailure {
                        if (_session.value !is UiData.Loaded) {
                            _session.value = UiData.Error(it.message ?: "unknown error")
                        }
                    }
            }
            snapshotForCache?.let {
                persistCache(sessionId, it, it.entries, it.totalCount)
            }
        }
    }

    /**
     * Pop optimistic bubbles whose corresponding server-side "user"
     * entry has now landed. Delegates to the pure [reconcileOptimistic]
     * (in `:core`); MUST be called while holding [sessionMutex] —
     * mutates [_optimisticEntries], [optimisticIds] and
     * [optimisticClientSendIds] in lock-step.
     *
     * Matching is id-first (server echoes `client_send_id` on user
     * entries that carried `_meta.spk_client_send_id`) with a
     * content-match fallback for entries that don't carry one (older
     * server, desktop-originated send, etc.). The id path is what
     * makes long messages (> 200 chars, server-truncated preview)
     * dedupe correctly — the regression user-reported on 2026-05-18.
     */
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
        val localId = optimisticIdGen.incrementAndGet()
        val clientSendId = clientSendIdGen.incrementAndGet()
        val optimistic = EntrySummary(
            role = "user",
            preview = preview,
            clientSendId = clientSendId,
        )
        val stamped = stampClientSendId(blocks, clientSendId)
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
            // bubble being optimistic + the live session state — no
            // client-side gate or persistence needed.
            runCatching {
                active.queueCall("remote.solution_agent.send_message_blocks", params)
            }
                .mapCatching { resp ->
                    val err = resp.error
                    if (err != null) error(err.message)
                    val toolErr = resp.toolError()
                    if (toolErr != null) error(toolErr)
                }
                .onFailure {
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
        val optimistic = EntrySummary(role = "user", preview = preview, clientSendId = send.csid)
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
     * Notification-driven fast-pop of an optimistic bubble whose
     * server-side echo just arrived. Called from [onMessageAppended] when
     * the payload carries a `client_send_id` that matches one of our
     * stamped bubbles — pops it immediately rather than waiting for the
     * next `list_sessions`-driven reconcile. This is what closes the
     * round-trip window that produced the user-visible duplicate
     * reported 2026-05-18 (long message → server echoes via notification
     * → next refresh would normally reconcile, but the user already saw
     * the duplicate in between).
     *
     * MUST hold [sessionMutex] before mutating — same discipline as
     * [reconcileOptimisticLocked] / [removeOptimisticById]. The caller
     * wraps the call in `sessionMutex.withLock`.
     */
    private fun popOptimisticByClientSendIdLocked(clientSendId: Long) {
        val idx = optimisticClientSendIds.indexOf(clientSendId)
        if (idx < 0) return
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

    /**
     * Render a one-line preview of a [blocks] list for the optimistic
     * bubble. Text blocks' first line wins (truncated); image / file
     * blocks contribute a short bracketed annotation so the user
     * recognises what they just sent before the server echoes the full
     * rendering back.
     */
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
        val active = context.activeClient() ?: return
        val sessionId = openSessionId ?: return
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
                .onFailure { context.emitError("cancel failed: ${it.message ?: "?"}") }
            _cancelInFlight.value = false
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
     * Reset the agent backing the currently-open session — drops the
     * pooled subprocess, closes the source session, and opens a fresh
     * one against the same `(solution, agent)` pair. The new session id
     * comes back on the RPC result; we emit it via [resetSwitch] so the
     * chat surface can navigate to the freshly-minted session (the
     * current screen's `DisposableEffect` would otherwise re-open the
     * closed source id on next composition).
     */
    fun resetContext() {
        val active = context.activeClient() ?: return
        val sessionId = openSessionId ?: return
        val params = buildJsonObject { put("session_id", sessionId) }
        scope.launch {
            runCatching { active.call("remote.solution_agent.restart_agent", params) }
                .mapCatching { resp -> resp.decodeResultOrThrow(RestartAgentResult.serializer()) }
                .onSuccess {
                    // Evict the OLD session's cache — the new id starts
                    // with no transcript. Done after the RPC succeeds so
                    // a failed restart leaves the cache intact.
                    sessionHistoryRepository.evict(sessionId)
                    _resetSwitch.tryEmit(it.sessionId)
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
        val active = context.activeClient() ?: return
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

    // ---- Draft seed methods (R-6c-multi) ----

    suspend fun loadDraftSeed(sessionId: String): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        val bounced = draftRepository.bouncedFor(sessionId)
        if (bounced != null) bounced to true
        else draftRepository.load(sessionId) to false
    }

    suspend fun saveDraft(sessionId: String, text: String) = withContext(Dispatchers.IO) {
        draftRepository.save(sessionId, text)
    }

    fun clearDraft(sessionId: String) {
        draftRepository.clear(sessionId)
    }
}
