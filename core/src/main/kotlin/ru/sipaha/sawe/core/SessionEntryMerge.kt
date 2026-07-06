package ru.sipaha.sawe.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure content-merge / dedup algorithms used by the chat detail
 * collaborator. Extracted out of the Android `:app` module so they can be
 * unit-tested on the JVM without an Android context.
 *
 * Each function is total over its inputs (never throws), returns new
 * immutable lists, and never touches state-flows. The state-flow-touching
 * wrappers live in `SessionDetailStore` and just call these.
 */

/**
 * Replace the entry whose server index is [index], or insert [entry] at the
 * sorted-ascending-by-index position when no such slot exists. [current] must
 * be sorted ascending by [EntrySummary.index].
 *
 * [entry] is stamped with [index] so the stored list always carries correct
 * indices even when the single-entry fetch (`get_session_entry`) returned the
 * sentinel `index = -1` (pre-R-6e servers omit the field). The store addresses
 * entries by the index it requested, so the stored copy must agree — otherwise
 * the row would key on `pos`/hash instead of `idx:N`.
 *
 * Pure — never mutates [current].
 */
fun upsertEntryAtIndex(
    current: List<EntrySummary>,
    index: Int,
    entry: EntrySummary,
): List<EntrySummary> {
    val stamped = if (entry.index == index) entry else entry.copy(index = index)
    val pos = current.indexOfFirst { it.index == index }
    if (pos >= 0) {
        return current.toMutableList().also { it[pos] = stamped }
    }
    val insertAt = current.indexOfFirst { it.index > index }
    return if (insertAt < 0) {
        current + stamped
    } else {
        current.toMutableList().also { it.add(insertAt, stamped) }
    }
}

/**
 * True when [entries] (sorted ascending by index) still reach the session's
 * newest entry — i.e. they form a valid TAIL window that a resume /
 * `after_index` diff merge may keep instead of destructively refetching.
 *
 * Tail-anchoring is the ONLY invariant checked. Index-contiguity is
 * deliberately NOT required: a per-tab view (`subagent_filter`, default
 * `__main__`) returns entries whose `index` is the ABSOLUTE timeline position
 * with the OTHER tab's entries omitted (see the server's `get_session` —
 * `EntrySummary.index` is the enumerate position over ALL entries). So a
 * filtered tab on any session with active subagents legitimately has interior
 * index GAPS — that is not corruption. Requiring dense contiguity here made
 * every tail-resync tick (~4s while the agent runs) misfire on such a session:
 * the integrity check failed, `resumeSession` ran a destructive
 * `fetchInitialPage`/full-replace, and a scrolled-up reader got flung to the
 * top of a freshly-reloaded tail page.
 *
 * [totalCount] is the FILTERED entry count. Because a filtered view's entries
 * are a subset of `[0..newestIndex]`, that count never exceeds
 * `newestIndex + 1`, so `newest >= totalCount - 1` holds automatically for any
 * window that reaches the newest entry, and fails only when the window
 * genuinely falls short of the newest (the diff missed newer entries) — which
 * is exactly when a refetch is warranted. A partial scrolled-up window
 * (`size < totalCount`, older entries not yet loaded) stays valid. [totalCount]
 * < 0 (unknown / pre-R-6e) treats any non-empty window as a valid tail. Empty
 * [entries] counts as complete only when the session is itself empty (or
 * totalCount unknown).
 */
fun isTailAnchoredWindow(entries: List<EntrySummary>, totalCount: Int): Boolean {
    if (entries.isEmpty()) return totalCount <= 0
    val newest = entries.last().index
    return totalCount < 0 || newest >= totalCount - 1
}

