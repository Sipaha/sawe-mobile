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

// ---------------------------------------------------------------------
// Entry-body deltas (WireFeature.ENTRY_BODY_DELTA)
// ---------------------------------------------------------------------

/**
 * How many held bodies a poll offers the server at most.
 *
 * The server caps a response page at 10 changed entries, and in practice
 * what actually mutates between two polls is one streaming assistant entry
 * plus a live tool call or two. Eight newest bodies covers that with room
 * to spare; more would only grow the request.
 */
const val KNOWN_ENTRIES_MAX = 8

/**
 * Smallest body worth offering a digest for, in UTF-8 bytes.
 *
 * Each offered entry costs roughly 70 bytes of request. Below this
 * threshold the whole body is cheaper than the digest that would save it,
 * so offering one is a straight loss.
 *
 * Measured against the OFFERED prefix, not the held body: the offer is
 * what bounds the saving, so a long body whose stable prefix is tiny is
 * still not worth offering.
 */
const val MIN_DELTA_BODY_BYTES = 512

/**
 * Build the `known_entries` offer for the next `get_session_changes` poll
 * from the window this client currently holds.
 *
 * Newest first, so the cap keeps the entries that actually change; skips
 * anything without a body, with the `-1` index sentinel (whose index the
 * server could not resolve), or whose offered prefix is shorter than
 * [MIN_DELTA_BODY_BYTES]. Returns an empty list when nothing qualifies —
 * which callers must treat exactly as "absent", never as "delta
 * everything".
 *
 * **What is offered is the body MINUS its trailing whitespace run, not the
 * whole body**, and that detail is what makes the feature save anything at
 * all. The server renders an assistant entry as `"## Assistant\n\n" + body
 * + "\n\n"` (`session_entry_to_markdown`), so when the body grows the
 * trailing `"\n\n"` MOVES: the held rendering is not a byte-prefix of the
 * grown one, it diverges two bytes from its own end. Offering the whole
 * held length would therefore fail the server's prefix check on every
 * growing assistant entry — correct, but a whole-body fallback every time,
 * i.e. zero saving on precisely the traffic this feature exists to cut.
 * Trimming back to the last non-whitespace byte offers the part that is
 * genuinely stable, and the tail the server returns simply starts there
 * and re-includes the whitespace.
 *
 * The trim can only ever remove ASCII `\n` / space, which are never part
 * of a multi-byte sequence, so the offered offset is always a UTF-8
 * character boundary and the server's `is_char_boundary` check always
 * passes. And because the server verifies whatever prefix the caller
 * claims — it has no opinion about how the caller chose it — this policy
 * is client-local and cannot desync the two implementations.
 *
 * Pure but not free: it hashes up to [KNOWN_ENTRIES_MAX] bodies, so call it
 * from the poll coroutine, never on the main thread.
 *
 * The caller must additionally gate on
 * [WireFeature.ENTRY_BODY_DELTA] before putting the result on the wire —
 * [RemoteClient.getSessionChanges] enforces that gate itself, so the worst
 * case of forgetting is wasted hashing, not a failed poll.
 */
fun buildKnownEntries(held: List<EntrySummary>): List<KnownEntryDto> =
    held.asReversed()
        .asSequence()
        .mapNotNull { entry ->
            val markdown = entry.markdown ?: return@mapNotNull null
            if (entry.index < 0) return@mapNotNull null
            val bytes = markdown.toByteArray(Charsets.UTF_8)
            val offered = stableBodyPrefixLength(bytes)
            if (offered < MIN_DELTA_BODY_BYTES) return@mapNotNull null
            KnownEntryDto(
                index = entry.index,
                markdownLen = offered.toLong(),
                markdownHash = Digests.bodyDigest(bytes.copyOf(offered)),
            )
        }
        .take(KNOWN_ENTRIES_MAX)
        .toList()

/**
 * Length of the leading run of [bytes] that a growing body keeps unchanged
 * — everything up to the last byte that is not an ASCII newline or space.
 *
 * See [buildKnownEntries] for why the trailing whitespace has to come off.
 * Returns 0 for an all-whitespace body, which that caller then skips.
 */
