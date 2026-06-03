package ru.sipaha.spkremote.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [stripInjectedMeta] — the Kotlin mirror of the desktop's
 * `conversation_render::strip_injected_meta`. The desktop bakes a
 * `[HH:MM:SS] ` prefix onto each queued follow-up (and prepends a one-line
 * hint on end-of-turn delivery); both reach the client unstripped in the
 * wire `EntrySummary`, so this stripper must remove them before render.
 *
 * Cases are kept in lockstep with the desktop unit tests so the two
 * implementations can't silently diverge.
 */
class StripInjectedMetaTest {

    private val HINT =
        "[Queued before your turn ended — not a reply to your last message.]"

    @Test
    fun `removes leading timestamp`() {
        assertEquals("actual user text", stripInjectedMeta("[10:39:12] actual user text"))
    }

    @Test
    fun `removes timestamp on each paragraph segment`() {
        assertEquals(
            "first\n\nsecond",
            stripInjectedMeta("[10:39:12] first\n\n[10:39:30] second"),
        )
    }

    @Test
    fun `removes leading hint line then timestamp`() {
        assertEquals("text", stripInjectedMeta("$HINT\n\n[10:39:12] text"))
    }

    @Test
    fun `passes through plain text`() {
        assertEquals("hi there", stripInjectedMeta("hi there"))
    }

    @Test
    fun `passes through non-timestamp bracket`() {
        // A leading bracket that is NOT a valid HH:MM:SS is left intact.
        assertEquals("[not-a-timestamp] text", stripInjectedMeta("[not-a-timestamp] text"))
    }

    @Test
    fun `strips only the first timestamp when the user text itself starts with one`() {
        // Server bakes one prefix in front of the user's literal `[..] `.
        // Only the injected prefix is removed; the user's stays.
        assertEquals(
            "[12:34:56] hi",
            stripInjectedMeta("[09:00:00] [12:34:56] hi"),
        )
    }

    @Test
    fun `empty string round-trips`() {
        assertEquals("", stripInjectedMeta(""))
    }
}
