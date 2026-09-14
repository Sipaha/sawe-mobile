package ru.sipaha.sawe.app.vm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ru.sipaha.sawe.app.data.ListCacheRepository
import ru.sipaha.sawe.app.data.SessionHistoryRepository
import ru.sipaha.sawe.core.AgentSummary
import ru.sipaha.sawe.core.CreateSessionResult
import ru.sipaha.sawe.core.GetSessionChildrenResult
import ru.sipaha.sawe.core.JsonRpc
import ru.sipaha.sawe.core.ListAgentsResult
import ru.sipaha.sawe.core.ListSessionsResult
import ru.sipaha.sawe.core.MemberAddCompletedPayload
import ru.sipaha.sawe.core.MemberAddProgressPayload
import ru.sipaha.sawe.core.MessageAppendedPayload
import ru.sipaha.sawe.core.RemoteClient
import ru.sipaha.sawe.core.ServerFeatures
import ru.sipaha.sawe.core.WireFeature
import ru.sipaha.sawe.core.AgentSessionContextResetPayload
import ru.sipaha.sawe.core.SessionActiveSubagentsChangedPayload
import ru.sipaha.sawe.core.SessionCreatedPayload
import ru.sipaha.sawe.core.SessionDirtyPayload
import ru.sipaha.sawe.core.SessionQueueChangedPayload
import ru.sipaha.sawe.core.SessionSummary
import ru.sipaha.sawe.core.UploadChunkAckedPayload
import ru.sipaha.sawe.core.WorkspaceSessionClosedPayload
import ru.sipaha.sawe.core.WorkspaceSessionDeletedPayload
import ru.sipaha.sawe.core.WorkspaceSessionMetricsChangedPayload
import ru.sipaha.sawe.core.WorkspaceSessionOpenedPayload
import ru.sipaha.sawe.core.WorkspaceSessionStateChangedPayload
import ru.sipaha.sawe.core.WorkspaceSolutionClosedPayload
import ru.sipaha.sawe.core.WorkspaceSolutionDeletedPayload
import ru.sipaha.sawe.core.WorkspaceSolutionOpenedPayload

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
 *     for the semantics. A user-initiated refresh cancels the previous
 *     in-flight one; a NOTIFICATION-driven one goes through
 *     [refreshSessionsDebounced], which coalesces the burst and waits for an
 *     in-flight refresh rather than cancelling a query the server has already
 *     executed.
 *  3. **The create-session in-flight flag is server-scoped** — it MUST
 *     be cleared in [reset] otherwise a server switch mid-create leaves
 *     the "Create" button permanently disabled on the new server.
 */
/**
 * The kind this client would rather the proxy stopped forwarding.
 *
 * `agent_session_message_appended` and `agent_session_dirty` are emitted for
 * the same server-side event, and the mobile handler for the former
 * (`SessionDetailStore.onMessageAppended`) does byte-for-byte what
 * `onSessionDirty` does: schedule a delta poll. Nothing reads the payload.
 * `dirty` is additionally coalesced per session inside the proxy, so
 * dropping the append twin loses no signal and removes one uncoalesced frame
 * per appended message.
 *
 * The kind stays SUBSCRIBED (it is still in [SUBSCRIPTION_KINDS]); only
 * forwarding is suppressed, so a server that ignores the request — or the
 * window before the capabilities probe answers — simply delivers both, as
 * it always did.
 */
private val SUPPRESSIBLE_KINDS: List<String> = listOf("agent_session_message_appended")

/**
 * What to ask the proxy to stop forwarding on a connection that advertised
 * [features] — empty unless [WireFeature.QUIET_MESSAGE_APPENDED] is on.
 *
 * `RemoteClient.subscribe` drops the parameter on its own when the token is
 * absent; deciding it here as well keeps the intent visible at the call
 * site and makes the rule testable without a socket.
 */
internal fun suppressKindsFor(features: ServerFeatures): List<String> =
    if (features.has(WireFeature.QUIET_MESSAGE_APPENDED)) SUPPRESSIBLE_KINDS else emptyList()

