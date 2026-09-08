package ru.sipaha.sawe.app.vm

import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.sipaha.sawe.app.data.DraftRepository
import ru.sipaha.sawe.app.data.EncryptedQueueStore
import ru.sipaha.sawe.app.data.PairedServer
import ru.sipaha.sawe.app.data.PairingRepository
import ru.sipaha.sawe.core.ConnectFailure
import ru.sipaha.sawe.core.ConnectionState
import ru.sipaha.sawe.core.JsonRpcError
import ru.sipaha.sawe.core.JsonRpcResponse
import ru.sipaha.sawe.core.QueuedMessage
import ru.sipaha.sawe.core.SUPPORTED_WIRE_SCHEMA_VERSION

/**
 * Pure-JVM coverage for the connection-lifecycle decisions carved out of
 * [ConnectionManager]. Each group corresponds to one audit finding; the
 * decision function is the part of the fix that can be pinned down without
 * a live socket.
 */
class ConnectionLifecycleDecisionsTest {

    private fun server(id: String, url: String = "sawe-remote://h:1?s=x") = PairedServer(
        id = id,
        pairingUrl = url,
        label = id,
        fingerprintHex = "ab",
        firstPairedAtMs = 1L,
        lastConnectedAtMs = null,
    )

    private fun capabilitiesResponse(wireSchemaVersion: Int, protocol: String = "1.2"): JsonRpcResponse =
        JsonRpcResponse(
            id = 1L,
            result = buildJsonObject {
                put(
                    "structuredContent",
                    buildJsonObject {
                        put("protocol_version", protocol)
                        put("wire_schema_version", wireSchemaVersion)
                    },
                )
            },
        )

    // ---- Cold-start landing must ALWAYS resolve ----

    /**
     * `landingRoute` is the only thing that ends the nav graph's cold-start
     * splash, and that splash has no timeout, no error state and no
     * back-press escape. An exception out of the keystore-backed pairing read
     * used to propagate out of `hydrateAndAutoConnect` and leave the flow at
     * `null` forever — a permanent spinner recoverable only by force-stop,
     * which is the same class of bug this whole effort set out to remove.
     * `MainActivity` no longer computes the route synchronously as a backstop,
     * so the guarantee has to hold here.
     */
    @Test
    fun `a pairing read that throws still lands the user somewhere`() {
        val decision = resolveColdStartLanding(
            loadServers = { error("keystore unavailable") },
            loadActiveId = { null },
        )
        assertEquals("pairing", decision.route)
        assertNull(decision.connectToServerId)
    }

    @Test
    fun `a failing active-id read still lands the user somewhere`() {
        val decision = resolveColdStartLanding(
            loadServers = { listOf(server("a")) },
            loadActiveId = { throw IllegalStateException("prefs corrupt") },
        )
        assertEquals("pairing", decision.route)
        assertNull(decision.connectToServerId)
    }

    @Test
    fun `a healthy read is unaffected by the guard`() {
        val decision = resolveColdStartLanding(
            loadServers = { listOf(server("a"), server("b")) },
            loadActiveId = { "b" },
        )
        assertEquals("servers", decision.route)
        assertEquals("b", decision.connectToServerId)
    }

    // ---- N-10: cold-start landing + auto-connect target ----

    @Test
    fun `no paired servers lands on pairing and connects to nothing`() {
        val decision = coldStartLanding(servers = emptyList(), storedActiveId = null)

        assertEquals("pairing", decision.route)
        assertNull(decision.connectToServerId)
    }

    @Test
    fun `a single paired server lands on workspace and is auto-connected`() {
        val decision = coldStartLanding(servers = listOf(server("a")), storedActiveId = null)

        assertEquals("workspace", decision.route)
        assertEquals("a", decision.connectToServerId)
    }

    @Test
    fun `two servers land on the picker and auto-connect the stored active one`() {
        val decision = coldStartLanding(
            servers = listOf(server("a"), server("b")),
            storedActiveId = "b",
        )

        assertEquals("servers", decision.route)
        assertEquals("b", decision.connectToServerId)
    }

    @Test
    fun `a dangling stored active id falls back to the most recently used server`() {
        // loadAll() sorts most-recently-connected first, so `first()` is the MRU.
        val decision = coldStartLanding(
            servers = listOf(server("a"), server("b")),
            storedActiveId = "gone",
        )

        assertEquals("a", decision.connectToServerId)
    }

    // ---- N-09 / N-18 / N-20: when a switch must actually re-bind ----

    @Test
    fun `switching to the already-active healthy server is a no-op`() {
        assertFalse(
            shouldRebind(
                force = false,
                targetServerId = "a",
                activeServerId = "a",
                hasClient = true,
                connectionState = ConnectionState.Connected,
            ),
        )
    }

    @Test
    fun `a transient reconnect does not re-bind either`() {
        assertFalse(
            shouldRebind(
                force = false,
                targetServerId = "a",
                activeServerId = "a",
                hasClient = true,
                connectionState = ConnectionState.Reconnecting(attempt = 3, nextRetryMs = 4_000L),
            ),
            "the client's own lifecycle loop recovers from this — churning it would restart the backoff",
        )
    }

