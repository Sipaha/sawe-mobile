package ru.sipaha.sawe.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Server-shaped DTOs for the `remote.solutions.*` and
 * `remote.solution_agent.*` JSON-RPC namespaces.
 *
 * These mirror the spk-editor MCP tool catalog's response shapes verbatim,
 * including snake_case field names. Session `state` is a structured tagged
 * object ([SessionStateDto]); map it to the UI classifier via
 * [SessionStateDto.displayState].
 */

/**
 * Latest chat-wire schema version this client understands. Bump this
 * whenever the structured wire shapes ([SessionStateDto] / [EntryRoleDto] /
 * [ToolCallStatusDto] tagging, role-typed payloads on [EntrySummary],
 * etc.) acquire a breaking change. Used to gate against a server that
 * advertises a newer schema in its `editor.capabilities` response — see
 * [isServerTooNew] and the gate site in `ConnectionManager`.
 *
 * Reverse direction (server older / pre-versioned / field absent) is
 * NOT gated: the server's contract is to keep emitting decodable shapes
 * within a major version, and every DTO already defaults forward-compat
 * fields. Only `server > supported` blocks the UI.
 *
 * **Version history**
 * - v1: initial versioned wire (structured [SessionStateDto]/[EntryRoleDto]/[ToolCallStatusDto])
 * - v2: added `workspace.*` notification namespace; renamed
 *       `SolutionSummary.window_open` → `open`; renamed
 *       `solution_agent.close_session` →
 *       `solution_agent.delete_session` (`workspace.close_session` is
 *       a NEW non-destructive tab-strip-remove tool, not a rename)
 */
const val SUPPORTED_WIRE_SCHEMA_VERSION: Int = 2

/**
 * True iff the server advertises a chat-wire schema this client doesn't
 * support yet. The UI surfaces an "update the app" gate on `true` instead
 * of trying to drive sessions off a wire it can't decode.
 *
 * Defaults to the build-time [SUPPORTED_WIRE_SCHEMA_VERSION] constant —
 * the [supported] override is for unit tests so the gate's threshold can
 * be exercised without rebuilding the constant.
 */
fun isServerTooNew(serverWire: Int, supported: Int = SUPPORTED_WIRE_SCHEMA_VERSION): Boolean =
    serverWire > supported

/**
 * Result envelope for `remote.editor.capabilities`.
 *
 * The server emits more fields than the client currently consumes
 * (build metadata, transport hints, …) — `ignoreUnknownKeys` drops the
 * rest. Today we only need:
 *   - [protocolVersion] for the optional version banner on the splash.
 *   - [wireSchemaVersion] for the breaking-incompatibility gate; see
 *     [isServerTooNew].
 *
 * **Both fields default to a pre-versioned sentinel** so an older server
 * that doesn't emit them decodes cleanly: missing `protocol_version`
 * becomes "unknown" (preserving prior behaviour), missing
 * `wire_schema_version` becomes `0` (older-than-supported → not gated).
 */
@Serializable
data class CapabilitiesDto(
    @SerialName("protocol_version") val protocolVersion: String = "unknown",
    @SerialName("wire_schema_version") val wireSchemaVersion: Int = 0,
)

