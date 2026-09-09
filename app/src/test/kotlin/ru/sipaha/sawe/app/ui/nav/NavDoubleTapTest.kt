package ru.sipaha.sawe.app.ui.nav

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The blank screen, pinned.
 *
 * Reported 2026-09-09: "жму назад на экране со списком диалогов — ухожу на
 * пустой экран, где ничего не могу сделать". The trigger turned out to be two
 * taps on the chat screen's top-bar "←": the exiting screen is still composed
 * and hittable during the transition, so its `onBack` runs twice, the second
 * `popBackStack()` pops the start destination, and a `NavHost` with an empty
 * back stack draws nothing at all. Confirmed on the device — the Activity
 * stayed resumed while `uiautomator` found no text on screen.
 *
 * The system Back button was never affected (`NavHost` only enables its
 * handler while `currentBackStack.size > 1`), which is why the bug looked
 * like it "couldn't be in the nav graph".
 *
 * Both tests below drive the callback twice in a row with no frame in
 * between. That is the double tap, minus the timing flake — waiting on the
 * clock would just make the test race the transition. [rawDoublePop] is the
 * control: it shows the same two calls DO empty the stack without the guard,
 * so a regression here can't pass vacuously.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NavDoubleTapTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun twoScreens(
        onBackFactory: (androidx.navigation.NavBackStackEntry, NavHostController) -> () -> Unit,
    ): Pair<NavHostController, () -> Unit> {
        lateinit var nav: NavHostController
        var onBack: () -> Unit = {}
        composeTestRule.setContent {
            nav = rememberNavController()
            NavHost(navController = nav, startDestination = "workspace") {
                composable("workspace") { Text("workspace") }
                composable("chat") { entry ->
                    onBack = onBackFactory(entry, nav)
                    Text("chat")
                }
            }
        }
        composeTestRule.runOnIdle { nav.navigate("chat") }
        composeTestRule.waitForIdle()
        assertEquals("chat", nav.currentDestination?.route)
        return nav to onBack
    }

    @Test
    fun `two taps on the back arrow leave the start destination standing`() {
        val (nav, onBack) = twoScreens { entry, controller ->
            { entry.ifResumed { controller.popBackStack() } }
        }

        composeTestRule.runOnUiThread {
            onBack()
            onBack()
        }
        composeTestRule.waitForIdle()

        assertNotNull("the NavHost back stack was emptied — blank screen", nav.currentDestination)
        assertEquals("workspace", nav.currentDestination?.route)
    }

    @Test
    fun rawDoublePop() {
        // Control case: the unguarded form this replaced. If this ever stops
        // emptying the stack, the guard above is no longer proving anything
        // and this whole file should be re-derived.
        val (nav, onBack) = twoScreens { _, controller ->
            { controller.popBackStack() }
        }

        composeTestRule.runOnUiThread {
            onBack()
            onBack()
        }
        composeTestRule.waitForIdle()

        assertEquals(
            "unguarded double pop no longer empties the back stack",
            null,
            nav.currentDestination,
        )
    }

    @Test
    fun `two taps on a row open one destination, not two`() {
        lateinit var nav: NavHostController
        var onOpen: () -> Unit = {}
        composeTestRule.setContent {
            nav = rememberNavController()
            NavHost(navController = nav, startDestination = "workspace") {
                composable("workspace") { entry ->
                    onOpen = entry.guarded { nav.navigate("chat") }
                    Text("workspace")
                }
                composable("chat") { Text("chat") }
            }
        }

        composeTestRule.runOnUiThread {
            onOpen()
            onOpen()
        }
        composeTestRule.waitForIdle()

        assertEquals("chat", nav.currentDestination?.route)
        // One `chat` on top of `workspace` — plus the graph's own root entry.
        assertEquals(
            "a double tap stacked the destination twice",
            1,
            nav.currentBackStack.value.count { it.destination.route == "chat" },
        )
    }
}
