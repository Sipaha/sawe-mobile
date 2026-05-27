package ru.sipaha.spkremote.app.vm

import kotlinx.coroutines.ExperimentalCoroutinesApi
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
}

/** Minimal in-memory mock of the wire client. Fill in surface as needed. */
open class FakeWorkspaceClient(
    val snapshotResult: WorkspaceSnapshotVM = WorkspaceSnapshotVM(0, emptyList()),
) : WorkspaceClient {
    override suspend fun fetchSnapshot(): WorkspaceSnapshotVM = snapshotResult
    // ... add lifecycle calls / closed list etc. in later tasks.
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
