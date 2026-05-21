package ru.sipaha.spkremote.core

/**
 * Pure decision logic for the chat connection-status banner (Feature B).
 *
 * The banner is shown ONLY when the connection is NOT [ConnectionState.Connected]
 * — a healthy session shows nothing. Given the current [ConnectionState] this
 * returns the static (state-dependent) label, or `null` when the banner must be
 * hidden. The Android composable appends the localized "последний обмен …"
 * relative-time suffix separately (it needs [android.text.format.DateUtils]),
 * so this stays platform-free and unit-testable.
 *
 * Strings are Russian to match the app's Russian UI.
 */
fun connectionBannerLabel(state: ConnectionState): String? = when (state) {
    is ConnectionState.Connected -> null
    is ConnectionState.Connecting -> "Подключение…"
    is ConnectionState.Reconnecting ->
        if (state.attempt > 1) "Переподключение… (попытка ${state.attempt})"
        else "Переподключение…"
    is ConnectionState.Disconnected -> "Нет связи"
    is ConnectionState.FailedTerminal -> "Нет связи"
}

/**
 * Whether the banner should append a "last exchange …" suffix. Only meaningful
 * when the banner is visible (a non-Connected state) AND we have a recorded
 * last-connected timestamp (the connection worked at some point this run).
 */
fun connectionBannerShowsLastExchange(state: ConnectionState, lastConnectedMs: Long?): Boolean =
    connectionBannerLabel(state) != null && lastConnectedMs != null
