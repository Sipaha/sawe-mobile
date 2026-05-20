package ru.sipaha.spkremote.app.vm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.sipaha.spkremote.app.data.ListCacheRepository
import ru.sipaha.spkremote.core.CatalogProjectInfo
import ru.sipaha.spkremote.core.CreateSolutionResult
import ru.sipaha.spkremote.core.GetSolutionResult
import ru.sipaha.spkremote.core.ListSolutionsResult
import ru.sipaha.spkremote.core.MemberAddCompletedPayload
import ru.sipaha.spkremote.core.MemberAddProgressPayload
import ru.sipaha.spkremote.core.SolutionSummary

/**
 * Owns the solutions list + per-solution detail state flows. Talks to
 * the desktop through [ConnectionContext.activeClient]; uses the
 * on-disk [ListCacheRepository] to keep the user looking at *something*
 * when the WebSocket isn't usable.
 */
internal class SolutionStore(
    private val scope: CoroutineScope,
    private val context: ConnectionContext,
    private val listCacheRepository: ListCacheRepository,
) : SolutionNotificationRouter {
    private val _solutions = MutableStateFlow<UiData<List<SolutionSummary>>>(UiData.Loading)
    val solutions: StateFlow<UiData<List<SolutionSummary>>> = _solutions.asStateFlow()

    private val _solutionDetails = MutableStateFlow<UiData<GetSolutionResult>>(UiData.Loading)
    val solutionDetails: StateFlow<UiData<GetSolutionResult>> = _solutionDetails.asStateFlow()

    /**
     * Registry (catalog) projects available to add to a Solution. Backs
     * the project picker; refreshed lazily on picker open via
     * [refreshCatalog]. Empty until the first refresh resolves.
     */
    private val _catalog = MutableStateFlow<List<CatalogProjectInfo>>(emptyList())
    val catalog: StateFlow<List<CatalogProjectInfo>> = _catalog.asStateFlow()

    /**
     * In-flight (and recently-failed) catalog member-adds, keyed by
     * `(solutionId, catalogId)`. Rendered as ghost member rows with a
     * spinner + percent, or an error message. Cleared on successful
     * completion; an entry with a non-null [MemberAddProgress.error]
     * sticks around so the user can see the failure.
     *
     * Empty-project adds are synchronous server-side and never pass
     * through here — they just refresh the open solution detail.
     */
    private val _memberAdds =
        MutableStateFlow<Map<Pair<String, String>, MemberAddProgress>>(emptyMap())
    val memberAdds: StateFlow<Map<Pair<String, String>, MemberAddProgress>> =
        _memberAdds.asStateFlow()

    // Single-flight guard for refresh.
    private var refreshSolutionsJob: Job? = null

    /** Hydrate from cache eagerly on `switchToServer`. */
    fun hydrateFromCache() {
        val cached = listCacheRepository.loadSolutions()
        if (cached != null) {
            _solutions.value = UiData.Loaded(cached)
        }
    }

    /** Reset on tear-down. */
    fun reset() {
        // Cancel any in-flight refresh first so its `onSuccess` doesn't
        // overwrite the freshly-reset `_solutions` with the previous
        // server's payload after we've already nulled it.
        refreshSolutionsJob?.cancel()
        refreshSolutionsJob = null
        _solutions.value = UiData.Loading
        // Drop the previous server's solution detail too — otherwise it
        // stays visible until the next `loadSolutionDetails` lands.
        _solutionDetails.value = UiData.Loading
        // Catalog + in-flight adds are server-scoped; a switch invalidates
        // both (different registry, different member-add operations).
        _catalog.value = emptyList()
        _memberAdds.value = emptyMap()
    }

    fun refreshSolutions() {
        val active = context.activeClient()
        if (active == null) {
            val cached = listCacheRepository.loadSolutions()
            if (cached != null) {
                _solutions.value = UiData.Loaded(cached)
                context.emitError(context.notConnectedMessage())
            } else {
                _solutions.value = UiData.Error(context.notConnectedMessage())
            }
            return
        }
        if (_solutions.value !is UiData.Loaded) {
            _solutions.value = UiData.Loading
        }
        singleFlightRefresh(
            scope = scope,
            target = _solutions,
            jobHolder = { refreshSolutionsJob },
            setJob = { refreshSolutionsJob = it },
            emitError = { context.emitError("Couldn't refresh solutions: $it") },
            fetch = {
                val resp = active.call("remote.solutions.list")
                resp.decodeResultOrThrow(ListSolutionsResult.serializer()).solutions
            },
            onSuccess = { listCacheRepository.saveSolutions(it) },
        )
    }

    /**
     * Create a new solution named [name] on the server. On success,
     * refresh the solutions list so the new entry surfaces. Errors are
     * pushed through the shared error channel — caller is expected to
     * pre-trim and pre-validate, but we surface server-side rejections
     * (e.g. duplicate name) here too.
     */
    fun createSolution(name: String) {
        val active = context.activeClient()
        if (active == null) {
            context.emitError(context.notConnectedMessage())
            return
        }
        val params = buildJsonObject { put("name", name) }
        scope.launch {
            runCatching { active.call("remote.solutions.create", params) }
                .mapCatching { resp ->
                    val err = resp.error
                    if (err != null) error(err.message)
                    val toolErr = resp.toolError()
                    if (toolErr != null) error(toolErr)
                }
                .onSuccess { refreshSolutions() }
                .onFailure { context.emitError("Couldn't create solution: ${it.message ?: "?"}") }
        }
    }

    /**
     * Delete the solution [solutionId] on the server. On success, refresh
     * the solutions list — the deleted entry will simply not reappear in
     * the next payload. Failures surface through the shared error channel.
     */
    fun deleteSolution(solutionId: String) {
        val active = context.activeClient()
        if (active == null) {
            context.emitError(context.notConnectedMessage())
            return
        }
        val params = buildJsonObject { put("solution_id", solutionId) }
        scope.launch {
            runCatching { active.call("remote.solutions.delete", params) }
                .mapCatching { resp ->
                    val err = resp.error
                    if (err != null) error(err.message)
                    val toolErr = resp.toolError()
                    if (toolErr != null) error(toolErr)
                }
                .onSuccess { refreshSolutions() }
                .onFailure { context.emitError("Couldn't delete solution: ${it.message ?: "?"}") }
        }
    }

    fun loadSolutionDetails(solutionId: String) {
        val active = context.activeClient()
        if (active == null) {
            _solutionDetails.value = UiData.Error(context.notConnectedMessage())
            return
        }
        _solutionDetails.value = UiData.Loading
        val params = buildJsonObject { put("solution_id", solutionId) }
        scope.launch {
            runCatching { active.call("remote.solutions.get", params) }
                .mapCatching { resp -> resp.decodeResultOrThrow(GetSolutionResult.serializer()) }
                .onSuccess { _solutionDetails.value = UiData.Loaded(it) }
                .onFailure { _solutionDetails.value = UiData.Error(it.message ?: "unknown error") }
        }
    }

    /**
     * Refresh the registry-projects catalog. Errors surface through the
     * shared error channel but leave the previously-loaded catalog in
     * place so the picker degrades gracefully on a transient failure.
     */
    fun refreshCatalog() {
        val active = context.activeClient()
        if (active == null) {
            context.emitError(context.notConnectedMessage())
            return
        }
        scope.launch {
            runCatching { active.catalogList() }
                .onSuccess { _catalog.value = it.projects }
                .onFailure { context.emitError("Couldn't load projects: ${it.message ?: "?"}") }
        }
    }

    /**
     * Remove a project from the registry (catalog). On success the catalog
     * is refreshed so it disappears from pickers. The server refuses while
     * any solution still uses it — that rejection is surfaced via the
     * shared error channel (listing the referencing solutions).
     */
    fun removeCatalogProject(catalogId: String) {
        val active = context.activeClient()
        if (active == null) {
            context.emitError(context.notConnectedMessage())
            return
        }
        scope.launch {
            runCatching { active.catalogRemove(catalogId) }
                .onSuccess { refreshCatalog() }
                .onFailure { context.emitError("Couldn't remove from catalog: ${it.message ?: "?"}") }
        }
    }

    /**
     * Add an existing catalog project to [solutionId]. Seeds an optimistic
     * ghost-row entry in [memberAdds] so the UI shows progress immediately;
     * real progress + completion arrive via the `solution_member_add_*`
     * notification handlers below. On RPC failure the ghost row is marked
     * with the error rather than silently dropped.
     */
    fun addMemberFromCatalog(solutionId: String, catalogId: String) {
        val active = context.activeClient()
        if (active == null) {
            context.emitError(context.notConnectedMessage())
            return
        }
        val key = solutionId to catalogId
        _memberAdds.value = _memberAdds.value + (key to MemberAddProgress(solutionId, catalogId))
        scope.launch {
            runCatching { active.addMember(solutionId, catalogId) }
                .onFailure { err ->
                    _memberAdds.value = _memberAdds.value +
                        (key to MemberAddProgress(solutionId, catalogId, error = err.message ?: "?"))
                    context.emitError("Couldn't add project: ${err.message ?: "?"}")
                }
        }
    }

    /**
     * Create a new empty (non-git) project named [name] inside
     * [solutionId]. Synchronous server-side — on success we re-fetch the
     * open solution so the new member appears immediately.
     */
    fun createEmptyMember(solutionId: String, name: String) {
        val active = context.activeClient()
        if (active == null) {
            context.emitError(context.notConnectedMessage())
            return
        }
        scope.launch {
            runCatching { active.addEmptyMember(solutionId, name) }
                .onSuccess {
                    loadSolutionDetails(solutionId)
                    // Now that the solution has a project, it can be opened
                    // on the desktop — surface it there as a running solution.
                    openOnDesktop(solutionId)
                }
                .onFailure { context.emitError("Couldn't create project: ${it.message ?: "?"}") }
        }
    }

    /**
     * Remove a member from [solutionId] (config-only on the server — the
     * worktree directory is left on disk). Refreshes the open solution
     * detail + the list so the member count and rows update.
     */
    fun removeMember(solutionId: String, catalogId: String) {
        val active = context.activeClient()
        if (active == null) {
            context.emitError(context.notConnectedMessage())
            return
        }
        scope.launch {
            runCatching { active.removeMember(solutionId, catalogId) }
                .onSuccess {
                    loadSolutionDetails(solutionId)
                    refreshSolutions()
                }
                .onFailure { context.emitError("Couldn't remove project: ${it.message ?: "?"}") }
        }
    }

    /**
     * Create a Solution named [name], then populate it: clone each catalog
     * project in [catalogIds] (async, surfaced as ghost rows) and create an
     * empty project for each name in [emptyNames] (synchronous). All
     * selections are optional — an empty Solution is valid. [onCreated] is
     * invoked with the new solution id once creation succeeds (before the
     * member-adds finish) so the caller can navigate straight into it.
     */
    fun createSolutionWith(
        name: String,
        catalogIds: List<String> = emptyList(),
        emptyNames: List<String> = emptyList(),
        onCreated: (solutionId: String) -> Unit = {},
    ) {
        val active = context.activeClient()
        if (active == null) {
            context.emitError(context.notConnectedMessage())
            return
        }
        val params = buildJsonObject { put("name", name) }
        scope.launch {
            runCatching {
                val resp = active.call("remote.solutions.create", params)
                resp.decodeResultOrThrow(CreateSolutionResult.serializer()).solutionId
            }
                .onSuccess { solutionId ->
                    refreshSolutions()
                    onCreated(solutionId)
                    // NOTE: don't open here — a brand-new solution has no
                    // members yet and the desktop can only open a solution
                    // that has at least one project. The open happens once
                    // the first project is added (see [createEmptyMember] /
                    // [onMemberAddCompleted]).
                    // Fan out the member-adds against the freshly-created
                    // solution. Catalog clones seed ghost rows; empty
                    // projects resolve synchronously and refresh detail.
                    for (catalogId in catalogIds) {
                        addMemberFromCatalog(solutionId, catalogId)
                    }
                    for (emptyName in emptyNames) {
                        createEmptyMember(solutionId, emptyName)
                    }
                }
                .onFailure { context.emitError("Couldn't create solution: ${it.message ?: "?"}") }
        }
    }

    /**
     * Open [solutionId] on the desktop without requesting focus. Best-effort:
     * failures (no active workspace, transient wire error) are non-critical
     * — the solution still exists and can be opened from the desktop — so we
     * surface them quietly rather than as a user-facing error.
     */
    private fun openOnDesktop(solutionId: String) {
        val active = context.activeClient() ?: return
        val params = buildJsonObject {
            put("solution_id", solutionId)
            put("focus", false)
        }
        scope.launch {
            runCatching { active.call("remote.solutions.open", params) }
                .onFailure { android.util.Log.w("SolutionStore", "open on desktop failed: ${it.message}") }
        }
    }

    // ---------------------------------------------------------------------
    // SolutionNotificationRouter — invoked from SessionListStore's single
    // notifications collector (wired by MainViewModel). Keep these cheap;
    // they run on the collector coroutine.
    // ---------------------------------------------------------------------

    override fun onMemberAddProgress(payload: MemberAddProgressPayload) {
        val key = payload.solutionId to payload.catalogId
        _memberAdds.value = _memberAdds.value + (key to MemberAddProgress(
            solutionId = payload.solutionId,
            catalogId = payload.catalogId,
            percent = payload.percent,
            stage = payload.stage,
        ))
    }

    override fun onMemberAddCompleted(payload: MemberAddCompletedPayload) {
        val key = payload.solutionId to payload.catalogId
        if (payload.error == null) {
            // Success — drop the ghost row; the member now exists. The
            // accompanying `solution_changed` event refreshes the detail.
            _memberAdds.value = _memberAdds.value - key
            // The solution now has a (cloned) project — openable on the
            // desktop, so surface it there as a running solution.
            openOnDesktop(payload.solutionId)
        } else {
            // Keep the row but mark it failed so the user sees why.
            _memberAdds.value = _memberAdds.value + (key to MemberAddProgress(
                solutionId = payload.solutionId,
                catalogId = payload.catalogId,
                error = payload.error,
            ))
        }
    }

    override fun onSolutionChanged() {
        // A solution mutated server-side (member added/removed/created).
        // Refresh the list and, if a detail is currently shown, re-fetch
        // it so the member list reflects the change.
        refreshSolutions()
        (_solutionDetails.value as? UiData.Loaded)?.value?.solution?.id?.let {
            loadSolutionDetails(it)
        }
    }
}

/**
 * Snapshot of one in-flight (or failed) member-add for the ghost-row UI.
 * [percent] is null for the indeterminate phase or before the first
 * progress tick; [error] is non-null only on a failed add.
 */
data class MemberAddProgress(
    val solutionId: String,
    val catalogId: String,
    val percent: Int? = null,
    val stage: String? = null,
    val error: String? = null,
)

/**
 * Callback surface [SessionListStore]'s consolidated collector uses to
 * forward solution member-add + change notifications to [SolutionStore].
 * Mirrors [DetailNotificationRouter]; wired by the coordinator after both
 * stores are constructed.
 */
internal interface SolutionNotificationRouter {
    fun onMemberAddProgress(payload: MemberAddProgressPayload)
    fun onMemberAddCompleted(payload: MemberAddCompletedPayload)
    fun onSolutionChanged()
}
