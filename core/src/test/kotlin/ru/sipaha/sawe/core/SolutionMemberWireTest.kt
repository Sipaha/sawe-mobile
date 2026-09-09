package ru.sipaha.sawe.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `solutions.*` member RPCs, pinned at the frame level.
 *
 * A member id is NOT a catalog id. The server split the two in
 * `a81166f241` and this client never followed: it kept sending and
 * expecting `catalog_id` everywhere a member was meant. The user hit it as
 * two dead ends at once — creating a project reported
 * *"Field 'catalog_id' is required … but it was missing"* even though the
 * server had created it, and the projects screen stayed on that error
 * because the refreshing `solutions.get` was undecodable too.
 *
 * [ru.sipaha.sawe.core.RemoteDtosTest] covers the decode direction. This
 * file covers the direction no decoder can catch: what we PUT on the wire.
 * `RemoveMemberParams` is `deny_unknown_fields`, so an extra `solution_id`
 * is a hard `-32602` rather than a tolerated leftover — the absence of the
 * key is the assertion that matters.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SolutionMemberWireTest {

    private fun fakePairing(): PairingUrl = PairingUrl(
        host = "127.0.0.1",
        port = 8443,
        secret = ByteArray(PairingUrl.SECRET_LEN) { it.toByte() },
        client = "solution-member-wire-test",
        fingerprint = ByteArray(PairingUrl.FP_LEN) { (255 - it).toByte() },
    )

    private fun TestScope.newClient(): Pair<RemoteClient, FakeRemoteTransportFactory> {
        val factory = FakeRemoteTransportFactory()
        val client = RemoteClient(
            url = fakePairing(),
            transportFactory = factory,
            backoff = BackoffStrategy.fixed(50L),
            nowMs = { testScheduler.currentTime },
            queueStore = InMemoryQueueStore(),
        )
        return client to factory
    }

    private suspend fun TestScope.connectAndHandshake(
        client: RemoteClient,
        factory: FakeRemoteTransportFactory,
    ) {
        val job = async { client.connect(scope = this@connectAndHandshake) }
        runCurrent()
        factory.latest().completeHandshake()
        runCurrent()
        job.await()
    }

    private fun FakeRemoteTransport.frameFor(method: String): JsonObject =
        sent.toList()
            .map { JsonRpc.json.parseToJsonElement(it).jsonObject }
            .last { it["method"]?.jsonPrimitive?.content == method }

    private fun JsonObject.params(): JsonObject = getValue("params").jsonObject

    private fun FakeRemoteTransport.answer(method: String, structured: String = "{}") {
        val id = frameFor(method)["id"]!!.jsonPrimitive.content
        emit("""{"jsonrpc":"2.0","id":$id,"result":{"structuredContent":$structured}}""")
    }

    @Test
    fun `remove_member identifies the row by member_id alone`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient()
            connectAndHandshake(client, factory)

            val call = async { client.removeMember(memberId = 42L) }
            runCurrent()
            val params = factory.latest().frameFor("remote.solutions.remove_member").params()

            assertEquals(42L, params.getValue("member_id").jsonPrimitive.content.toLong())
            // Both of these were sent by the broken version. `deny_unknown_fields`
            // on the server turns either one into a rejected call.
            assertNull(params["solution_id"], "solution_id would be rejected as an unknown field")
            assertNull(params["catalog_id"], "catalog_id is not a member identity")

            factory.latest().answer("remote.solutions.remove_member", """{"removed":true}""")
            runCurrent()
            call.await()
            client.close()
            runCurrent()
        }

    @Test
    fun `add_empty_member returns the new member id`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient()
            connectAndHandshake(client, factory)

            val call = async { client.addEmptyMember(solutionId = 19L, name = "new-project") }
            runCurrent()
            val params = factory.latest().frameFor("remote.solutions.add_empty_member").params()
            assertEquals(19L, params.getValue("solution_id").jsonPrimitive.content.toLong())
            assertEquals("new-project", params.getValue("name").jsonPrimitive.content)

            // Verbatim answer of the live editor (crates/solutions/src/mcp/
            // member_mgmt.rs `AddEmptyMemberResult`).
            factory.latest().answer("remote.solutions.add_empty_member", """{"member_id":52}""")
            runCurrent()
            assertEquals(52L, call.await().memberId)
            client.close()
            runCurrent()
        }

    @Test
    fun `add_member still identifies a catalog project by catalog_id`() =
        runTest(StandardTestDispatcher()) {
            // The rename is member-side only: cloning FROM the registry
            // genuinely takes a catalog id, and the progress notifications
            // are keyed by it. Guards against an over-eager rename.
            val (client, factory) = newClient()
            connectAndHandshake(client, factory)

            val call = async { client.addMember(solutionId = 14L, catalogId = 43L) }
            runCurrent()
            val params = factory.latest().frameFor("remote.solutions.add_member").params()
            assertEquals(14L, params.getValue("solution_id").jsonPrimitive.content.toLong())
            assertEquals(43L, params.getValue("catalog_id").jsonPrimitive.content.toLong())

            factory.latest().answer("remote.solutions.add_member", """{"operation_id":"op-1"}""")
            runCurrent()
            assertEquals("op-1", call.await().operationId)
            client.close()
            runCurrent()
        }

    @Test
    fun `a solutions_get answer with an empty project decodes end to end`() =
        runTest(StandardTestDispatcher()) {
            // Captured verbatim from the user's running editor
            // (`solutions.get` on solution 19): one member created by
            // add_empty_member, hence no `origin_catalog_id` at all. This is
            // the exact payload that used to fail the whole projects screen.
            val (client, factory) = newClient()
            connectAndHandshake(client, factory)

            val call = async { client.call("remote.solutions.get") }
            runCurrent()
            factory.latest().answer(
                "remote.solutions.get",
                """
                {"solution":{"id":19,"name":"Something New",
                 "root":"/home/spk/.spk/sawe/ss/something-new",
                 "members":[
                   {"id":52,"name":"new-project",
                    "local_path":"/home/spk/.spk/sawe/ss/something-new/new-project",
                    "status":"ok"}],
                 "last_opened_at":"2026-09-09T02:28:40.130+00:00","open":true,
                 "mcp_socket":"/home/spk/.spk/sawe/state/solutions/19/mcp.sock"}}
                """.trimIndent(),
            )
            runCurrent()

            val body = call.await().structuredContent()!!
            val decoded = JsonRpc.json.decodeFromJsonElement(
                GetSolutionResult.serializer(),
                body,
            )
            val member = decoded.solution.members.single()
            assertEquals(52L, member.memberId)
            assertEquals("new-project", member.name)
            assertNull(member.originCatalogId)
            client.close()
            runCurrent()
        }
}
