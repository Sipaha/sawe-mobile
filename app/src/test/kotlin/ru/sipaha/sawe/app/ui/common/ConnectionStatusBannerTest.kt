package ru.sipaha.sawe.app.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.sipaha.sawe.core.ConnectFailure
import ru.sipaha.sawe.core.ConnectionState

/**
 * The connection strip's terminal state (N-18).
 *
 * `connectionBannerLabel` renders `Disconnected` and `FailedTerminal`
 * identically as "Нет связи", which reads as an outage to wait out. A
 * `FailedTerminal` is nothing of the sort — the desktop no longer accepts this
 * phone's pairing secret and no amount of waiting fixes it — and nothing in
 * the app passed `onRePair`, so the one recovery affordance the strip already
 * had was unreachable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class ConnectionStatusBannerTest {

    @get:Rule
    val compose = createComposeRule()

    private val terminal = ConnectionState.FailedTerminal(ConnectFailure.AuthRejected())

    private fun setBanner(state: ConnectionState, onRePair: (() -> Unit)? = null) {
        compose.setContent {
            MaterialTheme {
                Surface {
                    LoudBanner(
                        state = state,
                        lastConnectedMs = null,
                        now = 1_000L,
                        onRePair = onRePair,
                    )
                }
            }
        }
    }

    @Test
    fun a_terminal_failure_says_the_pairing_is_invalid_and_offers_the_remedy() {
        var actions = 0
        setBanner(terminal, onRePair = { actions++ })

        val label = FAILED_TERMINAL_LABEL + FAILED_TERMINAL_ACTION_HINT
        compose.onNodeWithText(label).assertIsDisplayed()
        compose.onNodeWithText(label).performClick()
        assertEquals(1, actions)
    }

    /**
     * The action must not promise a reconnect. It used to read "нажмите,
     * чтобы переподключиться" and was wired to `retryConnection`, which
     * reconnects with the very secret the desktop is rejecting — the user
     * tapped, watched "Переподключение…", and landed back on the same banner
     * forever. It now navigates to the QR screen, the only thing that fixes
     * this state.
     */
    @Test
    fun the_terminal_action_does_not_promise_a_reconnect() {
        setBanner(terminal, onRePair = {})
        compose.onNodeWithText(
            FAILED_TERMINAL_LABEL + FAILED_TERMINAL_ACTION_HINT,
        ).assertIsDisplayed()
        assertFalse(
            "the terminal hint must not offer a reconnect: $FAILED_TERMINAL_ACTION_HINT",
            FAILED_TERMINAL_ACTION_HINT.contains("переподключ"),
        )
    }

    /**
     * A caller that hasn't wired the pairing route passes `null` and gets a
     * plain informational strip. It must not become a tap that goes nowhere —
     * an affordance that silently does nothing is what N-18 was about.
     */
    @Test
    fun a_terminal_failure_without_a_route_offers_no_dead_tap() {
        setBanner(terminal, onRePair = null)
        compose.onNodeWithText(FAILED_TERMINAL_LABEL).assertIsDisplayed()
        compose.onNodeWithText(
            FAILED_TERMINAL_LABEL + FAILED_TERMINAL_ACTION_HINT,
        ).assertDoesNotExist()
    }

    @Test
    fun a_plain_outage_is_still_the_generic_no_connection_line() {
        var rePairs = 0
        setBanner(ConnectionState.Disconnected, onRePair = { rePairs++ })

        // No re-pair hint and no tap target: reconnecting is the resilience
        // layer's job here, not the user's.
        compose.onNodeWithText("Нет связи").assertIsDisplayed()
        compose.onNodeWithText("Нет связи").performClick()
        assertEquals(0, rePairs)
    }

    @Test
    fun reconnecting_reports_the_attempt_number() {
        setBanner(ConnectionState.Reconnecting(attempt = 3, nextRetryMs = 1_000L))
        compose.onNodeWithText("Переподключение… (попытка 3)").assertIsDisplayed()
    }
}