    @Test
    fun `tapping the active server re-binds it out of a terminal failure`() {
        // The whole point of N-09/N-18: FailedTerminal is sticky, the client
        // stays non-null, and every recovery affordance used to return early.
        assertTrue(
            shouldRebind(
                force = false,
                targetServerId = "a",
                activeServerId = "a",
                hasClient = true,
                connectionState = ConnectionState.FailedTerminal(
                    ConnectFailure.AuthRejected(),
                ),
            ),
        )
    }

    @Test
    fun `a ViewModel with no client re-binds even for the active server id`() {
        assertTrue(
            shouldRebind(
                force = false,
                targetServerId = "a",
                activeServerId = "a",
                hasClient = false,
                connectionState = ConnectionState.Disconnected,
            ),
        )
    }

    @Test
    fun `force always re-binds`() {
        assertTrue(
            shouldRebind(
                force = true,
                targetServerId = "a",
                activeServerId = "a",
                hasClient = true,
                connectionState = ConnectionState.Connected,
            ),
        )
    }

    // ---- N-18: re-scanning the QR of an already-paired server ----

    @Test
    fun `re-scanning a QR with a rotated secret forces a re-bind`() {
        assertTrue(
            shouldForceRebindOnPair(
                previousUrl = "sawe-remote://h:1?secret=old",
                newUrl = "sawe-remote://h:1?secret=new",
                isTerminal = false,
            ),
        )
    }

    @Test
    fun `re-scanning the identical QR while terminal still forces a re-bind`() {
        assertTrue(
            shouldForceRebindOnPair(
                previousUrl = "sawe-remote://h:1?secret=same",
                newUrl = "sawe-remote://h:1?secret=same",
                isTerminal = true,
            ),
        )
    }

    @Test
    fun `re-scanning the identical QR of a healthy connection does not churn it`() {
        assertFalse(
            shouldForceRebindOnPair(
                previousUrl = "sawe-remote://h:1?secret=same",
                newUrl = "sawe-remote://h:1?secret=same",
                isTerminal = false,
            ),
        )
    }

    @Test
    fun `a brand new pairing is not a forced re-bind`() {
        assertFalse(
            shouldForceRebindOnPair(previousUrl = null, newUrl = "sawe-remote://h:1", isTerminal = false),
        )
    }

    // ---- N-11: only a decoded CapabilitiesDto may trip the schema gate ----

    @Test
    fun `a supported wire schema is compatible and carries the protocol version`() {
        val decision = schemaGate(Result.success(capabilitiesResponse(SUPPORTED_WIRE_SCHEMA_VERSION)))

        val compatible = decision as SchemaGateDecision.Compatible
        assertEquals("1.2", compatible.capabilities.protocolVersion)
    }

    @Test
    fun `an error envelope is a transient probe failure - never server too old`() {
        // The desktop answers -32603 for every call in the seconds between
        // its listener accepting the socket and its local MCP proxy binding.
        // Reading that as wire_schema_version = 0 stranded the phone on a
        // permanent "This server is too old" screen.
        val resp = JsonRpcResponse(
            id = 1L,
            error = JsonRpcError(code = -32603, message = "opening local MCP proxy: connection refused"),
        )

        val decision = schemaGate(Result.success(resp))

        assertTrue(
            decision is SchemaGateDecision.ProbeFailed,
            "an error envelope must not reach the incompatible-server gate, got $decision",
        )
    }

    @Test
    fun `a tool-level error is a transient probe failure`() {
        val contentItem: JsonElement = buildJsonObject {
            put("type", "text")
            put("text", "capabilities unavailable")
        }
        val resp = JsonRpcResponse(
            id = 1L,
            result = buildJsonObject {
                put("isError", true)
                put("content", buildJsonArray { add(contentItem) })
            },
        )

        assertTrue(schemaGate(Result.success(resp)) is SchemaGateDecision.ProbeFailed)
    }

    @Test
    fun `a missing structuredContent is a transient probe failure`() {
        val resp = JsonRpcResponse(id = 1L, result = buildJsonObject { })

        assertTrue(schemaGate(Result.success(resp)) is SchemaGateDecision.ProbeFailed)
    }

    @Test
    fun `a transport failure is a transient probe failure`() {
        val decision = schemaGate(Result.failure(IllegalStateException("not connected")))

        assertTrue(decision is SchemaGateDecision.ProbeFailed)
    }

    @Test
    fun `an object with no wire_schema_version is a probe failure, not a too-old server`() {
        // THE CM-1 regression. `CapabilitiesDto` defaults wireSchemaVersion to
        // 0 and JsonRpc.json ignores unknown keys, so decoding succeeds
        // against ANY object and yields 0 -> isServerTooOld(0) -> the
        // permanent "update the editor" screen. "It decoded" was never
        // evidence; the field's PRESENCE is.
        val resp = JsonRpcResponse(
            id = 1L,
            result = buildJsonObject { put("structuredContent", buildJsonObject { }) },
        )

        val decision = schemaGate(Result.success(resp))

        assertTrue(
            decision is SchemaGateDecision.ProbeFailed,
            "an empty capabilities object must not read as wire schema 0, got $decision",
        )
    }

    @Test
    fun `a partial capabilities object is a probe failure`() {
        // A degraded / rewriting proxy answering with only what it knows.
        val resp = JsonRpcResponse(
            id = 1L,
            result = buildJsonObject {
                put(
                    "structuredContent",
                    buildJsonObject { put("protocol_version", "1.2") },
                )
            },
        )

        assertTrue(schemaGate(Result.success(resp)) is SchemaGateDecision.ProbeFailed)
    }

