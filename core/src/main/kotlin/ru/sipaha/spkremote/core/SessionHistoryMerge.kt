package ru.sipaha.spkremote.core

/**
 * Pure-fn merge of a freshly-fetched [GetSessionResult] against a
 * locally-cached transcript snapshot. Mirrors the
 * [SessionEntryMerge.kt][applyAppendedPlaceholder] extraction pattern so
 * the `:app`-side `SessionHistoryRepository` integration can be exercised
 * on plain JVM without instantiating Android `SharedPreferences`.
 *
 * Inputs are primitives (not `CachedSessionHistory`) so `:core` doesn't
 * need to know about the `:app`-local DTO.
 *
 *  - `cachedEntries` — entries already on disk (oldest → newest).
 *  - `cachedLastIndex` — highest `EntrySummary.index` in `cachedEntries`,
 *     or null when the cache is empty. Sentinel `-1` indices in
 *     `cachedEntries` are tolerated but the caller must pass `null` here
 *     when no usable index is known.
 *  - `cachedTotalCount` — server-reported total at the time of last
 *     write, or `-1` when unknown / no cache exists.
 *  - `fetched` — server's response. May be a diff (when caller passed
 *     `after_index`) or a full transcript.
 *  - `afterIndexHint` — the `after_index` cursor the caller actually
 *     sent on the wire, or null when the request was an unbounded
 *     `get_session`. Used to disambiguate "diff fetch that returned
 *     nothing because nothing new" from "full fetch that returned an
 *     empty session".
 */
sealed interface MergeOutcome {
    /** Caller should splice [appended] entries onto the end of its current list. */
    data class Appended(
        val appended: List<EntrySummary>,
        val mergedEntries: List<EntrySummary>,
        val newTotalCount: Int,
    ) : MergeOutcome

    /** Cache is stale / missing — replace the whole transcript with [entries]. */
    data class FullReplace(
        val entries: List<EntrySummary>,
        val newTotalCount: Int,
    ) : MergeOutcome

    /**
     * Diff fetch couldn't bridge the cache to the server's state — the
     * caller must re-issue `get_session` WITHOUT `after_index` and then
     * apply [FullReplace] on the result.
     */
    data class GapDetected(val reason: String) : MergeOutcome
}

/**
 * Decide what to do with [fetched] given the disk-cache snapshot.
 *
 * Decision matrix:
 *
 *  1. Empty cache (null lastIndex) → [MergeOutcome.FullReplace] of
 *     `fetched.entries`. The request was an unbounded `get_session`, so
 *     the response IS the transcript.
 *
 *  2. Diff fetch (`afterIndexHint != null`) — server returned only
 *     entries with `index > afterIndexHint`. We splice into the cache:
 *     - If `fetched.totalCount` is unknown (-1, pre-R-6e server): we
 *       can't validate the gap-detect math, so we trust the diff and
 *       [Appended] anyway. Pre-R-6e servers never returned partial
 *       slices so this branch is only reachable on legacy builds where
 *       the request degrades to a full transcript and `entries.size`
 *       happens to equal the full size.
 *     - If `fetched.totalCount >= 0` AND
 *       `cachedTotalCount + fetched.entries.size == fetched.totalCount`
 *       → [Appended]. The gap is closed.
 *     - Otherwise → [MergeOutcome.GapDetected]. The server moved beyond
 *       what one `after_index` round-trip can cover (e.g. a compact ran
 *       and renumbered, or entries were trimmed). Caller falls back to
 *       full fetch.
 *
 *  3. Full fetch (`afterIndexHint == null`) on top of a non-empty cache
 *     → [MergeOutcome.FullReplace]. The caller asked for the whole
 *     transcript — replace the cache wholesale.
 *
 * The merged transcript on the [Appended] path is `cachedEntries +
 * fetched.entries`. Caller-side dedup by `entry.index` is NOT performed
 * here — the gap-detect math already guarantees disjointness when it
 * passes, and the alternative (silently dropping duplicates) would mask
 * server bugs we'd rather see.
 */
fun mergeSessionHistory(
    cachedEntries: List<EntrySummary>,
    cachedLastIndex: Int?,
    cachedTotalCount: Int,
    fetched: GetSessionResult,
    afterIndexHint: Int?,
): MergeOutcome {
    if (cachedLastIndex == null || cachedEntries.isEmpty()) {
        return MergeOutcome.FullReplace(
            entries = fetched.entries,
            newTotalCount = fetched.totalCount,
        )
    }
    if (afterIndexHint == null) {
        return MergeOutcome.FullReplace(
            entries = fetched.entries,
            newTotalCount = fetched.totalCount,
        )
    }
    val expectedTotal = if (cachedTotalCount >= 0) {
        cachedTotalCount + fetched.entries.size
    } else {
        -1
    }
    val gapClosed = when {
        fetched.totalCount < 0 -> true
        expectedTotal < 0 -> true
        expectedTotal == fetched.totalCount -> true
        else -> false
    }
    if (!gapClosed) {
        return MergeOutcome.GapDetected(
            "expected totalCount=$expectedTotal, server reported ${fetched.totalCount}",
        )
    }
    return MergeOutcome.Appended(
        appended = fetched.entries,
        mergedEntries = cachedEntries + fetched.entries,
        newTotalCount = if (fetched.totalCount >= 0) fetched.totalCount else cachedTotalCount + fetched.entries.size,
    )
}
