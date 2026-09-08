package ru.sipaha.sawe.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.Flow
import ru.sipaha.sawe.app.ui.nav.AppNav
import ru.sipaha.sawe.app.vm.MainViewModel

/**
 * Application root: the nav graph plus the ONE app-level snackbar host
 * that surfaces [MainViewModel.sendError].
 *
 * `sendError` is a `Channel`-backed [kotlinx.coroutines.flow.Flow] with a
 * single consumer by design (see its KDoc). Collecting it here — above the
 * `NavHost`, so the collector outlives every route transition — is what
 * makes a notice reach the user no matter which screen is on top. Route
 * composables must NOT collect it: two collectors split the stream and each
 * screen steals notices meant for the other.
 *
 * Screens keep their own local [SnackbarHostState] for messages they raise
 * themselves (an attachment that exceeds the size cap, the bounce-recovery
 * notice) — those are scoped to the screen that produced them and never
 * travel through this channel.
 */
@Composable
fun App(vm: MainViewModel = viewModel(), initialRoute: String? = null) {
    val noticeHostState = remember { SnackbarHostState() }
    LaunchedEffect(vm) { collectUserNotices(vm.sendError, noticeHostState) }
    Box(modifier = Modifier.fillMaxSize()) {
        AppNav(viewModel = vm, initialRoute = initialRoute)
        // Top-aligned so a notice reads above the content instead of being
        // clipped behind a chat compose row / bottom bar, and inset out of
        // the status bar + landscape cutout.
        SnackbarHost(
            hostState = noticeHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                )
                .padding(8.dp),
        )
    }
}

/**
 * Whether an incoming notice should be shown, given the message we most
 * recently showed ([lastShown], `null` before the first).
 *
 * The rule is "never say the same thing twice in a row". It exists because of
 * one emitter in particular: `SessionListStore.refreshSessions` fires
 * `notConnectedMessage()` on every invocation with no live client, and it is
 * invoked on route entry / solution open — repeatedly, while a reconnect is
 * in flight. Three taps while offline queued three identical snackbars back
 * to back, each merely repeating what the connection strip already said.
 *
 * Keyed on the last message *shown* rather than the one currently *visible*:
 * `showSnackbar` suspends for the snackbar's whole duration while the channel
 * behind the flow keeps buffering, so by the time a duplicate is dequeued its
 * twin has usually already dismissed itself — a "is it on screen right now"
 * check would let the whole burst through, one popup at a time.
 *
 * Deliberately NOT a timer. The 4-second "wait and see if the connection
 * heals" gate this replaced suppressed by *timing*, and so silently ate
 * genuine deferred-send failures that landed inside a reconnect window
 * (N-04). Suppressing by *content* means a distinct failure always gets
 * through, and any distinct message resets the latch.
 */
internal fun shouldShowNotice(incoming: String, lastShown: String?): Boolean =
    incoming != lastShown

/**
 * Drain [notices] into [host], collapsing consecutive duplicates and letting
 * a new message take over the screen immediately instead of queueing behind
 * the current one.
 */
internal suspend fun collectUserNotices(notices: Flow<String>, host: SnackbarHostState) =
    collectUserNotices(notices) { msg ->
        host.currentSnackbarData?.dismiss()
        host.showSnackbar(message = msg, duration = SnackbarDuration.Short)
    }

/**
 * [collectUserNotices] with the presentation step injected, so the
 * de-duplication rule can be exercised without a live `SnackbarHost` (without
 * one attached, `showSnackbar` suspends forever and the collector never
 * reaches the second emission).
 */
internal suspend fun collectUserNotices(notices: Flow<String>, show: suspend (String) -> Unit) {
    var lastShown: String? = null
    notices.collect { msg ->
        if (!shouldShowNotice(msg, lastShown)) return@collect
        lastShown = msg
        show(msg)
    }
}
