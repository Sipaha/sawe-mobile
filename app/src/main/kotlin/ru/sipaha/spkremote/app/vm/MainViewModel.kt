package ru.sipaha.spkremote.app.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.sipaha.spkremote.app.data.DraftRepository
import ru.sipaha.spkremote.app.data.LastSeenRepository
import ru.sipaha.spkremote.app.data.ListCacheRepository
import ru.sipaha.spkremote.app.data.NavStateRepository
import ru.sipaha.spkremote.app.data.PairedServer
import ru.sipaha.spkremote.core.AgentSummary
import ru.sipaha.spkremote.core.ConnectionState
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
 * Thin coordinator around three collaborators that share [viewModelScope]:
 *
 *   - [ConnectionManager] — pairing CRUD + the active [RemoteClient] +
 *     wire-level connection state observation.
 *   - [SolutionStore] — solutions list + solution detail RPCs.
 *   - [SessionStore] — sessions list, open-session transcript / diff
 *     streaming, optimistic bubbles, draft seeds, send/cancel/create.
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

    private val sessionStore: SessionStore = SessionStore(
        scope = viewModelScope,
        context = this,
        listCacheRepository = listCacheRepository,
        lastSeenRepository = lastSeenRepository,
        draftRepository = draftRepository,
    )

    // ---- ConnectionLifecycle impl (audit Fix T light variant) ----

    override fun onClientBound(url: PairingUrl, client: RemoteClient) {
        // Pull the solutions cache so the navigation surface is
        // interactable before the live refresh lands.
        solutionStore.hydrateFromCache()
    }

    override fun onTearDown() {
        // Drop per-store UI state on the same edge that drops the
        // client. Keeps the UI from briefly showing server A's
        // transcript while server B's initial fetch is pending.
        solutionStore.reset()
        sessionStore.reset()
    }

    override fun onReconnected() {
        // Disconnected → Connected edge. If a session detail screen
        // is currently active, resume it incrementally; otherwise
        // just refresh the solutions list.
        val openSid = sessionStore.openSessionId
        if (openSid != null) {
            sessionStore.resumeSession(openSid)
        }
        solutionStore.refreshSolutions()
    }

    override fun onMessageExpired(message: QueuedMessage) {
        sessionStore.handleExpiredMessage(message)
    }

    override fun onBeforeSwitch() {
        sessionStore.beforeServerSwitch()
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

    fun refreshSolutions() = solutionStore.refreshSolutions()
    fun loadSolutionDetails(solutionId: String) = solutionStore.loadSolutionDetails(solutionId)

    // ---- Sessions surface ----

    val sessions: StateFlow<UiData<List<SessionSummary>>> get() = sessionStore.sessions
    val session: StateFlow<UiData<GetSessionResult>> get() = sessionStore.session
    val isLoadingOlder: StateFlow<Boolean> get() = sessionStore.isLoadingOlder
    val optimisticEntries: StateFlow<List<EntrySummary>> get() = sessionStore.optimisticEntries
    val cancelInFlight: StateFlow<Boolean> get() = sessionStore.cancelInFlight
    val sessionChildren: StateFlow<Map<String, List<SessionSummary>>> get() = sessionStore.sessionChildren
    val agents: StateFlow<UiData<List<AgentSummary>>> get() = sessionStore.agents
    val createSessionInFlight: StateFlow<Boolean> get() = sessionStore.createSessionInFlight
    val lastCreateAutoOpened: StateFlow<Boolean> get() = sessionStore.lastCreateAutoOpened

    fun refreshSessions(solutionId: String) = sessionStore.refreshSessions(solutionId)
    fun startObservingSessions(solutionId: String) = sessionStore.startObservingSessions(solutionId)
    fun stopObservingSessions() = sessionStore.stopObservingSessions()
    fun clearSessions() = sessionStore.clearSessions()
    fun openSession(sessionId: String) = sessionStore.openSession(sessionId)
    fun closeSession() = sessionStore.closeSession()
    fun loadOlder(sessionId: String) = sessionStore.loadOlder(sessionId)
    fun sendMessage(text: String) = sessionStore.sendMessage(text)
    fun cancelTurn() = sessionStore.cancelTurn()
    fun loadAgents() = sessionStore.loadAgents()
    fun createSession(
        solutionId: String,
        agentId: String,
        initialMessage: String?,
        title: String?,
        cwd: String?,
        onCreated: (sessionId: String) -> Unit,
    ) = sessionStore.createSession(solutionId, agentId, initialMessage, title, cwd, onCreated)

    fun renameSession(sessionId: String, newTitle: String) =
        sessionStore.renameSession(sessionId, newTitle)

    suspend fun loadDraftSeed(sessionId: String): Pair<String, Boolean> =
        sessionStore.loadDraftSeed(sessionId)

    suspend fun saveDraft(sessionId: String, text: String) = sessionStore.saveDraft(sessionId, text)
    fun clearDraft(sessionId: String) = sessionStore.clearDraft(sessionId)

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
        // SessionStore's previous onCleared() did the same work that
        // [SessionStore.reset] already does via the onTearDown hook;
        // [ConnectionManager.tearDownConnection] fires onTearDown which
        // resets both stores. No separate hook needed (audit Fix U).
        connectionMgr.tearDownConnection()
    }
}
