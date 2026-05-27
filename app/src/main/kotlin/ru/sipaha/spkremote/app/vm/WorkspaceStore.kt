package ru.sipaha.spkremote.app.vm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sipaha.spkremote.core.SessionStateDto
import ru.sipaha.spkremote.core.SessionSummary
import ru.sipaha.spkremote.core.SolutionSummary
import ru.sipaha.spkremote.core.WorkspaceSessionClosedPayload
import ru.sipaha.spkremote.core.WorkspaceSessionDeletedPayload
import ru.sipaha.spkremote.core.WorkspaceSessionMetricsChangedPayload
import ru.sipaha.spkremote.core.WorkspaceSessionOpenedPayload
import ru.sipaha.spkremote.core.WorkspaceSessionStateChangedPayload
import ru.sipaha.spkremote.core.WorkspaceSolutionClosedPayload
import ru.sipaha.spkremote.core.WorkspaceSolutionDeletedPayload
import ru.sipaha.spkremote.core.WorkspaceSolutionOpenedPayload

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
 * Minimal client surface consumed by [WorkspaceStore]. Implemented by
 * [WorkspaceClientImpl] in production and a fake in tests.
 *
 * The four lifecycle calls return the server-assigned monotonic `seq`
 * (via the [ru.sipaha.spkremote.core.WorkspaceSeqAck] wire shape). The
 * caller uses that to reconcile optimistic mutations against the
 * matching delta — C6 wires the optimistic UI; C5 only ships the wire
 * surface so the picker / open-flow code paths in D can compile.
 */
interface WorkspaceClient {
    suspend fun fetchSnapshot(): WorkspaceSnapshotVM
    suspend fun fetchClosedSolutions(): List<ClosedSolutionRow>
    suspend fun openSolution(id: String): Long
    suspend fun closeSolution(id: String): Long
    suspend fun openSession(id: String): Long
    suspend fun closeSession(id: String): Long
}

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
 * - `workspace.*` notifications — applied to the snapshot via the `onXxx` hooks.
 *
 * Consistency rules:
 * - Strongly consistent fields (presence, open, state, title) — sequenced via
 *   `seq` per delta + bulk-refetch on gap / connect / foreground / pull.
 * - Eventually consistent fields (last_activity_at, total_tokens, max_tokens)
 *   — patched out-of-band via [WorkspaceSessionMetricsChangedPayload]; never
 *   triggers gap detection.
 *
 * Sequenced delta protocol:
 * - Each lifecycle notification carries a monotonically increasing `seq`.
 * - `seq == lastAppliedSeq + 1` → apply, advance.
 * - `seq <= lastAppliedSeq` → drop (duplicate / replay).
 * - `seq > lastAppliedSeq + 1` → gap; buffer, trigger bulk resync.
 * - Buffer is bounded (256) — overflow forces a resync and clears buffer.
 */