    @Test
    fun `structuredContent that is not an object is a probe failure`() {
        val resp = JsonRpcResponse(
            id = 1L,
            result = buildJsonObject { put("structuredContent", "not-an-object") },
        )

        assertTrue(schemaGate(Result.success(resp)) is SchemaGateDecision.ProbeFailed)
    }

    @Test
    fun `an explicit zero from a genuinely pre-versioned server still trips the gate`() {
        // The field is present and says 0 — that IS evidence, and it is what
        // the too-old branch is for. Only its absence is inconclusive.
        val resp = JsonRpcResponse(
            id = 1L,
            result = buildJsonObject {
                put(
                    "structuredContent",
                    buildJsonObject {
                        put("protocol_version", "0.9")
                        put("wire_schema_version", 0)
                    },
                )
            },
        )

        val decision = schemaGate(Result.success(resp))

        assertEquals(0, (decision as SchemaGateDecision.Incompatible).serverWireSchemaVersion)
    }

    @Test
    fun `a genuinely old server still trips the gate`() {
        val decision = schemaGate(Result.success(capabilitiesResponse(SUPPORTED_WIRE_SCHEMA_VERSION - 1)))

        val incompatible = decision as SchemaGateDecision.Incompatible
        assertEquals(SUPPORTED_WIRE_SCHEMA_VERSION - 1, incompatible.serverWireSchemaVersion)
        assertTrue(incompatible.message.contains("update the editor"))
    }

    @Test
    fun `a genuinely new server still trips the gate`() {
        val decision = schemaGate(Result.success(capabilitiesResponse(SUPPORTED_WIRE_SCHEMA_VERSION + 1)))

        val incompatible = decision as SchemaGateDecision.Incompatible
        assertTrue(incompatible.message.contains("update"))
    }

    // ---- N-09: UiState is derived from the observed wire state ----

    @Test
    fun `a failed first attempt reads as Connecting - not Disconnected`() {
        // The regression: connect() reports only the FIRST attempt, and its
        // failure used to pin UiState to Disconnected forever while the
        // lifecycle loop kept retrying behind it.
        val state = deriveUiState(
            state = ConnectionState.Reconnecting(
                attempt = 1,
                nextRetryMs = 1_000L,
                lastFailure = ConnectFailure.Unreachable("host unreachable"),
            ),
            everConnected = false,
            protocolVersion = null,
            firstGatePending = false,
        )

        assertEquals(UiState.Connecting, state)
    }

    @Test
    fun `the pre-connect Disconnected of a freshly bound client reads as Connecting`() {
        // The observer is installed BEFORE connect(), so its first emission
        // is the client's initial Disconnected. Mapping that to
        // UiState.Disconnected would bounce the nav graph to the QR screen.
        val state = deriveUiState(
            state = ConnectionState.Disconnected,
            everConnected = false,
            protocolVersion = null,
            firstGatePending = false,
        )

        assertEquals(UiState.Connecting, state)
    }

    @Test
    fun `a Wi-Fi blip after a successful connect keeps the user in the app`() {
        val state = deriveUiState(
            state = ConnectionState.Reconnecting(attempt = 2, nextRetryMs = 2_000L),
            everConnected = true,
            protocolVersion = "1.2",
            firstGatePending = false,
        )

        assertEquals(UiState.Connected("1.2"), state)
    }

    @Test
    fun `Connected waits for the first schema gate before leaving the spinner`() {
        assertEquals(
            UiState.Connecting,
            deriveUiState(
                state = ConnectionState.Connected,
                everConnected = true,
                protocolVersion = null,
                firstGatePending = true,
            ),
        )
    }

    @Test
    fun `Connected reports the gated protocol version once the probe lands`() {
        assertEquals(
            UiState.Connected("1.2"),
            deriveUiState(
                state = ConnectionState.Connected,
                everConnected = true,
                protocolVersion = "1.2",
                firstGatePending = false,
            ),
        )
    }

    @Test
    fun `a failed capabilities probe still lets the app run on the live socket`() {
        assertEquals(
            UiState.Connected(UNKNOWN_PROTOCOL_VERSION),
            deriveUiState(
                state = ConnectionState.Connected,
                everConnected = true,
                protocolVersion = null,
                firstGatePending = false,
            ),
        )
    }

    @Test
    fun `a terminal failure surfaces its reason as Disconnected`() {
        val state = deriveUiState(
            state = ConnectionState.FailedTerminal(ConnectFailure.AuthRejected()),
            everConnected = true,
            protocolVersion = "1.2",
            firstGatePending = false,
        )

        val disconnected = state as UiState.Disconnected
        assertNotNull(disconnected.error)
        assertNull(disconnected.lastUrl, "the pairing URL carries the HMAC secret — never echo it")
    }

    // ---- N-15: the foreground liveness probe needs two strikes ----

    @Test
    fun `one answered ping is alive`() {
        assertEquals(ProbeDecision.Alive, probeDecision(ProbeAttempt.Answered, null))
    }

