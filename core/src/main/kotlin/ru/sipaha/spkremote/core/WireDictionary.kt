package ru.sipaha.spkremote.core

/**
 * Preset DEFLATE dictionary (RFC 1950 FDICT) for the wire codec.
 *
 * It primes the compressor with the JSON-RPC envelope keys, method names,
 * DTO field names, and enum values that recur in almost every frame, so even
 * small messages (a one-line notification, a tiny request) compress well —
 * the back-references point into the dictionary instead of paying for the
 * literals each time.
 *
 * The bytes MUST stay byte-identical to the Rust copy in
 * `crates/remote_control/src/wire_dict.rs`. Both test suites pin
 * [WIRE_DICT_ADLER32] (the Adler-32 that zlib stamps as the DICTID) so any
 * drift between the two languages fails a test on both sides rather than
 * silently disabling the dictionary at runtime (a DICTID mismatch makes the
 * decompressor reject the dictionary).
 *
 * ASCII-only on purpose: UTF-8 bytes equal the ASCII bytes, so the literal is
 * trivially identical across Kotlin and Rust. Most-frequent substrings sit at
 * the END — zlib references the dictionary tail with the shortest distances.
 *
 * Dictionary id [WIRE_DICT_PROTO_V1]. Bump the id (never mutate v1 in place)
 * if the vocabulary ever changes, so old and new peers negotiate cleanly.
 */
internal const val WIRE_DICT_PROTO_V1: Int = 1

/** Dictionary id meaning "raw DEFLATE, no preset dictionary". */
internal const val WIRE_DICT_NONE: Int = 0

/**
 * Adler-32 of [WIRE_DICT_PROTO_V1_BYTES] — the DICTID zlib embeds when a
 * preset dictionary is used. Pinned identically here and in the Rust copy so
 * a dictionary edit on one side fails a test on both.
 */
internal const val WIRE_DICT_PROTO_V1_ADLER32: Long = 639723996L

private const val WIRE_DICT_TEXT: String =
    // Method names (rarer literals first).
    "remote.solution_agent.list_solutions remote.solution_agent.solution_details " +
        "remote.solution_agent.list_sessions remote.solution_agent.get_session " +
        "remote.solution_agent.read_session_history remote.solution_agent.send_message " +
        "remote.solution_agent.start_compact remote.solution_agent.reset_context " +
        "remote.solution_agent.rename_session remote.solution_agent.delete_session " +
        "remote.solution_agent.cancel_turn remote.solution_agent.authorize_tool_call " +
        "remote.solution_agent.get_session_background_shells " +
        "remote.solution_agent.get_session_background_agents " +
        "remote.solution_agent.upload_init remote.solution_agent.upload_chunk " +
        "remote.solution_agent.upload_finish remote.solution_agent.upload_status " +
        // Notification kinds.
        "session_state_changed session_entry_appended session_entry_updated " +
        "session_queue_changed session_created session_deleted agent_session_context_reset " +
        "workspace_session_metrics_changed upload_chunk_acked remote/notification " +
        // Enum values.
        "awaiting_input waiting_for_confirmation tool_status_started_at_ms " +
        "stopping running pending errored failed rejected canceled assistant " +
        // DTO field names.
        "\"parent_session_id\":\"acp_session_id\":\"active_subagents\":[\"background_agents\"" +
        "\"last_activity_at\":\"total_tokens\":\"max_tokens\":\"created_at\":\"context_count\":" +
        "\"solution_id\":\"agent_id\":\"session_id\":\"display_name\":\"total_size\":" +
        "\"received_bytes\":\"upload_id\":\"started_at_ms\":\"created_ms\":\"tool_call\":{" +
        "\"entries\":[\"sessions\":[\"total_count\":\"title\":\"state\":{\"kind\":\"" +
        "\"index\":\"role\":\"name\":\"status\":\"args\":\"text\":\"mime\":\"cwd\":\"" +
        // Most-frequent envelope / value fragments (kept at the tail).
        "tool_call user assistant idle done \"params\":{\"kind\":\"" +
        "{\"jsonrpc\":\"2.0\",\"id\":,\"method\":\"remote/notification\",\"params\":{" +
        "\"result\":{\"error\":{\"code\":,\"message\":\""

/** UTF-8 (== ASCII) bytes of the preset dictionary. */
internal val WIRE_DICT_PROTO_V1_BYTES: ByteArray = WIRE_DICT_TEXT.toByteArray(Charsets.US_ASCII)
