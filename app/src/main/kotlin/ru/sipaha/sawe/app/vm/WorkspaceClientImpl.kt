package ru.sipaha.sawe.app.vm

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.sipaha.sawe.core.RemoteClient
import ru.sipaha.sawe.core.SessionSummary
import ru.sipaha.sawe.core.WorkspaceListSolutionsResult
import ru.sipaha.sawe.core.WorkspaceSeqAck
import ru.sipaha.sawe.core.WorkspaceSnapshot
import ru.sipaha.sawe.core.WorkspaceSolution

/**
 * Wire-level [WorkspaceClient] backed by a live [RemoteClient].
 *
 * Each call resolves the active client via [getClient] (closure on the
 * coordinator so a server-switch invalidates it transparently). All six
 * surface methods funnel through [RemoteClient.call] + the standard
 * `decodeResultOrThrow` envelope unwrap used by every other RPC in this
 * project; no special `tools/call` plumbing is needed — the server-side
 * proxy already wraps `workspace.*` exactly like `remote.*` calls (see
 * [ru.sipaha.sawe.core.JsonRpcResponse] kdoc).
 *
 * The four lifecycle calls return the server-assigned `seq` from the
 * `WorkspaceSeqAck` shape; [WorkspaceStore] uses that to mark optimistic
 * mutations as confirmed when the matching delta arrives.
 */
internal class WorkspaceClientImpl(
    private val getClient: () -> RemoteClient?,
) : WorkspaceClient {

    override suspend fun fetchSnapshot(): WorkspaceSnapshotVM {
        val client = getClient() ?: error("Not connected")
        val resp = client.call("remote.workspace.snapshot", buildJsonObject {})
        val raw = resp.decodeResultOrThrow(WorkspaceSnapshot.serializer())
        return raw.toVM()
    }

    override suspend fun fetchClosedSolutions(): List<ClosedSolutionRow> {
        val client = getClient() ?: error("Not connected")
        val resp = client.call(
            "remote.workspace.list_solutions",
            buildJsonObject { put("open", false) },
        )
        val raw = resp.decodeResultOrThrow(WorkspaceListSolutionsResult.serializer())
        return raw.solutions.map {
            ClosedSolutionRow(
                id = it.id,
                name = it.name,
                memberCount = it.memberCount,
                lastOpenedAt = it.lastOpenedAt,
            )
        }
    }

    override suspend fun openSolution(id: Long): Long =
        lifecycleCall("remote.workspace.open_solution", "solution_id", id)

    override suspend fun closeSolution(id: Long): Long =
        lifecycleCall("remote.workspace.close_solution", "solution_id", id)

    override suspend fun openSession(id: String): Long =
        lifecycleCall("remote.workspace.open_session", "session_id", id)

    override suspend fun closeSession(id: String): Long =
        lifecycleCall("remote.workspace.close_session", "session_id", id)

    /** Solution-id lifecycle calls — emitted as a bare integer on the wire. */
    private suspend fun lifecycleCall(
        toolName: String,
        paramKey: String,
        value: Long,
    ): Long {
        val client = getClient() ?: error("Not connected")
        val resp = client.call(toolName, buildJsonObject { put(paramKey, value) })
        return resp.decodeResultOrThrow(WorkspaceSeqAck.serializer()).seq
    }

    /** Session-id lifecycle calls — emitted as a quoted string on the wire. */
    private suspend fun lifecycleCall(
        toolName: String,
        paramKey: String,
        value: String,
    ): Long {
        val client = getClient() ?: error("Not connected")
        val resp = client.call(toolName, buildJsonObject { put(paramKey, value) })
        return resp.decodeResultOrThrow(WorkspaceSeqAck.serializer()).seq
    }
}

private fun WorkspaceSnapshot.toVM(): WorkspaceSnapshotVM = WorkspaceSnapshotVM(
    seq = seq,
    solutions = solutions.map { it.toVM() },
)

private fun WorkspaceSolution.toVM(): OpenSolutionVM = OpenSolutionVM(
    id = id,
    name = name,
    memberCount = memberCount,
    sessions = sessions.map { it.toVM() },
)

private fun SessionSummary.toVM(): OpenSessionVM = OpenSessionVM(
    id = id,
    title = title,
    state = state,
    lastActivityAt = lastActivityAt,
    totalTokens = totalTokens,
    maxTokens = maxTokens,
)