    @Test
    fun `an error envelope proves the wire is alive`() {
        // -32603 while the desktop's MCP proxy is down says nothing about
        // the socket; tearing it down costs a TLS handshake and a full
        // workspace refetch and does not fix the proxy.
        assertEquals(ProbeDecision.Alive, probeDecision(ProbeAttempt.ErrorEnvelope, null))
    }

    @Test
    fun `a single timeout asks for a second strike instead of reconnecting`() {
        assertEquals(ProbeDecision.Retry, probeDecision(ProbeAttempt.Silent, null))
    }

    @Test
    fun `a slow link that answers the second ping keeps its socket`() {
        assertEquals(ProbeDecision.Alive, probeDecision(ProbeAttempt.Silent, ProbeAttempt.Answered))
        assertEquals(ProbeDecision.Alive, probeDecision(ProbeAttempt.Silent, ProbeAttempt.ErrorEnvelope))
    }

    @Test
    fun `two silences are a zombie socket`() {
        assertEquals(ProbeDecision.Dead, probeDecision(ProbeAttempt.Silent, ProbeAttempt.Silent))
    }

    @Test
    fun `a wire that answered moments ago is not probed again`() {
        // Every return from a file picker fires a foreground edge; each one
        // used to cost a full capabilities round-trip whose answer we already
        // had (CM-4).
        assertTrue(
            shouldSkipLivenessProbe(
                nowMs = 10_000L,
                lastWireResponseMs = 8_000L,
                windowMs = ConnectionManager.PROBE_SKIP_WINDOW_MS,
            ),
        )
    }

    @Test
    fun `a stale last-response does not suppress the probe`() {
        assertFalse(
            shouldSkipLivenessProbe(
                nowMs = 100_000L,
                lastWireResponseMs = 8_000L,
                windowMs = ConnectionManager.PROBE_SKIP_WINDOW_MS,
            ),
        )
    }

    @Test
    fun `a wire that has never answered is always probed`() {
        assertFalse(
            shouldSkipLivenessProbe(nowMs = 10_000L, lastWireResponseMs = 0L, windowMs = 5_000L),
        )
    }

    @Test
    fun `a clock that went backwards does not suppress the probe`() {
        // NTP correction / user changing the time: a negative age must not
        // read as "answered recently".
        assertFalse(
            shouldSkipLivenessProbe(nowMs = 1_000L, lastWireResponseMs = 90_000L, windowMs = 5_000L),
        )
    }

    @Test
    fun `the second strike is cheaper than the first`() {
        // Two full 12s budgets made a dead socket cost 24s of a user staring
        // at a screen. A link that missed the long budget has had its benefit
        // of the doubt (CM-4).
        assertTrue(ConnectionManager.PROBE_RETRY_TIMEOUT_MS < ConnectionManager.PROBE_TIMEOUT_MS)
        assertTrue(
            ConnectionManager.PROBE_TIMEOUT_MS + ConnectionManager.PROBE_RETRY_TIMEOUT_MS <= 20_000L,
        )
    }

    @Test
    fun `the probe budget is sized for a roaming link`() {
        assertTrue(
            ConnectionManager.PROBE_TIMEOUT_MS >= 12_000L,
            "an 8s cap killed healthy sockets whose round-trip had briefly stretched",
        )
    }

    @Test
    fun `the rpc heartbeat is no longer a 30 second cadence`() {
        // OkHttp's own 30s ping/pong is the zombie detector; the RPC ping
        // only adds coverage for a wedged dispatcher and must not wake the
        // radio twice a minute for it.
        assertTrue(ConnectionManager.HEARTBEAT_INTERVAL_MS >= 90_000L)
    }

    // ---- N-16: default-network changes ----

    @Test
    fun `the registration-time callback does not disturb a healthy connection`() {
        // registerDefaultNetworkCallback delivers onAvailable for the network
        // we are ALREADY on. That is not a change. (It is emphatically not the
        // handover case — see the onLost tests below, which is the distinction
        // the first cut of this got wrong.)
        assertEquals(
            NetworkChangeAction.Ignore,
            networkChangeAction(
                previousNetworkHandle = null,
                newNetworkHandle = 7L,
                connected = true,
                sawLoss = false,
            ),
        )
    }

    @Test
    fun `the first observed network wakes a stalled backoff`() {
        assertEquals(
            NetworkChangeAction.Wake,
            networkChangeAction(
                previousNetworkHandle = null,
                newNetworkHandle = 7L,
                connected = false,
                sawLoss = false,
            ),
        )
    }

    @Test
    fun `a re-delivered callback for the same network does nothing`() {
        assertEquals(
            NetworkChangeAction.Ignore,
            networkChangeAction(
                previousNetworkHandle = 7L,
                newNetworkHandle = 7L,
                connected = true,
                sawLoss = false,
            ),
        )
        assertEquals(
            NetworkChangeAction.Ignore,
            networkChangeAction(
                previousNetworkHandle = 7L,
                newNetworkHandle = 7L,
                connected = false,
                sawLoss = false,
            ),
        )
    }

    @Test
    fun `a Wi-Fi to cellular handover rebuilds the socket instead of waiting it out`() {
        assertEquals(
            NetworkChangeAction.ForceReconnect,
            networkChangeAction(
                previousNetworkHandle = 7L,
                newNetworkHandle = 8L,
                connected = true,
                sawLoss = false,
            ),
        )
    }

