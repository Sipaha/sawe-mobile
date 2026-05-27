package ru.sipaha.spkremote.app.vm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.sipaha.spkremote.core.SessionStateDto
import ru.sipaha.spkremote.core.SolutionSummary

sealed interface WorkspaceUiState {
    object Loading : WorkspaceUiState
    data class Loaded(val snapshot: WorkspaceSnapshotVM, val stale: Boolean) : WorkspaceUiState
    data class Error(val message: String) : WorkspaceUiState
}

data class WorkspaceSnapshotVM(
    val seq: Long,
    val solutions: List<OpenSolutionVM>,
)

data class OpenSolutionVM(
    val id: String,
    val name: String,
    val memberCount: Int,
    val sessions: List<OpenSessionVM>,
)

data class OpenSessionVM(
    val id: String,
    val title: String,
    val state: SessionStateDto,
    val lastActivityAt: Long,
    val totalTokens: Long?,
    val maxTokens: Long?,
)

data class ClosedSolutionRow(
    val id: String,
    val name: String,
    val memberCount: Int,
    val lastOpenedAt: String?,
)

/**
 * WorkspaceStore — the mobile snapshot of the desktop's open workspace.
 *
 * Owns:
 * - `state` — the live snapshot with strong consistency via the `seq` protocol.
 * - `closedSolutions` — lazy picker contents (refetched on sheet-open).
 *
 * Plan-1 wire surface consumed:
 * - `workspace.snapshot` — bulk RPC for cold start + resync.
 * - `workspace.list_solutions(open: false)` — picker query.
 * - `workspace.open_solution / close_solution / open_session / close_session` —
 *   lifecycle RPCs; called from `MainViewModel` wrappers. Optimistic UI: snapshot
 *   mutated locally first, `lastAppliedSeq` advanced only when the matching
 *   delta arrives.
 * - `workspace.*` notifications — applied to the snapshot via [onNotification].
 *
 * Consistency rules:
 * - Strongly consistent fields (presence, open, state, title) — sequenced via
 *   `seq` per delta + bulk-refetch on gap / connect / foreground / pull.
 * - Eventually consistent fields (last_activity_at, total_tokens, max_tokens)
 *   — patched out-of-band via [WorkspaceSessionMetricsChangedPayload]; never
 *   triggers gap detection.
 */
/**
 * Minimal client surface consumed by [WorkspaceStore].
 * C3 will replace this with the real interface from `:core`.
 */
interface WorkspaceStoreClient {
    suspend fun fetchSnapshot(): WorkspaceSnapshotVM
}

class WorkspaceStore(
    private val client: WorkspaceStoreClient,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    private val _state = MutableStateFlow<WorkspaceUiState>(WorkspaceUiState.Loading)
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()

    private val _closedSolutions = MutableStateFlow<UiData<List<ClosedSolutionRow>>>(UiData.Loading)
    val closedSolutions: StateFlow<UiData<List<ClosedSolutionRow>>> = _closedSolutions.asStateFlow()

    suspend fun refresh() {
        val snap = client.fetchSnapshot()
        _state.value = WorkspaceUiState.Loaded(snap, stale = false)
    }
}
