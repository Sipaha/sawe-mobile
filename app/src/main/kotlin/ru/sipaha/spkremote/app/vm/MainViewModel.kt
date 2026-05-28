package ru.sipaha.spkremote.app.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.sipaha.spkremote.app.data.AttachmentDraftRepository
import ru.sipaha.spkremote.app.data.DraftRepository
import ru.sipaha.spkremote.app.data.InFlightUploadsRepository
import ru.sipaha.spkremote.app.data.PendingSendsRepository
import ru.sipaha.spkremote.app.data.LastSeenRepository
import ru.sipaha.spkremote.app.data.ListCacheRepository
import ru.sipaha.spkremote.app.data.NavStateRepository
import ru.sipaha.spkremote.app.data.PairedServer
import ru.sipaha.spkremote.app.data.SessionHistoryRepository
import ru.sipaha.spkremote.core.AgentSummary
import ru.sipaha.spkremote.core.ConnectionState
import ru.sipaha.spkremote.core.ContentBlockDto
import ru.sipaha.spkremote.core.EntrySummary
import ru.sipaha.spkremote.core.GetSessionResult
import ru.sipaha.spkremote.core.GetSolutionResult
import ru.sipaha.spkremote.core.PairingUrl
import ru.sipaha.spkremote.core.QueuedMessage
import ru.sipaha.spkremote.core.RemoteClient
import ru.sipaha.spkremote.core.SessionSummary

sealed interface UiState {
    data class Disconnected(val lastUrl: String? = null, val error: String? = null) : UiState
    data object Connecting : UiState
    data class Connected(val protocolVersion: String) : UiState

    /**
     * The paired desktop is advertising a chat-wire schema this client
     * doesn't support yet (see [ru.sipaha.spkremote.core.isServerTooNew]).
     * We surface a terminal "update the app" screen instead of trying to
     * drive the UI off a wire it can't decode — silently rendering
     * mis-parsed sessions would be worse than refusing to operate. The
     * gate site (`ConnectionManager.switchToServerLocked`) also skips
     * `startObservingConnectionState` for this state so transient
     * reconnects don't briefly flash a stale Connected.
     */
    data class IncompatibleServer(
        val serverWireSchemaVersion: Int,
        val supportedWireSchemaVersion: Int,
        val message: String,
    ) : UiState
}

/**
 * Lightweight projection of [ConnectionState] for the navigation banner.
 *
 * Mirrors the underlying [ConnectionState] but drops the Connecting state
 * (no banner needed — the user is on the connecting spinner screen) and
 * only carries the fields the banner needs to render. Decoupling here
 * means the Compose layer doesn't need to import `:core` types directly.
 */
sealed interface ConnectionBanner {
    data object Hidden : ConnectionBanner

    /**
     * @param reason short user-facing summary of the failure that triggered
     *   the current Reconnecting cycle. Null only on the very first
     *   transition before any attempt has failed. Drives the banner's
     *   contextual text ("Reconnecting (host unreachable, attempt 3, next
     *   try in 4s)…") instead of a generic "Reconnecting…".
     */
    data class Reconnecting(
        val attempt: Int,
        val nextRetryMs: Long,
        val reason: String? = null,
    ) : ConnectionBanner

    data class FailedTerminal(val reason: String) : ConnectionBanner
}

/** Lightweight loadable wrapper for async-backed UI state. */
sealed interface UiData<out T> {
    data object Loading : UiData<Nothing>
    data class Loaded<T>(val value: T) : UiData<T>
    data class Error(val message: String) : UiData<Nothing>
}

