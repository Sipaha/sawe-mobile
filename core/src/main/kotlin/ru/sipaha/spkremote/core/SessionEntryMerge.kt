package ru.sipaha.spkremote.core

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
 * Outcome of [applyAppendedPlaceholder]:
 *
 *   - [Replaced] — the placeholder fit inside the current entries (either
 *     appended at the end, or in-place replaced an existing slot). The
 *     caller publishes [entries] onto the session flow.
 *   - [OutOfRange] — the notification's [MessageAppendedPayload.entryIndex]
 *     is beyond `entries.size`. The caller must fall back to a full
 *     refetch of the session because the local transcript is missing the
 *     intermediate entries.
 */
sealed interface AppendedPlaceholderOutcome {
    data class Replaced(val entries: List<EntrySummary>) : AppendedPlaceholderOutcome
    data object OutOfRange : AppendedPlaceholderOutcome
}

/**
 * Apply a `agent_session_message_appended` placeholder onto the current
 * entry list. Addresses entries by their SERVER index, never by list
 * position — [current] may be a paginated window (e.g. indices 878..927 at
 * list positions 0..49) where the two diverge. [current] must be sorted
 * ascending by [EntrySummary.index].
 *
 *   - `entryIndex` already present (≤ max index, slot exists) — replace the
 *     slot in-place (idempotent: replaying the same payload yields the same
 *     list).
 *   - `entryIndex == maxIndex + 1` — append at the end (the next entry; works
 *     on a window without refetching the whole transcript).
 *   - Otherwise (`entryIndex > maxIndex + 1`, a gap, or an index below the
 *     window) — the local view is missing the slot; caller must refetch.
 *
 * Pure — never mutates [current].
 */
fun applyAppendedPlaceholder(
    current: List<EntrySummary>,
    payload: MessageAppendedPayload,
): AppendedPlaceholderOutcome {
    // Propagate the csid(s) from the notification onto the placeholder.
    // Without this, the user-bubble status row flips through `None` for
    // the brief window between the placeholder landing and
    // `fetchAndReplaceEntry` repopulating the real entry — the row's
    // height pops in and out, producing a visible vertical jump in the
    // bubble. The notification already carries the csid for user
    // entries (server-side `event_sources::build_message_appended_payload`),
    // so we can pre-stamp the placeholder and keep the status as
    // `Delivered` from the moment the slot exists.
    //
    // The server index is stamped on too: it makes the placeholder a
    // first-class indexed entry that the index-based dedup in
    // `SessionDetailStore.resumeSession` / `loadOlder` recognises (a sentinel
    // -1 was invisible to that dedup and a racing tail-resync could merge a
    // second copy → the `idx:N` duplicate-key crash).
    val placeholder = EntrySummary(
        role = payload.role,
        preview = payload.preview,
        index = payload.entryIndex,
        clientSendId = payload.clientSendId,
        clientSendIds = payload.clientSendIds,
        createdMs = payload.createdMs,
    )
    val maxIndex = current.lastOrNull()?.index ?: -1
    return when {
        // Existing slot (idempotent replay / in-place update). Find by index,
        // not position — they differ on a paginated window. A `null` slot
        // inside the range means a hole the diff can't bridge → refetch.
        payload.entryIndex in 0..maxIndex -> {
            val pos = current.indexOfFirst { it.index == payload.entryIndex }
            if (pos < 0) {
                AppendedPlaceholderOutcome.OutOfRange
            } else {
                AppendedPlaceholderOutcome.Replaced(
                    current.toMutableList().also { it[pos] = placeholder },
                )
            }
        }
        payload.entryIndex == maxIndex + 1 ->
            AppendedPlaceholderOutcome.Replaced(current + placeholder)
        else -> AppendedPlaceholderOutcome.OutOfRange
    }
}

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
    val serverPreviews: MutableList<String> = serverUser
        .filter { it.clientSendId == null }
        .map { stripRoleHeading(it.preview) }
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
