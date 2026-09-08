package ru.sipaha.sawe.app.vm

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.sipaha.sawe.core.ConnectFailure
import ru.sipaha.sawe.core.FrameRefusedException
import ru.sipaha.sawe.core.FrameTooLargeException
import ru.sipaha.sawe.core.MessageRejectedException
import ru.sipaha.sawe.core.QueueCancelledException
import ru.sipaha.sawe.core.QueueTtlException
import ru.sipaha.sawe.core.QueuedMessage
import ru.sipaha.sawe.core.RemoteClient
import ru.sipaha.sawe.core.ServerFeatures
import ru.sipaha.sawe.core.TransportLostException
import ru.sipaha.sawe.core.WireFeature

/**
 * Pure-JVM coverage for the send-failure policy
 * ([classifySendFailure] / [sendFailureMessage]) and for the csid recovery
 * ([parseQueuedClientSendId]) the queue's terminal paths need.
 *
 * The bugs these pin:
 *  - N-01: every non-TTL send failure removed the bubble and the marker and
 *    surfaced a toast, but never returned the text to the composer — which the
 *    compose bar had already cleared. A NAT rebinding 200 ms after Send lost
 *    the message outright.
 *  - N-02: a `close()` mid-flight failed the caller with an exception it read
 *    as "dropped", so it deleted a disk record the next client was going to
 *    deliver.
 *  - N-03: nothing on a queue terminal path knew the marker's csid, so the
 *    marker outlived the message and re-materialised as a phantom bubble.
 */
class SendFailureClassificationTest {

    @Test
    fun `a closed client that kept the message on disk must not bounce or clear`() {
        assertEquals(
            SendFailureAction.KeepQueued,
            classifySendFailure(
                RemoteClient.ClosedException.StillQueued("q-1"),
                alreadyBouncedByQueue = false,
            ),
            "the record is intact for the next client: bouncing would let the user " +
                "send it twice, clearing would hide a message that is about to land",
        )
    }

    @Test
    fun `a closed client that dropped the message bounces the text`() {
        assertEquals(
            SendFailureAction.Bounce,
            classifySendFailure(
                RemoteClient.ClosedException.NotQueued(),
                alreadyBouncedByQueue = false,
            ),
        )
    }

    @Test
    fun `a socket that died with the frame on the wire bounces`() {
        assertEquals(
            SendFailureAction.Bounce,
            classifySendFailure(TransportLostException(), alreadyBouncedByQueue = false),
            "delivery is unknown, and with no negotiated csid dedupe a retry " +
                "could post the message twice — so the text goes back to the " +
                "composer, where it at least survives",
        )
    }

    @Test
    fun `oversized payloads bounce so the user can trim them`() {
        assertEquals(
            SendFailureAction.Bounce,
            classifySendFailure(FrameTooLargeException(1_000_000), alreadyBouncedByQueue = false),
        )
        assertEquals(
            SendFailureAction.Bounce,
            classifySendFailure(
                MessageRejectedException(ConnectFailure.ServerClosed(1009, "message too big")),
                alreadyBouncedByQueue = false,
            ),
        )
    }

    @Test
    fun `a refused frame never reached the wire and bounces`() {
        assertEquals(
            SendFailureAction.Bounce,
            classifySendFailure(FrameRefusedException(), alreadyBouncedByQueue = false),
        )
    }

    @Test
    fun `TTL expiry clears only - the queue already bounced it`() {
        assertEquals(
            SendFailureAction.ClearOnly,
            classifySendFailure(QueueTtlException(), alreadyBouncedByQueue = false),
        )
    }

