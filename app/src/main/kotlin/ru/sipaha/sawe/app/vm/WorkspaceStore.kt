package ru.sipaha.sawe.app.vm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sipaha.sawe.core.SessionStateDto
import ru.sipaha.sawe.core.SessionSummary
import ru.sipaha.sawe.core.SolutionSummary
import ru.sipaha.sawe.core.WorkspaceSessionClosedPayload
import ru.sipaha.sawe.core.WorkspaceSessionDeletedPayload
import ru.sipaha.sawe.core.WorkspaceSessionMetricsChangedPayload
import ru.sipaha.sawe.core.WorkspaceSessionOpenedPayload
import ru.sipaha.sawe.core.WorkspaceSessionStateChangedPayload
import ru.sipaha.sawe.core.WorkspaceSolutionClosedPayload
import ru.sipaha.sawe.core.WorkspaceSolutionDeletedPayload
import ru.sipaha.sawe.core.WorkspaceSolutionOpenedPayload

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
    val id: Long,
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
    val id: Long,
    val name: String,
    val memberCount: Int,
    val lastOpenedAt: String?,
)

/**
 * Minimal client surface consumed by [WorkspaceStore]. Implemented by
 * [WorkspaceClientImpl] in production and a fake in tests.
 *
 * The four lifecycle calls return the server-assigned monotonic `seq`
 * (via the [ru.sipaha.sawe.core.WorkspaceSeqAck] wire shape). The
 * caller uses that to reconcile optimistic mutations against the
 * matching delta — C6 wires the optimistic UI; C5 only ships the wire
 * surface so the picker / open-flow code paths in D can compile.
 */
interface WorkspaceClient {
    suspend fun fetchSnapshot(): WorkspaceSnapshotVM
    suspend fun fetchClosedSolutions(): List<ClosedSolutionRow>
    suspend fun openSolution(id: Long): Long
    suspend fun closeSolution(id: Long): Long
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

    // ---- Optimistic lifecycle mutations (C6) ----
    //
    // Each variant fires the wire RPC and (when meaningful) applies a
    // local snapshot mutation immediately so the UI reacts without
    // waiting for the round-trip + matching delta. On RPC failure we
    // attempt to roll back — but only if the snapshot hasn't already
    // moved on (a concurrent delta from the server is authoritative).
    //
    // Opens (`openSolutionOptimistic` / `openSessionOptimistic`) cannot
    // be applied optimistically because the local store doesn't have
    // the data needed to materialise a new row (sessions, member count,
    // titles). They rely on the server delta to surface the new row.

    suspend fun openSolutionOptimistic(id: Long) {
        // Opens are best applied via delta, not optimistic — we have no
        // local OpenSolutionVM data to splice in. Just fire the call.
        runCatching { client.openSolution(id) }
    }

    suspend fun closeSolutionOptimistic(id: Long) {
        val rollback = applyOptimistic { snap ->
            snap.copy(solutions = snap.solutions.filterNot { it.id == id })
        }
        runCatching { client.closeSolution(id) }
            .onFailure { rollback() }
    }

    suspend fun openSessionOptimistic(id: String) {
        // No-op locally — relies on server delta to surface the new session row.
        runCatching { client.openSession(id) }
    }

    suspend fun closeSessionOptimistic(id: String) {
        val rollback = applyOptimistic { snap ->
            snap.copy(solutions = snap.solutions.map { sol ->
                sol.copy(sessions = sol.sessions.filterNot { it.id == id })
            })
        }
        runCatching { client.closeSession(id) }
            .onFailure { rollback() }
    }

    /**
     * Apply [transform] to the current Loaded snapshot atomically and
     * return a rollback closure. If state isn't Loaded yet (cold start),
     * returns a no-op rollback — there's nothing to optimistically
     * mutate.
     *
     * The returned rollback only reverts if the snapshot still matches
     * our optimistic version; a concurrent delta may have moved the
     * store forward, in which case the delta is the new truth and we
     * must not undo it.
     */
    private suspend fun applyOptimistic(
        transform: (WorkspaceSnapshotVM) -> WorkspaceSnapshotVM,
    ): suspend () -> Unit {
        val before: WorkspaceSnapshotVM = snapshotMutex.withLock {
            val cur = _state.value as? WorkspaceUiState.Loaded
                ?: return@applyOptimistic { /* no-op: nothing to roll back */ }
            val transformed = transform(cur.snapshot)
            _state.value = WorkspaceUiState.Loaded(transformed, stale = cur.stale)
            cur.snapshot
        }
        return {
            snapshotMutex.withLock {
                val cur = _state.value as? WorkspaceUiState.Loaded ?: return@withLock
                // Only rollback if the current state is still our optimistic
                // version — a concurrent delta may have moved us forward; in
                // that case the delta is the new truth, don't undo it.
                if (cur.snapshot.solutions != before.solutions) {
                    _state.value = WorkspaceUiState.Loaded(before, stale = cur.stale)
                }
            }
        }
    }

    fun onSolutionOpened(seq: Long, solution: SolutionSummary?, sessions: List<SessionSummary>) {
        applyOrBufferSequenced(SequencedDelta.SolutionOpened(seq, solution, sessions))
    }

