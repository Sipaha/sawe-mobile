package ru.sipaha.spkremote.app.vm

import kotlinx.serialization.DeserializationStrategy
import ru.sipaha.spkremote.core.JsonRpc
import ru.sipaha.spkremote.core.JsonRpcResponse

/**
 * Generic decode-or-throw for `tools/call`-shaped JSON-RPC responses.
 *
 * Centralises the boilerplate that used to be inlined at every RPC
 * call site:
 *   - JSON-RPC envelope `error` → throw with the server-supplied
 *     `error.message`,
 *   - tool-level `isError: true` → throw with `content[0].text`,
 *   - missing `structuredContent` → throw "missing structuredContent"
 *     (compatible with the existing `error("missing structuredContent")`
 *     callers — same message text so any substring-matching code path
 *     keeps working),
 *   - otherwise decode `structuredContent` into [T] via [deserializer].
 *
 * The exception thrown is plain [IllegalStateException] (same as
 * `error(...)`) so `runCatching { ... }.mapCatching { resp.decodeResultOrThrow(...) }`
 * chains continue to land in the existing `.onFailure { ... }` handlers
 * verbatim — no exception-type changes propagate to call sites.
 */
internal fun <T> JsonRpcResponse.decodeResultOrThrow(
    deserializer: DeserializationStrategy<T>,
): T {
    val err = error
    if (err != null) error(err.message)
    val toolErr = toolError()
    if (toolErr != null) error(toolErr)
    val result = structuredContent() ?: error("missing structuredContent")
    return JsonRpc.json.decodeFromJsonElement(deserializer, result)
}
