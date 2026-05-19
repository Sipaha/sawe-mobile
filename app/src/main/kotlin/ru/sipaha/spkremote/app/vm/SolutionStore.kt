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
import ru.sipaha.spkremote.core.GetSolutionResult
import ru.sipaha.spkremote.core.ListSolutionsResult
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
) {
    private val _solutions = MutableStateFlow<UiData<List<SolutionSummary>>>(UiData.Loading)
    val solutions: StateFlow<UiData<List<SolutionSummary>>> = _solutions.asStateFlow()

    private val _solutionDetails = MutableStateFlow<UiData<GetSolutionResult>>(UiData.Loading)
    val solutionDetails: StateFlow<UiData<GetSolutionResult>> = _solutionDetails.asStateFlow()

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
}
