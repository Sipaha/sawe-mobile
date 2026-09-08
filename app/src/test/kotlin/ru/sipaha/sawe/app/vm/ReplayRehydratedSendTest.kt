package ru.sipaha.sawe.app.vm

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.sipaha.sawe.core.QueuedMessage
import ru.sipaha.sawe.core.QueuedSendAttempt
import ru.sipaha.sawe.core.ServerFeatures
import ru.sipaha.sawe.core.WireFeature

/**
 * Truth table for [canReplayRehydratedSend] — the predicate `:core`'s
 * [ru.sipaha.sawe.core.QueueController] asks before putting a queue record
 * written by a PREVIOUS process back on the wire.
 *
 * Two failure modes, and they pull in opposite directions:
 *  - too strict ⇒ a message typed offline bounces to the composer on the
 *    next cold start, which is the durable offline queue's whole purpose
 *    defeated. That is the `attempt == null` row, and it is the one to
 *    protect hardest.
 *  - too lax ⇒ the user's message is posted twice, because the desktop's
 *    `spk_client_send_id` table is in-memory, per-process and time-bounded.
 *
 * Everything below the first row is [canRequeueAmbiguousSend]'s policy
 * reached through a reconstruction, so the cases mirror the §4.6 table in
 * [SendFailureClassificationTest] on purpose.
 */
class ReplayRehydratedSendTest {

    private companion object {
        private const val INSTANCE = "8f1c2a90-5b3e-4d17-9c2f-6a0d1e7b4c33"
        private const val OTHER_INSTANCE = "0d5b1f22-77aa-4c31-8f90-1b6e3c9d2a44"
        private const val WINDOW_MS = 24L * 60 * 60 * 1000
        private const val DISPATCHED_AT = 1_757_232_041_123L

        /** A live peer that de-duplicates, on the same process we sent to. */
        private val LIVE_SAME_PROCESS = ServerFeatures(
            tokens = setOf(WireFeature.CSID_DEDUPE),
            serverInstanceId = INSTANCE,
            csidDedupeWindowMs = WINDOW_MS,
        )
    }

    /** A stamped `send_message_blocks` record, optionally already attempted. */
    private fun record(
        attempt: QueuedSendAttempt? = null,
        stamped: Boolean = true,
    ): QueuedMessage = QueuedMessage(
        id = "csid-send:1762345678901",
        method = "remote.solution_agent.send_message_blocks",
        params = buildJsonObject {
            put("session_id", "sess-1")
            put(
                "blocks",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", "run the tests")
                            if (stamped) {
                                putJsonObject("_meta") {
                                    put("spk_client_send_id", JsonPrimitive(1762345678901L))
                                }
                            }
                        },
                    )
                },
            )
        },
        enqueuedAtMs = DISPATCHED_AT - 5_000L,
        attempt = attempt,
    )

    private fun attempted(
        instanceId: String? = INSTANCE,
        atMs: Long = DISPATCHED_AT,
    ) = QueuedSendAttempt(atMs = atMs, instanceId = instanceId)

    @Test
    fun `a record that never reached a transport is always replayable`() {
        // The headline case: typed offline, process killed before a socket
        // ever existed. Sending it now is a first delivery.
        assertTrue(
            canReplayRehydratedSend(
                record(attempt = null),
                LIVE_SAME_PROCESS,
                nowMs = DISPATCHED_AT + 1_000L,
            ),
        )
        // …and it stays replayable on a peer that offers nothing at all —
        // there is nothing to de-duplicate, because nothing was sent.
        assertTrue(
            canReplayRehydratedSend(
                record(attempt = null),
                ServerFeatures.NONE,
                nowMs = DISPATCHED_AT + 1_000L,
            ),
        )
        // Even an unstamped legacy payload: the stamp only matters as
        // evidence about the server's table, and the table is not involved.
        assertTrue(
            canReplayRehydratedSend(
                record(attempt = null, stamped = false),
                LIVE_SAME_PROCESS,
                nowMs = DISPATCHED_AT + 1_000L,
            ),
        )
    }

    @Test
    fun `an attempted record replays onto the same process inside the window`() {
        assertTrue(
            canReplayRehydratedSend(
                record(attempt = attempted()),
                LIVE_SAME_PROCESS,
                nowMs = DISPATCHED_AT + 1_500L,
            ),
        )
    }

    @Test
    fun `a different editor process is a refusal`() {
        assertFalse(
            canReplayRehydratedSend(
                record(attempt = attempted()),
                LIVE_SAME_PROCESS.copy(serverInstanceId = OTHER_INSTANCE),
                nowMs = DISPATCHED_AT + 1_500L,
            ),
        )
    }

    @Test
    fun `a live peer without csid dedupe is a refusal even on the same instance`() {
        // Same process id, but it no longer advertises the token — a
        // downgraded desktop, or the window before the capabilities probe
        // answers. There is no table to absorb the repeat.
        assertFalse(
            canReplayRehydratedSend(
                record(attempt = attempted()),
                ServerFeatures(serverInstanceId = INSTANCE, csidDedupeWindowMs = WINDOW_MS),
                nowMs = DISPATCHED_AT + 1_500L,
            ),
        )
        assertFalse(
            canReplayRehydratedSend(
                record(attempt = attempted()),
                ServerFeatures.NONE,
                nowMs = DISPATCHED_AT + 1_500L,
            ),
        )
    }

    @Test
    fun `an attempt with no instance id is a refusal`() {
        // What the queue stamps when the frame went out before the
        // capabilities answer, or to a peer with no dedupe. Null must never
        // read as "same process".
        assertFalse(
            canReplayRehydratedSend(
                record(attempt = attempted(instanceId = null)),
                LIVE_SAME_PROCESS,
                nowMs = DISPATCHED_AT + 1_500L,
            ),
        )
    }

    @Test
    fun `a claim that has aged out of the window is a refusal`() {
        assertTrue(
            canReplayRehydratedSend(
                record(attempt = attempted()),
                LIVE_SAME_PROCESS,
                nowMs = DISPATCHED_AT + WINDOW_MS - 1L,
            ),
            "the last millisecond inside the window still counts",
        )
        assertFalse(
            canReplayRehydratedSend(
                record(attempt = attempted()),
                LIVE_SAME_PROCESS,
                nowMs = DISPATCHED_AT + WINDOW_MS,
            ),
        )
        // A clock that went backwards (NTP step, timezone-less reboot) is
        // not evidence of freshness either.
        assertFalse(
            canReplayRehydratedSend(
                record(attempt = attempted()),
                LIVE_SAME_PROCESS,
                nowMs = DISPATCHED_AT - 1L,
            ),
        )
    }

    @Test
    fun `a peer that advertises no window is a refusal`() {
        // The token without the bound: we cannot tell whether the claim is
        // still held, and guessing wrong duplicates the message.
        assertFalse(
            canReplayRehydratedSend(
                record(attempt = attempted()),
                LIVE_SAME_PROCESS.copy(csidDedupeWindowMs = null),
                nowMs = DISPATCHED_AT + 1_500L,
            ),
        )
    }

    @Test
    fun `an unstamped payload is a refusal once it has been attempted`() {
        // The server claims ids, not calls. An unstamped send was answered
        // `accepted` and entered into nothing, so a replay would post it a
        // second time no matter how healthy the connection looks.
        assertFalse(
            canReplayRehydratedSend(
                record(attempt = attempted(), stamped = false),
                LIVE_SAME_PROCESS,
                nowMs = DISPATCHED_AT + 1_500L,
            ),
        )
    }
}