    @Test
    fun `anything the queue already bounced clears only, never twice`() {
        // `DraftRepository.setBounced` APPENDS, so a second bounce for the same
        // message would show the user their text twice in the composer.
        val bouncedByQueue = listOf(
            FrameTooLargeException(1_000_000),
            MessageRejectedException(ConnectFailure.ServerClosed(1009, "message too big")),
            TransportLostException(),
            IllegalStateException("boom"),
        )
        for (t in bouncedByQueue) {
            assertEquals(
                SendFailureAction.ClearOnly,
                classifySendFailure(t, alreadyBouncedByQueue = true),
                "${t::class.simpleName} was already handed back by onMessageExpired",
            )
        }
    }

    @Test
    fun `a send the user withdrew clears only — the cancel path already gave the text back`() {
        // `cancelQueuedSendInternal` bounces the text and retires the marker
        // itself, then the withdraw completes the parked call with this
        // exception. Bouncing again would append the same message to the
        // composer a second time, because `setBounced` appends.
        assertEquals(
            SendFailureAction.ClearOnly,
            classifySendFailure(QueueCancelledException(), alreadyBouncedByQueue = false),
        )
    }

    @Test
    fun `an editor-link error envelope is treated as unknown delivery, not as a refusal`() {
        // The desktop proxy answers -32603 for its own failures and gives up
        // at 30 s — by which point it may already have applied the send.
        val envelope = SendEnvelopeException("opening local MCP proxy: connection refused")
        assertEquals(
            SendFailureAction.Bounce,
            classifySendFailure(envelope, alreadyBouncedByQueue = false),
        )
        assertEquals(
            "connection lost while sending — check the chat before sending it again",
            sendFailureMessage(envelope),
        )
        assertFalse(
            sendFailureMessage(envelope).contains("MCP proxy"),
            "internal proxy strings must never reach a snackbar",
        )
    }

    @Test
    fun `a tool-level refusal bounces and says what the tool said`() {
        val refused = SendRejectedByServerException("session not found")
        assertEquals(
            SendFailureAction.Bounce,
            classifySendFailure(refused, alreadyBouncedByQueue = false),
        )
        assertTrue(sendFailureMessage(refused).contains("session not found"))
    }

    @Test
    fun `an unrecognised failure still saves the text`() {
        assertEquals(
            SendFailureAction.Bounce,
            classifySendFailure(IllegalStateException("server said no"), alreadyBouncedByQueue = false),
            "the default must be the safe one — no send may leave the user with " +
                "an empty composer and nothing to show for it",
        )
    }

    @Test
    fun `every failure has a message and the size errors say so`() {
        assertTrue(
            sendFailureMessage(FrameTooLargeException(1_000_000)).contains("too large"),
        )
        assertTrue(
            sendFailureMessage(
                MessageRejectedException(ConnectFailure.ServerClosed(1009, "message too big")),
            ).contains("too large"),
        )
        assertTrue(sendFailureMessage(IllegalStateException("boom")).isNotBlank())
        assertTrue(sendFailureMessage(RuntimeException()).isNotBlank())
    }