internal class SessionListStore(
    private val scope: CoroutineScope,
    private val context: ConnectionContext,
    private val listCacheRepository: ListCacheRepository,
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
     * Chunk-REJECTION routing — the negative counterpart of
     * [uploadNotificationRouter], wired by the coordinator to
     * [UploadManager]. The server emits `upload_chunk_rejected` when it
     * refuses a chunk and, unless the upload id is unknown, names the offset
     * the next chunk must carry; forwarding it lets the upload re-seek
     * immediately instead of sitting out its 30 s ack timeout first.
     */
    internal var uploadRejectionRouter: ((UploadChunkRejectedPayload) -> Unit)? = null

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

    private var notificationsObserverJob: Job? = null

    /**
     * The currently-observed solution id for the list path. Null when
     * no list observer is active. The single notification collector
     * reads this to decide whether to refresh the sessions list when
     * an `agent_session_*` event arrives.
     */
    @Volatile
    private var observingSolutionId: Long? = null

    /**
     * Read-only accessor for the coordinator's foreground-refresh hook.
     * Returns the solution whose sessions list is currently being
     * observed (i.e. a session-list surface is mounted), or null when
     * no list surface is visible.
     */
    fun currentObservingSolutionId(): Long? = observingSolutionId

    private var refreshSessionsJob: Job? = null

    /**
     * Trailing-edge debounce timer for notification-driven list refreshes.
     * A busy solution fans `agent_session_state_changed` out per session on
     * every agent transition (and the supervisor adds more), so the raw
     * signal is dozens of `list_sessions` a minute — each one a full
     * round-trip on a serial connection, interleaved with the open chat's
     * transcript polls. User-initiated refreshes never go through this.
     */
    private var refreshDebounceJob: Job? = null

    /** Tear-down hook called from coordinator on server switch / disconnect. */
    fun reset() {
        notificationsObserverJob?.cancel()
        notificationsObserverJob = null
        observingSolutionId = null
        refreshSessionsJob?.cancel()
        refreshSessionsJob = null
        refreshDebounceJob?.cancel()
        refreshDebounceJob = null
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

    fun refreshSessions(solutionId: Long) {
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

    /**
     * Notification-driven variant of [refreshSessions].
     *
     * Two differences from the direct call, both aimed at the "reading one
     * chat on LTE costs dozens of `list_sessions` a minute" path:
     *  - a trailing-edge [SESSIONS_REFRESH_DEBOUNCE_MS] window collapses the
     *    burst of per-session `state_changed` events one agent transition
     *    fans out into a single request;
     *  - it WAITS for an in-flight refresh instead of cancelling it. The
     *    cancel was client-local — the server still executed the query and
     *    still wrote the response ahead of the replacement in its serial
     *    loop, so cancelling bought nothing and threw away a result that had
     *    already been paid for.
     */
    private fun refreshSessionsDebounced(solutionId: Long) {
        refreshDebounceJob?.cancel()
        refreshDebounceJob = scope.launch {
            delay(SESSIONS_REFRESH_DEBOUNCE_MS)
            refreshSessionsJob?.join()
            refreshSessions(solutionId)
        }
    }

    /**
     * True when [notifSessionId] is worth a list refresh: either it names a
     * session the held list already shows (its row needs updating) or the
     * notification didn't carry an id at all, in which case we can't tell and
     * refresh to be safe. Also true while the list hasn't loaded yet.
     */
    private fun concernsLoadedSession(notifSessionId: String?): Boolean =
        shouldRefreshForSessionEvent(
            notifSessionId = notifSessionId,
            loadedSessionIds = (_sessions.value as? UiData.Loaded)?.value?.map { it.id },
        )

    fun startObservingSessions(solutionId: Long) {
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
     * Force a fresh `subscribe(...)` + observer relaunch on the active
     * client, blocking until the `subscribe` RPC has settled. Critical on
     * the [ConnectionLifecycle.onReconnected] path — the server's
     * subscription set is per-WS-connection, so a transient drop +
     * reconnect on the same [RemoteClient] silently loses every kind we
     * had subscribed to, and the existing observer's collect loop (still
     * alive on the in-memory notifications [Flow]) wouldn't naturally
     * re-send the subscribe.
     *
     * Suspending until subscribe lands is what callers that race a
     * `workspace.snapshot` RPC against this restart need: the snapshot
     * must be minted while the new subscription set is already
     * registered server-side, otherwise a delta firing between snapshot-
     * mint and subscribe-completion is silently dropped (the gap-detector
     * self-heals on the next delta, but until then the mirror shows
     * stale data).
     */
    suspend fun restartNotificationsObserverAndAwait() {
        notificationsObserverJob?.cancel()
        notificationsObserverJob = null
        val active = context.activeClient() ?: return
        // Synchronously dispatch the subscribe first. Result is best-effort:
        // a transient failure here means the collector still launches and
        // the next reconnect cycle will retry — better than leaving the
        // wire un-observed. A CANCELLATION is not such a failure: it means
        // the caller is being torn down, and swallowing it would launch a
        // collector on a scope that is already dying.
        //
        // This runs on the Connected edge, BEFORE the capabilities probe has
        // answered, so `serverFeatures` is still empty and the suppression
        // list resolves to nothing. That is deliberate — replaying the
        // subscription set is what keeps pokes from being lost, and it must
        // not wait on a negotiation round trip. [applyNegotiatedFeatures]
        // re-issues the subscribe once the answer lands.
        runCatching { active.subscribe(SUBSCRIPTION_KINDS, suppressKindsFor(active.serverFeatures.value)) }
            .onFailure { if (it is CancellationException) throw it }
        notificationsObserverJob = scope.launch { runNotificationsCollector(active) }
    }

    /**
     * Re-send the subscription set now that the capabilities probe has
     * answered ([ConnectionLifecycle.onFeaturesNegotiated]).
     *
     * `suppress_kinds` is a per-connection instruction to the proxy and can
     * only be sent once the peer has advertised
     * [WireFeature.QUIET_MESSAGE_APPENDED]; the reconnect replay above runs
     * before negotiation by design, so without this second call the
     * suppression would never take effect on any connection. `subscribe` is
     * idempotent server-side and the kinds list is unchanged, so the re-send
     * costs one small frame and changes nothing when the token is absent.
     *
     * Deliberately does NOT restart the collector: it is already running on
     * the same client and the notification flow is process-local.
     */
    fun applyNegotiatedFeatures() {
        val active = context.activeClient() ?: return
        val suppress = suppressKindsFor(active.serverFeatures.value)
        if (suppress.isEmpty()) return
        scope.launch {
            runCatching { active.subscribe(SUBSCRIPTION_KINDS, suppress) }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    /**
     * Public entry point for the detail store to make sure the
     * notifications collector is running. Idempotent.
     */
    fun ensureNotificationsObserver() {
        val active = context.activeClient() ?: return
        if (notificationsObserverJob?.isActive == true) return
        notificationsObserverJob = scope.launch {
            // Always (re-)send the subscribe set when this job starts. The
            // server-side subscription list is per-WS-connection, so a
            // transient drop + reconnect on the SAME RemoteClient leaves the
            // new socket with no subscriptions — without this re-send, the
            // workspace.* / agent_session_* deltas never make it back to us.
            // (subscribe is documented idempotent server-side, so a duplicate
            // when the observer restarts for any other reason is harmless.)
            runCatching { active.subscribe(SUBSCRIPTION_KINDS, suppressKindsFor(active.serverFeatures.value)) }
                .onFailure { if (it is CancellationException) throw it }
            runNotificationsCollector(active)
        }
    }

    private suspend fun runNotificationsCollector(active: RemoteClient) {
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

    private companion object {
        /**
         * Trailing-edge quiet window for notification-driven list refreshes.
         * Long enough to swallow the per-session fan-out of one agent
         * transition, short enough that a session appearing / changing state
         * still shows up while the user is looking at the list.
         */
        private const val SESSIONS_REFRESH_DEBOUNCE_MS: Long = 1_500L

        /**
         * Notification kinds re-subscribed on every reconnect (server-side
         * subscription set is per-WS-connection, so we must replay
         * everything we want to hear after each new socket).
         */
        private val SUBSCRIPTION_KINDS: List<String> = listOf(
            "agent_session_state_changed",
            "agent_session_created",
            "agent_session_closed",
            "agent_session_title_changed",
            // Kept subscribed unconditionally, but asked to be suppressed on
            // the forwarding side when the peer supports it — see
            // [SUPPRESSIBLE_KINDS]. Never remove it from this list: the
            // suppression is a per-connection courtesy the server may ignore,
            // and against one that does ignore it this is still the only
            // append signal an older desktop sends.
            "agent_session_message_appended",
            "agent_session_queue_changed",
            // Content-free "transcript advanced — re-poll" signal. The detail
            // store polls get_session_changes from its cursor until the server
            // reports the selected stream caught up, so a single delivered
            // dirty heals a view left short by lost per-entry append pokes (the
            // "interrupted reply stays interrupted" bug).
            "agent_session_dirty",
            // Task/Agent subagent set changed mid-turn — routes to
            // SessionDetailStore.onActiveSubagentsChanged which moves
            // the tab strip; without this the strip only refreshes
            // via cold-start seed.
            "agent_session_active_subagents_changed",
            // Server wiped this session's transcript in-place (`/clear`
            // reset_context or `/compact` rotate_context). Without this
            // kind, the chat surface keeps showing stale entries until
            // a foreground / cold refresh.
            "agent_session_context_reset",
            // Chunked-upload acks share this collector — server
            // forwards them through the same notification path
            // (allow_list pattern `upload_*`). Subscribing here keeps a
            // single notification observer per active client; without
            // this UploadManager would need its own subscribe +
            // collector duplicating the lifecycle handling.
            "upload_chunk_acked",
            // Negative counterpart of the ack: the server refused a chunk
            // (out of order, overrun, or an upload id it no longer knows)
            // and names the offset the next chunk must carry. Without it the
            // client only learns something is wrong when its 30 s ack timeout
            // fires, and then has to guess where to resume. Additive on the
            // wire — an older server simply never sends it.
            "upload_chunk_rejected",
            // Solution member-add progress + completion + generic
            // solution change — drive the project-registry ghost rows
            // and the member-list refresh. Routed to CatalogStore via
            // [solutionNotificationRouter].
            "solution_member_add_progress",
            "solution_member_add_completed",
            "solution_changed",
            // Workspace (open-set) notifications — routed to
            // WorkspaceStore via [workspaceNotificationRouter]. Carry
            // sequenced deltas for the desktop's open workspace mirror;
            // gap detection is internal to the store.
            "workspace.solution_opened",
            "workspace.solution_closed",
            "workspace.solution_deleted",
            "workspace.session_opened",
            "workspace.session_closed",
            "workspace.session_deleted",
            "workspace.session_state_changed",
            "workspace.session_metrics_changed",
        )
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
        if (kind == "upload_chunk_rejected") {
            val router = uploadRejectionRouter ?: return
            val payload = data?.decodeOrNull(UploadChunkRejectedPayload.serializer()) ?: return
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
            // A session appearing or disappearing cannot be resolved from the
            // held list — always re-fetch.
            "agent_session_created",
            "agent_session_closed" -> if (solutionId != null) refreshSessionsDebounced(solutionId)
            // A state / title change for a session we are not showing tells us
            // nothing: the desktop fans these out for EVERY session of an
            // agent (a model-list probe alone touches all of them), and each
            // one used to cost a full `list_sessions` round-trip on the same
            // serial connection the open chat is polling.
            "agent_session_state_changed",
            "agent_session_title_changed" -> {
                val notifSessionId = data?.get("session_id")?.jsonPrimitive?.content
                if (solutionId != null && concernsLoadedSession(notifSessionId)) {
                    refreshSessionsDebounced(solutionId)
                }
            }
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
            "agent_session_dirty" -> {
                val payload = data?.let {
                    runCatching {
                        JsonRpc.json.decodeFromJsonElement(
                            SessionDirtyPayload.serializer(),
                            it,
                        )
                    }.getOrNull()
                } ?: return
                router.onSessionDirty(payload.sessionId)
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
            "agent_session_context_reset" -> {
                val payload = data?.decodeOrNull(AgentSessionContextResetPayload.serializer()) ?: return
                router.onSessionContextReset(payload)
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
        solutionId: Long,
        agentId: String,
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
            val firstAttempt = attemptCreateSession(active, solutionId, agentId)
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
                        val retry = attemptCreateSession(active, solutionId, agentId)
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
        solutionId: Long,
        agentId: String,
    ): Result<String> {
        val params = buildJsonObject {
            put("solution_id", solutionId)
            put("agent_id", agentId)
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
        solutionId: Long,
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
 * Whether a per-session `state_changed` / `title_changed` notification is worth
 * a `list_sessions` refresh.
 *
 * The desktop fans these out for EVERY session of an agent (a model-list probe
 * alone touches all of them, and the supervisor adds more), and each one used
 * to cost a full round-trip on the same serial connection the open chat polls.
 * A change to a session the held list doesn't show cannot alter a single row.
 *
 * Refreshes when the list hasn't loaded yet ([loadedSessionIds] is null) or the
 * notification carried no id — neither is evidence that the event is
 * irrelevant, and a missed refresh is worse than a spare one.
 */
internal fun shouldRefreshForSessionEvent(
    notifSessionId: String?,
    loadedSessionIds: List<String>?,
): Boolean {
    if (notifSessionId == null || loadedSessionIds == null) return true
    return notifSessionId in loadedSessionIds
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

    /**
     * A session-state or title-change notification. [notifSessionId] is null
     * when the payload didn't carry one.
     *
     * Known wire gap: `GetSessionChangesResult` carries no `title`, so the
     * delta poll this triggers can never bring a renamed session's new title
     * to the detail screen — that only refreshes on a full `get_session`. The
     * poll is still worth firing (a title change rides the same `change_seq`
     * bump as anything else that may have happened), but do not read it as
     * "the title will be up to date afterwards".
     */
    fun onSessionStateOrTitleChanged(notifSessionId: String?)

    /**
     * Content-free "transcript advanced — re-poll" signal. The detail store
     * polls `get_session_changes` from its held cursor until the server
     * reports the selected stream caught up (`has_more == false`), retrying
     * failed polls, so a single delivered dirty heals a view stranded by lost
     * per-entry append pokes.
     *
     * The payload's `current_seq` is deliberately NOT forwarded: it is the
     * session-GLOBAL `change_seq`, and the detail cursor is per-stream, so it
     * is not a target a caught-up client can generally reach.
     */
    fun onSessionDirty(sessionId: String)

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

    /**
     * The server wiped this session's transcript in-place via /clear or
     * /compact. The session id is unchanged. Implementations must drop
     * their cached entry list for [payload.sessionId] and re-fetch.
     */
    fun onSessionContextReset(payload: AgentSessionContextResetPayload)
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
