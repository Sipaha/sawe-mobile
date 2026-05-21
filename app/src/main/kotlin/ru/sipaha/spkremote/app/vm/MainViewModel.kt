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
import ru.sipaha.spkremote.core.SolutionSummary

sealed interface UiState {
    data class Disconnected(val lastUrl: String? = null, val error: String? = null) : UiState
    data object Connecting : UiState
    data class Connected(val protocolVersion: String) : UiState
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
 * Thin coordinator around four collaborators that share [viewModelScope]:
 *
 *   - [ConnectionManager] — pairing CRUD + the active [RemoteClient] +
 *     wire-level connection state observation.
 *   - [SolutionStore] — solutions list + solution detail RPCs.
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
    // ListCacheRepository is read by SolutionStore for solutions AND
    // SessionStore for sessions, and removeServer must wipe both). ----

    private val draftRepository: DraftRepository =
        DraftRepository.get(application) { connectionMgr.activeServerId.value }

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

    private val solutionStore: SolutionStore = SolutionStore(
        scope = viewModelScope,
        context = this,
        listCacheRepository = listCacheRepository,
    )

    private val lastSeenIndex: LastSeenIndex = LastSeenIndex(lastSeenRepository)

    private val sessionList: SessionListStore = SessionListStore(
        scope = viewModelScope,
        context = this,
        listCacheRepository = listCacheRepository,
        lastSeen = lastSeenIndex,
        sessionHistoryRepository = sessionHistoryRepository,
    ).also {
        // Fan out solution member-add + change notifications from the
        // single shared collector into SolutionStore (ghost rows + member
        // list refresh). Mirrors the upload-ack wiring below.
        it.solutionNotificationRouter = solutionStore
    }

    private val sessionDetail: SessionDetailStore = SessionDetailStore(
        scope = viewModelScope,
        context = this,
        draftRepository = draftRepository,
        lastSeen = lastSeenIndex,
        sessionList = sessionList,
        sessionHistoryRepository = sessionHistoryRepository,
        pendingSendsRepository = pendingSendsRepository,
    )

    /**
     * Chunked-upload coordinator for the mobile attach flow. Constructed
     * here so its lifetime matches the coordinator's; the compose row
     * reaches it via [startAttachmentUpload] / [cancelAttachmentUpload] /
     * [forgetAttachmentUpload] / [attachmentUploadStateOf] etc.
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
    }

    /**
     * Called from the [ForegroundEventBus] collector on every
     * background-to-foreground transition.
     *
     * Gated on `activeClient() != null` so that a foreground
     * transition while the connection is down (the reconnect logic
     * from R-6a will recover separately, and [ConnectionLifecycle.onReconnected]
     * already triggers a resume + solutions-refresh) doesn't queue a
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
    }

    // ---- ConnectionLifecycle impl (audit Fix T light variant) ----

    override fun onClientBound(url: PairingUrl, client: RemoteClient) {
        // Pull the solutions cache so the navigation surface is
        // interactable before the live refresh lands.
        solutionStore.hydrateFromCache()
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
        solutionStore.reset()
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
        // just refresh the solutions list.
        val openSid = sessionDetail.openSessionId
        if (openSid != null) {
            sessionDetail.resumeSession(openSid)
        }
        solutionStore.refreshSolutions()
        // Wake any paused upload coroutines back up. Each will hit
        // upload_status first (server is authoritative on offset).
        uploadManager.resumeAll()
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

    // ---- Solutions surface ----

    val solutions: StateFlow<UiData<List<SolutionSummary>>> get() = solutionStore.solutions
    val solutionDetails: StateFlow<UiData<GetSolutionResult>> get() = solutionStore.solutionDetails

    val catalog: StateFlow<List<ru.sipaha.spkremote.core.CatalogProjectInfo>>
        get() = solutionStore.catalog
    val memberAdds: StateFlow<Map<Pair<String, String>, MemberAddProgress>>
        get() = solutionStore.memberAdds

    fun refreshSolutions() = solutionStore.refreshSolutions()
    fun loadSolutionDetails(solutionId: String) = solutionStore.loadSolutionDetails(solutionId)
    fun createSolution(name: String) = solutionStore.createSolution(name)
    fun deleteSolution(solutionId: String) = solutionStore.deleteSolution(solutionId)
    fun refreshCatalog() = solutionStore.refreshCatalog()
    fun createSolutionWith(
        name: String,
        catalogIds: List<String>,
        emptyNames: List<String>,
        onCreated: (solutionId: String) -> Unit = {},
    ) = solutionStore.createSolutionWith(name, catalogIds, emptyNames, onCreated)
    fun addMemberFromCatalog(solutionId: String, catalogId: String) =
        solutionStore.addMemberFromCatalog(solutionId, catalogId)
    fun createEmptyMember(solutionId: String, name: String) =
        solutionStore.createEmptyMember(solutionId, name)
    fun removeMember(solutionId: String, catalogId: String) =
        solutionStore.removeMember(solutionId, catalogId)
    fun removeCatalogProject(catalogId: String) =
        solutionStore.removeCatalogProject(catalogId)

    // ---- Sessions surface ----

    val sessions: StateFlow<UiData<List<SessionSummary>>> get() = sessionList.sessions
    val session: StateFlow<UiData<GetSessionResult>> get() = sessionDetail.session
    val isLoadingOlder: StateFlow<Boolean> get() = sessionDetail.isLoadingOlder
    val optimisticEntries: StateFlow<List<EntrySummary>> get() = sessionDetail.optimisticEntries
    val pendingUploadProgress: StateFlow<Map<Long, PendingUploadProgress>>
        get() = sessionDetail.pendingUploadProgress
    val serverQueuedBundles: StateFlow<List<ru.sipaha.spkremote.core.QueuedBundleSummary>>
        get() = sessionDetail.serverQueuedBundles
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
    fun closeSession(sessionId: String) = sessionList.closeSession(sessionId)
    fun loadOlder(sessionId: String) = sessionDetail.loadOlder(sessionId)
    fun sendMessage(text: String) = sessionDetail.sendMessage(text)
    fun sendMessageBlocks(blocks: List<ContentBlockDto>) =
        sessionDetail.sendMessageBlocks(blocks)
    fun forceFlushQueue() = sessionDetail.forceFlushQueue()
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
    fun clearDraft(sessionId: String) = sessionDetail.clearDraft(sessionId)

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
