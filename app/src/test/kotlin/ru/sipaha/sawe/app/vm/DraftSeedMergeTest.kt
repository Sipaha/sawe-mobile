package ru.sipaha.sawe.app.vm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pure-JVM coverage for [mergeDraftSeed].
 *
 * The bug this pins (N-07): the seed used to be the bounced message ALONE,
 * chosen over the on-disk draft. A message that TTL-expired overnight
 * therefore overwrote whatever the user had started typing in the morning as
 * soon as they re-entered the chat — the debounced draft writer then persisted
 * the overwrite, so the NEWER text was the one destroyed.
 */
class DraftSeedMergeTest {

    @Test
    fun `a bounce never destroys a draft the user has started typing`() {
        assertEquals(
            "half-written thought\n\nrun the tests",
            mergeDraftSeed(draft = "half-written thought", bounced = "run the tests"),
        )
    }

    @Test
    fun `a bounce with no draft seeds the field on its own`() {
        assertEquals("run the tests", mergeDraftSeed(draft = "", bounced = "run the tests"))
        assertEquals("run the tests", mergeDraftSeed(draft = "   ", bounced = "run the tests"))
    }

    @Test
    fun `a draft with no bounce is untouched`() {
        assertEquals("half-written", mergeDraftSeed(draft = "half-written", bounced = ""))
    }

    @Test
    fun `re-seeding the same session does not duplicate the bounced text`() {
        // The seed is applied on every LaunchedEffect(sessionId); without this
        // the bounce would stack up once per re-entry.
        val once = mergeDraftSeed(draft = "note", bounced = "run the tests")
        assertEquals(once, mergeDraftSeed(draft = once, bounced = "run the tests"))
    }
}
