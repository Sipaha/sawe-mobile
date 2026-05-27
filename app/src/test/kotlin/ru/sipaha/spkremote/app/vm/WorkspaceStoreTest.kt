package ru.sipaha.spkremote.app.vm

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.sipaha.spkremote.core.SessionStateDto
import ru.sipaha.spkremote.core.SolutionSummary

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceStoreTest {

    @Test
    fun cold_bulk_populates_snapshot_with_correct_seq() = runTest {
        val fakeClient = FakeWorkspaceClient(
            snapshotResult = WorkspaceSnapshotVM(
                seq = 7,
                solutions = listOf(
                    OpenSolutionVM(
                        id = "s1", name = "alpha", memberCount = 1,
                        sessions = listOf(
                            OpenSessionVM(
                                id = "se1", title = "session 1",
                                state = SessionStateDto.Idle,
                                lastActivityAt = 1000L,
                                totalTokens = null, maxTokens = null,
                            )
                        )
                    )
                )
            )
        )
        val store = WorkspaceStore(client = fakeClient, scope = backgroundScope)

        store.refresh()
        runCurrent()

        val state = store.state.value as WorkspaceUiState.Loaded
        assertEquals(7L, state.snapshot.seq)
        assertEquals(1, state.snapshot.solutions.size)
        assertEquals("s1", state.snapshot.solutions[0].id)
        assertEquals(false, state.stale)
    }

    @Test
    fun seq_plus_one_delta_applies_and_advances_lastAppliedSeq() = runTest {
        val fake = FakeWorkspaceClient(
            snapshotResult = WorkspaceSnapshotVM(
                seq = 5,
                solutions = listOf(OpenSolutionVM("s1", "a", 0, emptyList())),
            )
        )
        val store = WorkspaceStore(client = fake, scope = backgroundScope)
        store.refresh()
        runCurrent()

        // Solution s2 opens via delta.
        store.onSolutionOpened(
            seq = 6,
            solution = SolutionSummary(
                id = "s2", name = "beta", root = "/y", memberCount = 0,
                lastOpenedAt = null, open = true, mainWindowId = null,
            ),
            sessions = emptyList(),
        )
        runCurrent()

        val state = store.state.value as WorkspaceUiState.Loaded
        assertEquals(6L, state.snapshot.seq)
        assertEquals(2, state.snapshot.solutions.size)
    }

    @Test
    fun seq_lower_or_equal_delta_is_dropped_as_duplicate() = runTest {
        val fake = FakeWorkspaceClient(
            snapshotResult = WorkspaceSnapshotVM(
                seq = 5, solutions = emptyList(),
            )
        )
        val store = WorkspaceStore(client = fake, scope = backgroundScope)
        store.refresh()
        runCurrent()

        store.onSolutionClosed(seq = 5, solutionId = "s1")
        runCurrent()

        val state = store.state.value as WorkspaceUiState.Loaded
        assertEquals(5L, state.snapshot.seq, "duplicate seq must not advance")
    }

    @Test
    fun seq_gap_triggers_resync() = runTest {
        var snapshotCalls = 0
        val fake = object : FakeWorkspaceClient() {
            override suspend fun fetchSnapshot(): WorkspaceSnapshotVM {
                snapshotCalls += 1
                return WorkspaceSnapshotVM(
                    seq = if (snapshotCalls == 1) 5 else 10,
                    solutions = emptyList(),
                )
            }
        }
        val store = WorkspaceStore(client = fake, scope = backgroundScope)
        store.refresh()
        runCurrent()

        store.onSolutionOpened(seq = 8, solution = anyTestSolution("s1"), sessions = emptyList())
        runCurrent()

        assertEquals(2, snapshotCalls, "gap (8 > 5+1) must trigger resync")
        val state = store.state.value as WorkspaceUiState.Loaded
        assertEquals(10L, state.snapshot.seq)
    }

    /**
     * Non-contiguous replay test:
     *
     * Initial state: seq=5 (from call 1).
     * Delta seq=6 applies inline.
     * Delta seq=8 causes a gap → resync call 2 returns seq=5 (no server progress).
     * After replay the contiguous run is [6] (seq=5+1=6 applies), but seq=8 is
     * leftover (gap from 6 to 8). The loop re-fetches (call 3) returning seq=12.
     * Leftover [8] is now < 12, so it's filtered out and the store settles at seq=12.
     * snapshotCalls must be 3: initial refresh + gap-resync + leftover-gap-resync.
     */
    @Test
    fun non_contiguous_buffered_deltas_force_additional_resync() = runTest {
        var snapshotCalls = 0
        val fake = object : FakeWorkspaceClient() {
            override suspend fun fetchSnapshot(): WorkspaceSnapshotVM {
                snapshotCalls += 1
                return WorkspaceSnapshotVM(
                    seq = when (snapshotCalls) {
                        1 -> 5L   // cold start
                        2 -> 5L   // resync triggered by seq=8 gap — server still at 5
                        else -> 12L  // second resync resolves the gap
                    },
                    solutions = emptyList(),
                )
            }
        }
        val store = WorkspaceStore(client = fake, scope = backgroundScope)

        // Call 1: cold start
        store.refresh()
        runCurrent()
        assertEquals(1, snapshotCalls, "cold refresh should call fetchSnapshot once")

        // Delta seq=6: contiguous → applies inline (no resync)
        store.onSolutionOpened(seq = 6, solution = anyTestSolution("s1"), sessions = emptyList())
        runCurrent()
        assertEquals(1, snapshotCalls, "seq=6 is contiguous, no resync expected")

        // Delta seq=8: gap (6+1≠8) → triggers call 2.
        // After call 2 returns seq=5, replay drains pending: seq=6 applies (5→6),
        // seq=8 is leftover (gap). Loop re-fetches (call 3) returning seq=12.
        // seq=8 < 12 is filtered, store settles at 12.
        store.onSolutionOpened(seq = 8, solution = anyTestSolution("s2"), sessions = emptyList())
        runCurrent()

        assertEquals(3, snapshotCalls,
            "gap + non-contiguous leftover must trigger two extra fetchSnapshot calls (calls 2 and 3)")
        val state = store.state.value as WorkspaceUiState.Loaded
        assertEquals(12L, state.snapshot.seq, "store must settle at seq=12 after second resync")
        assertEquals(false, state.stale, "store must not be stale after settling")
    }

    /**
     * Leftover-in-pending test:
     *
     * Demonstrates that when the server makes NO progress (returns same seq on
     * every call), leftover deltas that can never be applied keep triggering
     * re-fetches. This test verifies that a single gap-delta (seq=8 when current
     * is seq=5) results in multiple fetch calls when the server stays at seq=5.
     *
     * We cap the fake at 3 calls to avoid an infinite loop, returning a high seq
     * on the final call so the store can settle.
     */
    @Test
    fun leftover_deltas_trigger_repeated_resync_until_gap_closes() = runTest {
        var snapshotCalls = 0
        val fake = object : FakeWorkspaceClient() {
            override suspend fun fetchSnapshot(): WorkspaceSnapshotVM {
                snapshotCalls += 1
                return WorkspaceSnapshotVM(
                    // calls 1 and 2 return seq=5 (no server progress),
                    // call 3 returns seq=9 which covers the gap (seq=8 ≤ 9)
                    seq = if (snapshotCalls < 3) 5L else 9L,
                    solutions = emptyList(),
                )
            }
        }
        val store = WorkspaceStore(client = fake, scope = backgroundScope)

        // Call 1: cold start
        store.refresh()
        runCurrent()

        // Delta seq=8: gap at seq=5 → resync (call 2, returns 5 → leftover [8] remains)
        // → loop re-fetches (call 3, returns 9 → [8] filtered, done).
        store.onSolutionOpened(seq = 8, solution = anyTestSolution("s1"), sessions = emptyList())
        runCurrent()

        assertEquals(3, snapshotCalls,
            "two extra fetches needed: first resync at seq=5 still has gap, second resolves it")
        val state = store.state.value as WorkspaceUiState.Loaded
        assertEquals(9L, state.snapshot.seq)
        assertEquals(false, state.stale)
    }

    @Test
    fun metrics_patch_updates_only_known_session_and_does_not_advance_seq() = runTest {
        val fake = FakeWorkspaceClient(
            snapshotResult = WorkspaceSnapshotVM(
                seq = 5,
                solutions = listOf(OpenSolutionVM("s1", "a", 0, listOf(
                    OpenSessionVM("se1", "t", SessionStateDto.Idle, 0L, null, null)
                )))
            )
        )
        val store = WorkspaceStore(client = fake, scope = backgroundScope)
        store.refresh(); advanceUntilIdle()

        store.onSessionMetricsChanged(sessionId = "se1", lastActivityAt = 7777L, totalTokens = 99L, maxTokens = null)
        store.onSessionMetricsChanged(sessionId = "UNKNOWN", lastActivityAt = 0L, totalTokens = 0L, maxTokens = null)
        runCurrent()

        val state = store.state.value as WorkspaceUiState.Loaded
        assertEquals(5L, state.snapshot.seq, "metrics must not advance seq")
        val ses = state.snapshot.solutions[0].sessions[0]
        assertEquals(7777L, ses.lastActivityAt)
        assertEquals(99L, ses.totalTokens)
    }

    @Test
    fun refresh_closed_solutions_populates_picker() = runTest {
        val fake = FakeWorkspaceClient(
            closedListResult = listOf(
                ClosedSolutionRow("c1", "frozen", 2, lastOpenedAt = null)
            ),
        )
        val store = WorkspaceStore(client = fake, scope = backgroundScope)
        store.refreshClosedSolutions()
        advanceUntilIdle()

        val list = (store.closedSolutions.value as UiData.Loaded).value
        assertEquals(1, list.size)
        assertEquals("c1", list[0].id)
    }
}