/**
 * Thin coordinator around five collaborators that share [viewModelScope]:
 *
 *   - [ConnectionManager] — pairing CRUD + the active [RemoteClient] +
 *     wire-level connection state observation.
 *   - [CatalogStore] — registry catalog + per-solution member-management
 *     RPCs that back the `SolutionProjectsScreen` (the solutions-list
 *     surface itself lives on [WorkspaceStore] post-G1).
 *   - [WorkspaceStore] — open-set mirror of the desktop's workspace
 *     (solutions + sessions currently open) driven by sequenced
 *     `workspace.*` notifications with gap-driven resync.
 *   - [SessionListStore] — per-solution sessions list, agents catalogue,
 *     create / rename / sub-agent children, and the single shared
 *     notifications collector that fans out to the detail store.
 *   - [SessionDetailStore] — open-session transcript / diff streaming,
 *     optimistic bubbles, drafts, send / cancel / resume / paginate.
 *
 * Every public property / method below either delegates to a
 * collaborator or orchestrates a multi-collaborator action (e.g. server
 * removal wipes scoped repositories before letting [ConnectionManager]
 * drop the client). UI call sites are unchanged from the pre-split
 * god-object — the public surface preserves the same names.
 */
class MainViewModel(application: Application) : AndroidViewModel(application), ConnectionContext,
    ConnectionLifecycle {

    // ---- Per-server scoped repositories. They live on the coordinator
    // because more than one collaborator touches them (e.g.
    // ListCacheRepository is read by SessionListStore for the per-solution
    // sessions list, and removeServer must wipe every per-server file). ----

    private val draftRepository: DraftRepository =
        DraftRepository.get(application) { connectionMgr.activeServerId.value }

    private val attachmentDraftRepository: AttachmentDraftRepository =
        AttachmentDraftRepository.get(application) { connectionMgr.activeServerId.value }

    private val lastSeenRepository: LastSeenRepository =
        LastSeenRepository.get(application) { connectionMgr.activeServerId.value }

    private val navStateRepository: NavStateRepository =
        NavStateRepository.get(application) { connectionMgr.activeServerId.value }

    private val listCacheRepository: ListCacheRepository =
        ListCacheRepository.get(application) { connectionMgr.activeServerId.value }

    private val sessionHistoryRepository: SessionHistoryRepository =
        SessionHistoryRepository.get(application, viewModelScope) {
            connectionMgr.activeServerId.value
        }

    private val inFlightUploadsRepository: InFlightUploadsRepository =
        InFlightUploadsRepository.get(application) { connectionMgr.activeServerId.value }

    private val pendingSendsRepository: PendingSendsRepository =
        PendingSendsRepository.get(application) { connectionMgr.activeServerId.value }

    // ---- Collaborators (internal names suffixed with `Mgr` / `Store` so
    // they don't clash with the public state-flow names below). ----

    private val _sendError = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val sendError: SharedFlow<String> = _sendError

    private val connectionMgr: ConnectionManager = ConnectionManager(
        application = application,
        scope = viewModelScope,
        lifecycle = this,
    )

    private val catalogStore: CatalogStore = CatalogStore(
        scope = viewModelScope,
        context = this,
    )

    private val lastSeenIndex: LastSeenIndex = LastSeenIndex(lastSeenRepository)

    /**
     * Open-workspace mirror. Wired here so its lifetime matches the
     * coordinator's; the UI (D-phase) collects [workspaceState] and
     * [closedSolutions] flows exposed below. The wire client closes over
     * [connectionMgr.activeClient] so a server-switch is transparent.
     */
    private val workspaceStore: WorkspaceStore = WorkspaceStore(
        client = WorkspaceClientImpl(getClient = { connectionMgr.activeClient() }),
        scope = viewModelScope,
    )

    private val sessionList: SessionListStore = SessionListStore(
        scope = viewModelScope,
        context = this,
        listCacheRepository = listCacheRepository,
        lastSeen = lastSeenIndex,
        sessionHistoryRepository = sessionHistoryRepository,
    ).also {
        // Fan out solution member-add + change notifications from the
        // single shared collector into CatalogStore (ghost rows + member
        // list refresh on the projects screen). Mirrors the upload-ack
        // wiring below.
        it.solutionNotificationRouter = catalogStore
        // Workspace open-set notifications — same single-collector
        // fan-out; WorkspaceStore implements the router interface
        // directly so no adapter is needed.
        it.workspaceNotificationRouter = workspaceStore
    }

    /**
     * Chunked-upload coordinator for the mobile attach flow. Constructed
     * here so its lifetime matches the coordinator's; the compose row
     * reaches it via [startAttachmentUpload] / [cancelAttachmentUpload] /
     * [forgetAttachmentUpload] / [attachmentUploadStateOf] etc.
     *
     * Must be initialised BEFORE [sessionDetail] — the detail store reads
     * [UploadManager.stateFlowOf] to re-hydrate persisted attachment
     * drafts on cold start.
     */
    private val uploadManager: UploadManager = UploadManager(
        scope = viewModelScope,
        context = this,
        persistence = inFlightUploadsRepository,
        contentResolver = application.contentResolver,
    ).also {
        // Wire the upload-ack notification fan-out: the single shared
        // collector in SessionListStore forwards every upload_chunk_acked
        // payload to UploadManager via this callback. Mirrors how
        // SessionDetailStore plugs into the same observer via
        // DetailNotificationRouter.
        sessionList.uploadNotificationRouter = it::onChunkAcked
    }

    private val sessionDetail: SessionDetailStore = SessionDetailStore(
        scope = viewModelScope,
        context = this,
        draftRepository = draftRepository,
        attachmentDraftRepository = attachmentDraftRepository,
        uploadManager = uploadManager,
        lastSeen = lastSeenIndex,
        sessionList = sessionList,
        sessionHistoryRepository = sessionHistoryRepository,
        pendingSendsRepository = pendingSendsRepository,
    )

    init {
        // Foreground-refresh hook (see ForegroundEventBus KDoc + the
        // mobile-session-stuck-running finding). On every genuine
        // background-to-foreground transition, treat the server as
        // authoritative and re-fetch the session pill state +
        // sessions-list summaries that an
        // `agent_session_state_changed` notification might have been
        // delivered for while we were backgrounded. The first
        // cold-start ON_START is suppressed inside the bus, so this
        // collector only sees genuine resume edges.
        viewModelScope.launch {
            ForegroundEventBus.events.collect {
                onForegroundResume()
            }
        }
        // Stuck-Paused upload watchdog (see UploadManager kdoc). Polls the
        // raw connection-state flow exposed by ConnectionManager so it can
        // tell legitimately-paused-while-offline from stuck-Paused-while-
        // Connected.
        uploadManager.installStuckPausedWatchdog(connectionMgr.rawConnectionState)
    }

    /**
     * Called from the [ForegroundEventBus] collector on every
     * background-to-foreground transition.
     *
     * Gated on `activeClient() != null` so that a foreground
     * transition while the connection is down (the reconnect logic
     * from R-6a will recover separately, and [ConnectionLifecycle.onReconnected]
     * already triggers a resume + workspace-refresh) doesn't queue a
     * spurious "not connected" snackbar via
     * [SessionListStore.refreshSessions]'s offline branch.
     */
    private fun onForegroundResume() {
        val client = connectionMgr.activeClient() ?: return
        // Doze / app-suspend silently kills the WS but the lifecycle
        // loop is mid-backoff (often pinned at the 30s cap by attempt
        // ≥ 5). Short-circuit it so the user doesn't sit on the
        // "next try in 30s" banner the moment they unlock the phone.
        // No-op when we're already Connected/Connecting.
        client.wakeReconnect()
        val openSid = sessionDetail.openSessionId
        if (openSid != null) {
            sessionDetail.resumeSession(openSid)
        }
        val observingSid = sessionList.currentObservingSolutionId()
        if (observingSid != null) {
            sessionList.refreshSessions(observingSid)
        }
        // Workspace mirror — the server is authoritative on which
        // solutions / sessions are currently open. If we missed a
        // delta while backgrounded the next sequenced notification
        // would land with a gap and trigger a resync anyway; doing
        // it eagerly here cuts the lag on resume.
        viewModelScope.launch { workspaceStore.refresh() }
    }

    // ---- ConnectionLifecycle impl (audit Fix T light variant) ----

    override fun onClientBound(url: PairingUrl, client: RemoteClient) {
        // Workspace mirror is the authoritative landing surface post-G1
        // — its [ConnectionLifecycle.onReconnected] hook below kicks off
        // the first refresh once the wire is live. No solutions-list
        // cache to hydrate here anymore.
        // Revive any in-flight uploads persisted by a previous process
        // for THIS server. resumeAllFromDisk is idempotent — calling
        // it twice on a rapid re-bind is fine, the second call's list
        // will skip entries that are already registered in memory.
        uploadManager.resumeAllFromDisk()
        // Pending sends (Send-while-uploads-pending) MUST resume
        // AFTER uploadManager.resumeAllFromDisk — each waiter
        // coroutine calls UploadManager.awaitTerminal(localKey),
        // which only resolves once the upload's StateFlow exists
        // (created by the resume call above).
        sessionDetail.resumeDeferredSendsFromDisk(
            stateFlowOf = { localKey -> uploadManager.stateFlowOf(localKey) },
            forgetUpload = { localKey -> uploadManager.forget(localKey) },
        )
    }

    override fun onTearDown() {
        // Drop per-store UI state on the same edge that drops the
        // client. Keeps the UI from briefly showing server A's
        // transcript while server B's initial fetch is pending.
        catalogStore.reset()
        sessionList.reset()
        sessionDetail.reset()
        // The client just went away — every chunk-loop coroutine
        // would otherwise immediately see sendBinary return false on
        // its next iteration. Pause them explicitly so the StateFlow
        // surfaces a "paused (disconnected)" label rather than
        // racing into Failed states.
        uploadManager.pauseAll("connection closed")
    }

    override fun onReconnected() {
        // Disconnected → Connected edge. If a session detail screen
        // is currently active, resume it incrementally; otherwise
        // just refresh the workspace mirror.
        val openSid = sessionDetail.openSessionId
        if (openSid != null) {
            sessionDetail.resumeSession(openSid)
        }
        // Server's subscription set is per-WS-connection — every transient
        // drop loses our previous subscribe. The suspend variant SUSPENDS
        // until the `subscribe` RPC completes before letting workspace.refresh
        // fire — otherwise the snapshot RPC could land before the server
        // registers our subscription, opening a window where a delta fires
        // between snapshot-mint and subscribe-completion and is lost to us
        // (the gap-detector self-heals on the NEXT delta, but until then
        // the mirror shows stale data).
        viewModelScope.launch {
            sessionList.restartNotificationsObserverAndAwait()
            // Workspace mirror — treat the server as authoritative for
            // the open-set after any disconnect window. Clear the buffered
            // sequenced-delta queue first so any pre-disconnect deltas
            // (whose `seq` was minted by the previous coordinator instance
            // — potentially reset by a desktop restart) don't poison the
            // replay path with permanently unreachable gap targets.
            workspaceStore.clearBufferedDeltas()
            workspaceStore.refresh()
        }
        // Wake any paused upload coroutines back up. Each will hit
        // upload_status first (server is authoritative on offset).
        // The proactive [onConnectionInterrupted] hook ensures uploads
        // mid-pumpChunks were already moved to Paused on the drop edge,
        // so this single call covers the common case; the stuck-Paused
        // watchdog in [UploadManager.installStuckPausedWatchdog] is the
        // safety net for the rare missed-pauseAll race.
        uploadManager.resumeAll()
    }

    override fun onConnectionInterrupted() {
        // Transient drop — proactively pause every in-flight upload so
        // their chunk coroutines stop banging on a dead socket. Without
        // this, an upload mid-`pumpChunks` would sit waiting for an ack
        // until [ACK_TIMEOUT_MS] (30s) before flipping itself to Paused —
        // by which time the next [onReconnected] has already run its
        // `resumeAll` and missed this upload (state still Uploading at
        // the moment of resume).
        uploadManager.pauseAll("connection interrupted")
    }

    override fun onMessageExpired(message: QueuedMessage) {
        sessionDetail.handleExpiredMessage(message)
    }

    override fun onBeforeSwitch() {
        sessionDetail.beforeServerSwitch()
    }

    override fun onError(message: String) {
        _sendError.tryEmit(message)
    }

    // ---- ConnectionContext impl ----

    override fun activeClient(): RemoteClient? = connectionMgr.activeClient()
    override fun notConnectedMessage(): String = connectionMgr.notConnectedMessage()
    override fun emitError(message: String) {
        _sendError.tryEmit(message)
    }

    // ---- Connection / pairing surface ----

    val activeServerId: StateFlow<String?> get() = connectionMgr.activeServerId
    val pairedServers: StateFlow<List<PairedServer>> get() = connectionMgr.pairedServers
    val state: StateFlow<UiState> get() = connectionMgr.state
    val pairing: StateFlow<PairingUrl?> get() = connectionMgr.pairing
    val connectionBanner: StateFlow<ConnectionBanner> get() = connectionMgr.connectionBanner
    val rawConnectionState: StateFlow<ConnectionState> get() = connectionMgr.rawConnectionState
    val lastConnectedMs: StateFlow<Long?> get() = connectionMgr.lastConnectedMs

    /**
     * Pair a new server (or re-pair an existing one) from a raw
     * `spk-editor-remote://…` URL. Validates + persists asynchronously
     * on IO, then becomes the active server.
     */
    fun addServer(rawUrl: String) = connectionMgr.addServer(rawUrl)

    /**
     * Edit the label / host / port of an already-paired server (the
     * HMAC secret + fingerprint are preserved verbatim). Returns a
     * synchronous validation error string for the form, or `null` when
     * the input is valid and the actual persistence + reconnect happens
     * asynchronously. If [serverId] is currently active and the
     * transport fields changed, the active connection is torn down and
     * rebound to the new address; a label-only change is applied
     * without a reconnect.
     */
    fun editServer(serverId: String, label: String, host: String, port: Int): String? =
        connectionMgr.editServer(serverId, label, host, port)

    /**
     * Switch the active connection to [serverId]. No-ops if the target
     * is already active and a client exists; otherwise tears down the
     * current client, swaps the active id, and connects against the
     * new server's pairing URL.
     */
    fun switchToServer(serverId: String) = connectionMgr.switchToServer(serverId)

    /**
     * Multi-store orchestration: wipe per-server scoped state on every
     * coordinator-owned repository BEFORE asking [ConnectionManager] to
     * drop the pairing. The queue store is owned by [ConnectionManager]
     * and is wiped inside [ConnectionManager.removeServer].
     */
    fun removeServer(serverId: String) {
        // Move the prefs wipes off the Main thread (audit Fix J). Run
        // them in the same scope/dispatcher as ConnectionManager's own
        // IO block so the order is: prefs wipe → pairing remove →
        // optional reassign.
        viewModelScope.launch(Dispatchers.IO) {
            draftRepository.clearAllFor(serverId)
            attachmentDraftRepository.clearAllFor(serverId)
            lastSeenRepository.clearAllFor(serverId)
            navStateRepository.clearFor(serverId)
            listCacheRepository.clearAllFor(serverId)
            sessionHistoryRepository.evictAll(serverId)
            // Wipe in-flight uploads for the removed server so a
            // future reconnect against a different server doesn't
            // attempt a resume against a wire that knows nothing
            // about those upload_ids.
            uploadManager.forgetAllForServer(serverId)
            // Deferred-send records reference upload_ids in that
            // removed server's namespace — drop them too, otherwise
            // the next cold start would try to resume an orphan send
            // whose attachments live on a server we no longer pair
            // with.
            pendingSendsRepository.removeForServer(serverId)
            connectionMgr.removeServer(serverId)
        }
    }

    fun forgetPairing() {
        val active = connectionMgr.activeServerId.value ?: return
        removeServer(active)
    }

    fun coldStartLandingRoute(): String = connectionMgr.coldStartLandingRoute()

    // ---- Catalog / projects surface ----
    //
    // Per-solution member management for the `SolutionProjectsScreen`
    // (registry catalog, member-add ghost rows, open-solution details).
    // The solutions-list surface itself has moved to [WorkspaceStore]
    // post-G1; the legacy `SolutionsListScreen` + `SolutionDetailScreen`
    // are gone.

    val solutionDetails: StateFlow<UiData<GetSolutionResult>> get() = catalogStore.solutionDetails

    val catalog: StateFlow<List<ru.sipaha.spkremote.core.CatalogProjectInfo>>
        get() = catalogStore.catalog
    val memberAdds: StateFlow<Map<Pair<String, String>, MemberAddProgress>>
        get() = catalogStore.memberAdds

    fun loadSolutionDetails(solutionId: String) = catalogStore.loadSolutionDetails(solutionId)
    /**
     * Delete the solution [solutionId] on the server. The workspace
     * mirror picks up the removal via the `workspace.solution_deleted`
     * notification, so no manual list-refresh is needed.
     */
    fun deleteSolution(solutionId: String) {
        // Optimistic drop from the closed-solutions picker so the row vanishes
        // immediately. The server confirms via workspace.solution_deleted which
        // re-runs the same drop inside WorkspaceStore.applyDelta (idempotent
        // on an already-gone row). If the RPC fails the row reappears on the
        // next picker open.
        workspaceStore.dropClosedSolutionRow(solutionId)
        catalogStore.deleteSolution(solutionId)
    }
    /**
     * Create a new empty solution named [name]. The workspace mirror picks
     * up the new solution via the `workspace.solution_opened` delta —
     * no manual list refresh required.
     */
    fun createSolution(name: String) = catalogStore.createSolution(name)
    fun refreshCatalog() = catalogStore.refreshCatalog()
    fun addMemberFromCatalog(solutionId: String, catalogId: String) =
        catalogStore.addMemberFromCatalog(solutionId, catalogId)
    fun createEmptyMember(solutionId: String, name: String) =
        catalogStore.createEmptyMember(solutionId, name)
    fun removeMember(solutionId: String, catalogId: String) =
        catalogStore.removeMember(solutionId, catalogId)
    fun removeCatalogProject(catalogId: String) =
        catalogStore.removeCatalogProject(catalogId)

    // ---- Workspace (open-set) surface ----
    //
    // The desktop's open workspace mirrored over the wire — solutions
    // currently open in the editor + their open sessions. Lifecycle
    // RPCs (open / close) are exposed for the D-phase picker; the
    // notification dispatcher in [SessionListStore] keeps the snapshot
    // in sync via sequenced deltas + gap-driven resync.

    val workspaceState: StateFlow<WorkspaceUiState> get() = workspaceStore.state
    val closedSolutions: StateFlow<UiData<List<ClosedSolutionRow>>>
        get() = workspaceStore.closedSolutions

    /** Pull-to-refresh handle for the workspace pane. */
    fun refreshWorkspace() = viewModelScope.launch { workspaceStore.refresh() }

    /** Lazy picker query — call when the closed-solutions sheet opens. */
    fun refreshClosedSolutions() = viewModelScope.launch {
        workspaceStore.refreshClosedSolutions()
    }

    // ---- Workspace lifecycle wrappers (C6) ----
    //
    // Each wrapper fires the matching `workspace.*` RPC through the
    // optimistic-UI helpers on [WorkspaceStore]:
    //  - opens delegate to the server delta (no local materialisation),
    //  - closes apply locally first and roll back on RPC failure.
    //
    // NOTE on naming: the session-level pair uses a `…SessionTab`
    // suffix to avoid colliding with [openSession] (which navigates
    // into the chat detail view) and the no-arg [closeSession]
    // (which exits the chat detail). The `…SessionTab` wrappers are
    // SHALLOW "remove-from-tab-strip" actions, not destructive. The
    // destructive delete-the-session-entirely action is
    // [deleteSession] above, which targets
    // `solution_agent.delete_session`.

    fun openSolution(id: String) = viewModelScope.launch {
        workspaceStore.openSolutionOptimistic(id)
    }
    fun closeSolution(id: String) = viewModelScope.launch {
        workspaceStore.closeSolutionOptimistic(id)
    }
    fun openSessionTab(id: String) = viewModelScope.launch {
        workspaceStore.openSessionOptimistic(id)
    }
    fun closeSessionTab(id: String) = viewModelScope.launch {
        workspaceStore.closeSessionOptimistic(id)
    }

    // ---- Sessions surface ----

    val sessions: StateFlow<UiData<List<SessionSummary>>> get() = sessionList.sessions
    val session: StateFlow<UiData<GetSessionResult>> get() = sessionDetail.session
    val isLoadingOlder: StateFlow<Boolean> get() = sessionDetail.isLoadingOlder
    val optimisticEntries: StateFlow<List<EntrySummary>> get() = sessionDetail.optimisticEntries
    val pendingUploadProgress: StateFlow<Map<Long, PendingUploadProgress>>
        get() = sessionDetail.pendingUploadProgress
    val serverQueuedBundles: StateFlow<List<ru.sipaha.spkremote.core.QueuedBundleSummary>>
        get() = sessionDetail.serverQueuedBundles
    val activeSubagents: StateFlow<List<ru.sipaha.spkremote.core.SubagentDto>>
        get() = sessionDetail.activeSubagents
    val selectedSubagent: StateFlow<String?> get() = sessionDetail.selectedSubagent
    val cancelInFlight: StateFlow<Boolean> get() = sessionDetail.cancelInFlight
    val sessionChildren: StateFlow<Map<String, List<SessionSummary>>> get() = sessionList.sessionChildren
    val agents: StateFlow<UiData<List<AgentSummary>>> get() = sessionList.agents
    val createSessionInFlight: StateFlow<Boolean> get() = sessionList.createSessionInFlight
    val lastCreateAutoOpened: StateFlow<Boolean> get() = sessionList.lastCreateAutoOpened

    fun refreshSessions(solutionId: String) = sessionList.refreshSessions(solutionId)
    fun startObservingSessions(solutionId: String) = sessionList.startObservingSessions(solutionId)
    fun stopObservingSessions() = sessionList.stopObservingSessions()
    fun clearSessions() = sessionList.clearSessions()
    fun openSession(sessionId: String) = sessionDetail.openSession(sessionId)
    fun closeSession() = sessionDetail.closeSession()
    /**
     * Destructive: deletes the session entirely (transcript and all)
     * via `solution_agent.delete_session`. The non-destructive
     * remove-from-tab-strip is exposed separately as [closeSession]
     * (id variant) below, which targets `workspace.close_session`.
     */
    fun deleteSession(sessionId: String) = sessionList.deleteSession(sessionId)
    fun loadOlder(sessionId: String) = sessionDetail.loadOlder(sessionId)
    fun sendMessage(text: String) = sessionDetail.sendMessage(text)
    fun sendMessageBlocks(blocks: List<ContentBlockDto>) =
        sessionDetail.sendMessageBlocks(blocks)
    fun forceFlushQueue() = sessionDetail.forceFlushQueue()
    fun selectSubagent(id: String?) = sessionDetail.selectSubagent(id)
    /**
     * Pending-send variant for the chat compose row: caller pressed
     * Send while one or more attachments were still uploading. The
     * store creates the optimistic bubble immediately, waits for each
     * upload's terminal state via [awaitAttachmentUploadTerminal], and
     * fires `send_message_blocks` once every handle is available.
     */
    fun sendMessageBlocksDeferred(
        textBlock: ContentBlockDto.Text?,
        uploads: List<DeferredUpload>,
    ) = sessionDetail.sendMessageBlocksDeferred(
        textBlock = textBlock,
        uploads = uploads,
        stateFlowOf = { localKey -> uploadManager.stateFlowOf(localKey) },
        forgetUpload = { localKey -> uploadManager.forget(localKey) },
    )
    fun cancelTurn() = sessionDetail.cancelTurn()

    /**
     * Answer a tool-call authorization prompt on the currently-open
     * session — the user tapped one of the option buttons rendered on a
     * tool call awaiting confirmation. The store echoes [optionId] back
     * to the server, which resolves the choice and re-broadcasts the
     * entry with empty options so the buttons vanish on the next update.
     */
    fun authorizeToolCall(toolCallId: String, optionId: String) =
        sessionDetail.authorizeToolCall(toolCallId, optionId)

    /**
     * Reset the agent for the currently-open session. The server mints a
     * fresh session id and emits it via [resetSwitch]; UI surfaces should
     * observe that flow to hop navigation onto the new session.
     */
    fun resetContextOnActiveSession() = sessionDetail.resetContext()

    /**
     * Kick off the Compact context workflow on the currently-open
     * session. On a server-side decline (cold session, context below 20%,
     * busy, etc.) the snackbar surfaces the server's reason via
     * [sendError]; on success the workflow runs asynchronously and a new
     * session lands later via the standard notification path.
     */
    fun compactContextOnActiveSession() = sessionDetail.compactContext()

    /**
     * One-shot signal carrying the new session id after a successful
     * Reset context. The chat screen collects this and hops the
     * navigation route to the new id so the open chat surface stops
     * pointing at the closed source session.
     */
    val resetSwitch: Flow<String> get() = sessionDetail.resetSwitch
    fun loadAgents() = sessionList.loadAgents()
    fun createSession(
        solutionId: String,
        agentId: String,
        initialMessage: String?,
        title: String?,
        cwd: String?,
        onCreated: (sessionId: String) -> Unit,
    ) = sessionList.createSession(solutionId, agentId, initialMessage, title, cwd, onCreated)

    fun renameSession(sessionId: String, newTitle: String) =
        sessionList.renameSession(sessionId, newTitle)

    suspend fun loadDraftSeed(sessionId: String): Pair<String, Boolean> =
        sessionDetail.loadDraftSeed(sessionId)

    suspend fun saveDraft(sessionId: String, text: String) = sessionDetail.saveDraft(sessionId, text)
    fun flushDraft(sessionId: String, text: String) = sessionDetail.flushDraft(sessionId, text)
    fun clearDraft(sessionId: String) = sessionDetail.clearDraft(sessionId)

    fun pickedAttachments(sessionId: String): List<PickedAttachment> =
        sessionDetail.pickedAttachments(sessionId)
    fun setPickedAttachments(sessionId: String, attachments: List<PickedAttachment>) =
        sessionDetail.setPickedAttachments(sessionId, attachments)

    // ---- Chunked-upload surface for the attach flow ----

    /** See [UploadManager.start]. */
    fun startAttachmentUpload(
        uri: android.net.Uri,
        sessionId: String,
        mime: String,
        displayName: String,
        totalSize: Long,
    ): Pair<String, kotlinx.coroutines.flow.StateFlow<UploadManager.State>> =
        uploadManager.start(uri, sessionId, mime, displayName, totalSize)

    /** See [UploadManager.cancel]. */
    fun cancelAttachmentUpload(localKey: String) = uploadManager.cancel(localKey)

    /** See [UploadManager.forget]. */
    fun forgetAttachmentUpload(localKey: String) = uploadManager.forget(localKey)

    /** See [UploadManager.awaitTerminal]. */
    suspend fun awaitAttachmentUploadTerminal(localKey: String): String? =
        uploadManager.awaitTerminal(localKey)

    // ---- Nav-state surface (no collaborator — this is a tiny two-method
    // hook directly on the coordinator). ----

    /**
     * Async wrapper around `NavStateRepository.loadRoute()` — the only
     * cold-start caller (`AppNavGraph`) reads from a `LaunchedEffect`, and
     * the underlying lazy-open of `SharedPreferences` (and the
     * read itself) doesn't belong on the main thread.
     */
    suspend fun loadSavedRoute(): String? = withContext(Dispatchers.IO) {
        navStateRepository.loadRoute()
    }

    /**
     * Persist [route] on the IO dispatcher (fire-and-forget). Called from
     * `AppNavGraph`'s back-stack observer for every nav change. We launch
     * inside [viewModelScope] so the write outlives the calling
     * `LaunchedEffect` if the user backs out mid-write.
     */
    fun saveCurrentRoute(route: String) {
        viewModelScope.launch(Dispatchers.IO) {
            navStateRepository.saveRoute(route)
        }
    }

    override fun onCleared() {
        // The session stores' reset hooks are wired through onTearDown;
        // [ConnectionManager.tearDownConnection] fires onTearDown which
        // resets all three stores. No separate hook needed (audit Fix U).
        connectionMgr.tearDownConnection()
    }

}