    fun onSolutionClosed(seq: Long, solutionId: Long) {
        applyOrBufferSequenced(SequencedDelta.SolutionClosed(seq, solutionId))
    }

    fun onSolutionDeleted(seq: Long, solutionId: Long) {
        applyOrBufferSequenced(SequencedDelta.SolutionDeleted(seq, solutionId))
    }

    fun onSessionOpened(seq: Long, solutionId: Long, session: SessionSummary) {
        applyOrBufferSequenced(SequencedDelta.SessionOpened(seq, solutionId, session))
    }

    fun onSessionClosed(seq: Long, solutionId: Long, sessionId: String) {
        applyOrBufferSequenced(SequencedDelta.SessionClosed(seq, solutionId, sessionId))
    }

    fun onSessionDeleted(seq: Long, solutionId: Long, sessionId: String) {
        applyOrBufferSequenced(SequencedDelta.SessionDeleted(seq, solutionId, sessionId))
    }

    fun onSessionStateChanged(
        seq: Long, solutionId: Long, sessionId: String, state: SessionStateDto,
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
        _closedSolutions.value = UiData.Loaded(client.fetchClosedSolutions().sortedByLastOpenedDesc())
    }

    /**
     * Sort closed-solution rows by [ClosedSolutionRow.lastOpenedAt] descending
     * — most-recently-touched solutions first, nulls (no recorded activity)
     * sink to the bottom. `lastOpenedAt` is RFC3339 on the wire so byte-wise
     * string compare is equivalent to chronological compare; we use the raw
     * string to avoid parsing per element.
     */
    private fun List<ClosedSolutionRow>.sortedByLastOpenedDesc(): List<ClosedSolutionRow> =
        sortedWith(
            compareByDescending<ClosedSolutionRow> { it.lastOpenedAt != null }
                .thenByDescending { it.lastOpenedAt.orEmpty() },
        )

    /**
     * Drop every buffered sequenced delta. Called by [MainViewModel.onReconnected]
     * BEFORE the post-reconnect [refresh] because the desktop's
     * [WorkspaceEventCoordinator] resets its monotonic counter on every
     * process start — any delta we had buffered from the previous WS session
     * carries a `seq` that's gibberish against the new coordinator's stream
     * and can drive [runResyncLocked]'s replay loop into a permanent retry
     * (snapshot seq < buffered seq, gap never closes because the server
     * will never emit the missing intermediate seqs that "exist" only in
     * our pre-restart memory). Clearing on the reconnect edge keeps the
     * post-resync state derived entirely from the fresh snapshot + deltas
     * emitted from this point forward.
     */
    suspend fun clearBufferedDeltas() {
        snapshotMutex.withLock { pending.clear() }
    }

    /**
     * Optimistic local removal of a closed-solution row. Called by the picker
     * the moment the user taps Delete on an entry — the server later confirms
     * via the `workspace.solution_deleted` notification (which also runs
     * through this path inside [applyDelta]). Idempotent on a row that's
     * already gone, so the duplicate from the delta is harmless.
     */
    fun dropClosedSolutionRow(solutionId: Long) {
        val current = _closedSolutions.value as? UiData.Loaded ?: return
        val pruned = current.value.filterNot { it.id == solutionId }
        if (pruned.size != current.value.size) {
            _closedSolutions.value = UiData.Loaded(pruned)
        }
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
                // Catch RPC / transport failures here so a server error (e.g.
                // "method not found" against an older desktop, or a dropped
                // connection mid-fetch) degrades to UiData.Error rather than
                // bubbling up through viewModelScope and crashing the app.
                // We KEEP pending intact on failure — a subsequent successful
                // resync will replay them.
                val snap = try {
                    client.fetchSnapshot()
                } catch (t: Throwable) {
                    val message = t.message ?: t.javaClass.simpleName
                    if (_state.value !is WorkspaceUiState.Loaded) {
                        _state.value = WorkspaceUiState.Error(message)
                    }
                    return
                }
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
        data class SolutionClosed(override val seq: Long, val solutionId: Long) : SequencedDelta
        data class SolutionDeleted(override val seq: Long, val solutionId: Long) : SequencedDelta
        data class SessionOpened(override val seq: Long, val solutionId: Long, val session: SessionSummary) : SequencedDelta
        data class SessionClosed(override val seq: Long, val solutionId: Long, val sessionId: String) : SequencedDelta
        data class SessionDeleted(override val seq: Long, val solutionId: Long, val sessionId: String) : SequencedDelta
        data class SessionStateChanged(override val seq: Long, val solutionId: Long, val sessionId: String, val state: SessionStateDto) : SequencedDelta
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
                // Mirror the deletion into the closed-solutions picker list:
                // a deleted solution is gone from both surfaces. Closed-only
                // (not-deleted) transitions don't touch this list — the
                // server's next list_solutions(open=false) will populate it
                // when the user reopens the picker.
                if (d is SequencedDelta.SolutionDeleted) {
                    dropClosedSolutionRow(id)
                }
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