/** Minimal in-memory mock of the wire client. Fill in surface as needed. */
open class FakeWorkspaceClient(
    val snapshotResult: WorkspaceSnapshotVM = WorkspaceSnapshotVM(0, emptyList()),
    val closedListResult: List<ClosedSolutionRow> = emptyList(),
) : WorkspaceClient {
    override suspend fun fetchSnapshot(): WorkspaceSnapshotVM = snapshotResult
    override suspend fun fetchClosedSolutions(): List<ClosedSolutionRow> = closedListResult
    // Lifecycle methods are exercised by C6 — C5 ships only the wire surface,
    // and the eight existing tests don't drive them. Throw loudly if anything
    // calls them so a regression in the test setup is obvious.
    override suspend fun openSolution(id: String): Long = throw NotImplementedError("not used in C5 tests")
    override suspend fun closeSolution(id: String): Long = throw NotImplementedError("not used in C5 tests")
    override suspend fun openSession(id: String): Long = throw NotImplementedError("not used in C5 tests")
    override suspend fun closeSession(id: String): Long = throw NotImplementedError("not used in C5 tests")
}

/** Minimal valid SolutionSummary for tests that don't care about specifics. */
fun anyTestSolution(id: String): SolutionSummary = SolutionSummary(
    id = id,
    name = id,
    root = "/tmp/$id",
    memberCount = 0,
    lastOpenedAt = null,
    open = true,
    mainWindowId = null,
)