class WorkspaceStore(
    private val client: WorkspaceClient,
    private val scope: CoroutineScope,
) : WorkspaceNotificationRouter {
    private val _state = MutableStateFlow<WorkspaceUiState>(WorkspaceUiState.Loading)
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()

    private val _closedSolutions = MutableStateFlow<UiData<List<ClosedSolutionRow>>>(UiData.Loading)
    val closedSolutions: StateFlow<UiData<List<ClosedSolutionRow>>> = _closedSolutions.asStateFlow()

    private val snapshotMutex = Mutex()
    private val pending = ArrayDeque<SequencedDelta>()
    private var resyncInFlight = false

    // ---- public API ----

    suspend fun refresh() = snapshotMutex.withLock {
        runResyncLocked()
    }

    fun onSolutionOpened(seq: Long, solution: SolutionSummary?, sessions: List<SessionSummary>) {
        applyOrBufferSequenced(SequencedDelta.SolutionOpened(seq, solution, sessions))
    }

    fun onSolutionClosed(seq: Long, solutionId: String) {
        applyOrBufferSequenced(SequencedDelta.SolutionClosed(seq, solutionId))
    }

    fun onSolutionDeleted(seq: Long, solutionId: String) {
        applyOrBufferSequenced(SequencedDelta.SolutionDeleted(seq, solutionId))
    }

    fun onSessionOpened(seq: Long, solutionId: String, session: SessionSummary) {
        applyOrBufferSequenced(SequencedDelta.SessionOpened(seq, solutionId, session))
    }

    fun onSessionClosed(seq: Long, solutionId: String, sessionId: String) {
        applyOrBufferSequenced(SequencedDelta.SessionClosed(seq, solutionId, sessionId))
    }

    fun onSessionDeleted(seq: Long, solutionId: String, sessionId: String) {
        applyOrBufferSequenced(SequencedDelta.SessionDeleted(seq, solutionId, sessionId))
    }

    fun onSessionStateChanged(
        seq: Long, solutionId: String, sessionId: String, state: SessionStateDto,
    ) {
        applyOrBufferSequenced(SequencedDelta.SessionStateChanged(seq, solutionId, sessionId, state))
    }

    // ---- WorkspaceNotificationRouter — typed-payload fan-out from the
    // single shared collector in SessionListStore. Each one unpacks into the
    // existing split-arg public method above. ----

    override fun onSolutionOpened(payload: WorkspaceSolutionOpenedPayload) {
        onSolutionOpened(payload.seq, payload.solution, payload.sessions)
    }

    override fun onSolutionClosed(payload: WorkspaceSolutionClosedPayload) {
        onSolutionClosed(payload.seq, payload.solutionId)
    }

    override fun onSolutionDeleted(payload: WorkspaceSolutionDeletedPayload) {
        onSolutionDeleted(payload.seq, payload.solutionId)
    }

    override fun onSessionOpened(payload: WorkspaceSessionOpenedPayload) {
        onSessionOpened(payload.seq, payload.solutionId, payload.session)
    }

    override fun onSessionClosed(payload: WorkspaceSessionClosedPayload) {
        onSessionClosed(payload.seq, payload.solutionId, payload.sessionId)
    }

    override fun onSessionDeleted(payload: WorkspaceSessionDeletedPayload) {
        onSessionDeleted(payload.seq, payload.solutionId, payload.sessionId)
    }

    override fun onSessionStateChanged(payload: WorkspaceSessionStateChangedPayload) {
        onSessionStateChanged(payload.seq, payload.solutionId, payload.sessionId, payload.state)
    }

    override fun onSessionMetricsChanged(payload: WorkspaceSessionMetricsChangedPayload) {
        onSessionMetricsChanged(
            sessionId = payload.sessionId,
            lastActivityAt = payload.lastActivityAt,
            totalTokens = payload.totalTokens,
            maxTokens = payload.maxTokens,
        )
    }

    fun onSessionMetricsChanged(
        sessionId: String, lastActivityAt: Long?, totalTokens: Long?, maxTokens: Long?
    ) {
        scope.launch {
            snapshotMutex.withLock {
                val cur = _state.value as? WorkspaceUiState.Loaded ?: return@withLock
                val newSolutions = cur.snapshot.solutions.map { sol ->
                    val sIdx = sol.sessions.indexOfFirst { it.id == sessionId }
                    if (sIdx < 0) sol else sol.copy(
                        sessions = sol.sessions.toMutableList().also {
                            it[sIdx] = it[sIdx].copy(
                                lastActivityAt = lastActivityAt ?: it[sIdx].lastActivityAt,
                                totalTokens = totalTokens ?: it[sIdx].totalTokens,
                                maxTokens = maxTokens ?: it[sIdx].maxTokens,
                            )
                        }
                    )
                }
                _state.value = WorkspaceUiState.Loaded(
                    cur.snapshot.copy(solutions = newSolutions), stale = false,
                )
            }
        }
    }

    suspend fun refreshClosedSolutions() {
        _closedSolutions.value = UiData.Loading
        _closedSolutions.value = UiData.Loaded(client.fetchClosedSolutions())
    }

    // ---- internals ----

    private fun applyOrBufferSequenced(delta: SequencedDelta) {
        scope.launch {
            snapshotMutex.withLock {
                val cur = _state.value as? WorkspaceUiState.Loaded
                if (cur == null) {
                    pending.addLastBounded(delta)
                    return@withLock
                }
                val expected = cur.snapshot.seq + 1
                when {
                    delta.seq <= cur.snapshot.seq -> return@withLock // duplicate
                    delta.seq == expected -> {
                        val next = applyDelta(cur.snapshot, delta)
                        _state.value = WorkspaceUiState.Loaded(next, stale = false)
                    }
                    else -> {
                        pending.addLastBounded(delta)
                        if (!resyncInFlight) runResyncLocked()
                    }
                }
            }
        }
    }

    private suspend fun runResyncLocked() {
        resyncInFlight = true
        try {
            // Mark stale (keep showing old snapshot) instead of flicking to Loading
            // if we already have one. Cold start has no snapshot — show Loading.
            val cur = _state.value as? WorkspaceUiState.Loaded
            if (cur != null) {
                _state.value = WorkspaceUiState.Loaded(cur.snapshot, stale = true)
            }

            // Loop: re-fetch as long as we make no progress on a gap.
            // Each iteration may drain the contiguous prefix of pending; if a
            // gap still remains after replay, we re-fetch immediately rather
            // than leaving stale state until the next incoming delta.
            while (true) {
                val snap = client.fetchSnapshot()
                var s = snap
                // Replay buffered deltas with seq > snap.seq, contiguous run only.
                val drained = pending.toList()
                pending.clear()
                val sorted = drained.filter { it.seq > s.seq }.sortedBy { it.seq }
                var i = 0
                while (i < sorted.size && sorted[i].seq == s.seq + 1) {
                    s = applyDelta(s, sorted[i])
                    i += 1
                }
                val leftover = sorted.drop(i)
                if (leftover.isEmpty()) {
                    // Buffer fully drained — we're done.
                    _state.value = WorkspaceUiState.Loaded(s, stale = false)
                    break
                }
                // Gap remains: re-queue leftover and loop to fetch again.
                // (The buffer may also have received new deltas since we drained
                // it above — they're already in pending; leftover goes back too.)
                pending.addAll(0, leftover)
                _state.value = WorkspaceUiState.Loaded(s, stale = true)
                // Loop continues — resyncInFlight stays true throughout.
            }
        } finally {
            resyncInFlight = false
        }
    }

    private fun ArrayDeque<SequencedDelta>.addLastBounded(d: SequencedDelta) {
        if (size >= 256) {
            // Overflow — drop the buffer and force a resync at next chance.
            // Guard: addLastBounded is always called under snapshotMutex, so
            // reading resyncInFlight here is safe.  Skip the launch if a
            // resync is already in flight to avoid racing concurrent writes.
            clear()
            if (!resyncInFlight) {
                scope.launch { snapshotMutex.withLock { runResyncLocked() } }
            }
        }
        addLast(d)
    }

    private sealed interface SequencedDelta {
        val seq: Long
        data class SolutionOpened(override val seq: Long, val solution: SolutionSummary?, val sessions: List<SessionSummary>) : SequencedDelta
        data class SolutionClosed(override val seq: Long, val solutionId: String) : SequencedDelta
        data class SolutionDeleted(override val seq: Long, val solutionId: String) : SequencedDelta
        data class SessionOpened(override val seq: Long, val solutionId: String, val session: SessionSummary) : SequencedDelta
        data class SessionClosed(override val seq: Long, val solutionId: String, val sessionId: String) : SequencedDelta
        data class SessionDeleted(override val seq: Long, val solutionId: String, val sessionId: String) : SequencedDelta
        data class SessionStateChanged(override val seq: Long, val solutionId: String, val sessionId: String, val state: SessionStateDto) : SequencedDelta
    }

    private fun applyDelta(snap: WorkspaceSnapshotVM, d: SequencedDelta): WorkspaceSnapshotVM {
        val newSolutions = snap.solutions.toMutableList()
        when (d) {
            is SequencedDelta.SolutionOpened -> {
                val sol = d.solution ?: return snap.copy(seq = d.seq)
                if (newSolutions.none { it.id == sol.id }) {
                    newSolutions.add(OpenSolutionVM(
                        id = sol.id, name = sol.name, memberCount = sol.memberCount,
                        sessions = d.sessions.map { it.toVM() }
                    ))
                }
            }
            is SequencedDelta.SolutionClosed, is SequencedDelta.SolutionDeleted -> {
                val id = when (d) {
                    is SequencedDelta.SolutionClosed -> d.solutionId
                    is SequencedDelta.SolutionDeleted -> d.solutionId
                }
                newSolutions.removeAll { it.id == id }
            }
            is SequencedDelta.SessionOpened -> {
                val idx = newSolutions.indexOfFirst { it.id == d.solutionId }
                if (idx >= 0) {
                    val sol = newSolutions[idx]
                    if (sol.sessions.none { it.id == d.session.id }) {
                        newSolutions[idx] = sol.copy(sessions = sol.sessions + d.session.toVM())
                    }
                }
            }
            is SequencedDelta.SessionClosed, is SequencedDelta.SessionDeleted -> {
                val (solId, sesId) = when (d) {
                    is SequencedDelta.SessionClosed -> d.solutionId to d.sessionId
                    is SequencedDelta.SessionDeleted -> d.solutionId to d.sessionId
                }
                val idx = newSolutions.indexOfFirst { it.id == solId }
                if (idx >= 0) {
                    val sol = newSolutions[idx]
                    newSolutions[idx] = sol.copy(sessions = sol.sessions.filterNot { it.id == sesId })
                }
            }
            is SequencedDelta.SessionStateChanged -> {
                val idx = newSolutions.indexOfFirst { it.id == d.solutionId }
                if (idx >= 0) {
                    val sol = newSolutions[idx]
                    val sIdx = sol.sessions.indexOfFirst { it.id == d.sessionId }
                    if (sIdx >= 0) {
                        val updated = sol.sessions[sIdx].copy(state = d.state)
                        newSolutions[idx] = sol.copy(sessions = sol.sessions.toMutableList().also { it[sIdx] = updated })
                    }
                }
            }
        }
        return WorkspaceSnapshotVM(seq = d.seq, solutions = newSolutions)
    }
}

