package ru.sipaha.sawe.app.vm

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.sipaha.sawe.core.JsonRpcError
import ru.sipaha.sawe.core.JsonRpcResponse
import ru.sipaha.sawe.core.SUPPORTED_WIRE_SCHEMA_VERSION
import ru.sipaha.sawe.core.ServerFeatures
import ru.sipaha.sawe.core.WireFeature

/**
 * What the `editor.capabilities` gate is allowed to turn ON.
 *
 * Feature negotiation is the only thing standing between a new client and a
 * `-32602` from an old desktop, because every params struct the gated
 * parameters ride in is `deny_unknown_fields`. So the rule these tests pin is
 * one-directional: a token may only ever come from a capabilities object that
 * decoded cleanly on a compatible server, and every other outcome — a failed
 * probe, an incompatible peer, a server that predates negotiation — must
 * collapse to [ServerFeatures.NONE], i.e. to the pre-negotiation wire.
 *
 * The complementary half — that the client drops the set on every transition
 * out of `Connected` — lives on `RemoteClient` and is covered in `:core`;
 * there is no socket at this layer to drive.
 */
class ServerFeatureGateTest {

    private fun capabilitiesResponse(
        wire: Int = SUPPORTED_WIRE_SCHEMA_VERSION,
        features: List<String>? = listOf(
            WireFeature.ENTRY_BODY_DELTA,
            WireFeature.OMIT_PREVIEW,
            WireFeature.CSID_DEDUPE,
            WireFeature.QUIET_MESSAGE_APPENDED,
        ),
        instanceId: String? = "8f1c2a90-5b3e-4d17-9c2f-6a0d1e7b4c33",
        windowMs: Long? = 86_400_000L,
    ): JsonRpcResponse = JsonRpcResponse(
        id = 1,
        result = buildJsonObject {
            put(
                "structuredContent",
                buildJsonObject {
                    put("protocol_version", "2024-11-05")
                    put("wire_schema_version", wire)
                    if (features != null) {
                        put("wire_features", buildJsonArray { features.forEach { add(JsonPrimitive(it)) } })
                    }
                    if (instanceId != null) put("server_instance_id", instanceId)
                    if (windowMs != null) put("csid_dedupe_window_ms", windowMs)
                },
            )
        },
    )

    @Test
    fun `a compatible server's advertised tokens become the connection's feature set`() {
        val decision = schemaGate(Result.success(capabilitiesResponse()))
        val features = featuresFromGate(decision)
        assertTrue(features.has(WireFeature.ENTRY_BODY_DELTA))
        assertTrue(features.has(WireFeature.OMIT_PREVIEW))
        assertTrue(features.has(WireFeature.CSID_DEDUPE))
        assertTrue(features.has(WireFeature.QUIET_MESSAGE_APPENDED))
        assertEquals("8f1c2a90-5b3e-4d17-9c2f-6a0d1e7b4c33", features.serverInstanceId)
        assertEquals(86_400_000L, features.csidDedupeWindowMs)
    }

    @Test
    fun `a server that predates negotiation gets nothing`() {
        // `wire_features` absent — not empty. Both mean "no features" today,
        // but only the absent case means "this peer never heard of them".
        val decision = schemaGate(
            Result.success(capabilitiesResponse(features = null, instanceId = null, windowMs = null)),
        )
        val features = featuresFromGate(decision)
        assertEquals(ServerFeatures.NONE, features)
        assertTrue(features.tokens.isEmpty())
        assertNull(features.serverInstanceId)
    }

    @Test
    fun `an unknown token is ignored, never rejected`() {
        val decision = schemaGate(
            Result.success(capabilitiesResponse(features = listOf("teleportation", WireFeature.OMIT_PREVIEW))),
        )
        val features = featuresFromGate(decision)
        assertTrue(features.has(WireFeature.OMIT_PREVIEW), "the known token still lands")
        assertFalse(features.has(WireFeature.ENTRY_BODY_DELTA))
    }

    @Test
    fun `a probe that failed turns nothing on`() {
        // The desktop answers -32603 for every call while its local MCP proxy
        // is still binding. That says nothing about the peer's features, so
        // the connection must run the legacy wire for its whole life rather
        // than guess.
        val envelope = JsonRpcResponse(
            id = 1,
            error = JsonRpcError(code = -32603, message = "proxy not ready"),
        )
        val decision = schemaGate(Result.success(envelope))
        assertTrue(decision is SchemaGateDecision.ProbeFailed)
        assertEquals(ServerFeatures.NONE, featuresFromGate(decision))

        val transportDied = schemaGate(Result.failure(IllegalStateException("not connected")))
        assertEquals(ServerFeatures.NONE, featuresFromGate(transportDied))
    }

    @Test
    fun `an incompatible server turns nothing on`() {
        // It is about to be torn down; publishing its tokens would arm gated
        // parameters on a connection with nothing on the other end.
        val decision = schemaGate(
            Result.success(capabilitiesResponse(wire = SUPPORTED_WIRE_SCHEMA_VERSION + 1)),
        )
        assertTrue(decision is SchemaGateDecision.Incompatible)
        assertEquals(ServerFeatures.NONE, featuresFromGate(decision))
    }

    @Test
    fun `suppress_kinds is asked for only when the peer advertised it`() {
        // Before the gate answers the feature set is empty, which is exactly
        // the state the reconnect replay's `subscribe` runs in — so that call
        // asks to suppress nothing and the server behaves as it always has.
        assertTrue(suppressKindsFor(ServerFeatures.NONE).isEmpty())
        assertTrue(
            suppressKindsFor(ServerFeatures(tokens = setOf(WireFeature.OMIT_PREVIEW))).isEmpty(),
            "an unrelated token must not enable suppression",
        )
        assertEquals(
            listOf("agent_session_message_appended"),
            suppressKindsFor(ServerFeatures(tokens = setOf(WireFeature.QUIET_MESSAGE_APPENDED))),
        )
    }
}
