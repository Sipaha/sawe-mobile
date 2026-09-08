package ru.sipaha.sawe.app.vm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-JVM coverage for two store-side decisions:
 *  - [shouldMaterialiseMarker] — N-03, the phantom "Sending" bubble;
 *  - [shouldRefreshForSessionEvent] — N-32, the `list_sessions` storm.
 */
class PendingMarkerAndListRefreshTest {

    // ---- N-03: which pending-send markers may become a bubble ----

    @Test
    fun `a marker backed by a queue entry is shown`() {
        assertTrue(
            shouldMaterialiseMarker(csid = 7L, queuedCsids = setOf(7L), inFlightCsids = emptySet()),
        )
    }

    @Test
    fun `a marker owned by a live send coroutine is shown even with no queue entry`() {
        // The send may not have reached `queueCall`'s offline branch yet.
        assertTrue(
            shouldMaterialiseMarker(csid = 7L, queuedCsids = emptySet(), inFlightCsids = setOf(7L)),
        )
    }

    @Test
    fun `an orphan left by a process death is not shown`() {
        assertFalse(
            shouldMaterialiseMarker(csid = 7L, queuedCsids = setOf(8L), inFlightCsids = setOf(9L)),
            "nothing can ever resolve it, so it would be a permanent phantom that " +
                "comes back on every open and survives restarts",
        )
    }

    @Test
    fun `with no queue snapshot available every marker is kept`() {
        assertTrue(
            shouldMaterialiseMarker(csid = 7L, queuedCsids = null, inFlightCsids = emptySet()),
            "guessing wrong here erases a message the user is still waiting on",
        )
    }

    // ---- N-32: which notifications are worth a list_sessions ----

    @Test
    fun `a change to a session on screen refreshes`() {
        assertTrue(shouldRefreshForSessionEvent("s-2", listOf("s-1", "s-2", "s-3")))
    }

    @Test
    fun `a change to a session we do not show is ignored`() {
        assertFalse(
            shouldRefreshForSessionEvent("s-9", listOf("s-1", "s-2")),
            "the desktop fans state_changed out for every session of an agent; " +
                "each of those used to cost a full list_sessions round-trip",
        )
    }

    @Test
    fun `an unidentified event or an unloaded list still refreshes`() {
        assertTrue(shouldRefreshForSessionEvent(null, listOf("s-1")))
        assertTrue(shouldRefreshForSessionEvent("s-1", null))
        assertTrue(shouldRefreshForSessionEvent(null, null))
    }

    @Test
    fun `a loaded-but-empty list has no row any change could touch`() {
        assertFalse(shouldRefreshForSessionEvent("s-1", emptyList()))
    }
}