private fun stableBodyPrefixLength(bytes: ByteArray): Int {
    val newline = '\n'.code.toByte()
    val space = ' '.code.toByte()
    var end = bytes.size
    while (end > 0 && (bytes[end - 1] == newline || bytes[end - 1] == space)) {
        end--
    }
    return end
}

/** Outcome of splicing delta bodies back into whole ones. */
sealed interface BodyRehydration {
    /** Every entry now carries a whole [EntrySummary.markdown], or no body at all. */
    data class Ok(val delta: GetSessionChangesResult) : BodyRehydration

    /**
     * The delta could not be spliced — a protocol violation, or a base the
     * client no longer holds. The caller MUST discard the whole delta and
     * full-reload; publishing a partially-spliced window would put a
     * corrupted transcript on screen and, worse, into the disk cache.
     */
    data class Broken(val reason: String) : BodyRehydration
}

/**
 * Turn a delta's tail-only entry bodies back into whole ones by splicing
 * each tail onto the body this client already holds for the same index.
 *
 * Applied to the delta BEFORE [applySessionDelta], so everything downstream
 * — the published window, the disk cache, and the digest the next poll
 * offers — sees ordinary whole bodies and never learns that a tail existed.
 *
 * Returns [BodyRehydration.Ok] with the delta untouched when it carries no
 * delta bodies at all, which is the case for every response from a server
 * that does not implement [WireFeature.ENTRY_BODY_DELTA].
 *
 * All-or-nothing: the first anomaly aborts with [BodyRehydration.Broken] and
 * no partially-rehydrated list is ever returned. The anomalies are the
 * violations of [EntrySummary.markdownPrefixLen]'s exclusivity table, plus
 * the one legitimate-but-unusable case of a base body the client has since
 * dropped.
 */
fun rehydrateEntryBodies(
    held: List<EntrySummary>,
    delta: GetSessionChangesResult,
): BodyRehydration {
    if (delta.changedEntries.none { it.markdownPrefixLen != null }) {
        return BodyRehydration.Ok(delta)
    }
    val byIndex = held.associateBy { it.index }
    val out = ArrayList<EntrySummary>(delta.changedEntries.size)
    for (entry in delta.changedEntries) {
        val prefixLen = entry.markdownPrefixLen
        if (prefixLen == null) {
            if (entry.markdownTail != null) {
                return BodyRehydration.Broken("tail without prefix_len at ${entry.index}")
            }
            out += entry
            continue
        }
        val tail = entry.markdownTail
            ?: return BodyRehydration.Broken("prefix_len without tail at ${entry.index}")
        if (entry.markdown != null) {
            return BodyRehydration.Broken("both markdown and tail at ${entry.index}")
        }
        val base = byIndex[entry.index]?.markdown
            ?: return BodyRehydration.Broken("no held body for ${entry.index}")
        val baseBytes = base.toByteArray(Charsets.UTF_8)
        if (prefixLen < 0 || prefixLen > baseBytes.size) {
            return BodyRehydration.Broken("prefix_len out of range at ${entry.index}")
        }
        // Byte-indexed, NOT `base.substring(0, prefixLen)`: `prefixLen` is a
        // UTF-8 byte count, and substring indexes UTF-16 units. Decoding the
        // byte slice is only safe because the server proved the offset falls
        // on a char boundary before it agreed to send a tail — a
        // non-boundary offset is a digest mismatch there, so it comes back
        // as a whole body instead.
        val merged = String(baseBytes, 0, prefixLen.toInt(), Charsets.UTF_8) + tail
        val expected = entry.markdownLen
        if (expected != null && merged.toByteArray(Charsets.UTF_8).size.toLong() != expected) {
            return BodyRehydration.Broken("length mismatch at ${entry.index}")
        }
        out += entry.copy(markdown = merged, markdownPrefixLen = null, markdownTail = null)
    }
    return BodyRehydration.Ok(delta.copy(changedEntries = out))
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
    // This content-match key is why `preview` suppression carves out
    // `role == "user"` entries (§3.1 of the wire spec): these are exactly
    // those entries, and a server that blanked their preview would leave
    // the key empty for every one of them. The carve-out is the server's
    // contract, not something the client can compensate for — rebuilding
    // the key from `markdown` would mean reimplementing the server's
    // scalar-value-counting truncation against Kotlin's UTF-16 `length`.
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
