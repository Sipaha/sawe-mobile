package ru.sipaha.spkremote.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class JsonRpcEnvelopeTest {

    @Test
    fun `request serialises and deserialises with params`() {
        val params = buildJsonObject { put("kind", "ping") }
        val request = JsonRpcRequest(method = "remote.editor.capabilities", params = params, id = 7)
        val encoded = JsonRpc.encodeRequest(request)
        assertTrue(encoded.contains("\"jsonrpc\":\"2.0\""))
        assertTrue(encoded.contains("\"method\":\"remote.editor.capabilities\""))
        assertTrue(encoded.contains("\"id\":7"))
        assertTrue(encoded.contains("\"kind\":\"ping\""))

        val roundtrip = JsonRpc.json.decodeFromString(JsonRpcRequest.serializer(), encoded)
        assertEquals("remote.editor.capabilities", roundtrip.method)
        assertEquals(7L, roundtrip.id)
        assertEquals("2.0", roundtrip.jsonRpc)
        assertNotNull(roundtrip.params)
        assertEquals("\"ping\"", (roundtrip.params as JsonObject)["kind"].toString())
    }

    @Test
    fun `request without params serialises without a params field`() {
        val request = JsonRpcRequest(method = "remote.editor.capabilities", id = 1)
        val encoded = JsonRpc.encodeRequest(request)
        assertTrue(encoded.contains("\"method\":\"remote.editor.capabilities\""))
        assertTrue(!encoded.contains("\"params\""))
    }

    @Test
    fun `success response parses with result`() {
        val text = """{"jsonrpc":"2.0","id":1,"result":{"protocol_version":"1.0"}}"""
        val resp = JsonRpc.decodeResponse(text)
        assertEquals(1L, resp.id)
        assertNotNull(resp.result)
        assertNull(resp.error)
    }

    @Test
    fun `error response parses with error envelope`() {
        val text = """{"jsonrpc":"2.0","error":{"code":-32601,"message":"Method not found"},"id":1}"""
        val resp = JsonRpc.decodeResponse(text)
        assertEquals(1L, resp.id)
        assertNull(resp.result)
        val err = assertNotNull(resp.error)
        assertEquals(-32601, err.code)
        assertEquals("Method not found", err.message)
    }

    @Test
    fun `error response with optional data field parses`() {
        val text = """{"jsonrpc":"2.0","error":{"code":1,"message":"oops","data":{"hint":"retry"}},"id":2}"""
        val resp = JsonRpc.decodeResponse(text)
        val err = assertNotNull(resp.error)
        val data = assertNotNull(err.data)
        assertTrue(data.toString().contains("retry"))
    }

    @Test
    fun `response tolerates unknown extra fields`() {
        val text = """{"jsonrpc":"2.0","id":3,"result":{"x":1},"server_extra":"hello"}"""
        val resp = JsonRpc.decodeResponse(text)
        assertEquals(3L, resp.id)
        assertNotNull(resp.result)
    }

    @Test
    fun `toolError surfaces text from isError true responses`() {
        // The exact shape `handle_call_tool` emits when a tool's run()
        // returns Err — `content[0].text` carries the formatted error,
        // and `structuredContent` is omitted entirely (see types.rs
        // `skip_serializing_if = "Option::is_none"`).
        val text =
            """{"jsonrpc":"2.0","id":7,"result":{"content":[{"type":"text","text":"no_active_workspace_for_solution: open Solution lena-bug-0606"}],"isError":true}}"""
        val resp = JsonRpc.decodeResponse(text)
        assertEquals(null, resp.error)
        val toolErr = assertNotNull(resp.toolError())
        assertTrue(toolErr.contains("no_active_workspace_for_solution"))
        assertEquals(null, resp.structuredContent())
    }

    @Test
    fun `toolError returns null on success responses`() {
        val text =
            """{"jsonrpc":"2.0","id":8,"result":{"content":[{"type":"text","text":"ok"}],"isError":false,"structuredContent":{"session_id":"sess-1"}}}"""
        val resp = JsonRpc.decodeResponse(text)
        assertEquals(null, resp.toolError())
        assertNotNull(resp.structuredContent())
    }
}
