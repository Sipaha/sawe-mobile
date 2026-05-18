package ru.sipaha.spkremote.app.vm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ru.sipaha.spkremote.app.data.DraftRepository
import ru.sipaha.spkremote.app.data.LastSeenRepository
import ru.sipaha.spkremote.app.data.ListCacheRepository
import ru.sipaha.spkremote.core.AgentSummary
import ru.sipaha.spkremote.core.CreateSessionResult
import ru.sipaha.spkremote.core.EntrySummary
import ru.sipaha.spkremote.core.GetSessionChildrenResult
import ru.sipaha.spkremote.core.GetSessionResult
import ru.sipaha.spkremote.core.JsonRpc
import ru.sipaha.spkremote.core.ListAgentsResult
import ru.sipaha.spkremote.core.ListSessionsResult
import ru.sipaha.spkremote.core.MessageAppendedPayload
import ru.sipaha.spkremote.core.QueuedMessage
import ru.sipaha.spkremote.core.RemoteClient
import ru.sipaha.spkremote.core.SessionCreatedPayload
import ru.sipaha.spkremote.core.SessionSummary
import ru.sipaha.spkremote.core.stripRoleHeading
import java.util.concurrent.atomic.AtomicLong

/** Page size for [SessionStore.openSession] / [SessionStore.loadOlder]. */
private const val SESSION_PAGE_SIZE = 50

/**
 * Owns everything chat-surface-shaped: the per-solution sessions list,
 * the currently-open session's transcript, optimistic bubbles, sub-agent
 * children, draft seeds, and the two long-running subscriber jobs.
 *
 * Heavy-coupling collaborator — by far the largest of the three. Lives
 * on the same `viewModelScope` as the coordinator so coroutine
 * cancellation stays tied to the VM lifecycle.
 *
 * ### Invariants
 *
 *  1. **`openSessionId` is `@Volatile` + every `_session` write is
 *     guarded by a stale-write barrier** — any coroutine that resolves a
 *     network result MUST re-check `openSessionId == sessionId` immediately
 *     before writing `_session.value`, otherwise a late delivery for a
 *     just-closed session can resurrect the previous transcript on top
 *     of the new session's `Loading`.
 *  2. **`lastSeenEntryIndex` is mutated only from coroutines launched on
 *     [scope]** — it isn't thread-safe; cross-coroutine reads are fine
 *     because writes are sequenced through the same dispatcher.
 *  3. **`sessionStateSubscribed` / `sessionDetailSubscribed` rely on
 *     single-coroutine ownership** — both flags are flipped in the
 *     consolidated observer ([startDetailObserver]) and toggled false in
 *     [reset]; no other site touches them.
 *  4. **[reset] MUST precede any caller after a server switch** — the
 *     create-session flag and the lastSeen map are server-keyed; without
 *     [reset] they leak across servers. The coordinator guarantees this
 *     by routing `onTearDown` to [reset].
 *  5. **Read-modify-write mutations on `_session.value` are serialised
 *     under [sessionMutex]** — multiple coroutines (notification observer,
 *     fetchAndReplaceEntry, loadOlder, resumeSession) compete for the
 *     same flow; without the mutex a stale snapshot can be re-published
 *     on top of a newer one.
 *  6. **Optimistic bubbles carry a stable client id** — see
 *     [optimisticIdGen]. `reconcileOptimistic` matches by id (FIFO order
 *     of arrival) so sending duplicate text twice in a row no longer
 *     leaks one of the bubbles forever.
 */
