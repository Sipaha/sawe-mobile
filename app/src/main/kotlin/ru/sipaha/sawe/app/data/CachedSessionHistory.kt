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
    val solutionId: Long,
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
     * Server-reported `totalCount` at the time of last write. Seeds the
     * delta applier's tail-truncate shrink detection on the next open.
     * `-1` when unknown (pre-R-6e server response).
     */
    val totalCountAtLastWrite: Int,
    /**
     * Cache schema version. Default is the legacy sentinel `1`: a genuine
     * pre-Phase-6 blob was serialized (under `encodeDefaults = false`) without
     * any `schemaVersion` key, so it must decode back to `1` and be rejected by
     * [SessionHistoryRepository.gateBySchema]. Every NEW write is stamped with
     * [CACHE_SCHEMA_VERSION] in `SessionHistoryRepository.writeNow` before
     * encoding, so `3 != 1` (the default) forces the key to be persisted and the
     * blob decodes back to `3`, passing the gate. Bumped 2→3 for the numeric
     * Solution/catalog-id wire migration — a pre-migration blob has
     * [solutionId] as a JSON string and must be invalidated rather than
     * fail to decode.
     */
    val schemaVersion: Int = 1,
    /** Phase-5 delta cursor: the session epoch this cache was built against. */
    val epoch: Long = 0,
    /** Phase-5 delta cursor: the server `current_seq` at last write — passed as
     *  `since_seq` on the next get_session_changes poll. */
    val lastSeq: Long = 0,
) {
    companion object {
        const val CACHE_SCHEMA_VERSION = 3
    }
}
