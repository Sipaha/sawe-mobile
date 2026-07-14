package ru.sipaha.sawe.app.vm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.sipaha.sawe.core.CatalogProjectInfo
import ru.sipaha.sawe.core.GetSolutionResult
import ru.sipaha.sawe.core.MemberAddCompletedPayload
import ru.sipaha.sawe.core.MemberAddProgressPayload

/**
 * Project-registry catalog + per-solution member-management state for
 * the `SolutionProjectsScreen`. Carved out of the legacy `SolutionStore`
 * during G1 when the unified workspace took over the solutions-list
 * surface: only the catalog / member / solution-detail bits that
 * `SolutionProjectsScreen` still consumes survive here.
 *
 * What this store owns:
 *   - [solutionDetails] — the currently-open solution detail (members
 *     list backing the projects screen), loaded on demand.
 *   - [catalog] — registry projects available to add to a solution,
 *     refreshed lazily on picker open via [refreshCatalog].
 *   - [memberAdds] — in-flight (and recently-failed) catalog member-adds
 *     for the ghost-row UI; driven by the `solution_member_add_*`
 *     notification stream via [SolutionNotificationRouter].
 *
 * What it intentionally does NOT own (formerly on `SolutionStore`):
 *   - solutions list / list cache hydration — replaced by [WorkspaceStore],
 *   - `create_solution` / `create_solution_with` — replaced by F-phase
 *     workspace flows (not yet wired; the F1 plan tracks the dialog),
 *   - "refresh solutions" — workspace mirror is the authoritative list.
 */
internal class CatalogStore(
    private val scope: CoroutineScope,
    private val context: ConnectionContext,
) : SolutionNotificationRouter {
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
        MutableStateFlow<Map<Pair<Long, Long>, MemberAddProgress>>(emptyMap())
    val memberAdds: StateFlow<Map<Pair<Long, Long>, MemberAddProgress>> =
        _memberAdds.asStateFlow()

    /** Reset on tear-down (server switch / disconnect). */
    fun reset() {
        _solutionDetails.value = UiData.Loading
        _catalog.value = emptyList()
        _memberAdds.value = emptyMap()
    }

    /**
     * Create a new (empty) solution named [name] on the server. The
     * backend `solutions.create` tool auto-opens the new solution
     * (mark_open + sequenced `workspace.solution_opened` notification)
     * so the workspace mirror picks it up via the standard delta
     * applier — no client-side chain to `workspace.open_solution`
     * needed.  Failures surface through the shared error channel.
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
                .onFailure { context.emitError("Couldn't create solution: ${it.message ?: "?"}") }
        }
    }

    /**
     * Delete the solution [solutionId] on the server. Failures surface
     * through the shared error channel. The workspace mirror picks up the
     * removal via the `workspace.solution_deleted` notification — no
     * explicit list refresh needed.
     */
    fun deleteSolution(solutionId: Long) {
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
                .onFailure { context.emitError("Couldn't delete solution: ${it.message ?: "?"}") }
        }
    }

    fun loadSolutionDetails(solutionId: Long) {
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
                .onSuccess {
                    _solutionDetails.value = UiData.Loaded(it)
                    reconcileMemberAdds(solutionId, it.solution.members.map { m -> m.catalogId })
                }
                .onFailure { _solutionDetails.value = UiData.Error(it.message ?: "unknown error") }
        }
    }

    /**
     * Drop in-flight/failed member-add ghost rows whose project has already
     * landed as a member of [solutionId]. Self-heals a stuck ghost when the
     * `solution_member_add_completed` event was missed (e.g. a transient
     * disconnect or app-background mid-clone) — the member list is the
     * authoritative "the add finished" signal. A catalog clone only becomes
     * a member *after* the clone completes, so a landed catalog id always
     * means its ghost is stale.
     */
    private fun reconcileMemberAdds(solutionId: Long, landedCatalogIds: List<Long>) {
        if (landedCatalogIds.isEmpty()) return
        val landed = landedCatalogIds.toSet()
        val current = _memberAdds.value
        val pruned = current.filterNot { (key, _) ->
            key.first == solutionId && key.second in landed
        }
        if (pruned.size != current.size) _memberAdds.value = pruned
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
    fun removeCatalogProject(catalogId: Long) {
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
    fun addMemberFromCatalog(solutionId: Long, catalogId: Long) {
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
    fun createEmptyMember(solutionId: Long, name: String) {
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
     * detail so the member count and rows update; the workspace mirror
     * picks up the change via the `solution_changed` notification.
     */
    fun removeMember(solutionId: Long, catalogId: Long) {
        val active = context.activeClient()
        if (active == null) {
            context.emitError(context.notConnectedMessage())
            return
        }
        scope.launch {
            runCatching { active.removeMember(solutionId, catalogId) }
                .onSuccess { loadSolutionDetails(solutionId) }
                .onFailure { context.emitError("Couldn't remove project: ${it.message ?: "?"}") }
        }
    }

    /**
     * Open [solutionId] on the desktop without requesting focus. Best-effort:
     * failures (no active workspace, transient wire error) are non-critical
     * — the solution still exists and can be opened from the desktop — so we
     * surface them quietly rather than as a user-facing error.
     */
    private fun openOnDesktop(solutionId: Long) {
        val active = context.activeClient() ?: return
        val params = buildJsonObject {
            put("solution_id", solutionId)
            put("focus", false)
        }
        scope.launch {
            runCatching { active.call("remote.solutions.open", params) }
                .onFailure { android.util.Log.w("CatalogStore", "open on desktop failed: ${it.message}") }
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
        // Re-fetch the open detail (if any) so the member list reflects
        // the change. The workspace mirror has its own dedicated
        // notification stream for the open-set; we don't touch it here.
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
    val solutionId: Long,
    val catalogId: Long,
    val percent: Int? = null,
    val stage: String? = null,
    val error: String? = null,
)

/**
 * Callback surface [SessionListStore]'s consolidated collector uses to
 * forward solution member-add + change notifications to [CatalogStore].
 * Mirrors [DetailNotificationRouter]; wired by the coordinator after both
 * stores are constructed.
 */
internal interface SolutionNotificationRouter {
    fun onMemberAddProgress(payload: MemberAddProgressPayload)
    fun onMemberAddCompleted(payload: MemberAddCompletedPayload)
    fun onSolutionChanged()
}
