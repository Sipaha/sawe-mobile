package ru.sipaha.spkremote.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [stripRoleHeading].
 *
 * The function strips the upstream `acp_thread` role banner from the
 * leading line of a markdown / preview string so the chat-bubble renderer
 * doesn't double-encode the role (alignment + colour already encodes it)
 * and so optimistic-bubble dedupe by-string-equality fires correctly.
 *
 * The function also `.trim()`s the result — that's part of the documented
 * behaviour so a `## User\n\nhello` round-trips to `hello` rather than
 * `\nhello`.
 */
class StripRoleHeadingTest {

    @Test
    fun `strips bare User heading`() {
        assertEquals("hello", stripRoleHeading("## User\nhello"))
    }

    @Test
    fun `strips User with checkpoint suffix`() {
        assertEquals("body", stripRoleHeading("## User (checkpoint)\nbody"))
    }

    @Test
    fun `strips Assistant heading`() {
        assertEquals("body", stripRoleHeading("## Assistant\nbody"))
    }

    @Test
    fun `strips Plan heading`() {
        assertEquals("body", stripRoleHeading("## Plan\nbody"))
    }

    @Test
    fun `strips Tool heading`() {
        assertEquals("body", stripRoleHeading("## Tool\nbody"))
    }

    @Test
    fun `strips System heading`() {
        assertEquals("body", stripRoleHeading("## System\nbody"))
    }

    @Test
    fun `passes blank input through unchanged after trim`() {
        assertEquals("", stripRoleHeading(""))
        assertEquals("", stripRoleHeading("   \n  "))
    }

    @Test
    fun `does NOT strip a non-role heading`() {
        // The regex is intentionally narrow — only the upstream-emitted
        // role names should be eligible. A model-written `## Step 1`
        // must survive verbatim, including the trailing body. The
        // function still .trim()s the result so any leading/trailing
        // whitespace is normalised but the heading line itself stays.
        val input = "## Step 1\nbody"
        assertEquals(input, stripRoleHeading(input))
    }

    @Test
    fun `does NOT strip headings that look similar but are not in the role set`() {
        // `## Users` (plural) is not `User` — must not match. Same for
        // `## ASSISTANT` is fine (regex is IGNORE_CASE) but `## Assist`
        // alone should not match because the regex anchors on the full
        // role name.
        assertEquals("## Users\nbody", stripRoleHeading("## Users\nbody"))
        assertEquals("## Assist\nbody", stripRoleHeading("## Assist\nbody"))
    }

    @Test
    fun `is case-insensitive on the role name`() {
        // The regex is built with IGNORE_CASE. Server-side casing is
        // stable but tests pin the contract so a future case-fold change
        // is intentional, not accidental.
        assertEquals("body", stripRoleHeading("## user\nbody"))
        assertEquals("body", stripRoleHeading("## ASSISTANT\nbody"))
    }

    @Test
    fun `strips only the leading heading line in a multiline body`() {
        // Headings further inside the body must survive — `stripRoleHeading`
        // only touches the prefix.
        val md = "## User\nHere is my plan.\n\n## Plan\n1. step"
        assertEquals("Here is my plan.\n\n## Plan\n1. step", stripRoleHeading(md))
    }

    @Test
    fun `handles multiple blank lines between heading and body`() {
        // The regex consumes one-or-more trailing newlines so a typical
        // markdown-style `## User\n\nhello` (blank line between heading
        // and body) is collapsed cleanly.
        assertEquals("hello", stripRoleHeading("## User\n\nhello"))
        assertEquals("hello", stripRoleHeading("## User\n\n\nhello"))
    }

    @Test
    fun `tolerates leading whitespace before the heading`() {
        // The regex pattern starts with `\s*##` to absorb stray indentation
        // that a previous formatter might have left behind.
        assertEquals("hello", stripRoleHeading("   ## User\nhello"))
        assertEquals("hello", stripRoleHeading("\n## User\nhello"))
    }

    @Test
    fun `trims trailing whitespace after stripping`() {
        // Final .trim() pass on the result; verify it bites the trailing
        // newline a server-side renderer might have left in the preview.
        assertEquals("hello", stripRoleHeading("## User\nhello\n"))
        assertEquals("hello", stripRoleHeading("## User\nhello   \n"))
    }
}
