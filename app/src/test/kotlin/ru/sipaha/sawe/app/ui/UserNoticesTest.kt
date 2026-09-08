package ru.sipaha.sawe.app.ui

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The single app-level notice pipeline (N-57) and its duplicate-collapsing
 * rule (review finding 6).
 */
class UserNoticesTest {

    @Test
    fun `a repeat of what is already on screen is suppressed`() {
        assertFalse(shouldShowNotice("Not connected", "Not connected"))
    }

    @Test
    fun `a different message always gets through`() {
        // The point of dropping the old 4-second gate: a deferred-send failure
        // raised in the middle of a reconnect must still be shown, even though
        // a connection notice is on screen at that instant.
        assertTrue(shouldShowNotice("Upload failed — message not sent", "Not connected"))
        assertTrue(shouldShowNotice("Not connected", null))
        assertTrue(shouldShowNotice("Not connected", "Not connected."))
    }

    /**
     * `showSnackbar` suspends for the snackbar's whole visible duration while
     * the channel behind `sendError` keeps buffering, so an un-collapsed
     * collector turns a burst of identical `notConnectedMessage()` emissions
     * into a minute of back-to-back popups repeating what the connection
     * strip already says.
     *
     * The presentation step is injected and returns immediately, so what is
     * measured here is the de-duplication rule itself rather than snackbar
     * timing.
     */
    @Test
    fun `a burst of identical notices collapses to one`() = runTest {
        val shown = mutableListOf<String>()
        collectUserNotices(
            flowOf("Not connected", "Not connected", "Not connected"),
        ) { shown += it }
        assertEquals(listOf("Not connected"), shown)
    }

    /**
     * ...but a distinct failure in the middle of that burst still reaches the
     * user, and re-arms the latch. This is the case the old 4-second timing
     * gate silently ate (N-04).
     */
    @Test
    fun `a distinct failure inside a burst is never swallowed`() = runTest {
        val shown = mutableListOf<String>()
        collectUserNotices(
            flowOf(
                "Not connected",
                "Not connected",
                "`photo.jpg` failed to upload — message not sent",
                "Not connected",
            ),
        ) { shown += it }
        assertEquals(
            listOf(
                "Not connected",
                "`photo.jpg` failed to upload — message not sent",
                "Not connected",
            ),
            shown,
        )
    }

    /**
     * `MainViewModel.sendError` is `Channel`-backed and requires exactly one
     * consumer: a second collector splits the stream, and each screen then
     * steals notices meant for the other (N-57). Nothing in the type system
     * enforces that, so pin it here — the whole defect was a per-screen
     * `LaunchedEffect` that looked entirely reasonable in isolation.
     */
    @Test
    fun `exactly one collector of sendError exists in the app sources`() {
        val sources = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        assertTrue(sources.isNotEmpty(), "source scan found nothing; cwd=${File(".").absolutePath}")
        val collectors = sources.flatMap { file ->
            file.readLines()
                .filter { line ->
                    val code = line.substringBefore("//")
                    Regex("""sendError\s*(\.\w+\([^)]*\))*\s*\.collect""").containsMatchIn(code)
                }
                .map { "${file.path}: ${it.trim()}" }
        }
        assertEquals(emptyList<String>(), collectors,
            "sendError must be collected only via collectUserNotices in ui/App.kt")
    }
}
