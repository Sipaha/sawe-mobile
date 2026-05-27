package ru.sipaha.spkremote.app.vm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ru.sipaha.spkremote.app.data.ListCacheRepository
import ru.sipaha.spkremote.app.data.SessionHistoryRepository
import ru.sipaha.spkremote.core.AgentSummary
import ru.sipaha.spkremote.core.CreateSessionResult
import ru.sipaha.spkremote.core.GetSessionChildrenResult
import ru.sipaha.spkremote.core.JsonRpc
import ru.sipaha.spkremote.core.ListAgentsResult
import ru.sipaha.spkremote.core.ListSessionsResult
import ru.sipaha.spkremote.core.MemberAddCompletedPayload
import ru.sipaha.spkremote.core.MemberAddProgressPayload
import ru.sipaha.spkremote.core.MessageAppendedPayload
import ru.sipaha.spkremote.core.RemoteClient
import ru.sipaha.spkremote.core.SessionActiveSubagentsChangedPayload
import ru.sipaha.spkremote.core.SessionCreatedPayload
import ru.sipaha.spkremote.core.SessionQueueChangedPayload
import ru.sipaha.spkremote.core.SessionSummary
import ru.sipaha.spkremote.core.UploadChunkAckedPayload
import ru.sipaha.spkremote.core.WorkspaceSessionClosedPayload
import ru.sipaha.spkremote.core.WorkspaceSessionDeletedPayload
import ru.sipaha.spkremote.core.WorkspaceSessionMetricsChangedPayload
import ru.sipaha.spkremote.core.WorkspaceSessionOpenedPayload
import ru.sipaha.spkremote.core.WorkspaceSessionStateChangedPayload
import ru.sipaha.spkremote.core.WorkspaceSolutionClosedPayload
import ru.sipaha.spkremote.core.WorkspaceSolutionDeletedPayload
import ru.sipaha.spkremote.core.WorkspaceSolutionOpenedPayload

/**
 * Sessions-list + agents + create-session + sub-agent children + the
 * single shared notifications collector. The "list seam" half of the
 * previous god-object `SessionStore`.
 *
 * Responsibilities:
 *   - per-solution sessions list with single-flight refresh + cache hydration,
 *   - agents catalogue + auto-open-after-create flag,
 *   - create / rename / close-by-id orchestration,
 *   - sub-agent children map,
 *   - the single notifications-observer that fans out to both this store
 *     and [SessionDetailStore] (the detail side is consulted via a small
 *     callback set by the coordinator on construction).
 *
 * ### Invariants
 *
 *  1. **One notifications observer per active client** — started lazily
 *     when either [startObservingSessions] or [SessionDetailStore.openSession]
 *     needs it, torn down in [reset] on server switch.
 *  2. **`refreshSessionsJob` is single-flight** — see [singleFlightRefresh]
 *     for the semantics. A new refresh cancels the previous in-flight one.
 *  3. **The create-session in-flight flag is server-scoped** — it MUST
 *     be cleared in [reset] otherwise a server switch mid-create leaves
 *     the "Create" button permanently disabled on the new server.
 */