/**
 * Defense-in-depth guard for the chat list: collapse any entries that share
 * a server `index` (>= 0) down to their FIRST occurrence, preserving order.
 *
 * Two list slots with the same index resolve to the same `idx:N` LazyColumn
 * key and HARD-CRASH the chat ("Key idx:N was already used"). The merge /
 * append paths in `SessionDetailStore` are meant to keep indices unique, but
 * the position-vs-index dual-addressing there has produced this crash family
 * more than once — this guard makes a residual duplicate degrade to a
 * harmless double-render-avoided instead of an app kill.
 *
 *   - Entries with `index < 0` (optimistic bubbles, un-indexed streaming
 *     placeholders) are passed through untouched — they are legitimately
 *     distinct and never key on `idx:`.
 *   - When nothing is dropped the SAME list instance is returned, so the
 *     common (clean) case adds no allocation and keeps referential stability
 *     for `remember`/recomposition.
 *
 * Pure — never mutates [entries].
 */
fun dedupeEntriesByIndex(entries: List<EntrySummary>): List<EntrySummary> {
    val seen = HashSet<Int>()
    val out = ArrayList<EntrySummary>(entries.size)
    for (e in entries) {
        if (e.index >= 0 && !seen.add(e.index)) continue
        out.add(e)
    }
    return if (out.size == entries.size) entries else out
}

// =============================================================================
// Delta-sync applier (Task 2 — pure state-transition function)
// =============================================================================

/**
 * Immutable snapshot that [applySessionDelta] transforms. [entries] is sorted
 * ascending by the SELECTED stream's stream-local [EntrySummary.index].
 * [state]/[pendingBundles] are always concrete (the held current values);
 * [streams] is the held full stream list. [totalCount] is the SELECTED
 * stream's entry count last reported by the server (-1 = unknown).
 */
data class SessionDeltaState(
    val entries: List<EntrySummary>,
    val totalCount: Int,
    val state: SessionStateDto,
    val pendingBundles: List<QueuedBundleSummary>,
    val streams: List<StreamDto>,
    val currentSeq: Long,
)

/**
 * Apply a [GetSessionChangesResult] delta onto a [SessionDeltaState] snapshot
 * and return the new snapshot. Pure — never mutates [current].
 *
 * Precondition: [delta.reset] is false. When the server signals a reset the
 * caller MUST discard the held snapshot and issue a fresh `get_session` call
 * (Task 4 handles this in `SessionDetailStore`). This function is never called
 * on a reset delta; passing one yields a semantically valid but stale result
 * because the function has no way to detect that the epoch is no longer live.
 *
 * Application order:
 * 1. Upsert [delta.changedEntries] by absolute index into the sorted entry list.
 * 2. Remove entries whose index ∈ [delta.removedIndices].
 * 3. Tail-truncate: drop the last `shrink` entries by count (not by index
 *    cutoff) where `shrink = max(0, current.totalCount - delta.totalCount)`.
 *    Shrink is skipped when either totalCount is negative (unknown / pre-R-6e).
 *    The function coerces shrink to at most `entries.size` as a safety guard.
 *    This drop-by-count model is load-bearing for filtered (per-subagent-tab)
 *    views, where held indices are ABSOLUTE and sparse — dropping by
 *    `index >= totalCount` would incorrectly remove most of the window.
 * 4. Keep or replace each section: for [state]/[pendingBundles] a `null`
 *    section means "unchanged" and a present list (even empty) replaces the
 *    current value; [streams] is ALWAYS present (a plain list, not nullable)
 *    and unconditionally replaces the held stream mirror.
 * 5. Advance [currentSeq] and [totalCount] to delta's values.
 */
fun applySessionDelta(current: SessionDeltaState, delta: GetSessionChangesResult): SessionDeltaState {
    // Step 1: upsert changed entries.
    var entries = current.entries
    for (entry in delta.changedEntries) {
        entries = upsertEntryAtIndex(entries, entry.index, entry)
    }

    // Step 2: remove explicitly removed indices.
    if (delta.removedIndices.isNotEmpty()) {
        val removed = delta.removedIndices.toHashSet()
        entries = entries.filter { it.index !in removed }
    }

    // Step 3: tail-truncate shrink by COUNT from the tail.
    val shrink = if (current.totalCount >= 0 && delta.totalCount >= 0) {
        (current.totalCount - delta.totalCount).coerceIn(0, entries.size)
    } else {
        0
    }
    if (shrink > 0) {
        entries = entries.dropLast(shrink)
    }

    // Step 4: adopt nullable sections only when the delta carries them;
    // `streams` is always present, so replace unconditionally.
    val newState = delta.state ?: current.state
    val newPendingBundles = delta.pendingBundles ?: current.pendingBundles
    val newStreams = delta.streams

    // Step 5: advance cursors.
    return SessionDeltaState(
        entries = entries,
        totalCount = delta.totalCount,
        state = newState,
        pendingBundles = newPendingBundles,
        streams = newStreams,
        currentSeq = delta.currentSeq,
    )
}

