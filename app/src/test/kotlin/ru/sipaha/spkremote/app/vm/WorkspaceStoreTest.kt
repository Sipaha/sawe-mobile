package ru.sipaha.spkremote.app.vm

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.sipaha.spkremote.core.SessionStateDto

class WorkspaceStoreTest {

    @OptIn(ExperimentalCoroutinesApi::class)
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
        advanceUntilIdle()

        val state = store.state.value as WorkspaceUiState.Loaded
        assertEquals(7L, state.snapshot.seq)
        assertEquals(1, state.snapshot.solutions.size)
        assertEquals("s1", state.snapshot.solutions[0].id)
        assertEquals(false, state.stale)
    }
}

/** Minimal in-memory mock of the wire client. Fill in surface as needed. */
class FakeWorkspaceClient(
    val snapshotResult: WorkspaceSnapshotVM = WorkspaceSnapshotVM(0, emptyList()),
) : WorkspaceStoreClient {
    override suspend fun fetchSnapshot(): WorkspaceSnapshotVM = snapshotResult
    // ... add lifecycle calls / closed list etc. in later tasks.
}
