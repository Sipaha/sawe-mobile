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

/**
 * One member project inside a Solution as returned by `solutions.get`.
 * The dialog flow uses [localPath] to feed `create_session.cwd` so the
 * agent subprocess starts in the right worktree.
 */
@Serializable
data class SolutionMember(
    @SerialName("catalog_id") val catalogId: String,
    @SerialName("local_path") val localPath: String,
    val status: String,
)

@Serializable
data class SolutionDetails(
    val id: String,
    val name: String,
    val root: String,
    val members: List<SolutionMember> = emptyList(),
    @SerialName("last_opened_at") val lastOpenedAt: String? = null,
)

@Serializable
data class GetSolutionResult(
    val solution: SolutionDetails,
)

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
    /**
     * Running token total reported by the server-side adapter (F-server).
     * Null when the server omits the field (pre-F-server build) OR when the
     * adapter doesn't track usage. UI surfaces format via `abbreviateTokens`.
     */
    @SerialName("total_tokens") val totalTokens: Long? = null,
    /**
     * Parent session id when this session was spawned by another agent
     * (Claude Code Task dispatch, etc.). Null at the top of the tree.
     * Drives the sub-agent chip row on `SessionDetailScreen` (F-phone).
     */
    @SerialName("parent_session_id") val parentSessionId: String? = null,
)

/**
 * Result envelope for `remote.solution_agent.get_session_children` (F-server).
 *
 * Returns the immediate children of one session — sessions whose
 * `parent_session_id` matches the requested id. Order is by `created_at`
 * ascending (oldest sibling first). Empty list is a normal response for
 * top-level sessions that haven't dispatched any sub-agents.
 */
@Serializable
data class GetSessionChildrenResult(val children: List<SessionSummary>)

/**
 * Decoded `data` payload of an `agent_session_created` notification.
 *
 * F-server extended the event to carry `parent_session_id` so clients can
 * refresh a parent's chip row when a new sub-agent appears. Null means a
 * top-level session was created (no parent context). Older servers emit
 * the field as JSON `null` (or omit it entirely) — both decode to null.
 */
@Serializable
data class SessionCreatedPayload(
    @SerialName("session_id") val sessionId: String,
    @SerialName("parent_session_id") val parentSessionId: String? = null,
)

/**
 * Result envelope for `remote.solution_agent.list_sessions`.
 *
 * The server-side R-6e cursor params (`before_last_activity_at_ms`,
 * `count`) also surface a [totalCount] alongside the page of sessions.
 * Wiring the UI to consume it is deferred (R-6f cleanup) — the
 * sessions list is short enough today that a single full pull isn't a
 * load-bearing problem; carrying the field on the DTO is the cheap half
 * so the deferred UI work doesn't require another wire schema bump.
 *
 * Defaulted to `-1` so pre-R-6e server responses decode cleanly.
 */
@Serializable
data class ListSessionsResult(
    val sessions: List<SessionSummary>,
    @SerialName("total_count") val totalCount: Int = -1,
)

/**
 * One transcript entry returned by `remote.solution_agent.get_session`.
 *
 * The wire-side `EntrySummary` always carries a truncated `preview` (~200
 * chars). When the caller asks for `include_full_content=true` the server
 * also populates [markdown] with the entry's full markdown body. When
 * `include_images=true` and the entry is an assistant message containing
 * inline images, [images] is populated with base64-encoded blobs indexed by
 * their position in the markdown (referenced as `spk-image://N` URLs).
 *
 * For `role=tool_call` the server populates [toolCall] with name/status/args/
 * result; for `role=plan` it populates [plan] with the list of plan items.
 * These per-role payloads are absent on entries of other roles, so older
 * clients can ignore them safely.
 *
 * Backwards compatible with R-5d's flat `{role, preview}` shape — every new
 * field is nullable and defaulted, so the legacy minimal payload still
 * deserialises cleanly.
 */
@Serializable
data class EntrySummary(
    /** Role tag: `user`, `assistant`, `tool_call`, or `plan`. Use [parseEntryRole]. */
    val role: String,
    /** Truncated markdown rendering of the entry (≤200 chars, ellipsised). */
    val preview: String,
    /**
     * Absolute index of this entry inside the session's transcript
     * (post-R-6e). The server populates it on every page so paginated
     * clients can stitch slices together without an extra round-trip.
     *
     * **Sentinel:** defaults to `-1` so a pre-R-6e server (which doesn't
     * emit the field) still decodes cleanly. Any UI codepath that depends
     * on a valid index — `loadOlder`'s `before_index` cursor, the gap
     * detector in `resumeSession`, the disk-persisted lastSeen marker —
     * MUST guard against `-1` and either fall back to a full refetch or
     * skip the optimisation.
     */
    val index: Int = -1,
    /**
     * Full markdown body — only present when the request set
     * `include_full_content=true` AND the entry has more than the preview.
     */
    val markdown: String? = null,
    /**
     * Inline images referenced from [markdown] via `spk-image://N` URLs.
     * Present only when the request set `include_images=true` and the
     * entry actually carries image blocks. Index of each [EntryImage]
     * matches the `N` in the URL.
     */
    val images: List<EntryImage>? = null,
    /** Tool-call detail — present only on entries with `role=tool_call`. */
    @SerialName("tool_call") val toolCall: ToolCallSummary? = null,
    /** Plan detail — present only on entries with `role=plan`. */
    val plan: PlanSummary? = null,
)

/**
 * Base64-encoded inline image carried alongside an [EntrySummary].
 *
 * The server emits unpadded standard-alphabet base64. [index] matches the
 * `N` used to reference this image from markdown as `spk-image://N`.
 */