    @Test
    fun `coverage coming back short-circuits the capped backoff`() {
        assertEquals(
            NetworkChangeAction.Wake,
            networkChangeAction(
                previousNetworkHandle = 7L,
                newNetworkHandle = 8L,
                connected = false,
                sawLoss = false,
            ),
        )
    }

    @Test
    fun `only a handover may skip the force-reconnect grace`() {
        // The 12s grace damps *inferences* (a timed-out probe, an
        // unanswered heartbeat). A default-network handover is direct
        // evidence the socket is dead, so it is the one path allowed to
        // bypass it — and the only one, because the flag is derived from
        // the action rather than passed by hand at the call site.
        assertTrue(NetworkChangeAction.ForceReconnect.bypassesForceReconnectGrace)
        assertFalse(NetworkChangeAction.Wake.bypassesForceReconnectGrace)
        assertFalse(NetworkChangeAction.Ignore.bypassesForceReconnectGrace)
    }

    @Test
    fun `the Wi-Fi to cellular handover both forces and skips the grace`() {
        val action = networkChangeAction(
            previousNetworkHandle = 7L,
            newNetworkHandle = 8L,
            connected = true,
            sawLoss = false,
        )

        assertEquals(NetworkChangeAction.ForceReconnect, action)
        assertTrue(action.bypassesForceReconnectGrace)
    }

    // ---- N-19: what the app's visibility allows on the wire ----

    @Test
    fun `a foregrounded app with a client runs the heartbeat and no park`() {
        val policy = visibilityPolicy(
            appInForeground = true,
            hasClient = true,
            parkGraceElapsed = false,
            pendingWireWork = false,
            uploadWireWork = false,
        )

        assertTrue(policy.runHeartbeat)
        assertFalse(policy.parkReconnectLadder)
    }

    @Test
    fun `backgrounding parks both the heartbeat and the reconnect ladder`() {
        // The regression: an unconditional `while (true) { delay(30s) ... }`
        // plus a reconnect loop that redialled every 30s meant a phone face
        // down on a table brought its radio up twice a minute all night.
        val policy = visibilityPolicy(
            appInForeground = false,
            hasClient = true,
            parkGraceElapsed = true,
            pendingWireWork = false,
            uploadWireWork = false,
        )

        assertFalse(policy.runHeartbeat)
        assertTrue(policy.parkReconnectLadder)
    }

    @Test
    fun `a foregrounded app with no client has nothing to ping`() {
        val policy = visibilityPolicy(
            appInForeground = true,
            hasClient = false,
            parkGraceElapsed = false,
            pendingWireWork = false,
            uploadWireWork = false,
        )

        assertFalse(policy.runHeartbeat)
        assertFalse(
            policy.parkReconnectLadder,
            "there is no ladder to park, and the next bind must dial immediately",
        )
    }

    @Test
    fun `a client bound while backgrounded is parked from birth`() {
        assertTrue(
            visibilityPolicy(
                appInForeground = false,
                hasClient = false,
                parkGraceElapsed = true,
                pendingWireWork = false,
                uploadWireWork = false,
            ).parkReconnectLadder,
        )
    }

    @Test
    fun `a trip through the file picker does not park the ladder`() {
        // A full-screen system picker stops our Activity, producing the same
        // background edge as a real backgrounding. Parking for an excursion
        // measured in seconds costs a reconnect on the way back (CM-3).
        assertFalse(
            visibilityPolicy(
                appInForeground = false,
                hasClient = true,
                parkGraceElapsed = false,
                pendingWireWork = false,
                uploadWireWork = false,
            ).parkReconnectLadder,
        )
    }

    @Test
    fun `a queued message holds the park off`() {
        // Backgrounding right after Send must not silently strand the message
        // until the app is reopened.
        assertFalse(
            visibilityPolicy(
                appInForeground = false,
                hasClient = true,
                parkGraceElapsed = true,
                pendingWireWork = true,
                uploadWireWork = false,
            ).parkReconnectLadder,
        )
    }

    @Test
    fun `the heartbeat never runs in the background regardless of grace`() {
        for (grace in listOf(false, true)) {
            assertFalse(
                visibilityPolicy(
                    appInForeground = false,
                    hasClient = true,
                    parkGraceElapsed = grace,
                    pendingWireWork = false,
                    uploadWireWork = false,
                ).runHeartbeat,
                "heartbeat must stop on the background edge itself (grace=$grace)",
            )
        }
    }

    @Test
    fun `walking out of Wi-Fi range rebuilds the socket on the replacement network`() {
        // THE CM-2 regression. An abrupt loss delivers onLost(wifi) BEFORE
        // onAvailable(cell), and OkHttp has not hit its ping timeout yet, so
        // the state still reads Connected. Clearing the handle in onLost made
        // this look like the registration-time callback above — Ignore — and
        // the app sat on a socket bound to a dead interface for 30-60s, which
        // is exactly the failure N-16 exists to remove.
        assertEquals(
            NetworkChangeAction.ForceReconnect,
            networkChangeAction(
                previousNetworkHandle = 7L,
                newNetworkHandle = 8L,
                connected = true,
                sawLoss = true,
            ),
        )
    }

    @Test
    fun `a network that flaps and returns under the same handle still counts as a change`() {
        // Same interface identity, but it went away and came back: whatever
        // the socket was bound to did not survive. Only the no-loss repeat is
        // a no-op.
        assertEquals(
            NetworkChangeAction.ForceReconnect,
            networkChangeAction(
                previousNetworkHandle = 7L,
                newNetworkHandle = 7L,
                connected = true,
                sawLoss = true,
            ),
        )
    }

