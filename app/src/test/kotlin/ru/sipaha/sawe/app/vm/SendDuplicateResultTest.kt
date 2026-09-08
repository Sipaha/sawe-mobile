package ru.sipaha.sawe.app.vm

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.sipaha.sawe.core.JsonRpcError
import ru.sipaha.sawe.core.JsonRpcResponse
import ru.sipaha.sawe.core.SendDeliveryDto
import ru.sipaha.sawe.core.ServerFeatures
import ru.sipaha.sawe.core.WireFeature

/**
 * How the send path reads a `send_message_blocks` response
 * ([interpretSendResponse]).
 *
 * The rule with teeth: `delivery == duplicate` is a **success**. It is the
 * intended outcome of the §4.6 replay — the server recognised a
 * `spk_client_send_id` it had already accepted, started no second turn, and
 * enqueued nothing. Surfacing it as an error would put a red toast under a
 * message that is sitting in the transcript, and — worse — send the text back
 * to the composer for the user to post a second time, which is exactly the
 * duplicate the dedupe exists to prevent.
 */
class SendDuplicateResultTest {

    private fun response(structured: Any? = Unit, toolError: String? = null): JsonRpcResponse =
        JsonRpcResponse(
            id = 1,
            result = buildJsonObject {
                if (toolError != null) {
                    put("isError", true)
                    put(
                        "content",
                        buildJsonArray {
                            add(buildJsonObject { put("type", "text"); put("text", toolError) })
                        },
                    )
                }
                when (structured) {
                    Unit -> Unit // no structuredContent at all
                    is Pair<*, *> -> put(
                        "structuredContent",
                        buildJsonObject {
                            put("delivery", structured.first as String)
                            put(
                                "client_send_ids",
                                buildJsonArray { (structured.second as List<*>).forEach { add(JsonPrimitive(it as Long)) } },
                            )
                        },
                    )
                }
            },
        )

    @Test
    fun `a duplicate is a success carrying the csids the server recognised`() {
        val result = interpretSendResponse(response("duplicate" to listOf(1_757_232_041_123L)))
        assertEquals(SendDeliveryDto.Duplicate, result.delivery)
        assertEquals(listOf(1_757_232_041_123L), result.clientSendIds)
    }

    @Test
    fun `an accepted send decodes to Accepted`() {
        val result = interpretSendResponse(response("accepted" to listOf(7L)))
        assertEquals(SendDeliveryDto.Accepted, result.delivery)
    }

    @Test
    fun `a server that predates the field reports nothing, never Accepted`() {
        // Absent means "this build has no dedupe", which is a different fact
        // from "it accepted this call" — the send path's own "no error
        // envelope ⇒ delivered" rule is what covers it, unchanged.
        assertNull(interpretSendResponse(response()).delivery)
        assertTrue(interpretSendResponse(response()).clientSendIds.isEmpty())
    }

    @Test
    fun `an unparseable structuredContent degrades instead of failing the send`() {
        val garbage = JsonRpcResponse(
            id = 1,
            result = buildJsonObject { put("structuredContent", buildJsonObject { put("delivery", 42) }) },
        )
        assertNull(interpretSendResponse(garbage).delivery)
    }

    @Test
    fun `an error envelope is still an ambiguous link failure`() {
        // Delivery unknown — including the proxy's own 30 s timeout, by which
        // point the editor may well have applied the send.
        val resp = JsonRpcResponse(id = 1, error = JsonRpcError(code = -32603, message = "call timed out"))
        val thrown = assertThrows(SendEnvelopeException::class.java) { interpretSendResponse(resp) }
        assertTrue(thrown.raw.contains("timed out"))
    }

    @Test
    fun `a tool-level refusal is still unambiguous`() {
        assertThrows(SendRejectedByServerException::class.java) {
            interpretSendResponse(response(structured = Unit, toolError = "session no longer exists"))
        }
    }

    @Test
    fun `a verdict this build does not know decodes to Unknown instead of throwing`() {
        // The regression this pins: without the lenient serializer an
        // unrecognised string aborted the decode of the whole
        // SendMessageBlocksResult, `decodeResultOrThrow` propagated the
        // SerializationException, and the send path reported a FAILED send —
        // an error toast and a bounced draft for a message the server had
        // just accepted. Degrading is the whole point.
        val result = interpretSendResponse(response("merged_into_pending" to listOf(7L)))
        assertEquals(SendDeliveryDto.Unknown, result.delivery)
        assertEquals(listOf(7L), result.clientSendIds, "the rest of the envelope still decodes")
    }

    @Test
    fun `Unknown is not Duplicate, and is kept distinct from absent`() {
        val unknown = interpretSendResponse(response("something_new" to listOf(7L))).delivery
        assertNotEquals(SendDeliveryDto.Duplicate, unknown, "only `duplicate` may mean de-duplicated")
        assertNotEquals(SendDeliveryDto.Accepted, unknown)
        assertNotNull(
            unknown,
            "`null` means the server predates dedupe; Unknown means it is NEWER than " +
                "this build. Same consequence, different facts — collapsing them loses " +
                "the only signal that says an upgrade is what's needed",
        )
    }

    @Test
    fun `no verdict is ever evidence for a replay`() {
        // The §4.6 gate reads the negotiated feature set and the payload's own
        // stamp, never the response — so it cannot be talked into a replay by
        // a verdict it does not understand.
        val stamped = SendRetryContext(
            featuresAtDispatch = ServerFeatures(
                tokens = setOf(WireFeature.CSID_DEDUPE),
                serverInstanceId = "inst-1",
                csidDedupeWindowMs = 86_400_000L,
            ),
            currentServerInstanceId = "inst-1",
            dispatchedAtMs = 1_000L,
            nowMs = 2_000L,
            stampedOnTheWire = true,
        )
        assertTrue(canRequeueAmbiguousSend(stamped))
        assertFalse(
            canRequeueAmbiguousSend(stamped.copy(stampedOnTheWire = false)),
            "an unstamped send is claimed by nothing, whatever `delivery` said",
        )
    }
}
