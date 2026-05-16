package ru.sipaha.spkremote.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Opt-in end-to-end probe. Excluded from the default `:core:test` run via the
 * JUnit `excludeTags("integration")` filter in `core/build.gradle.kts`.
 *
 * Provide a pairing URL from a live editor instance:
 *
 *     SPK_EDITOR_PAIRING_URL='spk-remote://...' \
 *         ./gradlew :core:test -DincludeTags=integration
 *
 * The probes are grouped into a single `@Test` so handshake setup is paid
 * once and the assertions are ordered (the failure mode of test 4 below — the
 * allow-list negative case — needs a live `Established` socket from test 1).
 * Each step has its own `assert*` calls so failure messages point at the
 * specific probe that broke.
 */
@Tag("integration")
class LiveEditorIntegrationTest {

    @Test
    fun `connects, probes allow-list, subscribes`() = runBlocking {
        val raw = System.getenv("SPK_EDITOR_PAIRING_URL")
        assumeTrue(!raw.isNullOrBlank(), "SPK_EDITOR_PAIRING_URL not set; skipping")

        val url = PairingUrl.parse(raw!!).getOrThrow()
        val client = RemoteClient(url)
        try {
            // 1. Connect (TLS pin + HMAC handshake).
            withTimeout(15_000) { client.connect().getOrThrow() }

            // 2. remote.editor.capabilities — must succeed and (best effort)
            //    advertise a protocol_version field. The exact key path is
            //    server-side; we only assert it appears somewhere in the body.
            val caps = withTimeout(15_000) { client.call("remote.editor.capabilities") }
            assertNull(caps.error, "editor.capabilities should not error: ${caps.error}")
            assertNotNull(caps.result, "editor.capabilities should return a result")
            val capsBody = caps.result.toString()
            assertTrue(
                capsBody.contains("protocol_version"),
                "expected protocol_version in capabilities body, got: $capsBody",
            )

            // 3. remote.solutions.list — must succeed. The result may be an
            //    empty array if no solutions are open; that's fine.
            val solutions = withTimeout(15_000) { client.call("remote.solutions.list") }
            assertNull(solutions.error, "solutions.list should not error: ${solutions.error}")
            assertNotNull(solutions.result, "solutions.list should return a result")

            // 4. remote.lsp.start — negative probe. R-4's proxy allow-list does
            //    NOT include this method; the server must reject with
            //    JSON-RPC method-not-found (-32601). This proves the filter
            //    is on, not just that the proxy is willing to forward bytes.
            val rejected = withTimeout(15_000) { client.call("remote.lsp.start") }
            assertNotNull(rejected.error, "remote.lsp.start should be rejected by allow-list")
            assertEquals(
                -32601,
                rejected.error!!.code,
                "remote.lsp.start should produce method-not-found, got: ${rejected.error}",
            )

            // 5. remote.editor.subscribe — params object with a `kinds`
            //    array of event-kind strings. Server returns Ok on success.
            val subParams = buildJsonObject {
                put(
                    "kinds",
                    buildJsonArray {
                        add(JsonPrimitive("agent_session_message_appended"))
                    },
                )
            }
            val subscribed = withTimeout(15_000) {
                client.call("remote.editor.subscribe", subParams)
            }
            assertNull(
                subscribed.error,
                "editor.subscribe should not error: ${subscribed.error}",
            )
        } finally {
            client.close()
        }

        // 6. After close(), further calls must NOT succeed. We don't pin the
        //    exact failure shape (CompletableDeferred cancellation surfaces as
        //    a CancellationException; the websocket-refused path throws
        //    IllegalStateException) — just that the call doesn't return Ok.
        val closed = runCatching {
            withTimeout(2_000) { client.call("remote.editor.capabilities") }
        }
        assertTrue(
            closed.isFailure || closed.getOrNull()?.error != null,
            "post-close call should not succeed, got: ${closed.getOrNull()}",
        )
    }
}