    @Test
    fun `a loss while already reconnecting only wakes the ladder`() {
        assertEquals(
            NetworkChangeAction.Wake,
            networkChangeAction(
                previousNetworkHandle = 7L,
                newNetworkHandle = 8L,
                connected = false,
                sawLoss = true,
            ),
        )
    }

    // ---- CM-3: an upload in flight holds the reconnect ladder open ----

    @Test
    fun `backgrounding mid-upload does not park the ladder`() {
        // The socket can drop while the screen is off; the upload then sits
        // Paused and only a redial revives it. Parking there stranded a
        // 40 MB attachment until the user next opened the app.
        assertFalse(
            visibilityPolicy(
                appInForeground = false,
                hasClient = true,
                parkGraceElapsed = true,
                pendingWireWork = false,
                uploadWireWork = true,
            ).parkReconnectLadder,
        )
    }

    @Test
    fun `an upload that finished while backgrounded lets the next evaluation park`() {
        // The carve-out defers the park, it never cancels it — which is why
        // the park is re-evaluated rather than decided once.
        assertTrue(
            visibilityPolicy(
                appInForeground = false,
                hasClient = true,
                parkGraceElapsed = true,
                pendingWireWork = false,
                uploadWireWork = false,
            ).parkReconnectLadder,
        )
    }

    @Test
    fun `an upload never suppresses the heartbeat carve-out`() {
        // The heartbeat is about showing a banner to somebody looking at the
        // screen; an upload is not a reason to keep pinging.
        assertFalse(
            visibilityPolicy(
                appInForeground = false,
                hasClient = true,
                parkGraceElapsed = true,
                pendingWireWork = false,
                uploadWireWork = true,
            ).runHeartbeat,
        )
    }

    @Test
    fun `an upload with no registered work never holds the ladder`() {
        assertFalse(
            uploadHoldsLadderOpen(
                uploadsActive = false,
                nowMs = 10_000L,
                lastProgressAtMs = 9_000L,
                stallTimeoutMs = 60_000L,
            ),
        )
    }

    @Test
    fun `an upload that is moving keeps earning the radio`() {
        assertTrue(
            uploadHoldsLadderOpen(
                uploadsActive = true,
                nowMs = 100_000L,
                lastProgressAtMs = 95_000L,
                stallTimeoutMs = 60_000L,
            ),
        )
    }

    @Test
    fun `a slow upload is never cut off while acks keep arriving`() {
        // Progress restarts the clock, so a 40 MB upload on a slow link
        // holds the ladder for as long as it takes — the bound is on being
        // stuck, not on elapsed time.
        var now = 0L
        var lastProgress = 0L
        repeat(20) {
            now += 30_000L
            lastProgress = now // an ack landed
            assertTrue(
                uploadHoldsLadderOpen(
                    uploadsActive = true,
                    nowMs = now + 1_000L,
                    lastProgressAtMs = lastProgress,
                    stallTimeoutMs = 60_000L,
                ),
                "an upload that keeps acking must keep the ladder at t=$now",
            )
        }
    }

    @Test
    fun `a stuck upload eventually stops holding the ladder`() {
        // THE bound. A retrying Paused upload that never moves must not keep
        // the radio alive all night.
        assertFalse(
            uploadHoldsLadderOpen(
                uploadsActive = true,
                nowMs = 500_000L,
                lastProgressAtMs = 100_000L,
                stallTimeoutMs = 60_000L,
            ),
        )
    }

    @Test
    fun `an upload that has never moved does not hold the ladder`() {
        assertFalse(
            uploadHoldsLadderOpen(
                uploadsActive = true,
                nowMs = 10_000L,
                lastProgressAtMs = 0L,
                stallTimeoutMs = 60_000L,
            ),
        )
    }

    @Test
    fun `a backwards clock does not grant an unbounded hold`() {
        assertFalse(
            uploadHoldsLadderOpen(
                uploadsActive = true,
                nowMs = 1_000L,
                lastProgressAtMs = 900_000L,
                stallTimeoutMs = 60_000L,
            ),
        )
    }

    // ---- CM-3: the queue carve-out is bounded too ----

    @Test
    fun `a freshly queued message defers the park`() {
        assertTrue(
            queueHoldsLadderOpen(
                queueNonEmpty = true,
                nowMs = 70_000L,
                backgroundedAtMs = 10_000L,
                maxDeferralMs = 300_000L,
            ),
        )
    }

    @Test
    fun `an undeliverable queue stops deferring the park`() {
        // A message queued against a server that is simply switched off would
        // otherwise redial for its full 24h TTL with the screen dark.
        assertFalse(
            queueHoldsLadderOpen(
                queueNonEmpty = true,
                nowMs = 400_000L,
                backgroundedAtMs = 10_000L,
                maxDeferralMs = 300_000L,
            ),
        )
    }

    @Test
    fun `an empty queue never defers the park`() {
        assertFalse(
            queueHoldsLadderOpen(
                queueNonEmpty = false,
                nowMs = 20_000L,
                backgroundedAtMs = 10_000L,
                maxDeferralMs = 300_000L,
            ),
        )
    }