    @Test
    fun `the csid is recovered from a queued blocks payload`() {
        val message = QueuedMessage(
            id = "q-1",
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
                                putJsonObject("_meta") {
                                    put("spk_client_send_id", JsonPrimitive(1762345678901L))
                                }
                            },
                        )
                    },
                )
            },
            enqueuedAtMs = 0L,
        )
        assertEquals(1762345678901L, parseQueuedClientSendId(message))
    }

    @Test
    fun `an unstamped or legacy payload yields no csid instead of a wrong one`() {
        val legacy = QueuedMessage(
            id = "q-2",
            method = "remote.solution_agent.send_message",
            params = buildJsonObject {
                put("session_id", "sess-1")
                put("content", "hello")
            },
            enqueuedAtMs = 0L,
        )
        assertNull(parseQueuedClientSendId(legacy))

        val unstamped = QueuedMessage(
            id = "q-3",
            method = "remote.solution_agent.send_message_blocks",
            params = buildJsonObject {
                put("session_id", "sess-1")
                put(
                    "blocks",
                    buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", "hi") })
                    },
                )
            },
            enqueuedAtMs = 0L,
        )
        assertNull(parseQueuedClientSendId(unstamped))
        assertNull(parseQueuedClientSendId(unstamped.copy(params = null)))
    }

    // ---- §4.6: replaying an ambiguous send, and refusing to ----

    private companion object {
        private const val INSTANCE = "8f1c2a90-5b3e-4d17-9c2f-6a0d1e7b4c33"
        private const val WINDOW_MS = 24L * 60 * 60 * 1000
        private const val DISPATCHED_AT = 1_757_232_041_123L

        private val DEDUPING = ServerFeatures(
            tokens = setOf(WireFeature.CSID_DEDUPE),
            serverInstanceId = INSTANCE,
            csidDedupeWindowMs = WINDOW_MS,
        )

        /** All four §4.6 conditions satisfied; each test negates exactly one. */
        private val ALL_FOUR = SendRetryContext(
            featuresAtDispatch = DEDUPING,
            currentServerInstanceId = INSTANCE,
            dispatchedAtMs = DISPATCHED_AT,
            nowMs = DISPATCHED_AT + 1_500L,
            requeuesUsed = 0,
            stampedOnTheWire = true,
        )

        /** The two failures whose delivery is genuinely unknown. */
        private val AMBIGUOUS: List<Throwable> = listOf(
            TransportLostException(),
            SendEnvelopeException("proxy call timed out after 30s"),
        )
    }

    @Test
    fun `an ambiguous send replays when all four conditions hold`() {
        for (t in AMBIGUOUS) {
            assertEquals(
                SendFailureAction.Requeue,
                classifySendFailure(t, alreadyBouncedByQueue = false, retry = ALL_FOUR),
                "${t::class.simpleName}: the peer de-duplicates by client_send_id and " +
                    "is the same process, so replaying the identical bundle is free",
            )
        }
    }

    @Test
    fun `condition 1 - a peer without csid dedupe bounces`() {
        val noToken = ALL_FOUR.copy(
            featuresAtDispatch = DEDUPING.copy(tokens = emptySet()),
        )
        for (t in AMBIGUOUS) {
            assertEquals(SendFailureAction.Bounce, classifySendFailure(t, false, noToken))
        }
        assertEquals(
            SendFailureAction.Bounce,
            classifySendFailure(TransportLostException(), false, SendRetryContext.NONE),
            "the default context is the pre-feature wire and must never replay",
        )
    }

    @Test
    fun `condition 2 - a restarted desktop bounces`() {
        // The dedupe table is in-memory and died with the old process, so the
        // replay would be indistinguishable from a new message.
        val restarted = ALL_FOUR.copy(currentServerInstanceId = "0000ffff-dead-4bee-8000-000000000001")
        for (t in AMBIGUOUS) {
            assertEquals(SendFailureAction.Bounce, classifySendFailure(t, false, restarted))
        }
        assertEquals(
            SendFailureAction.Bounce,
            classifySendFailure(TransportLostException(), false, ALL_FOUR.copy(currentServerInstanceId = null)),
            "no negotiated connection to replay on is a refusal, not a match",
        )
        assertEquals(
            SendFailureAction.Bounce,
            classifySendFailure(
                TransportLostException(),
                false,
                ALL_FOUR.copy(
                    featuresAtDispatch = DEDUPING.copy(serverInstanceId = null),
                    currentServerInstanceId = null,
                ),
            ),
            "two unknowns must not compare equal",
        )
    }

    @Test
    fun `condition 3 - a send older than the server's dedupe window bounces`() {
        val aged = ALL_FOUR.copy(nowMs = DISPATCHED_AT + WINDOW_MS)
        for (t in AMBIGUOUS) {
            assertEquals(SendFailureAction.Bounce, classifySendFailure(t, false, aged))
        }
        assertEquals(
            SendFailureAction.Requeue,
            classifySendFailure(TransportLostException(), false, ALL_FOUR.copy(nowMs = DISPATCHED_AT + WINDOW_MS - 1)),
            "the boundary is exclusive: one millisecond inside the window is still inside",
        )
        assertEquals(
            SendFailureAction.Bounce,
            classifySendFailure(
                TransportLostException(),
                false,
                ALL_FOUR.copy(featuresAtDispatch = DEDUPING.copy(csidDedupeWindowMs = null)),
            ),
            "a peer that names no window has not promised one",
        )
    }

    @Test
    fun `condition 4 - an unambiguous failure never replays`() {
        // Each of these is either provably not applied or provably refused;
        // replaying buys nothing and the text belongs in the composer.
        val unambiguous = listOf(
            SendRejectedByServerException("session no longer exists"),
            FrameTooLargeException(1_000_000),
            FrameRefusedException(),
            MessageRejectedException(ConnectFailure.ServerClosed(1009, "message too big")),
            RemoteClient.ClosedException.NotQueued(),
        )
        for (t in unambiguous) {
            assertEquals(
                SendFailureAction.Bounce,
                classifySendFailure(t, alreadyBouncedByQueue = false, retry = ALL_FOUR),
                "${t::class.simpleName} is not an ambiguous delivery",
            )
        }
    }

    @Test
    fun `a replay budget stops a payload the desktop chokes on every time`() {
        assertEquals(
            SendFailureAction.Requeue,
            classifySendFailure(
                TransportLostException(),
                false,
                ALL_FOUR.copy(requeuesUsed = SendRetryContext.MAX_REQUEUES - 1),
            ),
        )
        assertEquals(
            SendFailureAction.Bounce,
            classifySendFailure(
                TransportLostException(),
                false,
                ALL_FOUR.copy(requeuesUsed = SendRetryContext.MAX_REQUEUES),
            ),
            "past the budget the user gets their text back rather than a bubble " +
                "that says Sending for the next 24 hours",
        )
    }

    @Test
    fun `a text the queue already handed back is never replayed`() {
        // Otherwise the message lands in the transcript AND sits in the
        // composer, i.e. the user sends it twice.
        for (t in AMBIGUOUS) {
            assertEquals(
                SendFailureAction.ClearOnly,
                classifySendFailure(t, alreadyBouncedByQueue = true, retry = ALL_FOUR),
            )
        }
    }

    @Test
    fun `a send the user withdrew is never replayed`() {
        assertEquals(
            SendFailureAction.ClearOnly,
            classifySendFailure(QueueCancelledException(), alreadyBouncedByQueue = false, retry = ALL_FOUR),
        )
    }

    @Test
    fun `an unstamped send is never replayed, whatever the response said`() {
        // The server claims IDs, not calls: a bundle that reaches it with no
        // `_meta.spk_client_send_id` is answered `delivery: accepted` and
        // entered into the dedupe table nowhere. Replaying it would post the
        // message twice, so the presence of the feature is not enough — the
        // stamp has to be on the payload.
        for (t in AMBIGUOUS) {
            assertEquals(
                SendFailureAction.Bounce,
                classifySendFailure(t, false, ALL_FOUR.copy(stampedOnTheWire = false)),
            )
        }
    }

    @Test
    fun `the stamp is read back off the serialised payload`() {
        val stamped = buildJsonObject {
            put("session_id", "s-7f3a")
            put(
                "blocks",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", "run the tests")
                            putJsonObject("_meta") { put("spk_client_send_id", 1_757_232_041_123L) }
                        },
                    )
                },
            )
        }
        assertEquals(1_757_232_041_123L, clientSendIdInSendParams(stamped))

        val unstamped = buildJsonObject {
            put("session_id", "s-7f3a")
            put(
                "blocks",
                buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "hi") }) },
            )
        }
        assertNull(clientSendIdInSendParams(unstamped))
        assertNull(clientSendIdInSendParams(null))
    }
}
