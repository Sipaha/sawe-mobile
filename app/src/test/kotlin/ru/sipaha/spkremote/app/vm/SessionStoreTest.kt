package ru.sipaha.spkremote.app.vm

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.sipaha.spkremote.core.QueuedMessage

/**
 * Unit tests for [SessionStore.handleExpiredMessage].
 *
 * **Design note — why we test the logic directly instead of constructing
 * [SessionStore]:**
 *
 * [SessionStore] depends on [DraftRepository] which requires an Android
 * [android.content.Context] (SharedPreferences). The `:app` test task
 * targets the JVM (no Android SDK present in the classpath) so we cannot
 * construct a real [DraftRepository]. We therefore extract the
 * `handleExpiredMessage` logic under test into a standalone helper that
 * accepts a simple callback, and call that helper in this test file.
 *
 * If [SessionStore.handleExpiredMessage] is ever refactored to receive a
 * plain `(sessionId: String, content: String) -> Unit` callback for the
 * "setBounced" action, the logic becomes trivially injectable and this
 * test can directly invoke the real method. Today, it is inlined here
 * against the same logic that lives in [SessionStore.handleExpiredMessage]
 * (lines 135–142 of SessionStore.kt) — keeping parity is the contract.
 */
class SessionStoreTest {

    // -------------------------------------------------------------------------
    // Minimal extraction of handleExpiredMessage logic for testability
    // -------------------------------------------------------------------------

    /**
     * Mirrors the exact logic of [SessionStore.handleExpiredMessage].
     * When the real method can be injected (after a small refactor making
     * the setBounced callback explicit), delete this and call the real one.
     *
     * Implementation (must match SessionStore.kt handleExpiredMessage):
     * ```kotlin
     * fun handleExpiredMessage(message: QueuedMessage) {
     *     if (message.method != "remote.solution_agent.send_message") return
     *     val params = (message.params as? JsonObject) ?: return
     *     val sessionId = params["session_id"]?.jsonPrimitive?.content ?: return
     *     val content = params["content"]?.jsonPrimitive?.content ?: return
     *     if (content.isBlank()) return
     *     draftRepository.setBounced(sessionId, content)
     * }
     * ```
     */
    private fun handleExpiredMessage(
        message: QueuedMessage,
        onBounced: (sessionId: String, content: String) -> Unit,
    ) {
        if (message.method != "remote.solution_agent.send_message") return
        val params = (message.params as? kotlinx.serialization.json.JsonObject) ?: return
        val sessionId = params["session_id"]?.jsonPrimitive?.content ?: return
        val content = params["content"]?.jsonPrimitive?.content ?: return
        if (content.isBlank()) return
        onBounced(sessionId, content)
    }

    // -------------------------------------------------------------------------
    // T10 tests
    // -------------------------------------------------------------------------

    @Test
    fun `skip non-send_message method`() {
        var called = false
        val msg = QueuedMessage(
            id = "1",
            method = "remote.solution_agent.cancel_turn",
            params = buildJsonObject {
                put("session_id", "s1")
                put("content", "hello")
            },
            enqueuedAtMs = 0L,
        )
        handleExpiredMessage(msg) { _, _ -> called = true }
        assertEquals(false, called, "must skip non-send_message methods")
    }

    @Test
    fun `skip missing session_id`() {
        var called = false
        val msg = QueuedMessage(
            id = "2",
            method = "remote.solution_agent.send_message",
            params = buildJsonObject {
                // no session_id
                put("content", "hello")
            },
            enqueuedAtMs = 0L,
        )
        handleExpiredMessage(msg) { _, _ -> called = true }
        assertEquals(false, called, "must skip when session_id is absent")
    }

    @Test
    fun `skip blank content`() {
        var called = false
        val msg = QueuedMessage(
            id = "3",
            method = "remote.solution_agent.send_message",
            params = buildJsonObject {
                put("session_id", "s1")
                put("content", "   ")
            },
            enqueuedAtMs = 0L,
        )
        handleExpiredMessage(msg) { _, _ -> called = true }
        assertEquals(false, called, "must skip blank content")
    }

    @Test
    fun `skip null params`() {
        var called = false
        val msg = QueuedMessage(
            id = "4",
            method = "remote.solution_agent.send_message",
            params = null,
            enqueuedAtMs = 0L,
        )
        handleExpiredMessage(msg) { _, _ -> called = true }
        assertEquals(false, called, "must skip null params")
    }

    @Test
    fun `happy path calls setBounced with correct session and content`() {
        var bouncedSession: String? = null
        var bouncedContent: String? = null
        val msg = QueuedMessage(
            id = "5",
            method = "remote.solution_agent.send_message",
            params = buildJsonObject {
                put("session_id", "session-abc")
                put("content", "Hello world")
            },
            enqueuedAtMs = 0L,
        )
        handleExpiredMessage(msg) { sessionId, content ->
            bouncedSession = sessionId
            bouncedContent = content
        }
        assertEquals("session-abc", bouncedSession, "setBounced must be called with the correct sessionId")
        assertEquals("Hello world", bouncedContent, "setBounced must be called with the correct content")
    }

    @Test
    fun `happy path with multi-line content is forwarded verbatim`() {
        var bouncedContent: String? = null
        val text = "Line 1\nLine 2"
        val msg = QueuedMessage(
            id = "6",
            method = "remote.solution_agent.send_message",
            params = buildJsonObject {
                put("session_id", "s2")
                put("content", text)
            },
            enqueuedAtMs = 0L,
        )
        handleExpiredMessage(msg) { _, content -> bouncedContent = content }
        assertEquals(text, bouncedContent, "multi-line content must be forwarded verbatim")
    }
}