/**
 * Pop optimistic bubbles whose corresponding server-side "user" entry
 * has now landed. Matching is **id-first** (via `client_send_id`
 * stamped on the optimistic bubble + echoed by the server) with a
 * content-match fallback for legacy / cross-client entries that don't
 * carry an id. Walks optimistic entries in arrival order; per-server-
 * entry consumption is FIFO so duplicate-content sends still dedupe in
 * order. Original arrival order of unmatched optimistic entries is
 * preserved. The companion id and client-send-id lists are rewritten
 * in lock-step so the cancel-by-id path in the producer keeps working.
 *
 * @param optimistic              Current optimistic bubble list.
 * @param optimisticIds           Stable per-bubble local id list paired
 *                                with [optimistic] by index.
 * @param optimisticClientSendIds Per-bubble `_meta.spk_client_send_id`
 *                                stamp paired with [optimistic] by
 *                                index. `null` slot when the bubble
 *                                wasn't stamped (legacy `sendMessage`
 *                                path, or a future producer that opts
 *                                out).
 * @param serverEntries           The newly-received transcript slice
 *                                (any role — the function filters for
 *                                `role == EntryRoleDto.User` internally).
 *
 * @return `(remainingOptimistic, remainingIds, remainingClientSendIds)`
 *   — all three lists are freshly-allocated, ready to overwrite the
 *   state holders, paired by index, same length.
 */
fun reconcileOptimistic(
    optimistic: List<EntrySummary>,
    optimisticIds: List<Long>,
    optimisticClientSendIds: List<Long?>,
    serverEntries: List<EntrySummary>,
): Triple<List<EntrySummary>, List<Long>, List<Long?>> {
    if (optimistic.isEmpty()) {
        return Triple(emptyList(), emptyList(), emptyList())
    }
    val serverUser = serverEntries.filter { it.role == EntryRoleDto.User }
    // Modern servers expose every csid the queue-merge rolled into a
    // single user entry via [EntrySummary.clientSendIds]; fall back to
    // the singular [EntrySummary.clientSendId] for old servers that
    // only populate one. Build the union into a flat set so every
    // optimistic bubble whose csid matches any contributor pops.
    val serverIds: MutableSet<Long> = HashSet<Long>().also { set ->
        for (entry in serverUser) {
            if (entry.clientSendIds.isNotEmpty()) {
                set.addAll(entry.clientSendIds)
            } else if (entry.clientSendId != null) {
                set.add(entry.clientSendId)
            }
        }
    }
    // Strip the queue's injected meta (`[HH:MM:SS] ` timestamp + hint line)
    // from the SERVER side of the content-match key: a queued follow-up's
    // echo carries it but the optimistic bubble below (raw local text)
    // does not, so without this the keys never match and the message
    // double-renders. Only the server side is stripped — stripping the
    // optimistic too would over-strip a user who literally typed
    // `[HH:MM:SS] …`. csid-stamped echoes never reach here (filtered out
    // above); this is the no-csid / legacy fallback path.
    val serverPreviews: MutableList<String> = serverUser
        .filter { it.clientSendId == null }
        .map { stripInjectedMeta(stripRoleHeading(it.preview)) }
        .toMutableList()
    val keptEntries = mutableListOf<EntrySummary>()
    val keptIds = mutableListOf<Long>()
    val keptCsids = mutableListOf<Long?>()
    for ((idx, opt) in optimistic.withIndex()) {
        val id = optimisticIds.getOrNull(idx) ?: -1L
        val csid = optimisticClientSendIds.getOrNull(idx)
        if (csid != null && serverIds.remove(csid)) {
            // Id-match — server already echoed this bubble. Drop.
            continue
        }
        val key = stripRoleHeading(opt.preview)
        val hit = serverPreviews.indexOf(key)
        if (hit >= 0) {
            // Content-match fallback (legacy server / cross-client).
            serverPreviews.removeAt(hit)
            continue
        }
        keptEntries.add(opt)
        keptIds.add(id)
        keptCsids.add(csid)
    }
    return Triple(keptEntries, keptIds, keptCsids)
}

