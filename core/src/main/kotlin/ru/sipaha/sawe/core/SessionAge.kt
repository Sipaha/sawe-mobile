package ru.sipaha.sawe.core

/** The largest day count [compactSessionAge] shows; past it the label stops changing. */
const val MAX_SESSION_AGE_DAYS: Long = 99

/** The widest label [compactSessionAge] can return, for sizing its fixed-width slot. */
const val WIDEST_SESSION_AGE: String = "99d"

/**
 * A session's age since its last activity, in at most three characters so it
 * fits a fixed-width slot: `now` (under a minute), `Nm`, `Nh`, then `Nd`
 * capped at `99d`. The same form as the desktop session tab's age
 * (`session_tab_strip::tab_age_label`), so the two surfaces read alike.
 * A timestamp in the future (clock skew) reads as `now`; a missing one
 * (`<= 0`) as an empty string.
 */
fun compactSessionAge(lastActivityAtMs: Long, nowMs: Long): String {
    if (lastActivityAtMs <= 0L) return ""
    val secs = (nowMs - lastActivityAtMs) / 1000
    return when {
        secs < 60 -> "now"
        secs < 3_600 -> "${secs / 60}m"
        secs < 86_400 -> "${secs / 3_600}h"
        else -> "${minOf(secs / 86_400, MAX_SESSION_AGE_DAYS)}d"
    }
}
