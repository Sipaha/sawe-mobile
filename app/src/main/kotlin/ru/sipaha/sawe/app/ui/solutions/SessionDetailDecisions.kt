package ru.sipaha.sawe.app.ui.solutions

import ru.sipaha.sawe.core.ChatItem
import ru.sipaha.sawe.core.EntrySummary

/**
 * Pure, platform-free decision helpers for the chat surface.
 *
 * Everything here is logic the composables in `SessionDetailScreen` used to
 * inline. It lives apart so it can be exercised by plain JUnit tests without
 * standing up a Compose/Robolectric host — the same pattern
 * `ru.sipaha.sawe.core.SessionEntryMerge` established for the merge rules.
 */

// ---------------------------------------------------------------------------
// Entry body
// ---------------------------------------------------------------------------

/**
 * The text to render for [entry] — its full body when the response carried
 * one, otherwise the truncated preview.
 *
 * The single accessor every renderer goes through, because
 * [EntrySummary.preview] is no longer guaranteed to be populated: a server
 * honouring `omit_preview_when_markdown` omits it for any non-user entry it
 * sent a body for, and the DTO defaults it to `""`. Reading `preview` on its
 * own therefore renders an EMPTY bubble against such a server, while
 * `markdown ?: preview` is correct in both directions and always has been —
 * the body is the fuller text whenever it exists.
 *
 * The remaining direct `preview` reads in the chat surface are the three the
 * spec's audit permits: a locally-built optimistic bubble (which has no
 * server body), a `role == user` entry (never suppressed — `reconcileOptimistic`
 * keys off it), and the `markdown == null` fallback branch of the assistant
 * bubble, which is guarded by construction.
 */
internal fun entryBodyText(entry: EntrySummary): String = entry.markdown ?: entry.preview

// ---------------------------------------------------------------------------
// LazyColumn keys
// ---------------------------------------------------------------------------

/**
 * Derive the LazyColumn key for every row of a rendered chat [items]
 * timeline, guaranteeing uniqueness.
 *
 * Identity preference per message, in order:
 *  1. `queued:<csid>` for a synthetic server-queue bubble — namespaced so it
 *     can't collide with the REAL flushed user entry carrying the same csid
 *     during the queue-drain → message-appended handoff.
 *  2. `csid:<clientSendId>` for a user entry — preserved across the
 *     optimistic-bubble → server-echo handoff so the row keeps its slot.
 *  3. `idx:<index>` for any server-known entry.
 *  4. `pos<position>:<role>` for un-indexed entries (optimistic bubbles,
 *     legacy entries) — position is unique within the list.
 *
 * Date separators key on their epoch day. That is unique *as long as the
 * timeline's timestamps are monotonic*: `withDateSeparators` emits a
 * separator on every date change, so a desktop clock that steps backwards
 * across local midnight between two consecutive appends produces the same
 * epoch day twice and LazyColumn dies with "Key date:N already used". Rather
 * than trust the wall clock, every repeat of ANY derived key is suffixed with
 * its (unique) position, which keeps the common case byte-identical to the
 * plain key and turns the pathological case into a harmless remount.
 *
 * [isServerQueued] identifies the synthetic queue bubbles by reference — the
 * caller holds them in an `IdentityHashMap` because two bundles can be equal
 * by value.
 */
internal fun chatItemKeys(
    items: List<ChatItem>,
    isServerQueued: (EntrySummary) -> Boolean = { false },
): List<String> {
    val used = HashSet<String>(items.size * 2)
    return items.mapIndexed { position, item ->
        val base = when (item) {
            is ChatItem.DateSeparator -> "date:${item.epochDay}"
            is ChatItem.Message -> {
                val entry = item.entry
                when {
                    isServerQueued(entry) -> "queued:${entry.clientSendId ?: "pos$position"}"
                    entry.clientSendId != null -> "csid:${entry.clientSendId}"
                    entry.index >= 0 -> "idx:${entry.index}"
                    else -> "pos$position:${entry.role}"
                }
            }
        }
        if (used.add(base)) base else "$base#pos$position"
    }
}

// ---------------------------------------------------------------------------
// Cancelling a parked send
// ---------------------------------------------------------------------------

