package ru.sipaha.sawe.app.ui.solutions

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.sipaha.sawe.core.EntryRoleDto
import ru.sipaha.sawe.core.EntrySummary

/**
 * The take-it-back affordance on a message parked in the offline queue (N-07).
 *
 * The finding: a send made while the wire was down showed a badge and then
 * nothing happened — for up to 24 hours, with no way to cancel it, edit it, or
 * get the text back. This is the way out. It is deliberately offered on exactly
 * one badge; a cancel on anything that has already been handed to the transport
 * would be a lie, because the server has no `spk_client_send_id` de-duplication
 * and the message may already have been applied.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class QueuedSendCancelUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun parkedSend(csid: Long? = 42L) = EntrySummary(
        role = EntryRoleDto.User,
        preview = "run the tests",
        clientSendId = csid,
    )

    // ---- which bubbles may offer it ---------------------------------------

    @Test
    fun only_a_parked_send_is_cancellable() {
        val entry = parkedSend()
        assertEquals(
            42L,
            cancellableQueuedSendId(entry, UserBubbleStatus.WaitingForConnection),
        )
        // Everything else is either already on the wire, already delivered, or
        // still holding server-side upload slots.
        assertNull(cancellableQueuedSendId(entry, UserBubbleStatus.Sending))
        assertNull(cancellableQueuedSendId(entry, UserBubbleStatus.Queued))
        assertNull(cancellableQueuedSendId(entry, UserBubbleStatus.Delivered))
        assertNull(cancellableQueuedSendId(entry, UserBubbleStatus.None))
        assertNull(
            cancellableQueuedSendId(
                entry,
                UserBubbleStatus.Uploading(sentBytes = 1L, totalBytes = 2L, paused = false),
            ),
        )
    }

    @Test
    fun a_bubble_with_no_client_send_id_is_not_cancellable() {
        // The queue entry is filed under that id; without it there is nothing
        // for the cancel to name, and cancelling "the first parked message"
        // would take back somebody else's.
        assertNull(
            cancellableQueuedSendId(
                parkedSend(csid = null),
                UserBubbleStatus.WaitingForConnection,
            ),
        )
    }

    // ---- what the user sees and taps --------------------------------------

    @Test
    fun a_parked_bubble_shows_a_cancel_the_user_can_actually_press() {
        var cancelled: Long? = null
        compose.setContent {
            MaterialTheme {
                Surface {
                    ChatBubble(
                        entry = parkedSend(),
                        userStatus = UserBubbleStatus.WaitingForConnection,
                        onCancelQueued = { cancelled = 42L },
                    )
                }
            }
        }

        compose.onNodeWithText("Waiting for connection").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel queued message").performClick()

        assertEquals(42L, cancelled)
    }

    @Test
    fun a_send_that_is_on_the_wire_offers_no_cancel() {
        compose.setContent {
            MaterialTheme {
                Surface {
                    ChatBubble(
                        entry = parkedSend(),
                        userStatus = UserBubbleStatus.Sending,
                        onCancelQueued = null,
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Cancel queued message").assertDoesNotExist()
    }

    @Test
    fun the_body_of_a_cancellable_bubble_still_renders_normally() {
        // The cancel sits in the status row underneath the body, so adding it
        // must not push the message itself out of the bubble.
        compose.setContent {
            MaterialTheme {
                Surface {
                    ChatBubble(
                        entry = parkedSend(),
                        userStatus = UserBubbleStatus.WaitingForConnection,
                        onCancelQueued = {},
                    )
                }
            }
        }

        compose.onNodeWithText("run the tests").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel queued message").assertIsDisplayed()
    }
}
