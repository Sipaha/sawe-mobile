package ru.sipaha.spkremote.app.ui.solutions

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.sipaha.spkremote.core.DisplayState

/**
 * Small colored pill rendering a session's [DisplayState].
 *
 * Shared by [SolutionDetailScreen] (session list rows) and
 * [SessionDetailScreen] (top bar) so both surfaces agree on the
 * label/color mapping. `raw` is the unparsed server-side state string
 * — surfaced verbatim (truncated) for [DisplayState.Unknown] so the
 * user can see the raw value while we add support for new states.
 *
 * `raw` is shown with `.ifBlank { "?" }` so an empty unknown state
 * doesn't render as an empty pill.
 */
@Composable
internal fun StatePill(state: DisplayState, raw: String) {
    val (label, fg, bg) = when (state) {
        DisplayState.Idle -> Triple(
            "Idle",
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.surfaceVariant,
        )
        DisplayState.Running -> Triple(
            "Running",
            MaterialTheme.colorScheme.onPrimary,
            MaterialTheme.colorScheme.primary,
        )
        DisplayState.AwaitingInput -> Triple(
            "Awaiting input",
            MaterialTheme.colorScheme.onTertiaryContainer,
            MaterialTheme.colorScheme.tertiaryContainer,
        )
        DisplayState.Errored -> Triple(
            "Errored",
            MaterialTheme.colorScheme.onErrorContainer,
            MaterialTheme.colorScheme.errorContainer,
        )
        DisplayState.Unknown -> Triple(
            raw.take(20).ifBlank { "?" },
            MaterialTheme.colorScheme.onSurface,
            MaterialTheme.colorScheme.surface,
        )
    }
    Surface(
        color = bg,
        contentColor = fg,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 0.dp,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
