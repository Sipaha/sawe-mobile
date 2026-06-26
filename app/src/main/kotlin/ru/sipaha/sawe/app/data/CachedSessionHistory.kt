package ru.sipaha.sawe.app.data

import kotlinx.serialization.Serializable
import ru.sipaha.sawe.core.EntrySummary

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
    val schemaVersion: Int = CACHE_SCHEMA_VERSION,
    /** Phase-5 delta cursor: the session epoch this cache was built against. */
    val epoch: Long = 0,
    /** Phase-5 delta cursor: the server `current_seq` at last write — passed as
     *  `since_seq` on the next get_session_changes poll. */
    val lastSeq: Long = 0,
) {
    companion object {
        const val CACHE_SCHEMA_VERSION = 2
    }
}