@Serializable
data class EntryImage(
    val index: Int,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("data_base64") val dataBase64: String,
)

/**
 * Server-side `EntrySummary.tool_call` payload for `role=tool_call` rows.
 *
 * [status] is a human-readable phrase: one of `"pending"`,
 * `"waiting for confirmation"`, `"running"`, `"done"`, `"failed"`,
 * `"rejected"`, `"canceled"`. Note the spaces (not underscores) and that
 * we DO NOT classify these into an enum — UI surfaces just render the
 * raw string with role-coloured pills.
 */
@Serializable
data class ToolCallSummary(
    val name: String,
    val status: String,
    @SerialName("args_preview") val argsPreview: String,
    @SerialName("result_preview") val resultPreview: String = "",
)

/**
 * Server-side `EntrySummary.plan` payload for `role=plan` rows.
 *
 * Each entry in [items] is a single plan step the agent committed to.
 */
@Serializable
data class PlanSummary(val items: List<String>)

/**
 * Result envelope for `remote.solution_agent.get_session_entry`.
 *
 * The server wraps the entry in a `{ entry: ... }` object rather than
 * returning it bare so future fields (cursor, neighbour ids, …) can be
 * added alongside without breaking clients that decode this type.
 */
@Serializable
data class GetSessionEntryResult(val entry: EntrySummary)

/**
 * Decoded payload of an `agent_session_message_appended` notification.
 *
 * Carries the entry index (post-R-5e); diff-streaming clients fetch the
 * full entry via `remote.solution_agent.get_session_entry { index }`
 * rather than re-polling the entire transcript on every append.
 */
@Serializable
data class MessageAppendedPayload(
    @SerialName("session_id") val sessionId: String,
    @SerialName("entry_index") val entryIndex: Int,
    val role: String,
    val preview: String,
)

/**
 * One adapter entry returned by `remote.solution_agent.list_agents`.
 *
 * [id] is the stable identifier passed to `create_session.agent_id`;
 * [displayName] is the human-readable label surfaced in the picker.
 */
@Serializable
data class AgentSummary(
    val id: String,
    @SerialName("display_name") val displayName: String,
)

/**
 * Result envelope for `remote.solution_agent.list_agents`.
 *
 * An empty [agents] list is a valid response when no adapters are
 * registered server-side — the UI must disable the create button and
 * surface a "no adapters available" hint rather than treat this as an
 * error condition.
 */
@Serializable
data class ListAgentsResult(val agents: List<AgentSummary>)

/**
 * Result envelope for `remote.solution_agent.create_session`.
 *
 * The wire-side structured-result key is `session_id` (not `id`), so we
 * map it explicitly via [SerialName]. The new session is empty (no
 * entries) until the caller fires `send_message` against it.
 */
@Serializable
data class CreateSessionResult(
    @SerialName("session_id") val sessionId: String,
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
    /**
     * Parent session id when this session was spawned by another agent.
     * Mirrors [SessionSummary.parentSessionId] — null at the top of the
     * agent-dispatch tree or on pre-F-server builds. F-phone uses this to
     * render the "Parent" chip on `SessionDetailScreen` without needing
     * a second list_sessions round-trip.
     */
    @SerialName("parent_session_id") val parentSessionId: String? = null,
    val entries: List<EntrySummary>,
    /**
     * Total number of entries in the session transcript (R-6e). When the
     * response is a paginated slice (`before_index` / `after_index` /
     * `count` set), [entries] is a subset and [totalCount] still reports
     * the full size — clients use this to drive the "Load older" gate and
     * the gap-detection safety net in `resumeSession`.
     *
     * **Sentinel:** defaults to `-1` for pre-R-6e server responses that
     * don't emit the field. UI codepaths that compare `totalCount` against
     * `entries.size` must short-circuit on `-1` and assume "everything
     * loaded" rather than fall through to a misleading gap-detect.
     */
    @SerialName("total_count") val totalCount: Int = -1,
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

/**
 * Drop the upstream `acp_thread` role banner — `## User`, `## User
 * (checkpoint)`, `## Assistant`, `## Plan`, `## Tool`, `## System` —
 * from the start of a markdown/preview string.
 *
 * The Zed core's `AgentThreadEntry::to_markdown` prepends one of these
 * headers to every entry. The Android client needs to strip them for
 * two reasons:
 *
 *  1. **Rendering**: the role is already encoded in bubble alignment +
 *     color, so the heading is visual noise; worse, the markdown
 *     renderer maps H2 to M3 `displayMedium` (~45 sp), turning a one-
 *     word "Assistant" banner into a screen-wide title.
 *  2. **Optimistic-bubble dedupe**: the server-echoed user `preview`
 *     contains `"## User\n\n…"` while the locally-appended optimistic
 *     bubble has just the raw user text. Without normalisation here,
 *     the dedupe by-string-equality never fires and the sent message
 *     appears twice in the chat.
 *
 * Conservative: only the prefix-line is stripped, and only when it
 * matches the upstream-emitted set. Anything else (a model-written
 * `## Step 1`) is preserved verbatim.
 */
fun stripRoleHeading(md: String): String = ROLE_HEADING_REGEX.replaceFirst(md, "").trim()

private val ROLE_HEADING_REGEX = Regex(
    pattern = """^\s*##\s+(?:User(?:\s+\(checkpoint\))?|Assistant|Plan|Tool|System)\s*\n+""",
    options = setOf(RegexOption.IGNORE_CASE),
)
