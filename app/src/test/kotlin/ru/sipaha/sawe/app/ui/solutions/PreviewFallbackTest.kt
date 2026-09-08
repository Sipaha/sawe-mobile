package ru.sipaha.sawe.app.ui.solutions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.sipaha.sawe.core.EntryRoleDto
import ru.sipaha.sawe.core.EntrySummary
import ru.sipaha.sawe.core.JsonRpc

/**
 * `preview` is no longer guaranteed to be on the wire.
 *
 * A server honouring `omit_preview_when_markdown` omits it for every
 * non-user entry it sent a body for, and the DTO defaults it to `""`. So
 * every renderer has to read the body first — which is what
 * [entryBodyText] is for, and what these tests pin: an entry that arrives
 * with a body and no preview must render the body, not an empty bubble.
 *
 * The single-accessor shape is deliberate. The alternative (`markdown ?:
 * preview` inlined at each of the eight call sites) is correct only for as
 * long as nobody adds a ninth, and the failure mode of getting it wrong is a
 * blank message rather than a crash.
 */
class PreviewFallbackTest {

    private fun entry(
        role: EntryRoleDto,
        preview: String = "",
        markdown: String? = null,
    ) = EntrySummary(role = role, index = 1, preview = preview, markdown = markdown)

    @Test
    fun `an entry with a body and a suppressed preview renders the body`() {
        for (role in EntryRoleDto.entries) {
            assertEquals(
                "the full body",
                entryBodyText(entry(role, preview = "", markdown = "the full body")),
                "$role: a suppressed preview must never win over the body it duplicated",
            )
        }
    }

    @Test
    fun `an entry with no body falls back to the preview it still carries`() {
        // `include_full_content:false` pages, and the streaming placeholder
        // window before the body lands. Suppression never applies to these —
        // the server only drops a preview when it sent a body beside it.
        assertEquals(
            "**Bash** `cargo test`…",
            entryBodyText(entry(EntryRoleDto.ToolCall, preview = "**Bash** `cargo test`…")),
        )
    }

    @Test
    fun `the body wins even when both are present`() {
        // Today's servers, and every old one: `preview` is the first ~200
        // scalar values of `markdown`, so preferring it would silently
        // truncate the message.
        assertEquals(
            "line one\nline two\nline three",
            entryBodyText(
                entry(EntryRoleDto.Assistant, preview = "line one…", markdown = "line one\nline two\nline three"),
            ),
        )
    }

    @Test
    fun `an entry JSON with no preview key decodes and renders`() {
        // End to end from the wire: this is the exact shape a server with
        // `omit_preview` emits for a streaming assistant reply.
        val decoded = JsonRpc.json.decodeFromString(
            EntrySummary.serializer(),
            """{"role":"assistant","index":42,"markdown":"grown body","markdown_len":10}""",
        )
        assertEquals("", decoded.preview, "the DTO must default it, not fail to decode")
        assertEquals("grown body", entryBodyText(decoded))
    }
}