@Serializable
data class SolutionSummary(
    val id: String,
    val name: String,
    val root: String,
    @SerialName("member_count") val memberCount: Int,
    @SerialName("last_opened_at") val lastOpenedAt: String? = null,
    @SerialName("open") val open: Boolean,
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

/**
 * One catalog (registry) project as returned by `catalog.list`.
 *
 * The server envelope carries more fields (`remote_url`, `cache_status`,
 * `default_branch`, …) but the mobile project picker only needs the id +
 * display name; `ignoreUnknownKeys` drops the rest. **The wire field for
 * the id is `id`, NOT `catalog_id`** (see `crates/solutions/src/mcp.rs`
 * `build_catalog_info`) — `solutions.add_member` then takes that same
 * value back as its `catalog_id` param, so we expose it as [catalogId]
 * on the mobile side for naming consistency with [SolutionMember].
 */
@Serializable
data class CatalogProjectInfo(
    @SerialName("id") val catalogId: String,
    val name: String,
)

@Serializable
data class CatalogListResult(val projects: List<CatalogProjectInfo> = emptyList())

/**
 * Result envelope for `solutions.create`. The wire field is `solution_id`
 * (not `id`); the "create solution with projects" flow needs the new id to
 * fire follow-up `add_member` / `add_empty_member` calls and to navigate.
 */
@Serializable
data class CreateSolutionResult(@SerialName("solution_id") val solutionId: String)

/**
 * Result envelope for `solutions.add_member`. The clone runs in the
 * background; [operationId] can be polled via `editor.get_operation` but
 * mobile instead watches the `solution_member_add_*` notifications.
 */
@Serializable
data class AddMemberResult(@SerialName("operation_id") val operationId: String)

/** Result envelope for `solutions.add_empty_member` — synchronous create. */
@Serializable
data class AddEmptyMemberResult(@SerialName("catalog_id") val catalogId: String)

/**
 * Decoded payload of a `solution_member_add_progress` notification.
 *
 * [percent] is nullable: the server's progress callback carries an
 * `Option<u8>`, so a tick with no known percentage arrives as JSON `null`
 * (e.g. the indeterminate "resolving objects" git phase).
 */
@Serializable
data class MemberAddProgressPayload(
    @SerialName("solution_id") val solutionId: String,
    @SerialName("catalog_id") val catalogId: String,
    val percent: Int? = null,
    val stage: String? = null,
)

/**
 * Decoded payload of a `solution_member_add_completed` notification.
 * [error] is null on success; non-null carries the failure (or
 * `"cancelled"`) for surfacing on the ghost member row.
 */
@Serializable
data class MemberAddCompletedPayload(
    @SerialName("solution_id") val solutionId: String,
    @SerialName("catalog_id") val catalogId: String,
    val error: String? = null,
)

@Serializable
data class SessionSummary(
    val id: String,
    @SerialName("solution_id") val solutionId: String,
    @SerialName("agent_id") val agentId: String,
    val title: String,
    /**
     * Structured session state — a tagged object discriminated on `kind`.
     * Map to the UI's [DisplayState] via [SessionStateDto.displayState]; the
     * `Running` start anchor and `Errored` message are reachable via
     * [SessionStateDto.startedAtMs] / [SessionStateDto.erroredMessage].
     */
    val state: SessionStateDto,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("last_activity_at") val lastActivityAt: Long,
    /**
     * Running token total reported by the server-side adapter (F-server).
     * Null when the server omits the field (pre-F-server build) OR when the
     * adapter doesn't track usage. UI surfaces format via `abbreviateTokens`.
     */
    @SerialName("total_tokens") val totalTokens: Long? = null,
    /**
     * Model context window in tokens — `totalTokens / maxTokens` is the
     * "context fill" fraction the chat header's meter renders. Mirrors
     * the desktop status row's `used / max` readout. `null` until the
     * agent reports its first usage update or on pre-`max_tokens` server
     * builds; the meter Composable renders nothing in that case so the
     * top bar stays clean.
     */
    @SerialName("max_tokens") val maxTokens: Long? = null,
    /**
     * Parent session id when this session was spawned by another agent
     * (Claude Code Task dispatch, etc.). Null at the top of the tree.
     * Drives the sub-agent chip row on `SessionDetailScreen` (F-phone).
     */
    @SerialName("parent_session_id") val parentSessionId: String? = null,
    /**
     * In-flight Claude Code sub-agents (Task/Agent tool-uses) for this
     * session, in server-side insertion order. Drives the subagent-tabs
     * strip on the detail screen — each entry is one pill alongside the
     * implicit "Main" tab. Live updates ride the
     * `agent_session_active_subagents_changed` notification; this field
     * is the cold-start seed. Defaults to empty so pre-Etap-5 server
     * responses decode cleanly.
     */
    @SerialName("active_subagents") val activeSubagents: List<SubagentDto> = emptyList(),
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
    /** Role tag — structured enum (`user`, `assistant`, `tool_call`, `plan`). */
    val role: EntryRoleDto,
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
    /**
     * Client-stamped correlation id for the originating user message — the
     * server lifts `_meta.spk_client_send_id` from the first ContentBlock of
     * the user message's chunks and re-emits it on this entry. Present only
     * for `role == "user"` entries that carry the meta (mobile sends stamp
     * one; desktop-originated sends omit it). Used by mobile to dedupe an
     * optimistic bubble against the server echo without depending on a
     * fragile content-match against the truncated preview.
     */
    @SerialName("client_send_id") val clientSendId: Long? = null,
    /**
     * Every distinct `spk_client_send_id` carried by this user entry,
     * in source order. The single-id [clientSendId] above stays for
     * back-compat — modern servers populate both, older servers only
     * populate the singular field. Mobile dedupe should prefer this
     * list when non-empty: the server-side queue-merge path rolls N
     * originating bundles into one ACP user message with N distinct
     * stamps, and only popping the first leaves N-1 optimistic
     * bubbles orphaned.
     */
    @SerialName("client_send_ids") val clientSendIds: List<Long> = emptyList(),
    /**
     * Unix-millis creation time captured server-side at first append. Null
     * for entries that predate the feature (UI shows no time, never a
     * fabricated one). Also delivered on the `agent_session_message_appended`
     * notification so a streamed entry shows its time without a refetch.
     */
    @SerialName("created_ms") val createdMs: Long? = null,
    /**
     * Parent Claude Code `Task`/`Agent` tool-use id (`toolu_xxx`) when this
     * entry was produced inside a sub-agent dispatch. Lifted server-side
     * from `_meta.claudeCode.parentToolUseId`. Null for the main agent's
     * entries; non-null entries are filtered into the matching subagent
     * tab on `SessionDetailScreen`. Pre-Etap-5 servers omit the field —
     * defaults to null so old transcripts still decode.
     */
    @SerialName("subagent_id") val subagentId: String? = null,
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
 * [status] is a structured enum — one of `pending`,
 * `waiting_for_confirmation`, `running`, `done`, `failed`, `rejected`,
 * `canceled` (snake_case on the wire).
 */
@Serializable
data class ToolCallSummary(
    @SerialName("tool_call_id") val toolCallId: String = "",
    val name: String,
    val status: ToolCallStatusDto,
    @SerialName("args_preview") val argsPreview: String,
    @SerialName("result_preview") val resultPreview: String = "",
    /**
     * Unix epoch in milliseconds captured the first time this tool
     * call transitioned into `running`. Server-side it's preserved
     * across the transition to terminal statuses so a completed tool
     * row could read "ran for Xs" too, but the chat surface only
     * shows the live "Xs" badge while `status == "running"` (the
     * tick is cancelled the moment the status changes). `null` for
     * tool calls that never entered `running` (e.g. cold-rehydrated
     * entries restored straight into a terminal status, or pending
     * calls that haven't started yet) — the badge stays hidden.
     */
    @SerialName("tool_status_started_at_ms") val toolStatusStartedAtMs: Long? = null,
    /**
     * Authorization options surfaced while the call is awaiting
     * confirmation (`status == "waiting for confirmation"`). Empty
     * otherwise. The UI renders one button per option and answers via
     * `remote.solution_agent.authorize_tool_call`, echoing the chosen
     * [ToolCallAuthOption.optionId] verbatim.
     */
    val options: List<ToolCallAuthOption> = emptyList(),
)

/**
 * One authorization choice the agent offered for a tool call awaiting
 * confirmation. [optionId] is opaque — pass it back verbatim to
 * `authorize_tool_call`. [kind] is one of `allow_once`, `allow_always`,
 * `reject_once`, `reject_always`; [isAllow] marks allow-style options so
 * the UI can render them as primary buttons.
 */
@Serializable
data class ToolCallAuthOption(
    @SerialName("option_id") val optionId: String,
    val label: String,
    val kind: String,
    @SerialName("is_allow") val isAllow: Boolean = false,
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
/**
 * One queued-message descriptor from the server's `pending_messages`.
 * Mirrors `mcp::QueuedBundleSummary` on the desktop side. Mobile
 * surfaces each as a Queued bubble in the chat list, on equal
 * footing whether the bundle was enqueued from a paired desktop or
 * from another mobile — single mechanism for "what's waiting for
 * the agent's current turn to finish".
 */
@Serializable
data class QueuedBundleSummary(
    /**
     * Every `spk_client_send_id` carried by the bundle. Mobile pops
     * its own local optimistic bubbles whose csid lands in this set,
     * so the bundle replaces them visually with a single Queued
     * bubble (matching the desktop's "single ghost bubble that
     * grows" UX for merged sends). Empty for desktop-typed bundles
     * (no client-stamped csid).
     */
    val csids: List<Long> = emptyList(),
    /**
     * Markdown rendering of the bundle's content (queue marker
     * stripped, `[image #N]` placeholders left in-place).
     */
    val preview: String,
    /**
     * Image-block count inside the bundle — drives the
     * `[image #N]` link affordance on the Queued bubble without
     * needing to ship the actual image bytes on this wire.
     */
    @SerialName("image_count") val imageCount: Int = 0,
)

/**
 * Decoded `params.payload` of an `agent_session_queue_changed`
 * notification. Server emits one of these every time
 * `SolutionSession::pending_messages` mutates (push, merge into
 * back, drain on flush, clear on cancel) so every paired client
 * keeps a live view of the unified server-side queue. `bundles: []`
 * is the canonical "queue is empty" payload.
 */
@Serializable
data class SessionQueueChangedPayload(
    @SerialName("session_id") val sessionId: String,
    val bundles: List<QueuedBundleSummary> = emptyList(),
)

/**
 * One in-flight Claude Code sub-agent (Task/Agent tool use) as the server
 * sees it. Mirrors `SubagentDto` on the desktop side. `id` is the parent
 * `toolu_xxx` tool-use id — the same value mobile filters entries by via
 * [EntrySummary.subagentId]. `label` is a human-friendly tab title chosen
 * by the server (Task description → subagent_type#short → "Agent <short>").
 * `startedAtMs` is wall-clock unix-millis captured when the sub-agent
 * dispatch first entered the active set.
 */
@Serializable
data class SubagentDto(
    val id: String,
    val label: String,
    @SerialName("started_at_ms") val startedAtMs: Long,
)

/**
 * Decoded `params.payload` of an `agent_session_active_subagents_changed`
 * notification. Server emits this whenever a sub-agent enters or leaves
 * the active set for [sessionId]; `activeSubagents` is the FULL post-change
 * list in server-side insertion order (empty when nothing is in flight).
 * Mobile mirrors the list verbatim into the detail store's tab strip.
 */
@Serializable
data class SessionActiveSubagentsChangedPayload(
    @SerialName("session_id") val sessionId: String,
    @SerialName("active_subagents") val activeSubagents: List<SubagentDto>,
)

/**
 * One background shell (`run_in_background` Bash tool use) the agent
 * launched for the open session. Mirrors `BackgroundShellDto` on the
 * desktop side. [id] is the stable shell id; [command] is the launched
 * command line; [state] is a free-form classifier string —
 * `"running"`, `"exited:N"` (N = exit code), `"exited"` (no code
 * captured), or `"killed"`. It is deliberately decoded as a raw String
 * (NOT a structured enum) so a future / unrecognised state value still
 * decodes cleanly; the UI maps anything it doesn't recognise to a
 * neutral pill.
 *
 * [mtimeMs] is wall-clock unix-millis of the shell's last output write
 * (null when the server hasn't captured one). [outputTail] is the
 * trailing slice of the shell's stdout — populated ONLY when the
 * request set `include_output=true` (the lite DTOs carried on the
 * `agent_session_background_shells_changed` notification omit it).
 */
@Serializable
data class BackgroundShellDto(
    val id: String,
    val command: String,
    val state: String,
    @SerialName("mtime_ms") val mtimeMs: Long? = null,
    @SerialName("output_tail") val outputTail: String? = null,
)

/**
 * Result envelope for
 * `remote.solution_agent.get_session_background_shells`. Empty list is a
 * normal response for sessions that never launched a background shell.
 * Defaulted to empty so a pre-feature server response decodes cleanly.
 */
@Serializable
data class GetSessionBackgroundShellsResult(
    @SerialName("background_shells") val backgroundShells: List<BackgroundShellDto> = emptyList(),
)

/**
 * Decoded `params.payload` of an `agent_session_background_shells_changed`
 * notification. Server emits this whenever the background-shell set for
 * [sessionId] mutates; [backgroundShells] is the FULL post-change list of
 * lite DTOs (no `output_tail`). Mobile mirrors the list verbatim into the
 * detail store's pill strip — the strip never needs `output_tail`, so no
 * refetch is triggered by the notification.
 */
@Serializable
data class SessionBackgroundShellsChangedPayload(
    @SerialName("session_id") val sessionId: String,
    @SerialName("background_shells") val backgroundShells: List<BackgroundShellDto> = emptyList(),
)

/**
 * One managed background agent the agent launched for the open session.
 * Mirrors `BackgroundAgentDto` on the desktop side. [id] is the stable
 * agent id; [label] is a human-readable name. Unlike the background-shell
 * DTO there is no `command`, no free-form `state` string, and no
 * `output_tail`: the run/done state is DERIVED client-side from
 * [stopReason] ([stopReason] == null → running, else → done with that
 * reason), and the minimal drill-in sheet needs no separate output fetch
 * since this DTO already carries everything it shows.
 *
 * [mtimeMs] is wall-clock unix-millis of the agent's last activity (null
 * when the server hasn't captured one). [stopReason] is null while the
 * agent is still running, else the reason string it stopped with.
 */
@Serializable
data class BackgroundAgentDto(
    val id: String,
    val label: String,
    @SerialName("mtime_ms") val mtimeMs: Long? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
)

/**
 * Result envelope for
 * `remote.solution_agent.get_session_background_agents`. Empty list is a
 * normal response for sessions that never launched a background agent.
 * Defaulted to empty so a pre-feature server response decodes cleanly.
 */
@Serializable
data class GetSessionBackgroundAgentsResult(
    @SerialName("background_agents") val backgroundAgents: List<BackgroundAgentDto> = emptyList(),
)

/**
 * Decoded `params.payload` of an `agent_session_background_agents_changed`
 * notification. Server emits this whenever the background-agent set for
 * [sessionId] mutates; [backgroundAgents] is the FULL post-change list of
 * DTOs. Mobile mirrors the list verbatim into the detail store's pill
 * strip — the DTO already carries everything the strip + drill-in show,
 * so no refetch is triggered by the notification.
 */
@Serializable
data class SessionBackgroundAgentsChangedPayload(
    @SerialName("session_id") val sessionId: String,
    @SerialName("background_agents") val backgroundAgents: List<BackgroundAgentDto> = emptyList(),
)

/**
 * Server-emitted `agent_session_context_reset` — fires when the desktop
 * wipes a session's transcript in-place via `/clear` (reset_context) or
 * `/compact` (rotate_context). The session_id is stable across the swap;
 * only the entries are gone. Mobile must drop its cached entry list and
 * re-fetch via `get_session` (no after_index cursor).
 *
 * `contextCount` is informational — incremented by /compact, unchanged
 * by /clear. Both code paths require the same client-side action.
 */
@Serializable
data class AgentSessionContextResetPayload(
    @SerialName("session_id") val sessionId: String,
    @SerialName("context_count") val contextCount: Int,
)

@Serializable
data class MessageAppendedPayload(
    @SerialName("session_id") val sessionId: String,
    @SerialName("entry_index") val entryIndex: Int,
    val role: EntryRoleDto,
    val preview: String,
    /**
     * Echoes the originating client's `_meta.spk_client_send_id` stamp on
     * `role == "user"` appends. Absent (null) when the entry didn't carry
     * the meta (desktop-originated send, or a mobile pre-stamp client),
     * which makes this a back-compat addition for older servers and a
     * fast-pop trigger for stamped sends — see SessionDetailStore's
     * notification handler.
     */
    @SerialName("client_send_id") val clientSendId: Long? = null,
    /**
     * All distinct `spk_client_send_id` stamps on the appended entry.
     * Populated when the server-side queue-merge path rolled N
     * originating bundles into one user message — see [EntrySummary]
     * for the same field's semantics. Mobile should pop every csid
     * in this list from its optimistic bubble set; falls back to
     * [clientSendId] singular for old-server compatibility.
     */
    @SerialName("client_send_ids") val clientSendIds: List<Long> = emptyList(),
    /**
     * Unix-millis creation time for the appended entry. Mirrors
     * [EntrySummary.createdMs] — carried on the notification for parity
     * with the entry the next delta poll materialises. Null for entries
     * that predate the feature or for old servers.
     */
    @SerialName("created_ms") val createdMs: Long? = null,
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

/**
 * Result envelope for `remote.solution_agent.restart_agent`.
 *
 * The server drops the agent subprocess pooled for the session's
 * `(solution, agent)` pair, closes the existing session, and opens a
 * brand-new one against the same project — returning the freshly-minted
 * id so the caller can switch focus to it.
 */
@Serializable
data class RestartAgentResult(
    @SerialName("session_id") val sessionId: String,
)

/**
 * Result envelope for `remote.solution_agent.reset_context`.
 *
 * The server wipes the session's conversation transcript + pending
 * queue + token meter while keeping the `SolutionSessionId` and the
 * user-set title stable — so the returned `sessionId` is always equal
 * to the input. Wired to the mobile's `Reset context` menu item; same
 * code path the desktop's `/clear` slash command takes.
 */
@Serializable
data class ResetContextResult(
    @SerialName("session_id") val sessionId: String,
)

/**
 * Result envelope for `remote.solution_agent.start_compact`.
 *
 * The compact orchestration is asynchronous — the server returns
 * immediately after enqueueing the compact-instructions prompt on the
 * agent's next turn ([queued] `= true`). A cold (sleeping) session is
 * woken first, then the prompt is queued. When a precondition isn't met
 * (session busy / context below 20% / less than 30k tokens of headroom),
 * [queued] is `false` and [message] carries the human-readable reason for
 * surfacing on the UI.
 */
@Serializable
data class StartCompactResult(
    val queued: Boolean,
    val message: String? = null,
)

@Serializable
data class GetSessionResult(
    val id: String,
    @SerialName("solution_id") val solutionId: String,
    @SerialName("agent_id") val agentId: String,
    val title: String,
    /** Structured session state — see [SessionSummary.state]. */
    val state: SessionStateDto,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("last_activity_at") val lastActivityAt: Long,
    /**
     * Epoch token for delta-sync. The client passes this as [GetSessionChangesResult.epoch]
     * to `get_session_changes.known_epoch` so the server can detect a state reset.
     * Defaults to 0 for pre-Phase-5 servers that don't emit the field.
     */
    val epoch: Long = 0,
    /**
     * The sequence cursor for delta-sync. Pass as `since_seq` on the next
     * `get_session_changes` poll. Defaults to 0 for pre-Phase-5 servers.
     */
    @SerialName("current_seq") val currentSeq: Long = 0,
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
    /**
     * Cold-start seed for the server-side `pending_messages` queue.
     * Each bundle is one Queued bubble in the chat list. Live updates
     * arrive via the `agent_session_queue_changed` notification —
     * this field is just what the queue looked like at the moment
     * `get_session` resolved. Empty for sessions with nothing
     * pending or pre-R6i server builds.
     */
    @SerialName("pending_bundles") val pendingBundles: List<QueuedBundleSummary> = emptyList(),
    /**
     * Cold-start seed for the subagent-tabs strip — mirrors
     * [SessionSummary.activeSubagents]. Live updates ride
     * `agent_session_active_subagents_changed`. Defaults to empty for
     * pre-Etap-5 servers.
     */
    @SerialName("active_subagents") val activeSubagents: List<SubagentDto> = emptyList(),
)

/**
 * Result envelope for `remote.solution_agent.get_session_changes` (Phase 5
 * delta-sync RPC). The client polls this instead of re-fetching the full
 * transcript on every change.
 *
 * **Absent-vs-empty distinction (CRITICAL):**
 * [state], [pendingBundles], and [activeSubagents] default to `null`. A
 * missing JSON key (absent section) decodes to `null` and means "section
 * unchanged — keep the cached value". A present-but-empty JSON array `[]`
 * decodes to an empty `List` (not null) and means "section changed and is
 * now empty — replace the cache with empty". Do NOT change the default for
 * these three fields to `emptyList()` — that would make the absent and
 * present-empty cases indistinguishable and silently reintroduce the
 * redundant re-renders this feature is designed to fix.
 *
 * [changedEntries] and [removedIndices] use `emptyList()` defaults
 * because the server omits them only when they're actually empty (no
 * absent-vs-empty ambiguity — empty == nothing changed).
 */
@Serializable
data class GetSessionChangesResult(
    val epoch: Long,
    @SerialName("current_seq") val currentSeq: Long,
    val reset: Boolean,
    @SerialName("total_count") val totalCount: Int,
    @SerialName("changed_entries") val changedEntries: List<EntrySummary> = emptyList(),
    @SerialName("removed_indices") val removedIndices: List<Int> = emptyList(),
    val state: SessionStateDto? = null,
    @SerialName("pending_bundles") val pendingBundles: List<QueuedBundleSummary>? = null,
    @SerialName("active_subagents") val activeSubagents: List<SubagentDto>? = null,
)

/**
 * Pure mutation used by mobile when the user taps Stop: flip the visible
 * session state to [SessionStateDto.Stopping] without touching entries or
 * any other field. The server's real `Stopping` (then `Idle`) push
 * reconciles whichever subsequent `GetSessionResult` lands.
 */
fun GetSessionResult.withOptimisticStopping(): GetSessionResult =
    copy(state = SessionStateDto.Stopping)

enum class DisplayState { Idle, Running, Stopping, AwaitingInput, Errored, Unknown }

enum class EntryRole { User, Assistant, ToolCall, Plan, Unknown }

// =====================================================================
// workspace.* DTOs (wire schema v2)
// =====================================================================

@Serializable
data class WorkspaceSolution(
    val id: String,
    val name: String,
    val root: String,
    @SerialName("member_count") val memberCount: Int,
    @SerialName("last_opened_at") val lastOpenedAt: String? = null,
    val open: Boolean,
    @SerialName("main_window_id") val mainWindowId: String? = null,
    val sessions: List<SessionSummary> = emptyList(),
)

@Serializable
data class WorkspaceSnapshot(
    val seq: Long,
    val solutions: List<WorkspaceSolution> = emptyList(),
)

@Serializable
data class WorkspaceListSolutionsResult(
    val solutions: List<SolutionSummary> = emptyList(),
)

@Serializable
data class WorkspaceSeqAck(val seq: Long)

// Delta payloads (sequenced — every one carries `seq`).

@Serializable
data class WorkspaceSolutionOpenedPayload(
    val seq: Long,
    val solution: SolutionSummary? = null,
    val sessions: List<SessionSummary> = emptyList(),
)

@Serializable
data class WorkspaceSolutionClosedPayload(
    val seq: Long,
    @SerialName("solution_id") val solutionId: String,
)

@Serializable
data class WorkspaceSolutionDeletedPayload(
    val seq: Long,
    @SerialName("solution_id") val solutionId: String,
)

@Serializable
data class WorkspaceSessionOpenedPayload(
    val seq: Long,
    @SerialName("solution_id") val solutionId: String,
    val session: SessionSummary,
)

@Serializable
data class WorkspaceSessionClosedPayload(
    val seq: Long,
    @SerialName("solution_id") val solutionId: String,
    @SerialName("session_id") val sessionId: String,
)

@Serializable
data class WorkspaceSessionDeletedPayload(
    val seq: Long,
    @SerialName("solution_id") val solutionId: String,
    @SerialName("session_id") val sessionId: String,
)

@Serializable
data class WorkspaceSessionStateChangedPayload(
    val seq: Long,
    @SerialName("solution_id") val solutionId: String,
    @SerialName("session_id") val sessionId: String,
    val state: SessionStateDto,
)

// Non-sequenced — no `seq` field, never triggers gap detection.
@Serializable
data class WorkspaceSessionMetricsChangedPayload(
    @SerialName("session_id") val sessionId: String,
    @SerialName("last_activity_at") val lastActivityAt: Long? = null,
    @SerialName("total_tokens") val totalTokens: Long? = null,
    @SerialName("max_tokens") val maxTokens: Long? = null,
)

/**
 * Structured session state as emitted by the editor — a tagged object
 * discriminated on the `kind` field (NOT kotlinx's default `type`).
 *
 *   `{"kind":"idle"}`
 *   `{"kind":"running","started_at_ms":N}`
 *   `{"kind":"stopping"}`
 *   `{"kind":"awaiting_input"}`
 *   `{"kind":"errored","message":"…"}`
 *
 * A custom serializer ([SessionStateDtoSerializer]) dispatches on `kind`
 * and falls back to [SessionStateDto.Unknown] for anything unrecognised —
 * including a missing `kind` field, a non-string `kind`, or an entirely
 * non-object payload. This forward-compat tolerance keeps a single
 * unknown wire-state from failing the surrounding decode of a list of
 * sessions or entries (see `entrySummaryWithUnknownRoleStillDecodes`).
 */
@Serializable(with = SessionStateDtoSerializer::class)
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("kind")
sealed interface SessionStateDto {
    @Serializable @SerialName("idle") data object Idle : SessionStateDto

    @Serializable @SerialName("running") data class Running(
        @SerialName("started_at_ms") val startedAtMs: Long,
    ) : SessionStateDto

    @Serializable @SerialName("stopping") data object Stopping : SessionStateDto

    @Serializable @SerialName("awaiting_input") data object AwaitingInput : SessionStateDto

    @Serializable @SerialName("errored") data class Errored(val message: String) : SessionStateDto

    /**
     * Forward-compat fallback for any `kind` the client doesn't recognise
     * (a future server adds e.g. `compacting`). Surfaces as
     * [DisplayState.Unknown] on the UI classifier so the chat header
     * renders a neutral state instead of crashing the surrounding decode.
     */
    data object Unknown : SessionStateDto
}

internal object SessionStateDtoSerializer : KSerializer<SessionStateDto> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun deserialize(decoder: Decoder): SessionStateDto {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return SessionStateDto.Unknown
        val element = jsonDecoder.decodeJsonElement() as? JsonObject
            ?: return SessionStateDto.Unknown
        val kind = (element["kind"] as? JsonPrimitive)?.contentOrNull
        val json = jsonDecoder.json
        return when (kind) {
            "idle" -> SessionStateDto.Idle
            "running" -> json.decodeFromJsonElement(SessionStateDto.Running.serializer(), element)
            "stopping" -> SessionStateDto.Stopping
            "awaiting_input" -> SessionStateDto.AwaitingInput
            "errored" -> json.decodeFromJsonElement(SessionStateDto.Errored.serializer(), element)
            else -> SessionStateDto.Unknown
        }
    }

    override fun serialize(encoder: Encoder, value: SessionStateDto) {
        // Round-trip serialization isn't needed by the client (server-
        // emitted), but provide it for tests and any future use.
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("SessionStateDto requires a Json encoder")
        val element: JsonElement = when (value) {
            SessionStateDto.Idle -> buildJsonObject { put("kind", "idle") }
            is SessionStateDto.Running -> buildJsonObject {
                put("kind", "running")
                put("started_at_ms", value.startedAtMs)
            }
            SessionStateDto.Stopping -> buildJsonObject { put("kind", "stopping") }
            SessionStateDto.AwaitingInput -> buildJsonObject { put("kind", "awaiting_input") }
            is SessionStateDto.Errored -> buildJsonObject {
                put("kind", "errored")
                put("message", value.message)
            }
            SessionStateDto.Unknown -> buildJsonObject { put("kind", "unknown") }
        }
        jsonEncoder.encodeJsonElement(element)
    }
}

/** Map structured state onto the small UI classifier enum. */
fun SessionStateDto.displayState(): DisplayState = when (this) {
    SessionStateDto.Idle -> DisplayState.Idle
    is SessionStateDto.Running -> DisplayState.Running
    SessionStateDto.Stopping -> DisplayState.Stopping
    SessionStateDto.AwaitingInput -> DisplayState.AwaitingInput
    is SessionStateDto.Errored -> DisplayState.Errored
    SessionStateDto.Unknown -> DisplayState.Unknown
}

/** Wall-clock ms when state flipped to `Running`, else null. */
fun SessionStateDto.startedAtMs(): Long? = (this as? SessionStateDto.Running)?.startedAtMs

/** The error message when state is `Errored`, else null. */
fun SessionStateDto.erroredMessage(): String? = (this as? SessionStateDto.Errored)?.message

/**
 * Structured entry role — snake_case enum on the wire. Decoded via a
 * tolerant custom serializer that maps any unrecognised string to
 * [Unknown], so a future server adding a new role doesn't fail the
 * surrounding `entries` list decode.
 */
@Serializable(with = EntryRoleDtoSerializer::class)
enum class EntryRoleDto {
    User,
    Assistant,
    ToolCall,
    Plan,
    /** Forward-compat fallback for any role string the client doesn't know. */
    Unknown,
}

internal object EntryRoleDtoSerializer : KSerializer<EntryRoleDto> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("EntryRoleDto", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): EntryRoleDto = when (decoder.decodeString()) {
        "user" -> EntryRoleDto.User
        "assistant" -> EntryRoleDto.Assistant
        "tool_call" -> EntryRoleDto.ToolCall
        "plan" -> EntryRoleDto.Plan
        else -> EntryRoleDto.Unknown
    }

    override fun serialize(encoder: Encoder, value: EntryRoleDto) {
        encoder.encodeString(
            when (value) {
                EntryRoleDto.User -> "user"
                EntryRoleDto.Assistant -> "assistant"
                EntryRoleDto.ToolCall -> "tool_call"
                EntryRoleDto.Plan -> "plan"
                EntryRoleDto.Unknown -> "unknown"
            },
        )
    }
}

/**
 * Bridge to the legacy [EntryRole] enum the app UI still consumes. The app
 * migration to [EntryRoleDto] is a separate task; this mapper keeps that
 * churn out of the core DTO change.
 */
fun EntryRoleDto.toEntryRole(): EntryRole = when (this) {
    EntryRoleDto.User -> EntryRole.User
    EntryRoleDto.Assistant -> EntryRole.Assistant
    EntryRoleDto.ToolCall -> EntryRole.ToolCall
    EntryRoleDto.Plan -> EntryRole.Plan
    EntryRoleDto.Unknown -> EntryRole.Unknown
}

/**
 * Structured tool-call status — snake_case enum on the wire. Decoded via
 * a tolerant custom serializer; any unrecognised string surfaces as
 * [Unknown] so a future server adding a new status doesn't fail decoding.
 */
@Serializable(with = ToolCallStatusDtoSerializer::class)
enum class ToolCallStatusDto {
    Pending,
    WaitingForConfirmation,
    Running,
    Done,
    Failed,
    Rejected,
    Canceled,
    /** Forward-compat fallback for any status string the client doesn't know. */
    Unknown,
}

internal object ToolCallStatusDtoSerializer : KSerializer<ToolCallStatusDto> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ToolCallStatusDto", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ToolCallStatusDto = when (decoder.decodeString()) {
        "pending" -> ToolCallStatusDto.Pending
        "waiting_for_confirmation" -> ToolCallStatusDto.WaitingForConfirmation
        "running" -> ToolCallStatusDto.Running
        "done" -> ToolCallStatusDto.Done
        "failed" -> ToolCallStatusDto.Failed
        "rejected" -> ToolCallStatusDto.Rejected
        "canceled" -> ToolCallStatusDto.Canceled
        else -> ToolCallStatusDto.Unknown
    }

    override fun serialize(encoder: Encoder, value: ToolCallStatusDto) {
        encoder.encodeString(
            when (value) {
                ToolCallStatusDto.Pending -> "pending"
                ToolCallStatusDto.WaitingForConfirmation -> "waiting_for_confirmation"
                ToolCallStatusDto.Running -> "running"
                ToolCallStatusDto.Done -> "done"
                ToolCallStatusDto.Failed -> "failed"
                ToolCallStatusDto.Rejected -> "rejected"
                ToolCallStatusDto.Canceled -> "canceled"
                ToolCallStatusDto.Unknown -> "unknown"
            },
        )
    }
}

