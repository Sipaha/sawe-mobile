package ru.sipaha.sawe.app.ui.solutions

import androidx.compose.runtime.saveable.SaverScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * [DraftTextSaver], the saved-state saver shared by every unbounded free-text
 * field in the chat surface: the compose-bar draft, both `NewSessionDialog`
 * fields, the rename dialog and the supervisor custom prompt (N-58, review
 * finding 13).
 *
 * `autoSaver` would put the raw `String` into the `NavBackStackEntry`'s saved
 * state, which travels through a Binder transaction on backgrounding — a
 * pasted build log then kills the app with `TransactionTooLargeException`.
 */
class DraftTextSaverTest {

    /** `canBeSaved` is only consulted for the value the saver returns. */
    private val scope = SaverScope { true }

    @Test
    fun `an ordinary field round-trips unchanged`() {
        with(DraftTextSaver) {
            val saved = scope.save("Fix the flaky upload test")
            assertEquals("Fix the flaky upload test", saved)
            assertEquals("Fix the flaky upload test", restore(saved!!))
        }
    }

    @Test
    fun `an empty field round-trips`() {
        with(DraftTextSaver) {
            val saved = scope.save("")
            assertEquals("", saved)
            assertEquals("", restore(saved!!))
        }
    }

    @Test
    fun `a field right at the cap is still saved`() {
        val atCap = "x".repeat(DRAFT_SAVEABLE_MAX_CHARS)
        with(DraftTextSaver) { assertEquals(atCap, scope.save(atCap)) }
    }

    /**
     * Over the cap the saver writes nothing, so `rememberSaveable` falls back
     * to its initialiser on restore. Every site that uses it has a fallback:
     * the compose bar re-seeds from the on-disk draft, the supervisor sheet
     * re-seeds from `state.customPrompt`, and the rename dialog from the
     * current title.
     */
    @Test
    fun `an oversized field is kept out of the bundle entirely`() {
        with(DraftTextSaver) {
            assertNull(scope.save("x".repeat(DRAFT_SAVEABLE_MAX_CHARS + 1)))
            assertNull(scope.save("x".repeat(1_000_000)))
        }
    }
}