    @Test
    fun `the park deferral is bounded in both directions`() {
        // Both carve-outs must terminate: an idle-but-pending app cannot end
        // up holding the radio indefinitely.
        assertTrue(ConnectionManager.BACKGROUND_PARK_MAX_DEFERRAL_MS > 0L)
        assertTrue(ConnectionManager.UPLOAD_STALL_PARK_MS > 0L)
        assertTrue(
            ConnectionManager.LADDER_PARK_RECHECK_MS <= ConnectionManager.UPLOAD_STALL_PARK_MS,
            "the re-check must be finer than the bound it is meant to observe",
        )
        assertTrue(
            ConnectionManager.LADDER_PARK_RECHECK_MS <=
                ConnectionManager.BACKGROUND_PARK_MAX_DEFERRAL_MS,
        )
    }

    @Test
    fun `an idle backgrounded app still parks and costs nothing`() {
        val policy = visibilityPolicy(
            appInForeground = false,
            hasClient = true,
            parkGraceElapsed = true,
            pendingWireWork = false,
            uploadWireWork = false,
        )

        assertTrue(policy.parkReconnectLadder)
        assertFalse(policy.runHeartbeat)
    }

    // ---- N-10: the "not connected" copy ----

    @Test
    fun `a paired-but-offline server is not told to pair a server`() {
        val text = notConnectedText(ConnectionState.Disconnected, hasPairedServers = true)

        assertFalse(
            text.contains("pair a server", ignoreCase = true),
            "telling a user with a working pairing to pair a server reads as data loss: $text",
        )
    }

    @Test
    fun `an unpaired app still points at the QR flow`() {
        val text = notConnectedText(ConnectionState.Disconnected, hasPairedServers = false)

        assertTrue(text.contains("pair a server", ignoreCase = true))
    }

    @Test
    fun `a terminal failure explains itself verbatim`() {
        val failure = ConnectFailure.AuthRejected()
        assertEquals(
            failure.userMessage,
            notConnectedText(ConnectionState.FailedTerminal(failure), hasPairedServers = true),
        )
    }

    // ---- N-03: which csids are still parked in the durable queue ----