/**
 * Result envelope for `remote.solution_agent.get_supervisor_state`.
 *
 * Carries the current Supervisor mode configuration and runtime statistics
 * for the requested session. All counter fields default to 0 / null so
 * pre-Supervisor server builds decode cleanly via the `ignoreUnknownKeys`
 * strategy.
 */
@Serializable
data class SupervisorStateDto(
    val enabled: Boolean = false,
    val status: String = "",
    @SerialName("consecutive_continues") val consecutiveContinues: Int = 0,
    @SerialName("max_continues") val maxContinues: Int = 0,
    @SerialName("custom_prompt") val customPrompt: String? = null,
    @SerialName("verdicts_total") val verdictsTotal: Int = 0,
    @SerialName("verdicts_continue") val verdictsContinue: Int = 0,
    @SerialName("verdicts_compact") val verdictsCompact: Int = 0,
    @SerialName("verdicts_done") val verdictsDone: Int = 0,
    @SerialName("verdicts_ask") val verdictsAsk: Int = 0,
    val audits: Int = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0L,
)

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

/**
 * Remove the agent-only metadata the desktop queue bakes into a follow-up
 * message before it lands in the transcript — an optional leading hint line
 * ([QUEUE_HINT_LINE]) and a per-`\n\n`-segment `[HH:MM:SS] ` timestamp
 * prefix — so the user bubble shows only what the user actually typed.
 *
 * Kotlin mirror of the desktop's `conversation_render::strip_injected_meta`.
 * The desktop bakes the timestamp at enqueue and strips it only at render
 * time, so the wire `EntrySummary` for a delivered follow-up still carries
 * it — stripping is the client's job. Keep [QUEUE_HINT_LINE] and the
 * timestamp shape byte-identical to `solution_agent::store::queue` so the
 * writer (desktop) and this stripper never desync.
 *
 * (Replaces the old `stripQueueMarker`, which targeted the verbose
 * "[The user typed the following at …]" marker the desktop has since
 * dropped in favour of the compact per-message timestamp + hint.)
 *
 * Conservative: a segment's `[..] ` lead-in is removed ONLY when the
 * bracket body is a valid `HH:MM:SS`, so an unrelated `[note] ` the user
 * wrote themselves is preserved verbatim.
 */
