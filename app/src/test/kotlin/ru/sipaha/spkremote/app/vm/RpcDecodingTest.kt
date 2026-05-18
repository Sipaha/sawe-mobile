package ru.sipaha.spkremote.app.vm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import ru.sipaha.spkremote.core.JsonRpcError
import ru.sipaha.spkremote.core.JsonRpcResponse

/**
 * Branch coverage for the `:app` `decodeResultOrThrow` helper:
 *   - happy path: envelope success + valid `structuredContent` → returns the
 *     deserialised DTO,
 *   - JSON-RPC envelope error → throws carrying the server-supplied message,
 *   - MCP tool-level error (`isError: true`) → throws with the
 *     `content[0].text` string,
 *   - response carries `result` but no `structuredContent` field → throws
 *     "missing structuredContent" (the precise message is contractual —
 *     downstream call sites substring-match on it).
 */
class RpcDecodingTest {

    @Serializable
    data class FakeDto(val foo: String)

    private fun jsonResult(structured: JsonElement?): JsonObject = buildJsonObject {
        if (structured != null) put("structuredContent", structured)
    }

    @Test
    fun `happy path decodes structuredContent into the dto`() {
        val resp = JsonRpcResponse(
            id = 1L,
            result = jsonResult(buildJsonObject { put("foo", "bar") }),
            error = null,
        )

        val dto = resp.decodeResultOrThrow(FakeDto.serializer())

        assertEquals("bar", dto.foo)
    }

    @Test
    fun `envelope error throws with the servers message`() {
        val resp = JsonRpcResponse(
            id = 1L,
            result = null,
            error = JsonRpcError(code = -32601, message = "method not found"),
        )

        val thrown = assertThrows(IllegalStateException::class.java) {
            resp.decodeResultOrThrow(FakeDto.serializer())
        }
        assertEquals("method not found", thrown.message)
    }

    @Test
    fun `tool-level isError throws with the content text`() {
        val contentItem: JsonElement = buildJsonObject {
            put("type", "text")
            put("text", "no_active_workspace_for_solution")
        }
        val result = buildJsonObject {
            put("isError", true)
            put(
                "content",
                buildJsonArray { add(contentItem) },
            )
        }
        val resp = JsonRpcResponse(id = 1L, result = result, error = null)

        val thrown = assertThrows(IllegalStateException::class.java) {
            resp.decodeResultOrThrow(FakeDto.serializer())
        }
        assertNotNull(thrown.message)
        assertEquals("no_active_workspace_for_solution", thrown.message)
    }

    @Test
    fun `missing structuredContent throws the contractual message`() {
        // A success-shaped envelope with `result` present but no
        // `structuredContent` (some tools return free-form content only).
        // Callers like the create-session auto-open path substring-match
        // on the error text, so the message must stay stable.
        val resp = JsonRpcResponse(
            id = 1L,
            result = jsonResult(structured = null),
            error = null,
        )

        val thrown = assertThrows(IllegalStateException::class.java) {
            resp.decodeResultOrThrow(FakeDto.serializer())
        }
        assertEquals("missing structuredContent", thrown.message)
    }
}
