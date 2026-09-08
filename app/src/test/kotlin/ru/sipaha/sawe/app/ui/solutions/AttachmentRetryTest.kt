package ru.sipaha.sawe.app.ui.solutions

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.sipaha.sawe.app.vm.PickedAttachment
import ru.sipaha.sawe.app.vm.UploadManager

/**
 * The failed-attachment recovery affordance (N-48).
 *
 * Before this, the card's "tap to retry" copy was a lie: the tap opened a
 * dialog whose only button was OK, and the sole way forward was to remove the
 * chip and pick the file again (Send stays disabled while any attachment is
 * Failed, so the user was stuck until they figured that out).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class AttachmentRetryTest {

    @get:Rule
    val compose = createComposeRule()

    private val uploadState =
        MutableStateFlow<UploadManager.State>(UploadManager.State.Failed("upload didn't complete"))

    private fun failedAttachment(reason: String = "upload didn't complete — tap to retry") =
        PickedAttachment(
            uri = Uri.EMPTY,
            displayName = "crash.log",
            mimeType = "text/plain",
            sizeBytes = 4_096L,
            localKey = "lk-1",
            uploadState = uploadState.also { it.value = UploadManager.State.Failed(reason) }
                .asStateFlow(),
        )

    @Test
    fun tapping_a_failed_card_offers_a_retry_that_actually_retries() {
        val retried = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Surface {
                    AttachmentPreviewCard(
                        attachment = failedAttachment(),
                        onRemove = {},
                        onRetry = { key -> retried += key; true },
                    )
                }
            }
        }

        compose.onNodeWithText("crash.log").performClick()
        compose.onNodeWithText("Upload failed: crash.log").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()

        assertEquals(listOf("lk-1"), retried)
        // A retry that took ownership closes the dialog — the card's own
        // progress overlay is now the status surface.
        compose.onNodeWithText("Upload failed: crash.log").assertDoesNotExist()
    }

    @Test
    fun a_refused_retry_switches_the_dialog_to_remove_and_re_attach() {
        var removed = false
        compose.setContent {
            MaterialTheme {
                Surface {
                    AttachmentPreviewCard(
                        attachment = failedAttachment(),
                        onRemove = { removed = true },
                        onRetry = { false },
                    )
                }
            }
        }

        compose.onNodeWithText("crash.log").performClick()
        compose.onNodeWithText("Retry").performClick()

        // No dead "tap to retry" promise: the dialog says what actually works.
        compose.onNodeWithText(
            "This upload can't be resumed. Remove the attachment and add it again.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithText("Remove").performClick()

        assertTrue(removed)
    }

    @Test
    fun the_failure_reason_stays_readable_in_the_dialog() {
        val reason = "upload_init failed: Network error: Software caused connection abort"
        compose.setContent {
            MaterialTheme {
                Surface {
                    AttachmentPreviewCard(
                        attachment = failedAttachment(reason),
                        onRemove = {},
                        onRetry = { true },
                    )
                }
            }
        }

        compose.onNodeWithText("crash.log").performClick()
        compose.onNodeWithText(reason).assertIsDisplayed()
    }

    /**
     * `UploadManager.retry` also answers `false` when a driver coroutine
     * happens to be running for this key at that instant — a transient. The
     * latch that switches the dialog to "remove and re-attach" used to be
     * permanent for the life of the `localKey`, so one unlucky tap pinned the
     * card to that advice even after the upload came back to life.
     */
    @Test
    fun a_refused_retry_is_forgotten_once_the_upload_is_live_again() {
        compose.setContent {
            MaterialTheme {
                Surface {
                    AttachmentPreviewCard(
                        attachment = failedAttachment(),
                        onRemove = {},
                        onRetry = { false },
                    )
                }
            }
        }

        compose.onNodeWithText("crash.log").performClick()
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Remove").assertIsDisplayed()

        // The upload resumes (a watchdog retry, a reconnect) and fails again.
        uploadState.value = UploadManager.State.Uploading(sent = 512L, total = 4_096L)
        compose.waitForIdle()
        uploadState.value = UploadManager.State.Failed("upload didn't complete — tap to retry")
        compose.waitForIdle()

        compose.onNodeWithText("crash.log").performClick()
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onNodeWithText("Remove").assertDoesNotExist()
    }
}
