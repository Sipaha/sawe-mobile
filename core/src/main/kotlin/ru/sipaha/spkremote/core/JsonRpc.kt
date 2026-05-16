package ru.sipaha.spkremote.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * JSON-RPC 2.0 envelopes spoken between the Android client and the editor's
 * embedded MCP socket (proxied through R-4's `remote.*` forwarder).
 *
 * Only the subset actually used over the wire is modelled here — the editor's
 * MCP server permits omitting `params` for parameterless methods, and we
 * intentionally allow `id` to be a number or a string to keep parity with the
 * server side.
 */

private val jsonFormat: Json = Json {
    ignoreUnknownKeys = true
    // We deliberately keep `encodeDefaults = true` (the kotlinx default) so that
    // `"jsonrpc":"2.0"` is always present on the wire, matching the JSON-RPC 2.0
    // spec. `explicitNulls = false` then drops nullable params/result/error/id
    // when they're null, so the wire payload stays compact.
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
data class JsonRpcRequest(
    @SerialName("jsonrpc") val jsonRpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null,
    val id: Long,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

@Serializable
data class JsonRpcResponse(
    @SerialName("jsonrpc") val jsonRpc: String = "2.0",
    val id: Long? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
) {
    val isSuccess: Boolean get() = error == null && result != null
}

object JsonRpc {
    val json: Json get() = jsonFormat

    fun encodeRequest(req: JsonRpcRequest): String =
        jsonFormat.encodeToString(JsonRpcRequest.serializer(), req)

    fun decodeResponse(text: String): JsonRpcResponse =
        jsonFormat.decodeFromString(JsonRpcResponse.serializer(), text)
}
