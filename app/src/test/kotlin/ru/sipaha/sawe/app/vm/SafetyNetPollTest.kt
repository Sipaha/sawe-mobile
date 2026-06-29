package ru.sipaha.sawe.app.vm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-JVM coverage for [shouldSafetyNetPoll], the coalescing decision behind
 * the periodic ~60s safety-net delta poll for the open session detail
 * ([SessionDetailStore.startSafetyNetPoll]). The full job is a coroutine loop
 * that is hard to unit-test deterministically, so the trigger decision is
 * factored into this pure function and asserted here.
 */
class SafetyNetPollTest {

    private val openId = "sess-open"

    @Test
    fun `ticks when its session is open and no delta poll is in flight`() {
        assertTrue(
            shouldSafetyNetPoll(
                tickSessionId = openId,
                openSessionId = openId,
                deltaPollInFlight = false,
            ),
            "a tick for the open session with no poll in flight must trigger a poll",
        )
    }

    @Test
    fun `coalesces - no-op while a delta or convergence poll is already in flight`() {
        assertFalse(
            shouldSafetyNetPoll(
                tickSessionId = openId,
                openSessionId = openId,
                deltaPollInFlight = true,
            ),
            "must be a no-op when the push path's delta/convergence poll is running, " +
                "so it never fights or double-applies",
        )
    }

    @Test
    fun `does not poll a session whose detail is no longer open`() {
        // Session switched away from this tick's session.
        assertFalse(
            shouldSafetyNetPoll(
                tickSessionId = openId,
                openSessionId = "sess-other",
                deltaPollInFlight = false,
            ),
            "a tick for a session that is no longer the open one must not poll",
        )
        // No session open at all (detail closed).
        assertFalse(
            shouldSafetyNetPoll(
                tickSessionId = openId,
                openSessionId = null,
                deltaPollInFlight = false,
            ),
            "a tick after the detail closed (openSessionId == null) must not poll",
        )
    }

    @Test
    fun `closed-detail check takes priority even if no poll is in flight`() {
        // Both guards off would poll; the scope guard must still veto.
        assertFalse(
            shouldSafetyNetPoll(
                tickSessionId = openId,
                openSessionId = null,
                deltaPollInFlight = false,
            ),
        )
    }
}
