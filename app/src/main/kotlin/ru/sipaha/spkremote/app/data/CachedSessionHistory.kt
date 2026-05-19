package ru.sipaha.spkremote.app.data

import kotlinx.serialization.Serializable
import ru.sipaha.spkremote.core.EntrySummary

/**
 * On-disk transcript snapshot for one session, persisted by
 * [SessionHistoryRepository] under the per-server `spk_history_cache`
 * prefs file.
 *
 * Lives in `:app` (not `:core`) because the cache is a mobile-side
 * optimisation and never crosses the wire.
 */
@Serializable
data class CachedSessionHistory(
    val sessionId: String,
    val solutionId: String,
    val agentId: String,
    val entries: List<EntrySummary>,
    /**
     * Highest `EntrySummary.index` present in [entries]; null when
     * [entries] is empty OR every entry carries the `-1` sentinel
     * (pre-R-6e server cache that wouldn't be safe to drive an
     * `after_index` diff against).
     */
    val lastIndex: Int?,
    /**
     * Server-reported `totalCount` at the time of last write. Used by
     * `mergeSessionHistory` for gap detection. `-1` when unknown
     * (pre-R-6e server response).
     */
    val totalCountAtLastWrite: Int,
    val schemaVersion: Int = 1,
)
