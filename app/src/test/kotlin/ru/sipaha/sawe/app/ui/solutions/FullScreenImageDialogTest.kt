package ru.sipaha.sawe.app.ui.solutions

import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.sipaha.sawe.core.EntryImage
import java.io.ByteArrayOutputStream

/**
 * The fullscreen image viewer's terminal states (review finding 14).
 *
 * "Decode in flight" and "this payload will never decode" both used to arrive
 * at the call site as a bare `null`, so a truncated base64 blob rendered a
 * progress spinner that span forever — the user was told to wait for something
 * that was never coming, and the only way out was to guess that tapping
 * dismisses.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class FullScreenImageDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private fun realPngBase64(): String {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun show(image: EntryImage) {
        compose.setContent {
            MaterialTheme {
                Surface { FullScreenImageDialog(image = image, onDismiss = {}) }
            }
        }
    }

    @Test
    fun an_undecodable_payload_reports_failure_instead_of_spinning_forever() {
        show(EntryImage(index = 0, mimeType = "image/png", dataBase64 = "not-base64-at-all!!"))
        compose.waitForIdle()
        compose.onNodeWithText(IMAGE_DECODE_FAILED_MESSAGE).assertIsDisplayed()
    }

    @Test
    fun a_truncated_payload_reports_failure() {
        val truncated = realPngBase64().take(12)
        show(EntryImage(index = 0, mimeType = "image/png", dataBase64 = truncated))
        compose.waitForIdle()
        compose.onNodeWithText(IMAGE_DECODE_FAILED_MESSAGE).assertIsDisplayed()
    }

    @Test
    fun a_decodable_payload_renders_the_image_and_no_failure_line() {
        show(EntryImage(index = 0, mimeType = "image/png", dataBase64 = realPngBase64()))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Full-screen image").assertIsDisplayed()
        compose.onNodeWithText(IMAGE_DECODE_FAILED_MESSAGE).assertDoesNotExist()
    }
}
