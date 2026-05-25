package ru.sipaha.spkremote.core

/**
 * Filter [entries] by the currently-selected sub-agent tab.
 *
 *  - `selectedId == null` → return only entries whose `subagentId` is null
 *    (the implicit "Main" tab — the main agent thread, plus optimistic /
 *    synthetic queue rows that never carry a subagent id).
 *  - `selectedId != null` → return only entries whose `subagentId` matches
 *    exactly. A sub-agent tab strictly hides Main-thread entries; the
 *    desktop subagent panel uses the same partitioning.
 *
 * Pure and side-effect-free so it can be unit-tested without Compose.
 * Order of [entries] is preserved.
 */
fun filterEntriesBySubagent(
    entries: List<EntrySummary>,
    selectedId: String?,
): List<EntrySummary> =
    if (selectedId == null) {
        entries.filter { it.subagentId == null }
    } else {
        entries.filter { it.subagentId == selectedId }
    }