internal class SessionStore(
    private val scope: CoroutineScope,
    private val context: ConnectionContext,
    private val listCacheRepository: ListCacheRepository,
    private val lastSeenRepository: LastSeenRepository,
    private val draftRepository: DraftRepository,
) {
    private val _sessions = MutableStateFlow<UiData<List<SessionSummary>>>(UiData.Loading)
    val sessions: StateFlow<UiData<List<SessionSummary>>> = _sessions.asStateFlow()

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

    private val _cancelInFlight = MutableStateFlow(false)
    val cancelInFlight: StateFlow<Boolean> = _cancelInFlight.asStateFlow()

    private val _sessionChildren = MutableStateFlow<Map<String, List<SessionSummary>>>(emptyMap())
    val sessionChildren: StateFlow<Map<String, List<SessionSummary>>> = _sessionChildren.asStateFlow()

    private val _agents = MutableStateFlow<UiData<List<AgentSummary>>>(UiData.Loading)
    val agents: StateFlow<UiData<List<AgentSummary>>> = _agents.asStateFlow()

    private val _createSessionInFlight = MutableStateFlow(false)
    val createSessionInFlight: StateFlow<Boolean> = _createSessionInFlight.asStateFlow()

    private val _lastCreateAutoOpened = MutableStateFlow(false)
    val lastCreateAutoOpened: StateFlow<Boolean> = _lastCreateAutoOpened.asStateFlow()

    private var detailSubscribed = false
    private var listSubscribed = false

    /**
     * Single notification collector that fans out to detail + list
     * handlers (audit Fix N). One long-running job per server, started
     * lazily when either [startObservingSessions] or [openSession] is
     * called and the active client doesn't already have a collector.
     */
    private var notificationsObserverJob: Job? = null

    /**
     * The currently-observed solution id for the list path. Null when
     * no list observer is active. The single notification collector
     * reads this to decide whether to refresh the sessions list when
     * a `agent_session_*` event arrives.
     */
    @Volatile
    private var observingSolutionId: String? = null

    @Volatile
    var openSessionId: String? = null
        private set

    private val lastSeenEntryIndex = mutableMapOf<String, Int>()
    private var refreshSessionsJob: Job? = null

    /**
     * Serialises read-modify-write mutations on [_session]. See class
     * KDoc invariant 5.
     */
    private val sessionMutex = Mutex()

    /** Tear-down hook called from coordinator on server switch / disconnect. */
    fun reset() {
        notificationsObserverJob?.cancel()
        notificationsObserverJob = null
        observingSolutionId = null
        // Cancel any in-flight session-list refresh so its onSuccess can't
        // overwrite the just-reset `_sessions` with the previous server's
        // payload after we've already nulled it.
        refreshSessionsJob?.cancel()
        refreshSessionsJob = null
        listSubscribed = false
        detailSubscribed = false
        _sessions.value = UiData.Loading
        _agents.value = UiData.Loading
        _isLoadingOlder.value = false
        _sessionChildren.value = emptyMap()
        // The create-session in-flight flag is keyed to the *previous*
        // server's client. If a create was racing the server switch its
        // continuation no-ops on the new server, so we must clear the
        // flag here — otherwise the Create button stays permanently
        // disabled on the new server.
        _createSessionInFlight.value = false
        // Detail-state mutations MUST run under `sessionMutex` — otherwise
        // an in-flight `withLock` block that already cleared its stale-write
        // barrier check (e.g. fetchSession onSuccess) could publish AFTER
        // this clear and resurrect the previous server's session state.
        // See class KDoc invariant 5 + closeSession() (mirrors this pattern).
        scope.launch {
            sessionMutex.withLock {
                openSessionId = null
                lastSeenEntryIndex.clear()
                _session.value = UiData.Loading
                _optimisticEntries.value = emptyList()
                optimisticIds.clear()
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
        // Don't cancel the consolidated observer here — [reset] (driven
        // by onTearDown) does that. Only drop the open-session marker
        // so resumeSession in onReconnected sees null.
        openSessionId = null
    }

    /** Bounce-to-input recovery routed here from ConnectionManager. */
    fun handleExpiredMessage(message: QueuedMessage) {
        if (message.method != "remote.solution_agent.send_message") return
        val params = (message.params as? JsonObject) ?: return
        val sessionId = params["session_id"]?.jsonPrimitive?.content ?: return
        val content = params["content"]?.jsonPrimitive?.content ?: return
        if (content.isBlank()) return
        draftRepository.setBounced(sessionId, content)
    }

    fun refreshSessions(solutionId: String) {
        val active = context.activeClient()
        if (active == null) {
            val cached = listCacheRepository.loadSessions(solutionId)
            if (cached != null) {
                _sessions.value = UiData.Loaded(cached)
                context.emitError(context.notConnectedMessage())
            } else {
                _sessions.value = UiData.Error(context.notConnectedMessage())
            }
            return
        }
        if (_sessions.value !is UiData.Loaded) {
            val cached = listCacheRepository.loadSessions(solutionId)
            if (cached != null) {
                _sessions.value = UiData.Loaded(cached)
            } else {
                _sessions.value = UiData.Loading
            }
        }
        val params = buildJsonObject { put("solution_id", solutionId) }
        singleFlightRefresh(
            scope = scope,
            target = _sessions,
            jobHolder = { refreshSessionsJob },
            setJob = { refreshSessionsJob = it },
            emitError = { context.emitError("Couldn't refresh sessions: $it") },
            fetch = {
                val resp = active.call("remote.solution_agent.list_sessions", params)
                resp.decodeResultOrThrow(ListSessionsResult.serializer()).sessions
            },
            onSuccess = { listCacheRepository.saveSessions(solutionId, it) },
        )
    }

    fun startObservingSessions(solutionId: String) {
        if (context.activeClient() == null) return
        observingSolutionId = solutionId
        ensureNotificationsObserver()
    }

    fun stopObservingSessions() {
        observingSolutionId = null
        // Don't tear the consolidated observer down if a detail screen
        // is still mounted — only ditch this side of the fan-out. The
        // observer itself is cancelled by [reset] on server switch.
    }

    fun clearSessions() {
        _sessions.value = UiData.Loading
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
        scope.launch {
            sessionMutex.withLock {
                openSessionId = sessionId
                _session.value = UiData.Loading
                _isLoadingOlder.value = false
                _optimisticEntries.value = emptyList()
                optimisticIds.clear()
                if (lastSeenEntryIndex[sessionId] == null) {
                    lastSeenRepository.get(sessionId)?.let { lastSeenEntryIndex[sessionId] = it }
                }
            }
            fetchInitialPage(active, sessionId)
        }
        loadChildren(sessionId)
        ensureNotificationsObserver()
    }

    /**
     * Lazily start (or restart) the consolidated notification collector
     * for the active client. Idempotent — if a job is already alive we
     * leave it. Cancelled in [reset] on server switch.
     */
    private fun ensureNotificationsObserver() {
        val active = context.activeClient() ?: return
        if (notificationsObserverJob?.isActive == true) return
        notificationsObserverJob = scope.launch {
            // Subscribe to both list-level and detail-level kinds in one
            // call so the server only sees one subscription event. The
            // subscribe() call is idempotent on the server side.
            if (!listSubscribed || !detailSubscribed) {
                runCatching {
                    active.subscribe(
                        listOf(
                            "agent_session_state_changed",
                            "agent_session_created",
                            "agent_session_closed",
                            "agent_session_title_changed",
                            "agent_session_message_appended",
                        ),
                    )
                }.onSuccess {
                    listSubscribed = true
                    detailSubscribed = true
                }
            }
            active.notifications.collect { frame ->
                val params = (frame as? JsonObject)?.get("params") as? JsonObject
                    ?: return@collect
                val kind = params["kind"]?.jsonPrimitive?.content ?: return@collect
                val data = params["data"] as? JsonObject
                handleNotification(kind, data)
            }
        }
    }

    private fun handleNotification(kind: String, data: JsonObject?) {
        // List handler — refresh sessions list when a session-shaped
        // event arrives, regardless of whether a detail screen is also
        // mounted.
        val solutionId = observingSolutionId
        when (kind) {
            "agent_session_state_changed",
            "agent_session_created",
            "agent_session_closed",
            "agent_session_title_changed" -> if (solutionId != null) refreshSessions(solutionId)
        }

        // Detail handler — only active when an open session is mounted.
        val openSid = openSessionId ?: return
        when (kind) {
            "agent_session_created" -> {
                val payload = data?.let {
                    runCatching {
                        JsonRpc.json.decodeFromJsonElement(
                            SessionCreatedPayload.serializer(),
                            it,
                        )
                    }.getOrNull()
                } ?: return
                val parent = payload.parentSessionId ?: return
                if (parent == openSid) {
                    loadChildren(parent)
                }
            }
            "agent_session_message_appended" -> {
                if (data == null) {
                    refreshSession(openSid)
                    return
                }
                val payload = runCatching {
                    JsonRpc.json.decodeFromJsonElement(
                        MessageAppendedPayload.serializer(),
                        data,
                    )
                }.getOrNull()
                if (payload == null) {
                    refreshSession(openSid)
                    return
                }
                if (payload.sessionId != openSid) return
                val prev = lastSeenEntryIndex[payload.sessionId] ?: -1
                if (payload.entryIndex > prev) {
                    lastSeenEntryIndex[payload.sessionId] = payload.entryIndex
                    lastSeenRepository.set(payload.sessionId, payload.entryIndex)
                }
                applyAppendedPlaceholder(payload)
                fetchAndReplaceEntry(openSid, payload.entryIndex)
            }
            "agent_session_state_changed",
            "agent_session_title_changed" -> {
                val notifSessionId = data?.get("session_id")?.jsonPrimitive?.content
                if (notifSessionId != null && notifSessionId != openSid) return
                refreshSession(openSid)
            }
        }
    }

    private fun loadChildren(sessionId: String) {
        val active = context.activeClient() ?: return
        val params = buildJsonObject { put("session_id", sessionId) }
        scope.launch {
            runCatching { active.call("remote.solution_agent.get_session_children", params) }
                .mapCatching { resp ->
                    resp.decodeResultOrThrow(GetSessionChildrenResult.serializer())
                }
                .onSuccess { result ->
                    _sessionChildren.value = _sessionChildren.value + (sessionId to result.children)
                }
        }
    }

    private fun applyAppendedPlaceholder(payload: MessageAppendedPayload) {
        scope.launch {
            sessionMutex.withLock {
                // stale-write barrier (see class kdoc invariant 1)
                if (openSessionId != payload.sessionId) return@withLock
                val current = _session.value as? UiData.Loaded ?: return@withLock
                val entries = current.value.entries
                val placeholder = EntrySummary(role = payload.role, preview = payload.preview)
                val newEntries = when {
                    payload.entryIndex == entries.size -> entries + placeholder
                    payload.entryIndex < entries.size ->
                        entries.toMutableList().also { it[payload.entryIndex] = placeholder }
                    else -> {
                        // Out-of-range index — fall back to a full refetch.
                        val active = context.activeClient() ?: return@withLock
                        scope.launch {
                            runCatching { fetchFullSession(active, payload.sessionId) }
                        }
                        return@withLock
                    }
                }
                _session.value = UiData.Loaded(current.value.copy(entries = newEntries))
            }
        }
    }

    private fun fetchAndReplaceEntry(sessionId: String, index: Int) {
        val active = context.activeClient() ?: return
        scope.launch {
            val result = runCatching {
                active.getSessionEntry(sessionId, index, includeImages = true)
            }.getOrNull() ?: return@launch
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
                _session.value = UiData.Loaded(current.value.copy(entries = newEntries))
                reconcileOptimisticLocked(newEntries)
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
    }

    fun closeSession() {
        scope.launch {
            sessionMutex.withLock {
                openSessionId = null
                _session.value = UiData.Loading
                _isLoadingOlder.value = false
                _optimisticEntries.value = emptyList()
                optimisticIds.clear()
            }
        }
    }

    private suspend fun fetchInitialPage(active: RemoteClient, sessionId: String) {
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("include_full_content", true)
            put("include_images", true)
            put("count", SESSION_PAGE_SIZE)
        }
        val outcome = runCatching { active.call("remote.solution_agent.get_session", params) }
            .mapCatching { resp -> resp.decodeResultOrThrow(GetSessionResult.serializer()) }
        sessionMutex.withLock {
            // stale-write barrier (see class kdoc invariant 1)
            if (openSessionId != sessionId) return@withLock
            outcome
                .onSuccess { result ->
                    _session.value = UiData.Loaded(result)
                    _isLoadingOlder.value = false
                    reconcileOptimisticLocked(result.entries)
                    result.entries.lastOrNull()?.takeIf { it.index >= 0 }?.let { newest ->
                        val prev = lastSeenEntryIndex[sessionId] ?: -1
                        if (newest.index > prev) {
                            lastSeenEntryIndex[sessionId] = newest.index
                            lastSeenRepository.set(sessionId, newest.index)
                        }
                    }
                }
                .onFailure {
                    if (_session.value !is UiData.Loaded) {
                        _session.value = UiData.Error(it.message ?: "unknown error")
                    }
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
        val lastSeen = lastSeenEntryIndex[sessionId]
            ?: lastSeenRepository.get(sessionId)
        if (lastSeen == null || current !is UiData.Loaded) {
            scope.launch { fetchInitialPage(active, sessionId) }
            return
        }
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("include_full_content", true)
            put("include_images", true)
            put("after_index", lastSeen)
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
                    _session.value = UiData.Loaded(
                        latest.value.copy(entries = merged, totalCount = mergedTotal),
                    )
                    reconcileOptimisticLocked(merged)
                    fresh.lastOrNull()?.takeIf { it.index >= 0 }?.let { newest ->
                        val prev = lastSeenEntryIndex[sessionId] ?: -1
                        if (newest.index > prev) {
                            lastSeenEntryIndex[sessionId] = newest.index
                            lastSeenRepository.set(sessionId, newest.index)
                        }
                    }
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
            sessionMutex.withLock {
                // stale-write barrier (see class kdoc invariant 1)
                if (openSessionId != sessionId) return@withLock
                outcome
                    .onSuccess { result ->
                        _session.value = UiData.Loaded(result)
                        reconcileOptimisticLocked(result.entries)
                    }
                    .onFailure {
                        if (_session.value !is UiData.Loaded) {
                            _session.value = UiData.Error(it.message ?: "unknown error")
                        }
                    }
            }
        }
    }

    /**
     * Pop optimistic bubbles whose corresponding server-side "user"
     * entry has now landed. Matching is by `stripRoleHeading(preview)`
     * content, walking optimistic entries in FIFO order and removing
     * one server preview from the candidate set per match. Original
     * arrival order of unmatched optimistic entries is preserved.
     * `optimisticIds` is rewritten in lock-step so the cancel-by-id
     * path in [sendMessage]'s onFailure still works.
     *
     * Audit Phase 3 caveat: this is content-match, NOT strict FIFO
     * stable-id matching. Sending the same text twice in a row where
     * the first send fails before the server echoes can briefly
     * appear to dedup the wrong bubble; the optimisticId rewrite
     * keeps the cancel path correct nonetheless.
     *
     * MUST be called while holding [sessionMutex] — mutates both
     * [_optimisticEntries] and [optimisticIds] in lock-step.
     */
    private fun reconcileOptimisticLocked(serverEntries: List<EntrySummary>) {
        if (_optimisticEntries.value.isEmpty()) return
        val serverUserCount = serverEntries.count { it.role == "user" }
        // Track which optimistic indices have already been accounted for
        // by prior reconciliations by comparing against the snapshot —
        // here we just pop the oldest N where N = min(optimistic, server-user).
        // Rationale: each `sendMessage` enqueues one optimistic; each
        // server-side "user" entry that arrives corresponds to one of
        // them in FIFO order (queue + server preserve send order).
        val current = _optimisticEntries.value
        val toDrop = minOf(current.size, serverUserCount)
        if (toDrop <= 0) return
        // The simplest stable FIFO pop is "drop the first toDrop" — but
        // serverUserCount includes ALL user entries the server knows
        // about, including ones from previous reconciliations. To avoid
        // re-counting, we only pop those whose content matches one of
        // the server's "user" previews (best-effort content sanity)
        // while still respecting FIFO order.
        val serverPreviews = serverEntries
            .filter { it.role == "user" }
            .map { stripRoleHeading(it.preview) }
            .toMutableList()
        val keptEntries = mutableListOf<EntrySummary>()
        val keptIds = mutableListOf<Long>()
        for ((idx, optimistic) in current.withIndex()) {
            val id = optimisticIds.getOrNull(idx) ?: -1L
            val key = stripRoleHeading(optimistic.preview)
            val hit = serverPreviews.indexOf(key)
            if (hit >= 0) {
                serverPreviews.removeAt(hit)
            } else {
                keptEntries.add(optimistic)
                keptIds.add(id)
            }
        }
        _optimisticEntries.value = keptEntries
        optimisticIds.clear()
        optimisticIds.addAll(keptIds)
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
                    sessionMutex.withLock {
                        // Match by stable id (audit Fix Q) — referential
                        // equality breaks when reconcileOptimistic has
                        // rebuilt the list. The two lists stay in sync
                        // because we mutate them together under the
                        // mutex.
                        val idx = optimisticIds.indexOf(localId)
                        if (idx >= 0) {
                            optimisticIds.removeAt(idx)
                            val list = _optimisticEntries.value.toMutableList()
                            if (idx < list.size) {
                                list.removeAt(idx)
                                _optimisticEntries.value = list
                            }
                        }
                    }
                    val msg = when (it) {
                        is RemoteClient.QueueTtlException ->
                            "send timed out — the editor was offline for too long"
                        is RemoteClient.ClosedException ->
                            "send cancelled — connection closed"
                        else -> it.message ?: "send failed"
                    }
                    context.emitError(msg)
                }
        }
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

    fun loadAgents() {
        val active = context.activeClient()
        _lastCreateAutoOpened.value = false
        if (active == null) {
            _agents.value = UiData.Error(context.notConnectedMessage())
            return
        }
        _agents.value = UiData.Loading
        scope.launch {
            runCatching {
                active.call("remote.solution_agent.list_agents", buildJsonObject {})
            }
                .mapCatching { resp ->
                    resp.decodeResultOrThrow(ListAgentsResult.serializer()).agents
                }
                .onSuccess { _agents.value = UiData.Loaded(it) }
                .onFailure { _agents.value = UiData.Error(it.message ?: "unknown error") }
        }
    }

    fun createSession(
        solutionId: String,
        agentId: String,
        initialMessage: String?,
        title: String?,
        cwd: String?,
        onCreated: (sessionId: String) -> Unit,
    ) {
        val active = context.activeClient()
        if (active == null) {
            context.emitError(context.notConnectedMessage())
            return
        }
        if (_createSessionInFlight.value) return
        _createSessionInFlight.value = true
        _lastCreateAutoOpened.value = false
        scope.launch {
            val firstAttempt = attemptCreateSession(active, solutionId, agentId, initialMessage, title, cwd)
            firstAttempt
                .onSuccess { sessionId ->
                    _createSessionInFlight.value = false
                    onCreated(sessionId)
                }
                .onFailure { firstError ->
                    val message = firstError.message.orEmpty()
                    if (message.contains("no_active_workspace_for_solution", ignoreCase = true)) {
                        val opened = attemptOpenSolution(active, solutionId)
                        if (opened.isFailure) {
                            _createSessionInFlight.value = false
                            val openErr = opened.exceptionOrNull()?.message ?: "open failed"
                            context.emitError("Couldn't open solution: $openErr")
                            return@launch
                        }
                        val retry = attemptCreateSession(active, solutionId, agentId, initialMessage, title, cwd)
                        retry
                            .onSuccess { sessionId ->
                                _lastCreateAutoOpened.value = true
                                _createSessionInFlight.value = false
                                onCreated(sessionId)
                            }
                            .onFailure { retryErr ->
                                _createSessionInFlight.value = false
                                context.emitError(
                                    "Create session failed after opening: ${retryErr.message ?: "?"}",
                                )
                            }
                    } else {
                        _createSessionInFlight.value = false
                        context.emitError("Create session failed: ${message.ifBlank { "?" }}")
                    }
                }
        }
    }

    private suspend fun attemptCreateSession(
        active: RemoteClient,
        solutionId: String,
        agentId: String,
        initialMessage: String?,
        title: String?,
        cwd: String?,
    ): Result<String> {
        val params = buildJsonObject {
            put("solution_id", solutionId)
            put("agent_id", agentId)
            if (!initialMessage.isNullOrBlank()) {
                put("initial_message", initialMessage)
            }
            if (!title.isNullOrBlank()) {
                put("title", title)
            }
            if (!cwd.isNullOrBlank()) {
                put("cwd", cwd)
            }
        }
        return runCatching {
            val resp = active.call("remote.solution_agent.create_session", params)
            resp.decodeResultOrThrow(CreateSessionResult.serializer()).sessionId
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        val active = context.activeClient()
        if (active == null) {
            context.emitError(context.notConnectedMessage())
            return
        }
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) {
            context.emitError("Session title can't be empty")
            return
        }
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("title", trimmed)
        }
        scope.launch {
            runCatching { active.call("remote.solution_agent.rename_session", params) }
                .mapCatching { resp ->
                    val err = resp.error
                    if (err != null) error(err.message)
                    val toolErr = resp.toolError()
                    if (toolErr != null) error(toolErr)
                }
                .onFailure { context.emitError("Couldn't rename session: ${it.message ?: "?"}") }
        }
    }

    private suspend fun attemptOpenSolution(
        active: RemoteClient,
        solutionId: String,
    ): Result<Unit> {
        val params = buildJsonObject { put("solution_id", solutionId) }
        return runCatching {
            val resp = active.call("remote.solutions.open", params)
            val err = resp.error
            if (err != null) error(err.message)
            val toolErr = resp.toolError()
            if (toolErr != null) error(toolErr)
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
