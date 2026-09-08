package ru.sipaha.sawe.app.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Reading and sharing a crash log (review findings 15 and N-60).
 *
 * Share used to kick off its own `CrashLogger.readCrashFile` on the screen's
 * `rememberCoroutineScope()` and then immediately close the dialog. A Back
 * press before that read finished cancelled the scope and `startActivity` was
 * never reached — no chooser, no error, nothing. The read now happens once,
 * off the main thread, for the dialog body, and the share is a pure function
 * of text the screen already holds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class CrashLogShareTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fileName = "crash-20260906-101500-000.log"
    private val body = "java.lang.IllegalStateException: boom\n\tat Foo.bar(Foo.kt:1)"

    @Before
    fun writeOneCrashFile() {
        val dir = File(context.filesDir, "crash-logs").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        File(dir, fileName).writeText(body)
    }

    /** Poll-safe: the semantics tree throws while mid-recomposition. */
    private fun textPresent(text: String): Boolean = runCatching {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)

    @Test
    fun the_listing_and_the_body_are_read_off_the_main_thread_and_still_arrive() {
        compose.setContent { MaterialTheme { Surface { CrashLogsScreen(onBack = {}) } } }

        compose.waitUntil(5_000) { textPresent(fileName) }
        compose.onNodeWithText(fileName).performClick()
        compose.waitUntil(5_000) { textPresent(body) }
        compose.onNodeWithText(body).assertIsDisplayed()
    }

    /**
     * Share stays disabled until there is something to share, rather than
     * being tappable in a state where it can only lose a race.
     */
    @Test
    fun share_becomes_available_once_the_body_has_loaded() {
        compose.setContent { MaterialTheme { Surface { CrashLogsScreen(onBack = {}) } } }

        compose.waitUntil(5_000) { textPresent(fileName) }
        compose.onNodeWithText(fileName).performClick()
        compose.waitUntil(5_000) { textPresent(body) }
        compose.onNodeWithText("Share").assertIsEnabled()
    }

    @Test
    fun the_share_intent_carries_the_text_the_user_was_reading() {
        val intent = crashLogShareIntent(fileName, body)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals("spk-editor crash: $fileName", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(body, intent.getStringExtra(Intent.EXTRA_TEXT))
    }
}
