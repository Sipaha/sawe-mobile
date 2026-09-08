package ru.sipaha.sawe.app.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.sipaha.sawe.core.ConnectionState
import ru.sipaha.sawe.core.connectionBannerLabel

/**
 * How long a non-Connected period must last before we surface the loud
 * full-text banner. Below this, the strip is a slim indeterminate
 * progress bar so the user sees "something is happening" without
 * every flaky-network blip flashing a full reason line at them.
 *
 * Tuned to a typical reconnect window: the WS handshake + auth
 * normally lands within 1-3 s on LTE, well under 15 s. Genuine
 * outages (server crashed, NAT pinch the heartbeat watchdog hasn't
 * cleared yet) take longer — those reach this threshold and get
 * the loud surface they deserve.
 */
private const val QUIET_THRESHOLD_MS: Long = 15_000L

/**
 * Cadence at which the [now] state updates while the strip is in
 * quiet mode. Fast enough that the threshold check fires within ~half
 * a second of the grace window expiring; slow enough that we're not
 * recomposing every frame.
 */
private const val QUIET_TICK_MS: Long = 500L

/**
 * Cadence for the "последний обмен N мин назад" suffix once we're in
 * loud mode. The suffix is minute-granularity from
 * [android.text.format.DateUtils.getRelativeTimeSpanString], so a
 * 15s tick keeps the label honest without busy-spinning.
 */
private const val LOUD_TICK_MS: Long = 15_000L

/**
 * Banner text for [ConnectionState.FailedTerminal]. Deliberately different
 * from the shared "Нет связи" of [ConnectionState.Disconnected]: this state
 * is not an outage the resilience layer will heal, it is "the desktop no
 * longer accepts this phone's pairing secret".
 */
internal const val FAILED_TERMINAL_LABEL: String = "Сопряжение недействительно"

/**
 * Tap hint for the terminal state. The action goes straight to the QR
 * pairing screen, which is the only thing that fixes it — reconnecting is
 * NOT the remedy, since the stored secret is the very thing the desktop is
 * rejecting, so retrying with it loops forever.
 */
internal const val FAILED_TERMINAL_ACTION_HINT: String = " · нажмите, чтобы сопрячь заново"

/**
 * Slim under-the-header connection-status strip. Single source of truth
 * for the "wire isn't healthy right now" UI — used by every screen with
 * a header (Workspace, SessionDetail, …).
 *
 * Two-stage surface:
 *  1. Grace window (first [QUIET_THRESHOLD_MS] of any non-Connected
 *     period): a 2-dp indeterminate progress bar across the top of the
 *     content area. Conveys "trying" without spamming the user with the
 *     reason for every blip the resilience layer recovers from in <1s.
 *  2. After the threshold: the loud strip with icon + reason +
 *     "последний обмен N мин назад" suffix. `tertiaryContainer` for
 *     soft states ([ConnectionState.Connecting] /
 *     [ConnectionState.Reconnecting]); `errorContainer` for hard
 *     outages ([ConnectionState.Disconnected] /
 *     [ConnectionState.FailedTerminal]).
 *
 * [onRePair] must navigate to the QR pairing screen. It is NOT a reconnect:
 * the whole point of [ConnectionState.FailedTerminal] is that the stored
 * secret is the thing being rejected, so retrying with it loops forever. It
 * stays nullable so a caller with no route passes `null` and gets a plain
 * informational strip rather than a tap that goes nowhere — the failure this
 * banner exists to stop being silent about deserves better than a dead
 * affordance.
 *
 * Hidden entirely while [state] is [ConnectionState.Connected] — a
 * healthy screen shows nothing.
 */
@Composable
fun ConnectionStatusBanner(
    state: ConnectionState,
    lastConnectedMs: Long?,
    onRePair: (() -> Unit)? = null,
) {
    val unhealthy = state !is ConnectionState.Connected

    // Stamp the moment the current unhealthy period started. Reset to
    // null on every recovery so the next dip gets a fresh grace window.
    // `LaunchedEffect(unhealthy)` re-fires only on the boolean flip, not
    // on inner-class transitions (Connecting → Reconnecting), which is
    // what we want.
    //
    // `rememberSaveable`, so a rotation mid-outage doesn't re-stamp the
    // start and restart the quiet window from zero — the user would rotate
    // the phone in frustration and the banner would go quiet again, forever
    // (N-60). Only stamp when we don't already have one for this dip.
    var unhealthySinceMs by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(unhealthy) {
        unhealthySinceMs = when {
            !unhealthy -> null
            unhealthySinceMs == null -> System.currentTimeMillis()
            else -> unhealthySinceMs
        }
    }

    AnimatedVisibility(visible = unhealthy) {
        val started = unhealthySinceMs ?: return@AnimatedVisibility
        // Tick `now` while visible. Fast tick during the grace window so
        // the threshold check fires promptly; slow tick afterwards just
        // for the minute-relative suffix.
        var now by remember(started) { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(started) {
            while (now - started < QUIET_THRESHOLD_MS) {
                delay(QUIET_TICK_MS)
                now = System.currentTimeMillis()
            }
            while (true) {
                delay(LOUD_TICK_MS)
                now = System.currentTimeMillis()
            }
        }
        val elapsed = now - started
        if (elapsed < QUIET_THRESHOLD_MS) {
            // Quiet mode: 2-dp indeterminate progress bar. The
            // surfaceVariant track keeps it visible on light + dark
            // themes without competing for attention with real content.
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        } else {
            LoudBanner(state = state, lastConnectedMs = lastConnectedMs, now = now, onRePair = onRePair)
        }
    }
}

/**
 * The full-text banner shown once the grace window in [ConnectionStatusBanner]
 * has expired. Split out (and `internal`) so its per-state copy can be tested
 * without waiting out the 15-second quiet period.
 */
@Composable
internal fun LoudBanner(
    state: ConnectionState,
    lastConnectedMs: Long?,
    now: Long,
    onRePair: (() -> Unit)?,
) {
    // `connectionBannerLabel` renders Disconnected and FailedTerminal
    // identically ("Нет связи"), which reads as a network problem the user
    // should wait out — but FailedTerminal means the pairing itself is no
    // longer valid (a rotated secret, or a pin mismatch that survived three
    // consecutive attempts) and waiting will never fix it. Say so, and hand
    // the user the one action that resolves it: a fresh QR scan (N-18).
    val terminal = state is ConnectionState.FailedTerminal
    val text = if (terminal) FAILED_TERMINAL_LABEL else connectionBannerLabel(state) ?: return
    val isHardOutage = state is ConnectionState.Disconnected ||
        state is ConnectionState.FailedTerminal
    val container = if (isHardOutage) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val onContainer = if (isHardOutage) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }

    val suffix = if (lastConnectedMs != null) {
        val relative = android.text.format.DateUtils.getRelativeTimeSpanString(
            lastConnectedMs,
            now,
            android.text.format.DateUtils.MINUTE_IN_MILLIS,
            android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
        " · последний обмен $relative"
    } else {
        ""
    }
    val clickable = onRePair != null && terminal
    val tapHint = if (clickable) FAILED_TERMINAL_ACTION_HINT else ""
    Surface(
        color = container,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onRePair != null && clickable) Modifier.clickable(onClick = onRePair)
                else Modifier,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isHardOutage) Icons.Filled.CloudOff else Icons.Filled.Warning,
                contentDescription = null,
                tint = onContainer,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text + suffix + tapHint,
                style = MaterialTheme.typography.labelMedium,
                color = onContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
