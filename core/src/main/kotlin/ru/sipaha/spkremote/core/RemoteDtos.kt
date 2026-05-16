package ru.sipaha.spkremote.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Server-shaped DTOs for the `remote.solutions.*` and
 * `remote.solution_agent.*` JSON-RPC namespaces.
 *
 * These mirror the spk-editor MCP tool catalog's response shapes verbatim,
 * including snake_case field names and the "state is a Rust Debug string"
 * quirk on [SessionSummary]. See [parseDisplayState] for the classifier the
 * UI uses to map that raw debug payload onto a small enum.
 */

@Serializable
data class SolutionSummary(
    val id: String,
    val name: String,
    val root: String,
    @SerialName("member_count") val memberCount: Int,
    @SerialName("last_opened_at") val lastOpenedAt: String? = null,
    @SerialName("window_open") val windowOpen: Boolean,
    @SerialName("main_window_id") val mainWindowId: String? = null,
)

@Serializable
data class ListSolutionsResult(val solutions: List<SolutionSummary>)

@Serializable
data class SessionSummary(
    val id: String,
    @SerialName("solution_id") val solutionId: String,
    @SerialName("agent_id") val agentId: String,
    val title: String,
    /**
     * Raw `format!("{:?}", state)` dump from the server side. Stable prefixes:
     * `Idle`, `Running`, `AwaitingInput`, `Errored`. Use [parseDisplayState]
     * to classify — do NOT try to parse the parenthesised payload, it is
     * a Rust Debug rendering and shape varies (e.g. embedded `Instant`).
     */
    val state: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("last_activity_at") val lastActivityAt: Long,
)

@Serializable
data class ListSessionsResult(val sessions: List<SessionSummary>)

/**
 * One transcript entry returned by `remote.solution_agent.get_session`.
 *
 * The server-side `EntrySummary` collapses arbitrary entry content (markdown,
 * image blocks, tool-call args/results) into a single truncated `preview`
 * string (~200 chars, ellipsised on overflow). That is the only message
 * content available remotely today — full content / images / tool details
 * require a future server-side extension. See R-5d follow-up notes.
 */
@Serializable
data class EntrySummary(
    /** Role tag: `user`, `assistant`, `tool_call`, or `plan`. Use [parseEntryRole]. */
    val role: String,
    /** Truncated markdown rendering of the entry. */
    val preview: String,
)

@Serializable
data class GetSessionResult(
    val id: String,
    @SerialName("solution_id") val solutionId: String,
    @SerialName("agent_id") val agentId: String,
    val title: String,
    /** Raw Rust `Debug` string — see [SessionSummary.state]. Classify via [parseDisplayState]. */
    val state: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("last_activity_at") val lastActivityAt: Long,
    val entries: List<EntrySummary>,
)

enum class DisplayState { Idle, Running, AwaitingInput, Errored, Unknown }

enum class EntryRole { User, Assistant, ToolCall, Plan, Unknown }

/**
 * Classify a server-side entry `role` string. Unknown roles fall through
 * to [EntryRole.Unknown] rather than throwing — older clients should
 * survive a future role expansion without crashing.
 */
fun parseEntryRole(raw: String): EntryRole = when (raw) {
    "user" -> EntryRole.User
    "assistant" -> EntryRole.Assistant
    "tool_call" -> EntryRole.ToolCall
    "plan" -> EntryRole.Plan
    else -> EntryRole.Unknown
}

/**
 * Classify a raw debug-formatted state string (see [SessionSummary.state]).
 *
 * The server emits Rust `Debug` format. Variants without payload come out as
 * the bare variant name (`"Idle"`); variants with payload come out as
 * `"VariantName { ... }"` or `"VariantName(...)"`. Prefix-match is the only
 * stable thing across server versions because the payload shape can change
 * (the `Running` variant has already been seen to embed an `Instant`).
 */
fun parseDisplayState(raw: String): DisplayState = when {
    raw.startsWith("Idle") -> DisplayState.Idle
    raw.startsWith("Running") -> DisplayState.Running
    raw.startsWith("AwaitingInput") -> DisplayState.AwaitingInput
    raw.startsWith("Errored") -> DisplayState.Errored
    else -> DisplayState.Unknown
}