private fun SessionSummary.toVM(): OpenSessionVM = OpenSessionVM(
    id = id, title = title, state = state,
    lastActivityAt = lastActivityAt,
    totalTokens = totalTokens, maxTokens = maxTokens,
)

/**
 * Typed fan-out for the 8 `workspace.*` notifications dispatched by the
 * single shared collector in [SessionListStore]. Mirrors the
 * [SolutionNotificationRouter] / [DetailNotificationRouter] pattern:
 * [SessionListStore] owns the wire subscription, decodes the payload,
 * and delegates here. [WorkspaceStore] is the canonical implementation.
 *
 * `seq` ordering + gap detection is internal to the store — the router
 * just forwards typed payloads.
 */
internal interface WorkspaceNotificationRouter {
    fun onSolutionOpened(payload: WorkspaceSolutionOpenedPayload)
    fun onSolutionClosed(payload: WorkspaceSolutionClosedPayload)
    fun onSolutionDeleted(payload: WorkspaceSolutionDeletedPayload)
    fun onSessionOpened(payload: WorkspaceSessionOpenedPayload)
    fun onSessionClosed(payload: WorkspaceSessionClosedPayload)
    fun onSessionDeleted(payload: WorkspaceSessionDeletedPayload)
    fun onSessionStateChanged(payload: WorkspaceSessionStateChangedPayload)
    fun onSessionMetricsChanged(payload: WorkspaceSessionMetricsChangedPayload)
}
