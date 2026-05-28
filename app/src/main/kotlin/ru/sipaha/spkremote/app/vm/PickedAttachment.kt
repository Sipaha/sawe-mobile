package ru.sipaha.spkremote.app.vm

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

/**
 * Live (non-persisted) representation of one file the user has picked
 * for attaching AND for which an upload has been started. URI is the
 * canonical handle — the actual bytes stream through [UploadManager]
 * over the WebSocket binary-frame path, not the old
 * "readBytes into base64" route.
 *
 * Lives in `vm/` so [SessionDetailStore] can hold a per-session draft
 * list that survives the chat-detail composable unmount on back-nav
 * (the previous home in `ui/solutions/SessionDetailScreen.kt` was a
 * composable-local `remember(sessionId)` that reset to empty on every
 * remount — typed text was persisted via [DraftRepository] but
 * attachments had no equivalent and were silently lost). The
 * [uploadState] StateFlow stays a live reference to [UploadManager];
 * the wrapper here just keeps the metadata + upload key paired so the
 * compose bar can re-render the chip after a remount.
 *
 * [localKey] is the [UploadManager]-assigned identifier used for
 * cancel / forget. [uploadState] is the per-upload StateFlow the
 * preview card collects to drive progress / Done / Failed UI.
 */
data class PickedAttachment(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val localKey: String,
    val uploadState: StateFlow<UploadManager.State>,
)
