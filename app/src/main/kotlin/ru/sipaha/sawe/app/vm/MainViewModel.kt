package ru.sipaha.sawe.app.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.sipaha.sawe.app.data.AttachmentDraftRepository
import ru.sipaha.sawe.app.data.DraftRepository
import ru.sipaha.sawe.app.data.EncryptedQueueStore
import ru.sipaha.sawe.app.data.InFlightUploadsRepository
import ru.sipaha.sawe.app.data.PendingSendsRepository
import ru.sipaha.sawe.app.data.ListCacheRepository
import ru.sipaha.sawe.app.data.NavStateRepository
import ru.sipaha.sawe.app.data.PairedServer
import ru.sipaha.sawe.app.data.PairingRepository
import ru.sipaha.sawe.app.data.PersistenceHealth
import ru.sipaha.sawe.app.data.SessionHistoryRepository
import ru.sipaha.sawe.core.AgentSummary
import ru.sipaha.sawe.core.ConnectionState
import ru.sipaha.sawe.core.SupervisorStateDto
import ru.sipaha.sawe.core.ContentBlockDto
import ru.sipaha.sawe.core.EntrySummary
import ru.sipaha.sawe.core.GetSessionResult
import ru.sipaha.sawe.core.GetSolutionResult
import ru.sipaha.sawe.core.PairingUrl
import ru.sipaha.sawe.core.QueuedMessage
import ru.sipaha.sawe.core.RemoteClient
import ru.sipaha.sawe.core.SessionSummary
import java.io.File

sealed interface UiState {
    data class Disconnected(val lastUrl: String? = null, val error: String? = null) : UiState
    data object Connecting : UiState
    data class Connected(val protocolVersion: String) : UiState

    /**
     * The paired desktop is advertising a chat-wire schema this client
     * doesn't support yet (see [ru.sipaha.sawe.core.isServerTooNew]).
     * We surface a terminal "update the app" screen instead of trying to
     * drive the UI off a wire it can't decode — silently rendering
     * mis-parsed sessions would be worse than refusing to operate. The
     * gate site (`ConnectionManager.startSchemaGate`) tears the client
     * down on this decision, so the screen offers a Retry that rebuilds
     * the connection from scratch; only a successfully decoded
     * `CapabilitiesDto` can put us here.
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

    /**
     * One-shot user-facing notices (send failures, RPC errors, lost
     * persistence).
     *
     * A `SharedFlow` with `replay = 0` used to drop every emission made
     * while no screen happened to be collecting — which is most of them,
     * because the two collectors lived inside route composables that leave
     * the composition as soon as Workspace or Settings is on top. A
     * buffered [Channel] holds the message until someone reads it (N-57).
     *
     * **Exactly ONE collector.** These are one-shot notices with a single
     * consumer by design — an app-level `SnackbarHost` that outlives every
     * route. Two collectors would split the stream between them.
     */
    private val _userNotices = Channel<String>(
        capacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val sendError: Flow<String> = _userNotices.receiveAsFlow()

    private val connectionMgr: ConnectionManager = ConnectionManager(
        application = application,
        scope = viewModelScope,
        lifecycle = this,
    )

    private val catalogStore: CatalogStore = CatalogStore(
        scope = viewModelScope,
        context = this,
    )

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
        // The `content://` grant the photo / document picker hands over
        // dies with the process, so a resume after a process kill can only
        // read the bytes through a copy we own. Without a directory here
        // the whole staging path is inert and every resumed upload fails
        // on the lapsed grant (N-42 / UP-1).
        stagingDir = File(application.cacheDir, ATTACHMENT_STAGING_DIR),
    ).also {
        // Wire the upload-ack notification fan-out: the single shared
        // collector in SessionListStore forwards every upload_chunk_acked
        // payload to UploadManager via this callback. Mirrors how
        // SessionDetailStore plugs into the same observer via
        // DetailNotificationRouter.
        sessionList.uploadNotificationRouter = it::onChunkAcked
        // Same fan-out for the negative ack. Without it a refused chunk is
        // indistinguishable from a slow one and the chunk loop sits out its
        // full 30s ack budget before re-seeking (N-47 / N-55).
        sessionList.uploadRejectionRouter = it::onChunkRejected
        // Let the connection layer see that an attachment still needs the
        // wire, so backgrounding mid-upload doesn't park the reconnect
        // ladder out from under it (CM-3).
        connectionMgr.uploadWireWorkProvider = it::wireWorkSnapshot
    }

