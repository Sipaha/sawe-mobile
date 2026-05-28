package ru.sipaha.spkremote.app.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.sipaha.spkremote.core.ConnectionState
import ru.sipaha.spkremote.core.connectionBannerLabel

/**
 * Slim under-the-header connection-status strip. Single source of truth
 * for the "wire isn't healthy right now" UI — used by every screen with
 * a header (Workspace, SessionDetail, …). Replaces the older global
 * banner above the NavHost which pushed page content down on every
 * transient drop.
 *
 * Hidden entirely while [state] is [ConnectionState.Connected] — a
 * healthy screen shows nothing. Other states render a `tertiaryContainer`
 * (soft, "transient, will heal itself") or `errorContainer` (hard outage
 * / re-pair required) strip with an icon, the label from
 * [connectionBannerLabel], and an optional "последний обмен N мин назад"
 * suffix derived from [lastConnectedMs].
 *
 * If [onRePair] is supplied the strip becomes clickable while
 * [ConnectionState.FailedTerminal] holds — the only state where the
 * user can actually do something. Callers that don't own a re-pair
 * route (e.g. the in-chat banner) pass `null` and the strip stays
 * informational.
 */
@Composable
fun ConnectionStatusBanner(
    state: ConnectionState,
    lastConnectedMs: Long?,
    onRePair: (() -> Unit)? = null,
) {
    val label = connectionBannerLabel(state)
    var lastLabel by remember { mutableStateOf(label) }
    if (label != null) lastLabel = label
    AnimatedVisibility(visible = label != null) {
        // `label` becomes null the same frame `visible` flips to false, so we
        // read the cached `lastLabel` here — it stays non-null throughout the
        // exit animation so the content has something to render while fading.
        val text = lastLabel ?: return@AnimatedVisibility
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

        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(lastConnectedMs) {
            if (lastConnectedMs == null) return@LaunchedEffect
            while (true) {
                delay(15_000L)
                now = System.currentTimeMillis()
            }
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
        val tapHint = if (onRePair != null && state is ConnectionState.FailedTerminal) {
            " · нажмите чтобы перепарить"
        } else {
            ""
        }

        val clickable = onRePair != null && state is ConnectionState.FailedTerminal
        Surface(
            color = container,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (clickable && onRePair != null) Modifier.clickable(onClick = onRePair)
                    else Modifier
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
}