/**
 * The `client_send_id` the bubble for [entry] would cancel, or null when it
 * must not offer a cancel at all.
 *
 * Exactly one badge qualifies: [UserBubbleStatus.WaitingForConnection], which
 * means the send is parked in the durable offline queue and cannot move until
 * the wire comes back — the one state a message can sit in for up to its
 * 24-hour TTL with nothing happening (N-07). Every other badge describes a
 * message that IS moving: `Sending` and `Queued` are one round trip from a
 * server that may already have applied them, `Uploading` still owns server-side
 * upload slots, and `Delivered` is done. Offering a cancel on those could only
 * lie: `spk_client_send_id` de-duplication makes a replay free, but there is
 * nothing anywhere that can undo a delivery.
 *
 * A bubble with no csid is not cancellable either: the queue entry is filed
 * under that id, so without it there is nothing to name.
 */
internal fun cancellableQueuedSendId(entry: EntrySummary, status: UserBubbleStatus): Long? =
    if (status == UserBubbleStatus.WaitingForConnection) entry.clientSendId else null

// ---------------------------------------------------------------------------
// Attachment mime allow-list
// ---------------------------------------------------------------------------

/**
 * Mimes outside the `text/` family that the desktop's `upload::is_text_like`
 * accepts. Kept byte-identical to the server list — an upload the server refuses at
 * `send_message_blocks` resolution time has already consumed one of its four
 * per-session upload slots for up to an hour, so the only cheap place to say
 * no is before `upload_init`.
 */
private val TEXT_LIKE_MIMES: Set<String> = setOf(
    "application/json",
    "application/xml",
    "application/x-yaml",
    "application/yaml",
    "application/javascript",
    "application/typescript",
    "application/sql",
    "application/x-sh",
)

/**
 * Mime filter handed to `ActivityResultContracts.OpenDocument`.
 *
 * `application/octet-stream` is in the list on purpose. `OpenDocument`'s
 * filter is enforced by the document provider, and Android's `MimeTypeMap`
 * has no entry for `kt`, `kts`, `rs`, `toml`, `go`, `yml`, … so
 * `ExternalStorageProvider` reports every source file as octet-stream.
 * Omitting it greys out exactly the files [normalizeAttachmentMime] exists to
 * rescue — "attach the failing test" stops working, which is a worse
 * regression than the one N-44 set out to fix. Genuinely unusable types
 * (PDF, zip, video) still carry a specific mime and stay filtered out.
 *
 * The filter is advisory in both directions: a provider may hand back
 * anything, and octet-stream covers real binaries too, so
 * [attachmentMimeIsSupported] re-checks after the pick.
 */
internal val ATTACHMENT_FILE_PICKER_MIME_TYPES: Array<String> =
    (listOf("text/*", "application/octet-stream") + TEXT_LIKE_MIMES).toTypedArray()

/**
 * Canonical form of [mime] for comparison: parameters stripped, lowercased,
 * trimmed. `application/json; charset=utf-8` and `Application/JSON` both
 * become `application/json`.
 */
private fun canonicalMime(mime: String): String =
    mime.substringBefore(';').trim().lowercase()

/**
 * Whether the desktop will accept an attachment of [mime]: images become an
 * inline `Image` content block, text-like files a fenced code block, and
 * everything else is rejected with `unsupported_mime`.
 *
 * The server matches **case-sensitively on the whole string** (`upload.rs`
 * `is_text_like` uses `matches!`, and the image branch is
 * `mime.starts_with("image/")`), so `IMAGE/JPEG` and
 * `application/json; charset=utf-8` would both be refused there. We accept
 * them here and rely on [normalizeAttachmentMime] to put the *canonical*
 * form on the wire — rejecting a perfectly good JPEG because a provider
 * shouted its mime would be a worse answer than normalising it.
 */
internal fun attachmentMimeIsSupported(mime: String): Boolean {
    val normalized = canonicalMime(mime)
    return normalized.startsWith("image/") ||
        normalized.startsWith("text/") ||
        normalized in TEXT_LIKE_MIMES
}

/**
 * Common source-file extensions that Android's document providers report as
 * `application/octet-stream` because the platform mime map has no entry for
 * them. They are plain UTF-8 text and the desktop renders them fine, so we
 * relabel rather than reject — otherwise "attach the failing test" would be
 * impossible for most of the languages this app is used to work on.
 */
