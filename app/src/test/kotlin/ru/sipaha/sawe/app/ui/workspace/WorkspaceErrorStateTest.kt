package ru.sipaha.sawe.app.ui.workspace

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The workspace's failed-snapshot state (N-12).
 *
 * `refreshWorkspace()` existed on the ViewModel with no caller anywhere in
 * `ui/`, so a first `workspace.snapshot` that timed out on a slow link left
 * the screen showing a bare error string until the user happened to background
 * and resume the app (which is what re-runs the probe). The retry button is
 * the reachable path back. The rendered look is covered by
 * `WorkspaceScreenSnapshotTest.error_state_with_retry`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class WorkspaceErrorStateTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun the_error_state_offers_a_retry_bound_to_a_refresh() {
        var retries = 0
        compose.setContent {
            MaterialTheme {
                Surface {
                    ErrorState(msg = "Request timed out", onRetry = { retries++ })
                }
            }
        }

        compose.onNodeWithText("Request timed out").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(2, retries)
    }
}