internal class SessionListStore(
    private val scope: CoroutineScope,
    private val context: ConnectionContext,
    private val listCacheRepository: ListCacheRepository,
    private val lastSeen: LastSeenIndex,
    private val sessionHistoryRepository: SessionHistoryRepository,
) {
    private val _sessions = MutableStateFlow<UiData<List<SessionSummary>>>(UiData.Loading)
    val sessions: StateFlow<UiData<List<SessionSummary>>> = _sessions.asStateFlow()

    private val _agents = MutableStateFlow<UiData<List<AgentSummary>>>(UiData.Loading)
    val agents: StateFlow<UiData<List<AgentSummary>>> = _agents.asStateFlow()

    private val _createSessionInFlight = MutableStateFlow(false)
    val createSessionInFlight: StateFlow<Boolean> = _createSessionInFlight.asStateFlow()

    private val _lastCreateAutoOpened = MutableStateFlow(false)
    val lastCreateAutoOpened: StateFlow<Boolean> = _lastCreateAutoOpened.asStateFlow()

    private val _sessionChildren = MutableStateFlow<Map<String, List<SessionSummary>>>(emptyMap())
    val sessionChildren: StateFlow<Map<String, List<SessionSummary>>> = _sessionChildren.asStateFlow()

    /**
     * Detail-side notification routing — wired by the coordinator after
     * both stores are constructed. The list store owns the collector
     * (so we don't double-subscribe) and just delegates detail-shaped
     * events through this callback.
     */
    internal var detailNotificationRouter: DetailNotificationRouter? = null

    /**
     * Upload-ack notification routing — wired by the coordinator after
     * [UploadManager] is constructed. The single collector receives
     * `upload_chunk_acked` notifications and forwards them via this
     * callback so the per-upload state-machine coroutine can advance
     * its offset. Same single-collector discipline as the detail
     * router above to avoid double subscriptions.
     */
    internal var uploadNotificationRouter: ((UploadChunkAckedPayload) -> Unit)? = null

    /**
     * Solution member-add / change notification routing — wired by the
     * coordinator to [CatalogStore]. Same single-collector discipline as
     * the detail + upload routers above: the list store owns the one
     * subscription, [CatalogStore] just consumes the typed payloads.
     */
    internal var solutionNotificationRouter: SolutionNotificationRouter? = null

    /**
     * Workspace (`workspace.*`) notification routing — wired by the
     * coordinator to [WorkspaceStore]. Same single-collector discipline
     * as the detail / upload / solution routers above: this store owns
     * the subscription, [WorkspaceStore] consumes the typed payloads.
     */
    internal var workspaceNotificationRouter: WorkspaceNotificationRouter? = null

    private var listSubscribed = false
    private var detailSubscribed = false
    private var notificationsObserverJob: Job? = null

    /**
     * The currently-observed solution id for the list path. Null when
     * no list observer is active. The single notification collector
     * reads this to decide whether to refresh the sessions list when
     * an `agent_session_*` event arrives.
     */
    @Volatile
    private var observingSolutionId: String? = null

    /**
     * Read-only accessor for the coordinator's foreground-refresh hook.
     * Returns the solution whose sessions list is currently being
     * observed (i.e. a session-list surface is mounted), or null when
     * no list surface is visible.
     */
    fun currentObservingSolutionId(): String? = observingSolutionId

    private var refreshSessionsJob: Job? = null

    /** Tear-down hook called from coordinator on server switch / disconnect. */
    fun reset() {
        notificationsObserverJob?.cancel()
        notificationsObserverJob = null
        observingSolutionId = null
        refreshSessionsJob?.cancel()
        refreshSessionsJob = null
        listSubscribed = false
        detailSubscribed = false
        _sessions.value = UiData.Loading
        _agents.value = UiData.Loading
        _sessionChildren.value = emptyMap()
        // The create-session in-flight flag is keyed to the *previous*
        // server's client. If a create was racing the server switch its
        // continuation no-ops on the new server, so we must clear the
        // flag here — otherwise the Create button stays permanently
        // disabled on the new server.
        _createSessionInFlight.value = false
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
            onSuccess = { sessions ->
                listCacheRepository.saveSessions(solutionId, sessions)
                // GC sweep — drop history-cache entries for sessions that
                // no longer exist on the server, scoped to this solution
                // so other solutions on the same server aren't touched.
                sessionHistoryRepository.prune(
                    keepSessionIds = sessions.map { it.id }.toHashSet(),
                    scopeSolutionId = solutionId,
                )
            },
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

    /**
     * Public entry point for the detail store to make sure the
     * notifications collector is running. Idempotent.
     */
    fun ensureNotificationsObserver() {
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
                            "agent_session_queue_changed",
                            // Task/Agent subagent set changed mid-turn —
                            // routes to SessionDetailStore.onActiveSubagentsChanged
                            // which moves the tab strip; without this the
                            // strip only refreshes via cold-start seed.
                            "agent_session_active_subagents_changed",
                            // Chunked-upload acks share this collector — server
                            // forwards them through the same notification path
                            // (allow_list pattern `upload_*`). Subscribing
                            // here keeps a single notification observer per
                            // active client; without this UploadManager would
                            // need its own subscribe + collector duplicating
                            // the lifecycle handling.
                            "upload_chunk_acked",
                            // Solution member-add progress + completion +
                            // generic solution change — drive the project-
                            // registry ghost rows and the member-list
                            // refresh. Routed to CatalogStore via
                            // [solutionNotificationRouter].
                            "solution_member_add_progress",
                            "solution_member_add_completed",
                            "solution_changed",
                            // Workspace (open-set) notifications — routed
                            // to WorkspaceStore via [workspaceNotificationRouter].
                            // Carry sequenced deltas for the desktop's open
                            // workspace mirror; gap detection is internal
                            // to the store.
                            "workspace.solution_opened",
                            "workspace.solution_closed",
                            "workspace.solution_deleted",
                            "workspace.session_opened",
                            "workspace.session_closed",
                            "workspace.session_deleted",
                            "workspace.session_state_changed",
                            "workspace.session_metrics_changed",
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
                // The server (`editor_mcp::notifications::emit`) wraps each
                // notification as `params: { kind, payload }`. An earlier
                // mobile revision read `params["data"]` — that key never
                // exists on the wire, so EVERY notification arrived with
                // `data = null`. Most handlers had a "data missing →
                // refresh-all" fallback, which masked the bug for text
                // chat (`agent_session_message_appended` triggered a
                // full re-fetch and the new entry appeared via that
                // path). `upload_chunk_acked` has no fallback (its only
                // job is to forward the ack offset into the per-upload
                // channel), so the silent null sent every chunk-ack
                // straight to /dev/null and the upload coroutine timed
                // out at 30 s → Paused at 0 B. Diagnosed 2026-05-19
                // via server-side `binary frame written: offset=0` +
                // mobile `Paused at 0 B / 3.0 MB`.
                val payload = params["payload"] as? JsonObject
                handleNotification(kind, payload)
            }
        }
    }

    private fun handleNotification(kind: String, data: JsonObject?) {
        // Upload-ack — bypass the list/detail branches entirely; this
        // is the chunked-upload notification path (see
        // [uploadNotificationRouter] kdoc).
        if (kind == "upload_chunk_acked") {
            val router = uploadNotificationRouter ?: return
            val payload = data?.let {
                runCatching {
                    JsonRpc.json.decodeFromJsonElement(
                        UploadChunkAckedPayload.serializer(),
                        it,
                    )
                }.getOrNull()
            } ?: return
            router(payload)
            return
        }

        // Solution member-add / change events — fan out to CatalogStore
        // for the project-registry ghost rows + member-list refresh.
        // Independent of the session list/detail routing below.
        when (kind) {
            "solution_member_add_progress" -> {
                val payload = data?.let {
                    runCatching {
                        JsonRpc.json.decodeFromJsonElement(
                            MemberAddProgressPayload.serializer(),
                            it,
                        )
                    }.getOrNull()
                } ?: return
                solutionNotificationRouter?.onMemberAddProgress(payload)
                return
            }
            "solution_member_add_completed" -> {
                val payload = data?.let {
                    runCatching {
                        JsonRpc.json.decodeFromJsonElement(
                            MemberAddCompletedPayload.serializer(),
                            it,
                        )
                    }.getOrNull()
                } ?: return
                solutionNotificationRouter?.onMemberAddCompleted(payload)
                return
            }
            "solution_changed" -> {
                solutionNotificationRouter?.onSolutionChanged()
                return
            }
        }

        // Workspace open-set notifications — fan out the typed payloads
        // to WorkspaceStore. Decoding failures (e.g. an unexpected wire
        // shape from a future server) silently drop the delta; the store
        // will detect the gap on the next sequenced event and trigger a
        // bulk resync.
        when (kind) {
            "workspace.solution_opened" -> {
                val router = workspaceNotificationRouter ?: return
                val payload = data?.decodeOrNull(WorkspaceSolutionOpenedPayload.serializer()) ?: return
                router.onSolutionOpened(payload)
                return
            }
            "workspace.solution_closed" -> {
                val router = workspaceNotificationRouter ?: return
                val payload = data?.decodeOrNull(WorkspaceSolutionClosedPayload.serializer()) ?: return
                router.onSolutionClosed(payload)
                return
            }
            "workspace.solution_deleted" -> {
                val router = workspaceNotificationRouter ?: return
                val payload = data?.decodeOrNull(WorkspaceSolutionDeletedPayload.serializer()) ?: return
                router.onSolutionDeleted(payload)
                return
            }
            "workspace.session_opened" -> {
                val router = workspaceNotificationRouter ?: return
                val payload = data?.decodeOrNull(WorkspaceSessionOpenedPayload.serializer()) ?: return
                router.onSessionOpened(payload)
                return
            }
            "workspace.session_closed" -> {
                val router = workspaceNotificationRouter ?: return
                val payload = data?.decodeOrNull(WorkspaceSessionClosedPayload.serializer()) ?: return
                router.onSessionClosed(payload)
                return
            }
            "workspace.session_deleted" -> {
                val router = workspaceNotificationRouter ?: return
                val payload = data?.decodeOrNull(WorkspaceSessionDeletedPayload.serializer()) ?: return
                router.onSessionDeleted(payload)
                return
            }
            "workspace.session_state_changed" -> {
                val router = workspaceNotificationRouter ?: return
                val payload = data?.decodeOrNull(WorkspaceSessionStateChangedPayload.serializer()) ?: return
                router.onSessionStateChanged(payload)
                return
            }
            "workspace.session_metrics_changed" -> {
                val router = workspaceNotificationRouter ?: return
                val payload = data?.decodeOrNull(WorkspaceSessionMetricsChangedPayload.serializer()) ?: return
                router.onSessionMetricsChanged(payload)
                return
            }
        }

        // List handler — refresh sessions list when a session-shaped
        // event arrives, regardless of whether a detail screen is also
        // mounted.
        //
        // Fallback when no solution-list screen is observing: derive the
        // solution id from the currently-loaded sessions cache. This is
        // the SessionDetailScreen-mounted case where the previously-mounted
        // list surface already disposed and reset `observingSolutionId = null` —
        // without the fallback, an `agent_session_state_changed`
        // notification fired by a /compact (rotate_context sets
        // `cached_total_tokens = None` then emits SessionStateChanged)
        // wouldn't re-refresh the list, so the chat header's
        // ContextFillMeter would keep rendering the pre-compact
        // percentage until the user backed out and pulled the list
        // manually.
        val cachedSolutionId = (_sessions.value as? UiData.Loaded)
            ?.value
            ?.firstOrNull()
            ?.solutionId
        val solutionId = observingSolutionId ?: cachedSolutionId
        when (kind) {
            "agent_session_state_changed",
            "agent_session_created",
            "agent_session_closed",
            "agent_session_title_changed" -> if (solutionId != null) refreshSessions(solutionId)
        }

        // Detail handler — fan out to the detail store. The router
        // is responsible for filtering by [SessionDetailStore.openSessionId].
        val router = detailNotificationRouter ?: return
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
                router.onChildSessionCreated(parent)
            }
            "agent_session_message_appended" -> {
                if (data == null) {
                    router.onMessageAppendedFallback()
                    return
                }
                val payload = runCatching {
                    JsonRpc.json.decodeFromJsonElement(
                        MessageAppendedPayload.serializer(),
                        data,
                    )
                }.getOrNull()
                if (payload == null) {
                    router.onMessageAppendedFallback()
                    return
                }
                router.onMessageAppended(payload)
            }
            "agent_session_state_changed",
            "agent_session_title_changed" -> {
                val notifSessionId = data?.get("session_id")?.jsonPrimitive?.content
                router.onSessionStateOrTitleChanged(notifSessionId)
            }
            "agent_session_queue_changed" -> {
                val payload = data?.let {
                    runCatching {
                        JsonRpc.json.decodeFromJsonElement(
                            SessionQueueChangedPayload.serializer(),
                            it,
                        )
                    }.getOrNull()
                } ?: return
                router.onSessionQueueChanged(payload)
            }
            "agent_session_active_subagents_changed" -> {
                val payload = data?.let {
                    runCatching {
                        JsonRpc.json.decodeFromJsonElement(
                            SessionActiveSubagentsChangedPayload.serializer(),
                            it,
                        )
                    }.getOrNull()
                } ?: return
                router.onActiveSubagentsChanged(payload)
            }
        }
    }

    /**
     * Fetch the sub-agent children of [sessionId] and merge into
     * [_sessionChildren]. Public so the detail store can request it on
     * [SessionDetailStore.openSession] / on parent-creation events.
     */
    fun loadChildren(sessionId: String) {
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

    /**
     * Delete the session [sessionId] on the server (DESTRUCTIVE — wipes
     * the transcript). On success, optimistically remove the session from
     * the in-memory list (so the row vanishes immediately even before the
     * `workspace.session_deleted` notification round-trips back) and
     * trigger a refresh against the currently-observed solution (if any)
     * so cached state stays consistent with the server. Failures surface
     * through the shared error channel.
     *
     * Wire-schema v2 renamed `solution_agent.close_session` →
     * `solution_agent.delete_session` to match the actual semantics; the
     * non-destructive "remove the tab" action is now
     * `workspace.close_session`, wired separately through
     * [WorkspaceStore.closeSessionOptimistic].
     */
    fun deleteSession(sessionId: String) {
        val active = context.activeClient()
        if (active == null) {
            context.emitError(context.notConnectedMessage())
            return
        }
        val params = buildJsonObject { put("session_id", sessionId) }
        scope.launch {
            runCatching { active.call("remote.solution_agent.delete_session", params) }
                .mapCatching { resp ->
                    val err = resp.error
                    if (err != null) error(err.message)
                    val toolErr = resp.toolError()
                    if (toolErr != null) error(toolErr)
                }
                .onSuccess {
                    val current = _sessions.value
                    if (current is UiData.Loaded) {
                        val filtered = current.value.filterNot { it.id == sessionId }
                        if (filtered.size != current.value.size) {
                            _sessions.value = UiData.Loaded(filtered)
                        }
                    }
                    sessionHistoryRepository.evict(sessionId)
                    val solutionId = observingSolutionId
                    if (solutionId != null) refreshSessions(solutionId)
                }
                .onFailure { context.emitError("Couldn't delete session: ${it.message ?: "?"}") }
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
}

/**
 * Callback surface that [SessionListStore] uses to forward detail-shaped
 * notifications to [SessionDetailStore]. Each method is invoked from
 * the single consolidated collector coroutine inside the list store —
 * implementations should treat them as ordinary suspend-friendly
 * dispatch points and offload work to their own scope.
 */
internal interface DetailNotificationRouter {
    /** Called when a child session was created somewhere; the detail store decides if [parentSessionId] matches its open session. */
    fun onChildSessionCreated(parentSessionId: String)

    /** Typed payload for a message-appended event. */
    fun onMessageAppended(payload: MessageAppendedPayload)

    /** Fallback when the message-appended notification couldn't be decoded — full refetch. */
    fun onMessageAppendedFallback()

    /** A session-state or title-change notification. [notifSessionId] is null when the payload didn't carry one. */
    fun onSessionStateOrTitleChanged(notifSessionId: String?)

    /**
     * The server-side `pending_messages` queue mutated. Carries every
     * bundle currently waiting for the agent turn to finish — drives
     * the cross-client Queued bubble surface in the detail screen.
     * `bundles: []` is the canonical "queue drained" payload.
     */
    fun onSessionQueueChanged(payload: SessionQueueChangedPayload)

    /**
     * Active subagents (Task/Agent in-flight tabs) changed for
     * [payload.sessionId]. The payload always carries the FULL new set
     * (empty = no subagents in flight); insertion order matches the
     * server-side `active_subagent_order` Vec — render as-is.
     */
    fun onActiveSubagentsChanged(payload: SessionActiveSubagentsChangedPayload)
}

/**
 * Compact decode helper for the workspace notification dispatch — mirrors
 * the `runCatching { JsonRpc.json.decodeFromJsonElement(...) }.getOrNull()`
 * idiom used inline elsewhere in this file. Centralised here purely to
 * cut down the per-kind boilerplate when there are 8 kinds to wire.
 */
private fun <T> JsonObject.decodeOrNull(
    deserializer: kotlinx.serialization.DeserializationStrategy<T>,
): T? = runCatching {
    JsonRpc.json.decodeFromJsonElement(deserializer, this)
}.getOrNull()