private val TEXT_EXTENSIONS: Set<String> = setOf(
    "bash", "c", "cc", "cfg", "conf", "cpp", "cs", "diff", "env", "go", "gradle",
    "h", "hpp", "ini", "java", "kt", "kts", "log", "lua", "m", "md", "mjs",
    "patch", "php", "pl", "properties", "ps1", "py", "rb", "rs", "scala",
    "sh", "swift", "toml", "ts", "tsx", "jsx", "vue", "yaml", "yml", "zsh",
)

/**
 * Mimes a document provider hands back when it simply doesn't know — safe to
 * override from the file extension. A provider that reports a *specific*
 * wrong type is left alone; guessing over a confident answer would be worse.
 */
private val UNKNOWN_MIMES: Set<String> = setOf(
    "application/octet-stream",
    "content/unknown",
    "*/*",
    "",
)

/**
 * Resolve the mime we should upload [rawMime]/[displayName] under. This is the
 * value that goes on the wire, so it has to be one the server's *exact*,
 * case-sensitive match accepts.
 *
 *  - a usable answer → its canonical form (`Application/JSON` →
 *    `application/json`, `text/plain; charset=utf-8` → `text/plain`). Passing
 *    the raw string through is what made a locally-accepted pick fail at
 *    `send_message_blocks` with `unsupported_mime` after it had already
 *    burned one of the four per-session upload slots for an hour;
 *  - a placeholder plus a known text extension → `text/plain`, so a `.kt` /
 *    `.toml` / `.yml` file the platform has no mime for still uploads;
 *  - anything else → [rawMime] verbatim, so the rejection message quotes what
 *    the provider actually said.
 */
internal fun normalizeAttachmentMime(rawMime: String, displayName: String): String {
    val normalized = canonicalMime(rawMime)
    if (normalized !in UNKNOWN_MIMES) {
        return if (attachmentMimeIsSupported(normalized)) normalized else rawMime
    }
    val extension = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return if (extension in TEXT_EXTENSIONS) "text/plain" else rawMime
}

/**
 * User-facing reason for a pick we refuse locally. Names the file so a
 * multi-pick tells the user WHICH one was dropped.
 */
internal fun unsupportedAttachmentMessage(displayName: String, mime: String): String =
    "`$displayName` ($mime) isn't supported — attach an image or a text file"

// ---------------------------------------------------------------------------
// Deferred-send attachment restore
// ---------------------------------------------------------------------------

/**
 * Whether the compose bar should re-read its picked-attachment list from the
 * store.
 *
 * The store keys in-flight deferred sends by `client_send_id` in
 * `pendingUploadProgress`; an id LEAVING that map means the send reached a
 * terminal state. On failure the store re-stages the attachment references it
 * used to drop, so the chips must come back without waiting for a remount
 * (N-04). On success the on-disk slot is empty, so the re-read is a no-op.
 */
internal fun deferredSendSettled(previous: Set<Long>, current: Set<Long>): Boolean =
    previous.any { it !in current }

// ---------------------------------------------------------------------------
// Draft saved-state cap
// ---------------------------------------------------------------------------

/**
 * Ceiling on how much draft text we put into the saved-instance-state
 * `Bundle`.
 *
 * `rememberSaveable`'s `autoSaver` writes the raw `String` into the
 * `NavBackStackEntry`'s saved state, which ends up in `onSaveInstanceState`
 * and travels over a Binder transaction with a hard ~1 MB per-process budget.
 * A pasted build log is easily past that, and the failure mode is
 * `TransactionTooLargeException` — the app dies the moment the user presses
 * Home (N-58). 16 k characters is far more than any realistic hand-typed
 * chat message and still an order of magnitude under the budget.
 */
internal const val DRAFT_SAVEABLE_MAX_CHARS: Int = 16_384

/**
 * What to write into saved instance state for a draft of [text].
 *
 * Returns `null` for anything over [DRAFT_SAVEABLE_MAX_CHARS] — the draft is
 * ALSO on disk (`DraftRepository`, written by the debounced writer and flushed
 * synchronously when the compose bar leaves composition), and the screen
 * re-seeds from disk on every open, so dropping the bundle copy costs nothing
 * but avoids blowing the Binder limit.
 */
internal fun draftSaveableValue(text: String): String? =
    if (text.length <= DRAFT_SAVEABLE_MAX_CHARS) text else null
