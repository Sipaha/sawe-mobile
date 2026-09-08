package ru.sipaha.sawe.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The mixed-version guard for the additive wire parameters
 * (`known_entries`, `omit_preview_when_markdown`, `suppress_kinds`).
 *
 * These are not "nice to gate". The server's params structs are
 * `deny_unknown_fields`, so ONE un-negotiated key against an older desktop
 * is a `-32602` — a failed poll, not a graceful degradation. So the
 * assertions here are about a key's ABSENCE from the serialised frame,
 * which is the only thing the old server actually sees.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WireFeatureGateTest {

    private fun fakePairing(): PairingUrl = PairingUrl(
        host = "127.0.0.1",
        port = 8443,
        secret = ByteArray(PairingUrl.SECRET_LEN) { it.toByte() },
        client = "feature-gate-test",
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

    /** Every feature this client knows about, as a live server would send them. */
    private fun allFeatures(): ServerFeatures = CapabilitiesDto(
        wireSchemaVersion = SUPPORTED_WIRE_SCHEMA_VERSION,
        wireFeatures = listOf(
            WireFeature.ENTRY_BODY_DELTA,
            WireFeature.OMIT_PREVIEW,
            WireFeature.CSID_DEDUPE,
            WireFeature.QUIET_MESSAGE_APPENDED,
        ),
        serverInstanceId = "8f1c2a90-5b3e-4d17-9c2f-6a0d1e7b4c33",
        csidDedupeWindowMs = 86_400_000L,
    ).toServerFeatures()

    private fun FakeRemoteTransport.frameFor(method: String): JsonObject =
        sent.toList()
            .map { JsonRpc.json.parseToJsonElement(it).jsonObject }
            .last { it["method"]?.jsonPrimitive?.content == method }

    private fun JsonObject.params(): JsonObject = getValue("params").jsonObject

    /** Answer the last frame for [method] so the suspended call resolves. */
    private fun FakeRemoteTransport.answer(method: String, structured: String = "{}") {
        val id = frameFor(method)["id"]!!.jsonPrimitive.content
        emit("""{"jsonrpc":"2.0","id":$id,"result":{"structuredContent":$structured}}""")
    }

    private val emptyDelta = """
        {"epoch":1,"current_seq":2,"reset":false,"total_count":0,
         "streams":[],"selected_stream_id":{"type":"main"}}
    """.trimIndent()

    private val someKnownEntries = listOf(
        KnownEntryDto(index = 42, markdownLen = 8123, markdownHash = "a".repeat(32)),
    )

    // -----------------------------------------------------------------
    // known_entries / omit_preview_when_markdown
    // -----------------------------------------------------------------

    @Test
    fun `gated params never reach a server that did not advertise them`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient()
            connectAndHandshake(client, factory)
            // Nothing published: this is exactly the old-server case, and
            // also the window between Connected and the capabilities answer.
            assertEquals(ServerFeatures.NONE, client.serverFeatures.value)

            val poll = async {
                client.getSessionChanges(
                    sessionId = "s-1",
                    sinceSeq = 1,
                    knownEpoch = 1,
                    knownEntries = someKnownEntries,
                    omitPreviewWhenMarkdown = true,
                )
            }
            runCurrent()
            val params = factory.latest()
                .frameFor("remote.solution_agent.get_session_changes")
                .params()
            assertNull(params["known_entries"], "known_entries leaked to an un-negotiated peer")
            assertNull(params["omit_preview_when_markdown"], "omit_preview leaked")
            // The un-gated params are of course still there.
            assertEquals("s-1", params.getValue("session_id").jsonPrimitive.content)

            factory.latest().answer("remote.solution_agent.get_session_changes", emptyDelta)
            runCurrent()
            poll.await()
            client.close()
            runCurrent()
        }

    @Test
    fun `gated params are sent once the peer advertised the tokens`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient()
            connectAndHandshake(client, factory)
            client.publishServerFeatures(allFeatures())

            val poll = async {
                client.getSessionChanges(
                    sessionId = "s-1",
                    sinceSeq = 1,
                    knownEpoch = 1,
                    knownEntries = someKnownEntries,
                    omitPreviewWhenMarkdown = true,
                )
            }
            runCurrent()
            val params = factory.latest()
                .frameFor("remote.solution_agent.get_session_changes")
                .params()
            assertEquals(true, params.getValue("omit_preview_when_markdown").jsonPrimitive.content.toBoolean())
            val known = params.getValue("known_entries").jsonArray.single().jsonObject
            assertEquals(42, known.getValue("index").jsonPrimitive.content.toInt())
            assertEquals(8123L, known.getValue("markdown_len").jsonPrimitive.content.toLong())
            assertEquals("a".repeat(32), known.getValue("markdown_hash").jsonPrimitive.content)

            factory.latest().answer("remote.solution_agent.get_session_changes", emptyDelta)
            runCurrent()
            poll.await()
            client.close()
            runCurrent()
        }

    /**
     * `null` and `[]` are the same request: no key on the wire. An empty
     * list means "I hold nothing worth diffing", never "delta everything".
     */
    @Test
    fun `an empty known_entries list is the same as none`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient()
            connectAndHandshake(client, factory)
            client.publishServerFeatures(allFeatures())

            for (offer in listOf<List<KnownEntryDto>?>(null, emptyList())) {
                val poll = async {
                    client.getSessionChanges(
                        sessionId = "s-1",
                        sinceSeq = 1,
                        knownEpoch = 1,
                        knownEntries = offer,
                        omitPreviewWhenMarkdown = false,
                    )
                }
                runCurrent()
                val params = factory.latest()
                    .frameFor("remote.solution_agent.get_session_changes")
                    .params()
                assertNull(params["known_entries"], "empty offer must not put a key on the wire")
                // `false` is never serialised either.
                assertNull(params["omit_preview_when_markdown"])
                factory.latest().answer("remote.solution_agent.get_session_changes", emptyDelta)
                runCurrent()
                poll.await()
            }
            client.close()
            runCurrent()
        }

    // -----------------------------------------------------------------
    // Per-connection lifetime of the negotiated set
    // -----------------------------------------------------------------

    @Test
    fun `features reset on a drop and are not inherited by the reconnect`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient()
            connectAndHandshake(client, factory)
            client.publishServerFeatures(allFeatures())
            assertTrue(client.serverFeatures.value.has(WireFeature.ENTRY_BODY_DELTA))

            factory.latest().closeFromServer()
            runCurrent()
            assertEquals(
                ServerFeatures.NONE,
                client.serverFeatures.value,
                "a dropped socket must not leave its flags behind",
            )

            advanceTimeBy(80L)
            runCurrent()
            factory.latest().completeHandshake()
            runCurrent()
            // Reconnected onto what may be a DOWNGRADED desktop: still NONE
            // until that desktop's own capabilities answer arrives.
            assertEquals(ConnectionState.Connected, client.connectionState.value)
            assertEquals(ServerFeatures.NONE, client.serverFeatures.value)

            val poll = async {
                client.getSessionChanges(
                    sessionId = "s-1",
                    sinceSeq = 1,
                    knownEpoch = 1,
                    knownEntries = someKnownEntries,
                )
            }
            runCurrent()
            assertNull(
                factory.latest()
                    .frameFor("remote.solution_agent.get_session_changes")
                    .params()["known_entries"],
                "the first poll after a reconnect must take the legacy path",
            )
            factory.latest().answer("remote.solution_agent.get_session_changes", emptyDelta)
            runCurrent()
            poll.await()
            client.close()
            runCurrent()
        }

    @Test
    fun `close clears the negotiated set`() = runTest(StandardTestDispatcher()) {
        val (client, factory) = newClient()
        connectAndHandshake(client, factory)
        client.publishServerFeatures(allFeatures())
        client.close()
        runCurrent()
        assertEquals(ServerFeatures.NONE, client.serverFeatures.value)
    }

    /**
     * The capabilities probe is an ordinary call, so its answer can land
     * after the socket it was asked on already died. Applying it then would
     * re-arm gated parameters on a connection that negotiated nothing —
     * precisely what the reset exists to prevent.
     */
    @Test
    fun `a capabilities answer that lands after the drop is ignored`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient()
            connectAndHandshake(client, factory)
            factory.latest().closeFromServer()
            runCurrent()

            client.publishServerFeatures(allFeatures())
            assertEquals(ServerFeatures.NONE, client.serverFeatures.value)
            assertFalse(client.serverFeatures.value.has(WireFeature.CSID_DEDUPE))

            client.close()
            runCurrent()
        }

    // -----------------------------------------------------------------
    // subscribe(suppressKinds)
    // -----------------------------------------------------------------

    @Test
    fun `suppress_kinds is omitted unless the peer advertised the token`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient()
            connectAndHandshake(client, factory)

            val sub = async {
                client.subscribe(
                    kinds = listOf("agent_session_message_appended", "agent_session_dirty"),
                    suppressKinds = listOf("agent_session_message_appended"),
                )
            }
            runCurrent()
            val params = factory.latest().frameFor("remote.editor.subscribe").params()
            assertNull(params["suppress_kinds"], "suppress_kinds leaked to an un-negotiated peer")
            // The kind stays SUBSCRIBED either way — suppression is "ask for
            // less", never "stop listening", so an un-negotiated peer keeps
            // delivering exactly what it does today.
            assertTrue(
                params.getValue("kinds").jsonArray
                    .map { it.jsonPrimitive.content }
                    .contains("agent_session_message_appended"),
            )
            factory.latest().answer("remote.editor.subscribe")
            runCurrent()
            sub.await()
            client.close()
            runCurrent()
        }

    @Test
    fun `suppress_kinds rides the subscribe once the token is advertised`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient()
            connectAndHandshake(client, factory)
            client.publishServerFeatures(allFeatures())

            val sub = async {
                client.subscribe(
                    kinds = listOf("agent_session_message_appended", "agent_session_dirty"),
                    suppressKinds = listOf("agent_session_message_appended"),
                )
            }
            runCurrent()
            val params = factory.latest().frameFor("remote.editor.subscribe").params()
            assertEquals(
                listOf("agent_session_message_appended"),
                params.getValue("suppress_kinds").jsonArray.map { it.jsonPrimitive.content },
            )
            factory.latest().answer("remote.editor.subscribe")
            runCurrent()
            sub.await()
            client.close()
            runCurrent()
        }

    /**
     * The reconnect replay fires before the new connection has negotiated
     * anything, so it must carry the plain `kinds` — a replay that inherited
     * the previous socket's suppression would be the same
     * `deny_unknown_fields` failure as an inherited `known_entries`.
     */
    @Test
    fun `the reconnect replay drops suppress_kinds until the peer re-advertises`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient()
            connectAndHandshake(client, factory)
            client.publishServerFeatures(allFeatures())

            val sub = async {
                client.subscribe(
                    kinds = listOf("agent_session_dirty"),
                    suppressKinds = listOf("agent_session_message_appended"),
                )
            }
            runCurrent()
            factory.latest().answer("remote.editor.subscribe")
            runCurrent()
            sub.await()

            factory.latest().closeFromServer()
            runCurrent()
            advanceTimeBy(80L)
            runCurrent()
            factory.latest().completeHandshake()
            runCurrent()

            val replay = factory.latest().frameFor("remote.editor.subscribe").params()
            assertNull(replay["suppress_kinds"], "the replay must not inherit suppression")
            assertEquals(
                listOf("agent_session_dirty"),
                replay.getValue("kinds").jsonArray.map { it.jsonPrimitive.content },
            )
            client.close()
            runCurrent()
        }
}