fun stripInjectedMeta(text: String): String {
    val body = if (text.startsWith(QUEUE_HINT_LINE)) {
        text.substring(QUEUE_HINT_LINE.length).trimStart('\n')
    } else {
        text
    }
    return body.split("\n\n").joinToString("\n\n", transform = ::stripOneTimestamp)
}

/**
 * Drop a single leading `[HH:MM:SS] ` prefix from one `\n\n` segment, if
 * present and shaped like a real timestamp. Mirrors the desktop's
 * `conversation_render::strip_one_timestamp`.
 */
private fun stripOneTimestamp(segment: String): String {
    if (!segment.startsWith(TS_PREFIX_OPEN)) return segment
    val rest = segment.substring(TS_PREFIX_OPEN.length)
    val close = rest.indexOf(TS_PREFIX_CLOSE)
    if (close < 0) return segment
    val stamp = rest.substring(0, close)
    val isHms = stamp.length == 8 &&
        stamp[2] == ':' && stamp[5] == ':' &&
        stamp.withIndex().all { (i, c) -> i == 2 || i == 5 || c.isDigit() }
    return if (isHms) rest.substring(close + TS_PREFIX_CLOSE.length) else segment
}

private const val TS_PREFIX_OPEN = "["
private const val TS_PREFIX_CLOSE = "] "
private const val QUEUE_HINT_LINE =
    "[Queued before your turn ended — not a reply to your last message.]"
