package ru.sipaha.spkremote.app.vm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.sipaha.spkremote.app.data.CachedSessionHistory
import ru.sipaha.spkremote.app.data.DraftRepository
import ru.sipaha.spkremote.app.data.SessionHistoryRepository
import ru.sipaha.spkremote.core.AppendedPlaceholderOutcome
import ru.sipaha.spkremote.core.ContentBlockDto
import ru.sipaha.spkremote.core.EntrySummary
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
import ru.sipaha.spkremote.core.reconcileOptimisticContent
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/** Page size for [SessionDetailStore.openSession] / [SessionDetailStore.loadOlder]. */
private const val SESSION_PAGE_SIZE = 50

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
 *  4. **Optimistic bubbles carry a stable client id** — see
 *     [optimisticIdGen]. The pure `reconcileOptimisticContent` matches
 *     by content (FIFO order of arrival) so sending duplicate text
 *     twice in a row reconciles when both echoes land.
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
     * Per-optimistic-bubble flag marking whether the bubble was produced by
     * a multi-block send (`sendMessageBlocks`) rather than a plain text
     * [sendMessage]. Paired with [optimisticIds] by list index. Mutated
     * together with the entry / id lists under [sessionMutex].
     *
     * **Why a separate list?** The content-match dedupe in
     * `reconcileOptimisticContent` compares the optimistic `preview` (the
     * user's typed text + a `[image]` / `[file]` annotation) against the
     * server-echoed user entry. For a blocks send the server formats the
     * user entry with ACP rendering — different structure, different
     * preview — so the content-match fires for plain-text bubbles only.
     * We use this flag to drive the FIFO "drop oldest blocks bubble when
     * any user-role echo arrives" fallback in [reconcileOptimisticLocked].
     */
    private val optimisticBlocksFlags: MutableList<Boolean> = mutableListOf()

    private val _cancelInFlight = MutableStateFlow(false)
    val cancelInFlight: StateFlow<Boolean> = _cancelInFlight.asStateFlow()

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
                optimisticBlocksFlags.clear()
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
                optimisticBlocksFlags.clear()
                lastSeen.primeFromDisk(sessionId)
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
                optimisticBlocksFlags.clear()
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
        applyAppendedPlaceholderToFlow(payload)
        fetchAndReplaceEntry(openSid, payload.entryIndex)
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

    // -------------------------------------------------------------------------
    // Network ops
    // -------------------------------------------------------------------------

    private fun applyAppendedPlaceholderToFlow(payload: MessageAppendedPayload) {
        scope.launch {
            sessionMutex.withLock {
                // stale-write barrier (see class kdoc invariant 1)
                if (openSessionId != payload.sessionId) return@withLock
                val current = _session.value as? UiData.Loaded ?: return@withLock
                when (val outcome = applyAppendedPlaceholder(current.value.entries, payload)) {
                    is AppendedPlaceholderOutcome.Replaced -> {
                        _session.value = UiData.Loaded(current.value.copy(entries = outcome.entries))
                    }
                    AppendedPlaceholderOutcome.OutOfRange -> {
                        // Out-of-range index — fall back to a full refetch.
                        val active = context.activeClient() ?: return@withLock
                        scope.launch {
                            runCatching { fetchFullSession(active, payload.sessionId) }
                        }
                    }
                }
            }
        }
    }

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
     * entry has now landed. Delegates to the pure
     * `reconcileOptimisticContent` (in `:core`); MUST be called while
     * holding [sessionMutex] — mutates both [_optimisticEntries] and
     * [optimisticIds] in lock-step.
     *
     * Audit Phase 3 caveat: this is content-match, NOT strict FIFO
     * stable-id matching. Sending the same text twice in a row where
     * the first send fails before the server echoes can briefly
     * appear to dedup the wrong bubble; the optimisticId rewrite
     * keeps the cancel path correct nonetheless.
     */
    private fun reconcileOptimisticLocked(serverEntries: List<EntrySummary>) {
        if (_optimisticEntries.value.isEmpty()) return
        // Snapshot the pre-reconcile state so we can rewrite the parallel
        // [optimisticBlocksFlags] list to match the kept-id ordering that
        // the pure reconciler returns.
        val priorEntries = _optimisticEntries.value
        val priorIds = optimisticIds.toList()
        val priorBlocks = optimisticBlocksFlags.toList()
        val (keptEntries, keptIds) = reconcileOptimisticContent(
            optimistic = priorEntries,
            optimisticIds = priorIds,
            serverUserEntries = serverEntries,
        )
        // Rebuild the blocks-flag list against the post-reconcile id list
        // so positional pairing with entries/ids stays consistent.
        val keptBlocks = keptIds.map { id ->
            val idx = priorIds.indexOf(id)
            if (idx >= 0) priorBlocks.getOrElse(idx) { false } else false
        }
        // Fallback dedupe (block-bearing bubbles): the content-match
        // above cannot dedup a blocks-bearing optimistic bubble because
        // the server-side rendered user entry doesn't equal the local
        // preview. If the server emitted at least one user entry that
        // the content-match consumed (or that wasn't in `priorEntries`
        // at all), pop the oldest still-pending blocks bubble — under
        // the assumption the user can't fire two blocks sends faster
        // than a round-trip. Worst case the wrong bubble disappears for
        // a fraction of a second before the next refresh restores it.
        val serverUserCount = serverEntries.count { it.role == "user" }
        val poppedByContent = priorEntries.size - keptEntries.size
        val remainingUserEchoes = (serverUserCount - poppedByContent).coerceAtLeast(0)
        var blocksToDrop = remainingUserEchoes
        val finalEntries = keptEntries.toMutableList()
        val finalIds = keptIds.toMutableList()
        val finalBlocks = keptBlocks.toMutableList()
        var i = 0
        while (i < finalBlocks.size && blocksToDrop > 0) {
            if (finalBlocks[i]) {
                finalEntries.removeAt(i)
                finalIds.removeAt(i)
                finalBlocks.removeAt(i)
                blocksToDrop -= 1
            } else {
                i += 1
            }
        }
        _optimisticEntries.value = finalEntries
        optimisticIds.clear()
        optimisticIds.addAll(finalIds)
        optimisticBlocksFlags.clear()
        optimisticBlocksFlags.addAll(finalBlocks)
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        val active = context.activeClient() ?: return
        val sessionId = openSessionId ?: return
        val optimistic = EntrySummary(role = "user", preview = text)
        val localId = optimisticIdGen.incrementAndGet()
        scope.launch {
            sessionMutex.withLock {
                _optimisticEntries.value = _optimisticEntries.value + optimistic
                optimisticIds.add(localId)
                optimisticBlocksFlags.add(false)
            }
            val params = buildJsonObject {
                put("session_id", sessionId)
                put("content", text)
            }
            runCatching { active.queueCall("remote.solution_agent.send_message", params) }
                .mapCatching { resp ->
                    val err = resp.error
                    if (err != null) error(err.message)
                    // Match the envelope-error precedent set by
                    // [decodeResultOrThrow]: tool-level `isError: true`
                    // must surface to the user the same way.
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
     * Multi-block variant of [sendMessage] backing the mobile attach flow.
     * The block list is encoded as `Vec<acp::ContentBlock>` on the wire
     * and dispatched via `remote.solution_agent.send_message_blocks`.
     *
     * The optimistic bubble carries a flattened text preview (text blocks
     * concatenated, plus `[image]` / `[file]` annotations for non-text
     * payloads) so the chat surface shows immediate feedback. The bubble
     * is dropped on the next user-role server echo — see
     * [reconcileOptimisticLocked]'s fallback path. Failure removes the
     * bubble synchronously and surfaces the reason via [ConnectionContext.emitError].
     */
    fun sendMessageBlocks(blocks: List<ContentBlockDto>) {
        if (blocks.isEmpty()) return
        val active = context.activeClient() ?: return
        val sessionId = openSessionId ?: return
        val preview = buildBlocksPreview(blocks)
        val optimistic = EntrySummary(role = "user", preview = preview)
        val localId = optimisticIdGen.incrementAndGet()
        scope.launch {
            sessionMutex.withLock {
                _optimisticEntries.value = _optimisticEntries.value + optimistic
                optimisticIds.add(localId)
                optimisticBlocksFlags.add(true)
            }
            val blocksJson = JsonRpc.json.encodeToJsonElement(
                ListSerializer(ContentBlockDto.serializer()),
                blocks,
            )
            val params = buildJsonObject {
                put("session_id", sessionId)
                put("blocks", blocksJson)
            }
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
     * Drop one optimistic bubble matched by stable [localId]. Both
     * [optimisticIds] and [optimisticBlocksFlags] stay paired by index
     * with [_optimisticEntries] because we always mutate the three lists
     * under [sessionMutex] together; the indexOf lookup here is therefore
     * referentially safe even after a content-match reconcile.
     */
    private suspend fun removeOptimisticById(localId: Long) {
        sessionMutex.withLock {
            val idx = optimisticIds.indexOf(localId)
            if (idx < 0) return@withLock
            optimisticIds.removeAt(idx)
            if (idx < optimisticBlocksFlags.size) {
                optimisticBlocksFlags.removeAt(idx)
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