    private fun queuedSend(id: String, csid: Long?): QueuedMessage = QueuedMessage(
        id = id,
        method = "remote.solution_agent.send_message_blocks",
        params = buildJsonObject {
            put("session_id", "s1")
            put(
                "blocks",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", "hi")
                            if (csid != null) {
                                // Numeric primitive — the shape
                                // `stampClientSendId` actually writes.
                                put("_meta", buildJsonObject { put("spk_client_send_id", csid) })
                            }
                        },
                    )
                },
            )
        },
        enqueuedAtMs = 1_000L,
    )

    @Test
    fun `stamped queue entries are surfaced as csids`() {
        val ids = queuedClientSendIdsOf(listOf(queuedSend("q1", 11L), queuedSend("q2", 22L)))

        assertEquals(setOf(11L, 22L), ids)
    }

    @Test
    fun `an unstamped legacy send contributes no csid`() {
        // The pre-stamp text-only `send_message` path, and any payload
        // without blocks[0]._meta. Mapping it to a placeholder would make
        // an unrelated pending-send marker look queued and keep a phantom
        // "Sending" bubble alive forever.
        val ids = queuedClientSendIdsOf(
            listOf(
                queuedSend("q1", csid = null),
                QueuedMessage(
                    id = "q2",
                    method = "remote.solution_agent.send_message",
                    params = buildJsonObject { put("text", "hi") },
                    enqueuedAtMs = 1_000L,
                ),
                queuedSend("q3", 33L),
            ),
        )

        assertEquals(setOf(33L), ids)
    }

    @Test
    fun `an empty queue means no marker is backed by a queued send`() {
        assertTrue(queuedClientSendIdsOf(emptyList()).isEmpty())
    }

    // ---- N-61: dropped-persistence notices ----

    /**
     * Every store that can reach `PersistenceHealth.droppedStores` needs
     * user-visible copy: the collector in [MainViewModel] acknowledges the
     * drop whether or not a notice comes back, so a missing branch means
     * the data is gone and the user is told nothing.
     *
     * The store ids are read **out of the sources** rather than restated
     * here. A store can only be dropped if it is opened through
     * `EncryptedPrefs.open` (that is the only caller of
     * `PersistenceHealth.reportDropped`), so every such call site is found
     * and its id constant resolved. A ninth store therefore fails this test
     * instead of silently going quiet — which is exactly what
     * `spk_inflight_uploads` did for one release, when the Tink migration
     * moved it off its own `EncryptedSharedPreferences` onto the shared
     * opener and nobody added the copy.
     */
    @Test
    fun `every keystore-recoverable store has user-visible copy`() {
        val ids = encryptedStoreIdsFromSources()
        assertTrue(
            ids.size >= 8,
            "source scan resolved only $ids — the scan is broken, not the app",
        )
        for (name in ids) {
            assertNotNull(persistenceDropNotice(name), "no notice copy for $name")
            // The other half of the copy. A store with a notice but no
            // summary vanishes from a coalesced batch — just as quiet as a
            // store with no copy at all, and harder to notice.
            assertNotNull(persistenceDropSummary(name), "no summary copy for $name")
        }
    }

    /**
     * Logical store ids of every
     * `EncryptedPrefs.open(context, X, <legacy format>)` call in the app
     * sources, with `X` resolved to the `const val` string declared in the
     * same file (the invariant the whole data package follows: the store id
     * lives next to the repository that owns it). An argument that cannot be
     * resolved fails rather than being skipped, so the scan cannot quietly
     * shrink to nothing.
     *
     * The third argument is matched but not resolved — it is required at the
     * call site by the compiler, and its only job here is to keep this regex
     * honest about the shape it is scanning for.
     */
    private fun encryptedStoreIdsFromSources(): Set<String> {
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "no sources at ${root.absolutePath}; cwd=${File(".").absolutePath}")
        val callSite =
            Regex("""EncryptedPrefs\.open\s*\(\s*\w+\s*,\s*(\w+)\s*,\s*[\w.]+\s*\)""")
        // Deliberately does not match the closing quote: a raw string cannot
        // end on one without ambiguity, and the capture already stops there.
        val constDecl = Regex("""const\s+val\s+(\w+)\s*(?::\s*\w+\s*)?=\s*"([^"]*)""")
        val ids = mutableSetOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            val consts = constDecl.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
            text.lineSequence().forEach { line ->
                val name = callSite.find(line.substringBefore("//"))?.groupValues?.get(1) ?: return@forEach
                val value = consts[name]
                assertNotNull(value, "${file.name}: cannot resolve `$name` passed to EncryptedPrefs.open")
                ids += value!!
            }
        }
        return ids
    }

    @Test
    fun `an unknown store produces no notice`() {
        assertNull(persistenceDropNotice("spk_something_else"))
    }

    // ---- coalescing: one Keystore loss is ONE event ----

    /**
     * Every store shares one Android Keystore master key, so losing it
     * drops all eight at once. Eight snackbars in a row would be the same
     * event reported eight times.
     */
    @Test
    fun `a whole-batch drop produces one notice, not one per store`() {
        val ids = encryptedStoreIdsFromSources()
        assertTrue(ids.size >= 8, "source scan resolved only $ids — the scan is broken")

        val notice = requireNotNull(coalescedPersistenceDropNotice(ids)) {
            "a batch of every store produced no notice at all"
        }

        // "One notice" is not just the return type: the cause is stated
        // once, not once per store.
        assertEquals(1, notice.split(CAUSE).size - 1, "the cause is repeated in: $notice")
        // And nothing went quiet to achieve that.
        for (name in ids) {
            val summary = requireNotNull(persistenceDropSummary(name))
            // ignoreCase: the first summary in the list opens the sentence.
            assertTrue(notice.contains(summary, ignoreCase = true), "$name is missing from: $notice")
        }
        assertTrue(notice.endsWith(PAIRING_ACTION), "no pairing instruction in: $notice")
    }

    /**
     * The single-store wording is the specific and actionable one, and the
     * single-store case is the one where the user can do something. It is
     * deliberately untouched by coalescing.
     */
    @Test
    fun `one dropped store keeps its own wording`() {
        for (name in encryptedStoreIdsFromSources()) {
            assertEquals(
                persistenceDropNotice(name),
                coalescedPersistenceDropNotice(setOf(name)),
                "coalescing changed the single-store notice for $name",
            )
        }
    }

    /**
     * "Scan the QR code again" is the only action any of these losses has,
     * so it has to survive the summary — the user is otherwise left with a
     * pairing screen and no idea why.
     */
    @Test
    fun `the pairing instruction survives coalescing`() {
        val withPairing = requireNotNull(
            coalescedPersistenceDropNotice(
                setOf(
                    PairingRepository.PREFS_NAME,
                    EncryptedQueueStore.PREFS_NAME,
                    DraftRepository.PREFS_NAME,
                ),
            ),
        )
        assertTrue(withPairing.contains(PAIRING_ACTION), "no pairing instruction in: $withPairing")

        // ...and is not offered when the pairing is intact, since there
        // would be nothing to re-scan.
        val withoutPairing = requireNotNull(
            coalescedPersistenceDropNotice(
                setOf(EncryptedQueueStore.PREFS_NAME, DraftRepository.PREFS_NAME),
            ),
        )
        assertFalse(
            withoutPairing.contains("QR"),
            "offered a QR re-scan for a batch that kept its pairing: $withoutPairing",
        )
        assertTrue(withoutPairing.endsWith(CAUSE), "unexpected trailer in: $withoutPairing")
    }

    /**
     * A batch is acknowledged whether or not copy came back, so an unknown
     * id must not suppress the stores that do have copy, and a batch of
     * nothing but unknown ids must stay silent rather than emit a sentence
     * about nothing.
     */
    @Test
    fun `unknown ids neither speak nor silence the rest`() {
        assertNull(coalescedPersistenceDropNotice(setOf("spk_something_else")))
        assertNull(coalescedPersistenceDropNotice(emptySet()))
        assertEquals(
            persistenceDropNotice(DraftRepository.PREFS_NAME),
            coalescedPersistenceDropNotice(setOf("spk_something_else", DraftRepository.PREFS_NAME)),
        )
    }

    private companion object {
        /** The half-sentence every notice ends the diagnosis with. */
        const val CAUSE = "the device's secure key changed."

        const val PAIRING_ACTION = "Scan the QR code again."
    }
}
