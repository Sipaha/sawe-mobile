package ru.sipaha.sawe.app.vm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Single-flight admission for the delta poll ([DeltaPollGate]).
 *
 * This is the part of N-29 that the convergence rule alone does not express.
 * The original loop cancelled and re-armed the in-flight `get_session_changes`
 * on every push — the server executed and transmitted each cancelled response
 * anyway, so under a stream of pokes no poll ever completed, the cursor never
 * advanced, and the next request re-fetched the same growing page. These pin
 * the replacement: a poke joins the running poll instead of killing it.
 *
 * They also pin the check-then-act window (VM-2) that the first version of the
 * fix left open: between the loop deciding to stop and its coroutine actually
 * completing, `Job.isActive` still reads true, so a poke arriving there was
 * filed on a loop that would never read it again and the session sat behind
 * until the 60 s safety net. That window is unreachable while every writer
 * shares the main thread — and becomes live the moment one of them moves off
 * it, which the audit's own N-56 recommends. The gate closes it in code.
 */
class DeltaPollGateTest {

    @Test
    fun `the first request starts a poll`() {
        val gate = DeltaPollGate()
        assertTrue(gate.admit(), "nothing is running, so the caller must start the loop")
        assertTrue(gate.isRunning())
    }

    @Test
    fun `a poke during an in-flight poll is recorded, not started, and never cancels it`() {
        val gate = DeltaPollGate()
        gate.admit()
        // Five pushes while one request is on the wire.
        repeat(5) {
            assertFalse(
                gate.admit(),
                "a second loop must never start — and the running one must never be cancelled",
            )
        }
        // Exactly ONE extra iteration, not five.
        assertTrue(gate.continueOrFinish(hasMore = false), "the recorded poke is honoured")
        assertFalse(
            gate.continueOrFinish(hasMore = false),
            "and consumed — five pokes collapse into one re-poll, not five",
        )
    }

    @Test
    fun `a caught-up page with no pending poke closes the gate`() {
        val gate = DeltaPollGate()
        gate.admit()
        assertFalse(gate.continueOrFinish(hasMore = false))
        assertFalse(gate.isRunning())
        assertTrue(gate.admit(), "the next poke starts a fresh loop")
    }

    @Test
    fun `pagination keeps the loop running without any poke`() {
        val gate = DeltaPollGate()
        gate.admit()
        assertTrue(gate.continueOrFinish(hasMore = true))
        assertTrue(gate.isRunning())
        assertFalse(gate.continueOrFinish(hasMore = false))
    }

    @Test
    fun `VM-2 a poke that arrives after the stop decision starts a new loop`() {
        val gate = DeltaPollGate()
        gate.admit()
        // The loop decides to stop. Its coroutine has NOT completed yet, so a
        // Job.isActive check would still say "running".
        assertFalse(gate.continueOrFinish(hasMore = false))
        // The poke lands in exactly that window.
        assertTrue(
            gate.admit(),
            "it must start a fresh loop; filing it on the loop that just stopped " +
                "would strand the session until the 60 s safety net",
        )
    }

    @Test
    fun `VM-2 a poke that arrives just before the stop decision is honoured by that loop`() {
        val gate = DeltaPollGate()
        gate.admit()
        assertFalse(gate.admit(), "recorded on the running loop")
        // The loop now reaches its tail with a caught-up page. It must NOT
        // stop: the recorded poke is newer than the response it just applied.
        assertTrue(gate.continueOrFinish(hasMore = false))
        assertTrue(gate.isRunning())
    }

    @Test
    fun `an abnormal exit re-arms only when a poke is outstanding`() {
        val gate = DeltaPollGate()
        gate.admit()
        assertFalse(
            gate.finishAndShouldRearm(),
            "no poke arrived — a dead link must not respawn the loop",
        )
        assertFalse(gate.isRunning())

        gate.admit()
        gate.admit() // a poke arrives while the loop is dying
        assertTrue(
            gate.finishAndShouldRearm(),
            "the poke was never served, so the caller has to re-arm",
        )
        assertFalse(gate.isRunning())
    }

    @Test
    fun `a second abnormal finish does not re-arm twice`() {
        val gate = DeltaPollGate()
        gate.admit()
        gate.admit()
        assertTrue(gate.finishAndShouldRearm())
        assertFalse(gate.finishAndShouldRearm(), "the gate is already closed")
    }

    @Test
    fun `reset closes the gate so a cancelled job cannot re-arm`() {
        val gate = DeltaPollGate()
        gate.admit()
        gate.admit() // a poke is outstanding...
        // ...but the session is switching away, so the poll's result would be
        // for the wrong session/stream.
        gate.reset()
        assertFalse(gate.isRunning())
        assertFalse(
            gate.finishAndShouldRearm(),
            "the cancelled job's finally must not resurrect the poll",
        )
    }
}