/**
 * Back-compat shim — forwards to [reconcileOptimistic] with an empty
 * client-send-id list (every slot treated as `null`), so this function
 * exercises only the content-match fallback path. Kept until every
 * caller migrates to the id-aware variant; no production code path
 * should rely on this shim.
 */
@Deprecated(
    message = "Use reconcileOptimistic with client_send_id list for id-based dedupe.",
    replaceWith = ReplaceWith("reconcileOptimistic(optimistic, optimisticIds, List(optimistic.size) { null }, serverUserEntries)"),
)
fun reconcileOptimisticContent(
    optimistic: List<EntrySummary>,
    optimisticIds: List<Long>,
    serverUserEntries: List<EntrySummary>,
): Pair<List<EntrySummary>, List<Long>> {
    val (entries, ids, _) = reconcileOptimistic(
        optimistic = optimistic,
        optimisticIds = optimisticIds,
        optimisticClientSendIds = List(optimistic.size) { null },
        serverEntries = serverUserEntries,
    )
    return entries to ids
}

/**
 * Pure parser for the bounce-to-input recovery path. Returns
 * `(sessionId, content)` when [message] is a non-blank user-shaped
 * queued message that survived a process restart and we can route its
 * text back into the compose field, or `null` when the message should
 * be silently dropped.
 *
 * Two methods are recognised:
 *  - `remote.solution_agent.send_message` — the legacy text-only
 *    path. `content` is the raw text payload.
 *  - `remote.solution_agent.send_message_blocks` — the multi-block
 *    path (post-R-6c-attach). We recover the FIRST text block's body
 *    as the bounce content so the user gets their typed message back.
 *    Image / file blocks can't be reconstructed without the original
 *    URIs (which we never persisted), so they are dropped from the
 *    bounce — V1 behaviour, acceptable given the queue TTL is 24 h
 *    and the user is likely to re-attach if they cared.
 *
 * Extracted from `SessionDetailStore.handleExpiredMessage` so the JVM
 * test target can exercise it directly without instantiating a
 * `DraftRepository` (which depends on Android `SharedPreferences`).
 */
fun parseExpiredSendMessage(message: QueuedMessage): Pair<String, String>? {
    val params = (message.params as? JsonObject) ?: return null
    val sessionId = params["session_id"]?.jsonPrimitive?.content ?: return null
    val content: String? = when (message.method) {
        "remote.solution_agent.send_message" ->
            params["content"]?.jsonPrimitive?.content
        "remote.solution_agent.send_message_blocks" ->
            extractFirstTextBlockBody(params["blocks"] as? JsonArray)
        else -> return null
    }
    if (content.isNullOrBlank()) return null
    return sessionId to content
}

/**
 * Pluck the body of the first `{"type": "text", "text": "..."}` entry
 * from a `blocks` array. Returns null when [blocks] is null, empty, or
 * contains only non-text variants. The discriminator is read from the
 * `"type"` field (matches the ACP `#[serde(tag = "type")]` envelope —
 * see [ContentBlockDto]).
 */
private fun extractFirstTextBlockBody(blocks: JsonArray?): String? {
    if (blocks == null) return null
    for (element in blocks) {
        val obj = element as? JsonObject ?: continue
        val type = obj["type"]?.jsonPrimitive?.content ?: continue
        if (type == "text") {
            val text = obj["text"]?.jsonPrimitive?.content
            if (!text.isNullOrBlank()) return text
        }
    }
    return null
}
