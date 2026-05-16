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
        assertTrue(resp.isSuccess)
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
        assertTrue(!resp.isSuccess)
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
}