    private val sessionDetail: SessionDetailStore = SessionDetailStore(
        scope = viewModelScope,
        context = this,
        draftRepository = draftRepository,
        attachmentDraftRepository = attachmentDraftRepository,
        uploadManager = uploadManager,
        sessionList = sessionList,
        sessionHistoryRepository = sessionHistoryRepository,
        pendingSendsRepository = pendingSendsRepository,
    ).also {
        // The durable outbound queue belongs to ConnectionManager, so the
        // detail store can't check it itself. Without this hook the store
        // keeps every persisted pending-send marker rather than guessing —
        // correct, but it means a marker orphaned by a process death comes
        // back as a permanent phantom "Sending" bubble on every open
        // (N-03).
        it.queuedCsidsProvider = { connectionMgr.queuedClientSendIds() }
        // Same reason: the queue — and therefore the only operation that can
        // take a parked message back out of it — belongs to ConnectionManager.
        // Unset, the store refuses every cancel, which is the safe default.
        it.cancelQueuedCallProvider = { queueId -> connectionMgr.cancelQueuedCall(queueId) }
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
                connectionMgr.onAppForegrounded()
                onForegroundResume()
            }
        }
        // Background edge: park the wire heartbeat so a phone face-down on
        // a table stops waking its radio for a UI nobody is looking at.
        viewModelScope.launch {
            ForegroundEventBus.backgroundEvents.collect {
                connectionMgr.onAppBackgrounded()
            }
        }
        // Pay the encrypted-prefs opens (Tink keyset unwrap through the
        // Android Keystore, plus the one-off legacy import) once, eagerly,
        // off Main — instead of once per store on Main across the
        // cold-start path.
        viewModelScope.launch {
            connectionMgr.warmUpPersistence(
                pendingSends = pendingSendsRepository,
                history = sessionHistoryRepository,
                inFlightUploads = inFlightUploadsRepository,
                drafts = draftRepository,
                attachmentDrafts = attachmentDraftRepository,
                listCache = listCacheRepository,
            )
        }
        // Persisted data that had to be discarded because the Keystore key
        // behind it went missing. Tell the user once, then acknowledge so
        // the notice doesn't repeat.
        //
        // ONE notice per batch, not one per store: every store is wrapped by
        // the same Keystore master key, so losing it drops all eight at once
        // and eight snackbars would be the same event reported eight times.
        // The acknowledge is batched with it, so the flow re-emits once.
        viewModelScope.launch {
            PersistenceHealth.droppedStores.collect { dropped ->
                if (dropped.isEmpty()) return@collect
                coalescedPersistenceDropNotice(dropped)?.let { emitError(it) }
                PersistenceHealth.acknowledgeDropped(dropped)
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
     *
     * The liveness probe and the refetch run CONCURRENTLY. Sequencing them
     * made every resume wait out the probe's budget before touching the
     * screen the user is looking at, and on a zombie socket that was the
     * full two-strike cost — the app looked dead for the duration. If the
     * probe does find the wire dead it forces a reconnect and
     * [onReconnected] re-runs the catch-up, so the concurrent refetch is at
     * worst wasted, never wrong (CM-4).
     */
    private fun onForegroundResume() {
        val client = connectionMgr.activeClient() ?: run {
            // No client is exactly the post-schema-gate state: the gate
            // tears the client down, so an editor that has since been
            // updated would otherwise need the user to find the Retry
            // button. Re-probe it on the foreground edge (N-20).
            if (state.value is UiState.IncompatibleServer) connectionMgr.retryActiveServer()
            return
        }
        // Doze / app-suspend silently kills the WS but the lifecycle
        // loop is mid-backoff (often pinned at the 30s cap by attempt
        // ≥ 5). Short-circuit it so the user doesn't sit on the
        // "next try in 30s" banner the moment they unlock the phone.
        // No-op when we're already Connected/Connecting.
        client.wakeReconnect()
        // A socket that survived the background window can be a zombie
        // (Doze froze traffic; OkHttp still reads Connected). Probe it in
        // its own coroutine — a dead wire ends in forceReconnect, and
        // onReconnected drives the catch-up from there.
        viewModelScope.launch { connectionMgr.probeLivenessNow() }
        viewModelScope.launch {
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
            workspaceStore.refresh()
        }
    }

    // ---- ConnectionLifecycle impl (audit Fix T light variant) ----

    override fun onClientBound(url: PairingUrl, client: RemoteClient) {
        // Nothing to hydrate before the socket exists. Disk-persisted
        // uploads and deferred sends are revived from [onReconnected]
        // instead: resuming them here ran every `upload_status` RPC
        // against a client whose transport was still null, which
        // immediately threw NotConnectedException and rewrote a perfectly
        // good "paused at 1.2 MB" as garbage (N-46). The workspace mirror
        // is likewise refreshed from the Connected edge.
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
        // Revive in-flight uploads persisted by a previous process for
        // THIS server, now that there is a live wire for their
        // `upload_status` probe to land on. Idempotent — entries already
        // registered in memory are skipped, so firing on every reconnect
        // edge is safe.
        uploadManager.resumeAllFromDisk()
        // Pending sends (Send-while-uploads-pending) MUST resume AFTER
        // uploadManager.resumeAllFromDisk — each waiter coroutine calls
        // UploadManager.awaitTerminal(localKey), which only resolves once
        // the upload's StateFlow exists (created by the resume above).
        sessionDetail.resumeDeferredSendsFromDisk(
            stateFlowOf = { localKey -> uploadManager.stateFlowOf(localKey) },
            forgetUpload = { localKey -> uploadManager.forget(localKey) },
        )
        // Wake any paused upload coroutines back up. Each will hit
        // upload_status first (server is authoritative on offset).
        // The proactive [onConnectionInterrupted] hook ensures uploads
        // mid-pumpChunks were already moved to Paused on the drop edge,
        // so this single call covers the common case; the stuck-Paused
        // watchdog in [UploadManager.installStuckPausedWatchdog] is the
        // safety net for the rare missed-pauseAll race.
        uploadManager.resumeAll()
    }

    override fun onFeaturesNegotiated() {
        // The reconnect replay in [onReconnected] runs before the
        // capabilities probe answers, so its `subscribe` could not carry a
        // `suppress_kinds` list. Re-issue it now that the negotiated set is
        // known — a no-op when the peer advertised nothing to suppress.
        sessionList.applyNegotiatedFeatures()
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
        emitError(message)
    }

    // ---- ConnectionContext impl ----

    override fun activeClient(): RemoteClient? = connectionMgr.activeClient()
    override fun notConnectedMessage(): String = connectionMgr.notConnectedMessage()
    override suspend fun probeLivenessNow(): Boolean = connectionMgr.probeLivenessNow()
    override fun emitError(message: String) {
        _userNotices.trySend(message)
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
     * `sawe-remote://…` URL. Validates + persists asynchronously
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
        viewModelScope.launch { removeServerAndAwait(serverId) }
    }

    /**
     * Suspending variant of [removeServer] so a caller that navigates on
     * completion (Settings' "Forget server") can read the resulting
     * paired-server list instead of racing the wipe and reading the stale
     * one (N-27).
     *
     * The repository wipes run on IO; [ConnectionManager.removeServer]
     * itself runs on the caller's Main-confined scope because it mutates
     * the active client and fires the lifecycle callbacks that the stores
     * expect on Main.
     */
    suspend fun removeServerAndAwait(serverId: String) {
        // Prefs wipes off the Main thread (audit Fix J), ordered before
        // the pairing removal so a crash mid-way can't leave a paired
        // server pointing at half-wiped per-server state.
        withContext(Dispatchers.IO) {
            draftRepository.clearAllFor(serverId)
            attachmentDraftRepository.clearAllFor(serverId)
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
        }
        connectionMgr.removeServer(serverId)
    }

    fun forgetPairing() {
        val active = connectionMgr.activeServerId.value ?: return
        removeServer(active)
    }

    /**
     * Forget the active server and suspend until the pairing list has
     * actually been updated, so the caller's navigation decision reads the
     * post-removal list (N-27).
     */
    suspend fun forgetPairingAndAwait() {
        val active = connectionMgr.activeServerId.value ?: return
        removeServerAndAwait(active)
    }

    /**
     * Cold-start landing route, resolved off the Main thread. Null while
     * the pairing repository is still being read; the nav graph shows a
     * splash until it resolves. The auto-connect that used to hang off the
     * Activity's `savedInstanceState == null` branch now lives in
     * [ConnectionManager]'s own hydration, so a ViewModel constructed after
     * a background process kill reconnects on its own (N-10).
     */
    val landingRoute: StateFlow<String?> get() = connectionMgr.landingRoute

    /**
     * Rebuild the connection to the active server. Backs the Retry
     * affordance on the incompatible-server gate and the re-pair banner —
     * both of those states are otherwise unrecoverable without a
     * force-stop (N-20).
     */
    fun retryConnection() = connectionMgr.retryActiveServer()

    // ---- Catalog / projects surface ----
    //
    // Per-solution member management for the `SolutionProjectsScreen`
    // (registry catalog, member-add ghost rows, open-solution details).
    // The solutions-list surface itself has moved to [WorkspaceStore]
    // post-G1; the legacy `SolutionsListScreen` + `SolutionDetailScreen`
    // are gone.

    val solutionDetails: StateFlow<UiData<GetSolutionResult>> get() = catalogStore.solutionDetails

    val catalog: StateFlow<List<ru.sipaha.sawe.core.CatalogProjectInfo>>
        get() = catalogStore.catalog
    val memberAdds: StateFlow<Map<Pair<Long, Long>, MemberAddProgress>>
        get() = catalogStore.memberAdds

    fun loadSolutionDetails(solutionId: Long) = catalogStore.loadSolutionDetails(solutionId)
    /**
     * Delete the solution [solutionId] on the server. The workspace
     * mirror picks up the removal via the `workspace.solution_deleted`
     * notification, so no manual list-refresh is needed.
     */
    fun deleteSolution(solutionId: Long) {
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
    fun addMemberFromCatalog(solutionId: Long, catalogId: Long) =
        catalogStore.addMemberFromCatalog(solutionId, catalogId)
    fun createEmptyMember(solutionId: Long, name: String) =
        catalogStore.createEmptyMember(solutionId, name)
    fun removeMember(solutionId: Long, catalogId: Long) =
        catalogStore.removeMember(solutionId, catalogId)
    fun removeCatalogProject(catalogId: Long) =
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

    fun openSolution(id: Long) = viewModelScope.launch {
        workspaceStore.openSolutionOptimistic(id)
    }
    fun closeSolution(id: Long) = viewModelScope.launch {
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
    val serverQueuedBundles: StateFlow<List<ru.sipaha.sawe.core.QueuedBundleSummary>>
        get() = sessionDetail.serverQueuedBundles
    val streams: StateFlow<List<ru.sipaha.sawe.core.StreamDto>>
        get() = sessionDetail.streams
    val selectedStream: StateFlow<ru.sipaha.sawe.core.StreamIdDto> get() = sessionDetail.selectedStream
    val cancelInFlight: StateFlow<Boolean> get() = sessionDetail.cancelInFlight
    val sessionChildren: StateFlow<Map<String, List<SessionSummary>>> get() = sessionList.sessionChildren
    val agents: StateFlow<UiData<List<AgentSummary>>> get() = sessionList.agents
    val createSessionInFlight: StateFlow<Boolean> get() = sessionList.createSessionInFlight
    val lastCreateAutoOpened: StateFlow<Boolean> get() = sessionList.lastCreateAutoOpened

    fun refreshSessions(solutionId: Long) = sessionList.refreshSessions(solutionId)
    fun startObservingSessions(solutionId: Long) = sessionList.startObservingSessions(solutionId)
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

    /**
     * Take back the message parked in the offline queue under [csid] (N-07).
     *
     * Best-effort by contract: a message already handed to the transport
     * cannot be recalled, and the store surfaces that as a notice rather than
     * pretending it was cancelled.
     */
    fun cancelQueuedSend(csid: Long) = sessionDetail.cancelQueuedSend(csid)
    fun selectStream(id: ru.sipaha.sawe.core.StreamIdDto) = sessionDetail.selectStream(id)
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
        solutionId: Long,
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

    /**
     * Text the offline queue handed back while the chat was open — a TTL
     * expiry, an abandoned message, or a send the user cancelled.
     *
     * [loadDraftSeed] only runs on open, so before this a bounce that landed
     * mid-session stayed invisible until the user navigated away and back
     * (N-07). Single-consumer: the compose bar collects it, merges it into the
     * live draft and calls [consumeBounce].
     */
    val bouncedDrafts: Flow<BouncedDraft> get() = sessionDetail.bouncedDrafts

    /** See [SessionDetailStore.consumeBounce]. */
    suspend fun consumeBounce(sessionId: String) = sessionDetail.consumeBounce(sessionId)

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

    /**
     * See [UploadManager.retry] — restarts a failed attachment upload in
     * place, under the same key and StateFlow the card is collecting.
     * Returns false when the upload is unrecoverable (unknown key, or a
     * driver is already running), which the card turns into "remove and
     * re-attach" (N-48).
     */
    fun retryAttachmentUpload(localKey: String): Boolean = uploadManager.retry(localKey)

    /** See [UploadManager.awaitTerminal]. */
    suspend fun awaitAttachmentUploadTerminal(localKey: String): String? =
        uploadManager.awaitTerminal(localKey)

    // ---- Supervisor surface ----

    /**
     * Live Supervisor state for the currently-open session. Populated by
     * [loadSupervisorState] and updated by [setSupervisorEnabled] /
     * [setSupervisorPrompt]. Null until the first successful load.
     */
    val supervisorState: StateFlow<SupervisorStateDto?> get() = sessionDetail.supervisorState

    /**
     * Load (or refresh) the Supervisor state for [sessionId] from the
     * server. Typically called when the Supervisor settings sheet opens.
     */
    fun loadSupervisorState(sessionId: String) =
        sessionDetail.loadSupervisorState(sessionId)

    /** Enable or disable the Supervisor for [sessionId]. */
    fun setSupervisorEnabled(sessionId: String, enabled: Boolean) =
        sessionDetail.setSupervisorEnabled(sessionId, enabled)

    /**
     * Set a custom Supervisor instruction prompt for [sessionId]. Pass
     * null or blank to clear the custom prompt (server uses its default).
     */
    fun setSupervisorPrompt(sessionId: String, prompt: String?) =
        sessionDetail.setSupervisorPrompt(sessionId, prompt)

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

/**
 * Sub-directory of `cacheDir` holding staged copies of picked attachments.
 *
 * `cacheDir` on purpose: the copy is disposable — it exists only to outlive
 * the `content://` grant for the duration of one upload, and the OS may
 * reclaim it under storage pressure, which resume already handles as an
 * unreadable source.
 */
internal const val ATTACHMENT_STAGING_DIR: String = "uploads"

/**
 * User-facing copy for a persisted store that had to be discarded, in the
 * order a multi-store notice lists them.
 *
 * The encrypted-prefs layer recovers from a lost Android Keystore keyset
 * by dropping the unreadable file and starting over, which silently loses
 * whatever was in it. The user has to be told, because the consequences
 * (a queued message that will never be delivered, a chat that scrolls back
 * to nothing) are otherwise indistinguishable from a bug.
 *
 * Two strings per store, in ONE entry on purpose:
 *
 *  - [DropCopy.notice] — the whole sentence, shown when this store is the
 *    only thing that was lost. It is the specific, actionable wording and
 *    it is deliberately unchanged;
 *  - [DropCopy.summary] — the same loss as a noun phrase, for the sentence
 *    that reports several stores at once.
 *
 * Splitting these across two `when`s would let a store acquire one and not
 * the other, and the missing one would be invisible: a store absent from
 * the multi-store list is exactly as quiet as a store with no copy at all.
 *
 * The map is ordered (a `LinkedHashMap`), and that order is the order of
 * the coalesced sentence — pairing first, because it is the only loss with
 * something the user can do about it.
 */
private class DropCopy(val notice: String, val summary: String)

/**
 * Every store that reaches [PersistenceHealth.droppedStores] — i.e. every
 * store opened through [ru.sipaha.sawe.app.data.EncryptedPrefs.open] —
 * needs an entry here, or the loss is acknowledged and swallowed with
 * nothing shown. `ConnectionLifecycleDecisionsTest` enumerates those call
 * sites out of the sources and fails on a store that has no copy.
 */
private val PERSISTENCE_DROP_COPY: Map<String, DropCopy> = linkedMapOf(
    PairingRepository.PREFS_NAME to DropCopy(
        notice = "Saved servers were lost — the device's secure key changed. " +
            "Scan the QR code again.",
        summary = "saved servers",
    ),
    EncryptedQueueStore.PREFS_NAME to DropCopy(
        notice = "Queued messages waiting to be sent were lost — the device's secure key changed.",
        summary = "queued messages",
    ),
    PendingSendsRepository.PREFS_NAME to DropCopy(
        notice = "Attachments waiting to be sent were lost — the device's secure key changed.",
        summary = "attachments waiting to be sent",
    ),
    InFlightUploadsRepository.PREFS_NAME to DropCopy(
        notice = "Files still uploading were lost — the device's secure key changed. " +
            "Attach them again.",
        summary = "files still uploading",
    ),
    DraftRepository.PREFS_NAME to DropCopy(
        notice = "Unsent drafts were lost — the device's secure key changed. Type them again.",
        summary = "unsent drafts",
    ),
    AttachmentDraftRepository.PREFS_NAME to DropCopy(
        notice = "Attachments picked but not yet sent were lost — the device's secure key " +
            "changed. Attach them again.",
        summary = "attachments picked but not yet sent",
    ),
    SessionHistoryRepository.PREFS_NAME to DropCopy(
        notice = "Cached chat history was cleared — the device's secure key changed.",
        summary = "cached chat history",
    ),
    ListCacheRepository.PREFS_NAME to DropCopy(
        notice = "The offline list of solutions and chats was cleared — the device's secure " +
            "key changed. It refills on the next connection.",
        summary = "the offline list of solutions and chats",
    ),
)

/**
 * The notice for a single dropped store, or null for a store whose loss is
 * invisible to the user.
 */
internal fun persistenceDropNotice(prefsName: String): String? =
    PERSISTENCE_DROP_COPY[prefsName]?.notice

/**
 * The noun phrase [prefsName]'s loss contributes to a multi-store notice.
 *
 * Exposed for the test that walks the store ids out of the sources: it is
 * the half of the copy that a coalesced notice can drop silently.
 */
internal fun persistenceDropSummary(prefsName: String): String? =
    PERSISTENCE_DROP_COPY[prefsName]?.summary

/**
 * ONE notice for a whole batch of dropped stores.
 *
 * Every store's Tink keyset is wrapped by the same Android Keystore master
 * key, so the failure that drops one drops all of them: a single Keystore
 * loss used to produce up to eight snackbars in a row, which is one event
 * reported eight times.
 *
 * One store → its own [DropCopy.notice], unchanged. That is the specific
 * wording, and the single-store case is the one where the user can act.
 *
 * Several → one sentence in the same voice, naming the cause once and
 * listing what went with it. The pairing instruction is appended when
 * `spk_pairing` is in the batch, because "scan the QR code again" is the
 * only action any of these losses has, and it must not be lost to the
 * summary.
 *
 * Returns null when nothing in [dropped] has user-visible copy — the
 * caller still acknowledges the batch, exactly as it did per store.
 */
internal fun coalescedPersistenceDropNotice(dropped: Collection<String>): String? {
    // Iterating the table (not `dropped`) fixes the order and drops the
    // unknown ids in one step.
    val known = PERSISTENCE_DROP_COPY.keys.filter { it in dropped }
    if (known.isEmpty()) return null
    if (known.size == 1) return PERSISTENCE_DROP_COPY.getValue(known.single()).notice

    val summaries = known.map { PERSISTENCE_DROP_COPY.getValue(it).summary }
    val subject = summaries.dropLast(1).joinToString(", ") + " and " + summaries.last()
    val action =
        if (PairingRepository.PREFS_NAME in known) " Scan the QR code again." else ""
    return subject.replaceFirstChar { it.uppercase() } +
        " were lost — the device's secure key changed." + action
}
