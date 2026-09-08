package ru.sipaha.sawe.app.vm

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import ru.sipaha.sawe.app.data.InFlightUploadStore
import ru.sipaha.sawe.app.data.PersistedUpload
import ru.sipaha.sawe.core.ConnectionState
import ru.sipaha.sawe.core.JsonRpc
import ru.sipaha.sawe.core.JsonRpcResponse
import ru.sipaha.sawe.core.NotConnectedException
import ru.sipaha.sawe.core.RemoteClient
import ru.sipaha.sawe.core.UPLOAD_CHUNK_PAYLOAD_BYTES
import ru.sipaha.sawe.core.UploadAbortParams
import ru.sipaha.sawe.core.UploadChunkAckedPayload
import ru.sipaha.sawe.core.UploadFinishParams
import ru.sipaha.sawe.core.UploadFinishResult
import ru.sipaha.sawe.core.UploadInitParams
import ru.sipaha.sawe.core.UploadInitResult
import ru.sipaha.sawe.core.UploadStatusParams
import ru.sipaha.sawe.core.UploadStatusResult
import ru.sipaha.sawe.core.buildUploadChunkFrame

/**
 * Decoded `data` payload of an `upload_chunk_rejected` notification.
 *
 * The server emits exactly one of `upload_chunk_acked` /
 * `upload_chunk_rejected` per binary chunk frame it processes, so a
 * rejected chunk surfaces on the client immediately instead of after the
 * ack timeout.
 *
 *  * `reason = "out_of_order"` / `"overrun"` — [expectedOffset] carries
 *    the byte offset the server actually wants next; the chunk loop
 *    re-seeks there and continues.
 *  * `reason = "unknown_upload_id"` — the slot is gone (desktop restart,
 *    TTL sweep). Handled by the same re-init-from-zero path as an
 *    `unknown_upload_id` answer to `upload_status`.
 *  * [expectedOffset] `= null` — the server cannot tell us where to
 *    resume, so the upload fails terminally.
 *
 * Lives here rather than in `:core` next to [UploadChunkAckedPayload]
 * only because `:core` was frozen when this notification landed; it is
 * a plain decode of an already-supported wire notification.
 */
@Serializable
data class UploadChunkRejectedPayload(
    @SerialName("upload_id") val uploadId: Long,
    val offset: Long,
    @SerialName("expected_offset") val expectedOffset: Long? = null,
    val reason: String,
)

/**
 * Stream plumbing the chunk loop needs to be exact about. Kept pure (no
 * Android, no coroutines) so the awkward cases — a provider whose `skip`
 * returns short, a source that ends before its declared size, a `read`
 * that returns fewer bytes than asked for — are covered by JVM tests.
 */
internal object UploadStreamIo {

    /** Scratch buffer for read-and-discard seeking. */
    private const val SKIP_SCRATCH_BYTES: Int = 64 * 1024

    /**
     * Advance [stream] by [target] bytes. `InputStream.skip` is allowed
     * to skip fewer bytes than asked (pipe-backed content providers
     * routinely do), so loop and fall back to reading-and-discarding.
     * Returns false when the source ran out before [target].
     */
    fun skipFully(stream: InputStream, target: Long): Boolean {
        var remaining = target
        val scratch = ByteArray(SKIP_SCRATCH_BYTES)
        while (remaining > 0L) {
            val skipped = stream.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
                continue
            }
            val want = minOf(scratch.size.toLong(), remaining).toInt()
            val read = stream.read(scratch, 0, want)
            if (read <= 0) return false
            remaining -= read
        }
        return true
    }

    /** Read until [buf] is full or the stream ends; returns the byte count. */
    fun readFully(stream: InputStream, buf: ByteArray): Int {
        var filled = 0
        while (filled < buf.size) {
            val read = try {
                stream.read(buf, filled, buf.size - filled)
            } catch (e: IOException) {
                if (filled == 0) throw e else break
            }
            if (read <= 0) break
            filled += read
        }
        return filled
    }
}

/** Why an upload RPC raised instead of answering. See [UploadDecisions.classifyCallFailure]. */
internal enum class CallFailureKind {
    /** This coroutine was cancelled — re-throw, never publish a state. */
    CANCELLED,

    /** No live socket; keep the previous state and do not spend an RPC. */
    NOT_CONNECTED,

    /** The request went unanswered; the upload stays resumable. */
    TRANSPORT,
}

/**
 * Pure, side-effect-free decisions taken by [UploadManager].
 *
 * Extracted so the interesting policy — what makes a failure terminal,
 * how the ack budget scales with the chunk size, which uploads belong to
 * the currently-bound server, when a chunk may be handed to the socket —
 * is covered by plain JVM unit tests instead of only by an emulator run.
 */
internal object UploadDecisions {

    /** Never shrink a chunk below this; smaller frames are all header + ack overhead. */
    const val MIN_CHUNK_PAYLOAD_BYTES: Int = 32 * 1024

    /** Ack budget floor — a fast link never waits less than this. */
    const val MIN_ACK_TIMEOUT_MS: Long = 30_000L

    /** Ack budget ceiling — past this the socket is dead, not slow. */
    const val MAX_ACK_TIMEOUT_MS: Long = 180_000L

    /**
     * Slowest link we are willing to call "healthy" (32 kbps). The ack
     * budget is at least the time this link needs to push one chunk, so
     * a genuinely slow-but-working connection never gets labelled Paused.
     */
    const val MIN_ASSUMED_THROUGHPUT_BYTES_PER_SEC: Long = 4_096L

    /**
     * Above this measured ack RTT one chunk occupies the socket for
     * longer than the connection heartbeat interval, so halve the chunk.
     */
    const val CHUNK_RTT_HIGH_MS: Long = 6_000L

    /** Below this the link is idling between chunks — grow the chunk. */
    const val CHUNK_RTT_LOW_MS: Long = 1_500L

    /**
     * How many bytes the transport's send queue may still hold before we
     * hand it another chunk. The WebSocket writer is strictly FIFO, so
     * everything queued ahead of the heartbeat ping delays it; 64 KiB is
     * ~2.5 s on a 200 kbps link, comfortably inside the 8 s heartbeat
     * budget.
     */
    const val MAX_PENDING_WRITE_BYTES: Long = 64L * 1024L

    /**
     * What a raised exception from [RemoteClient.call] means for an
     * upload.
     *
     * The distinction that matters: a [TimeoutCancellationException] is
     * `call`'s own per-request budget expiring, *not* job cancellation.
     * Classifying it as cancellation would abandon the upload silently;
     * classifying it (or any other raised exception) as a failure would
     * make a transport hiccup terminal even though the bytes already on
     * the server stay valid. Only a parsed error *from the server* may
     * be terminal — those never reach this function.
     */
    fun classifyCallFailure(cause: Throwable): CallFailureKind = when {
        cause is TimeoutCancellationException -> CallFailureKind.TRANSPORT
        cause is CancellationException -> CallFailureKind.CANCELLED
        cause is NotConnectedException -> CallFailureKind.NOT_CONNECTED
        else -> CallFailureKind.TRANSPORT
    }

    /**
     * Byte offset a "we could not even try" pause should report. Keeps
     * whatever progress the UI already shows and otherwise falls back to
     * the last offset the server confirmed — never to an identifier or
     * any other non-byte-count quantity.
     */
    fun resumeOffset(
        current: UploadManager.State,
        lastConfirmedOffset: Long,
        totalSize: Long,
    ): Long = when (current) {
        is UploadManager.State.Uploading -> current.sent
        is UploadManager.State.Paused -> current.sent
        else -> lastConfirmedOffset
    }.coerceIn(0L, totalSize)

    /**
     * True when a server-returned message means "I have no such upload"
     * — the desktop restarted, the TTL sweep collected the slot, or the
     * id belongs to a different server. The client still holds the file,
     * so the recovery is `upload_init` from offset 0, not a failure.
     *
     * The server spells this three different ways depending on the entry
     * point (`unknown_upload_id: 17` from `upload_status`,
     * `unknown_upload_id` as a chunk-rejection reason, but
     * `finish: unknown upload_id 17` from `upload_finish`), so the match
     * has to tolerate the separator drifting between `_` and a space.
     */
    fun isExpiredSlot(message: String): Boolean =
        UNKNOWN_UPLOAD_ID.containsMatchIn(message) ||
            message.contains("not found", ignoreCase = true)

    private val UNKNOWN_UPLOAD_ID =
        Regex("""unknown[ _]+upload[ _]?id""", RegexOption.IGNORE_CASE)

    /**
     * True when the local source can no longer be read at all — a lapsed
     * `content://` grant or a deleted file. Nothing the client can retry,
     * so this is the one genuinely terminal local failure.
     */
    fun isUnreadableSource(cause: Throwable): Boolean =
        cause is SecurityException || cause is FileNotFoundException

    /**
     * How long to wait for one chunk's ack. Scales with the chunk size
     * (a 256 KiB chunk on a 32 kbps link legitimately takes ~64 s) and
     * with the RTT we have actually measured on this upload, so a slow
     * but healthy link keeps uploading instead of flapping Paused.
     */
    fun ackTimeoutMs(chunkBytes: Int, smoothedAckRttMs: Long?): Long {
        val throughputFloorMs =
            chunkBytes.toLong() * 1_000L / MIN_ASSUMED_THROUGHPUT_BYTES_PER_SEC
        val rttFloorMs = smoothedAckRttMs?.let { it * 3L } ?: 0L
        return maxOf(MIN_ACK_TIMEOUT_MS, throughputFloorMs, rttFloorMs)
            .coerceAtMost(MAX_ACK_TIMEOUT_MS)
    }

    /**
     * Chunk size for the next chunk given the smoothed ack RTT. Halves
     * on a link that keeps the socket busy longer than the heartbeat
     * interval, doubles back up when acks come back quickly.
     */
    fun nextChunkBytes(currentBytes: Int, smoothedAckRttMs: Long?): Int {
        if (smoothedAckRttMs == null) return currentBytes
        val next = when {
            smoothedAckRttMs > CHUNK_RTT_HIGH_MS -> currentBytes / 2
            smoothedAckRttMs < CHUNK_RTT_LOW_MS -> currentBytes * 2
            else -> currentBytes
        }
        return next.coerceIn(MIN_CHUNK_PAYLOAD_BYTES, UPLOAD_CHUNK_PAYLOAD_BYTES)
    }

    /**
     * Halve the chunk after an ack budget expired.
     *
     * The expired budget is deliberately NOT fed into [smoothAckRttMs]:
     * it is a timeout, not a measurement, and treating it as one lets
     * repeated timeouts ratchet the next budget up (30 → 90 → 135 → 180 s)
     * while the chunk is already at its floor — which is how a
     * black-holed-but-Connected socket eats the deferred-send stall
     * guard. Shrink the chunk, leave the measured RTT alone.
     */
    fun shrinkChunkBytes(currentBytes: Int): Int =
        (currentBytes / 2).coerceIn(MIN_CHUNK_PAYLOAD_BYTES, UPLOAD_CHUNK_PAYLOAD_BYTES)

    /** Exponentially-weighted ack RTT, weighted 3:1 towards history. */
    fun smoothAckRttMs(previousMs: Long?, sampleMs: Long): Long =
        if (previousMs == null) sampleMs else (previousMs * 3L + sampleMs) / 4L

    /**
     * True when [current] describes an upload that should be making
     * progress but has no live driver coroutine.
     *
     * `Paused` is the designed resume point, but a lost update (a pump
     * writing `Uploading` just after `pauseAll` published `Paused`) can
     * strand an upload in `Uploading`/`Queued` with a dead job, and
     * nothing else in the class ever looks at those. Recovery keys off
     * "is anyone driving this", not off the label.
     */
    fun needsDriver(current: UploadManager.State, jobActive: Boolean): Boolean = when {
        jobActive -> false
        current is UploadManager.State.Paused -> true
        current is UploadManager.State.Uploading -> true
        current is UploadManager.State.Queued -> true
        else -> false
    }

    /**
     * User-facing reason for "the bytes behind this attachment can no
     * longer be read".
     *
     * When [stagingConfigured] is false the manager was built without a
     * staging directory, so no upload can survive a process restart and
     * this failure was structurally guaranteed rather than the user's
     * file going away. Say so — a silent fallback is how the staging
     * path shipped as dead code in the first place.
     */
    fun unreadableSourceReason(stagingConfigured: Boolean): String =
        if (stagingConfigured) {
            "attachment is no longer readable — remove it and attach it again"
        } else {
            "attachment is no longer readable — remove it and attach it again " +
                "(this build has no attachment staging, so uploads cannot survive a restart)"
        }

    /** True when the transport's send queue has drained enough for another chunk. */
    fun canQueueChunk(
        queuedBytes: Long,
        budgetBytes: Long = MAX_PENDING_WRITE_BYTES,
    ): Boolean = queuedBytes <= budgetBytes

    /**
     * True when an upload recorded against [uploadServerId] may be driven
     * over the connection currently bound to [activeServerId]. Uploads
     * created before any server was known (`null`) are assumed to belong
     * to whatever is bound now.
     */
    fun belongsToActiveServer(uploadServerId: String?, activeServerId: String?): Boolean =
        uploadServerId == null || activeServerId == null || uploadServerId == activeServerId

    /**
     * Wording for an ack timeout. Only claim the server may be
     * unreachable when the socket itself is down — a still-Connected
     * socket that is merely slow must not accuse the network.
     */
    fun ackTimeoutReason(connected: Boolean): String =
        if (connected) {
            "slow connection — still waiting for the server to confirm this chunk"
        } else {
            "ack timeout — server may be unreachable"
        }
}

/**
 * Per-attachment chunked-upload state machine for the mobile attach
 * flow.
 *
 * Each `start()` call returns a `(localKey, StateFlow<State>)` pair —
 * the compose row stashes both on the [PickedAttachment]
 * record so its preview card can render percent / done / failed states
 * by collecting the StateFlow, and the cancel × button can call
 * `cancel(localKey)`.
 *
 * ### State machine
 *
 *   Queued ──► Uploading ──► Done       (happy path)
 *      │           │
 *      │           └──► Paused ──► Uploading (reconnect resume)
 *      ▼
 *   Failed ──► Paused                       (explicit [retry])
 *
 *  * `Queued` — `start()` was called but the chunk loop hasn't begun
 *    (no active connection yet, or `upload_init` is in flight).
 *  * `Uploading(sent, total)` — chunk loop is making progress.
 *  * `Paused` — the socket is down, an ack timed out, or the transport
 *    dropped an RPC; the coroutine is dead, ready for `resumeAll()` to
 *    start a fresh loop against the server's authoritative offset.
 *  * `Done` — `upload_finish` returned a `spk-upload://<id>` handle the
 *    compose row attaches to its outbound `send_message_blocks` payload.
 *  * `Failed` — terminal for the automatic paths. Only a *server*
 *    verdict (a refused `upload_init`, a rejected chunk the server
 *    cannot give a resume offset for) or an unreadable local source
 *    lands here; every transport-level problem is `Paused` instead,
 *    because the bytes already on the server stay valid and
 *    `upload_finish` is idempotent. The user can restart a `Failed`
 *    upload in place with [retry].
 *
 * ### Notification plumbing
 *
 * Acks (`upload_chunk_acked`) and rejections (`upload_chunk_rejected`)
 * are forwarded to UploadManager by the single shared notification
 * collector in [SessionListStore.ensureNotificationsObserver] via the
 * [SessionListStore.uploadNotificationRouter] callback hooks. Each
 * forwarded payload is routed to the per-upload [chunkEvents] map; the
 * matching coroutine awaits the next event and advances, re-seeks or
 * fails accordingly.
 *
 * ### Persistence
 *
 * Every successful ack is written to [persistence] before the
 * StateFlow advances. The on-disk record is the only thing that
 * survives a process kill — on next launch, [resumeAllFromDisk] is
 * called by the coordinator and revives one coroutine per persisted
 * upload, each of which calls `upload_status` against the server to
 * get the authoritative offset before restarting the chunk loop.
 *
 * A picked `content://` grant does **not** survive process death, so the
 * bytes are copied into [stagingDir] (app-private storage) as soon as
 * the upload starts and every later read — including every resume — goes
 * through that copy.
 */
class UploadManager internal constructor(
    private val scope: CoroutineScope,
    private val context: ConnectionContext,
    private val persistence: InFlightUploadStore,
    /**
     * Application-scoped resolver — same instance lives for the entire
     * process lifetime, safe to capture. Lets pause/resume hooks (which
     * fire from connection-lifecycle callbacks without a UI context)
     * still drive the I/O stream open on resume without having to
     * round-trip the Activity's resolver back to the lifecycle layer.
     */
    private val contentResolver: ContentResolver,
    /**
     * App-private directory the picked bytes are copied into before the
     * first chunk goes out. The `content://` grant handed over by the
     * photo / document picker dies with the process, so a resume after a
     * process kill can only read the file through this copy.
     *
     * `null` disables staging and falls back to re-opening the original
     * URI on every resume — usable in tests, but a production wiring
     * must pass a real directory.
     */
    private val stagingDir: File? = null,
) {

    /**
     * False when this manager was built without a [stagingDir]. In that
     * mode an upload streams straight from its `content://` URI, so it
     * cannot survive process death: the picker grant dies with the
     * process and the resume fails terminally. Surfaced in the failure
     * copy ([UploadDecisions.unreadableSourceReason]), on the error
     * channel the moment the first upload starts, and here so wiring
     * tests can assert on it.
     */
    val isStagingConfigured: Boolean get() = stagingDir != null

    /** Guards the one-shot "staging isn't wired" notice. */
    private val stagingWarningEmitted = java.util.concurrent.atomic.AtomicBoolean(false)

    sealed class State {
        data class Queued(val total: Long) : State()
        data class Uploading(val sent: Long, val total: Long) : State()
        data class Paused(val sent: Long, val total: Long, val reason: String) : State()
        data class Done(val handle: String) : State()
        data class Failed(val reason: String) : State()
    }

    /**
     * Public read-only StateFlow per active upload, keyed by localKey.
     * Compose collects via `state.collectAsState()`; the row card
     * re-renders on every transition. Removed by [forget] / [cancel].
     */
    private val states = ConcurrentHashMap<String, MutableStateFlow<State>>()

    /**
     * Per-upload state-machine driver coroutine. Held so [pauseAll] /
     * [cancel] can cancel cleanly.
     */
    private val jobs = ConcurrentHashMap<String, Job>()

    /**
     * Per-upload chunk-event channel. Each chunk send awaits one element
     * here; the notification dispatcher pushes the server's verdict via
     * [onChunkAcked] / [onChunkRejected]. Capacity = Channel.CONFLATED
     * because the loop is stop-and-wait — at most one chunk is
     * outstanding, so only the most recent verdict can matter and the
     * dispatcher thread never blocks.
     */
    private val chunkEvents = ConcurrentHashMap<Long, Channel<ChunkEvent>>()

    private sealed interface ChunkEvent {
        data class Acked(val receivedBytes: Long) : ChunkEvent
        data class Rejected(val expectedOffset: Long?, val reason: String) : ChunkEvent
    }

    /**
     * uploadId → localKey reverse lookup for the notification path.
     * Populated after `upload_init` returns; cleared on terminal /
     * cancel transitions. ConcurrentHashMap because writes happen on
     * the upload coroutine and reads happen on the notification
     * dispatcher.
     */
    private val uploadIdToLocalKey = ConcurrentHashMap<Long, String>()

    /**
     * Snapshot of the picker-supplied metadata for each active upload.
     * Lets [pauseAll] persist a PersistedUpload record without re-reading
     * the picker info, and lets the resume path re-construct a coroutine
     * that knows its display name / mime / total size without another
     * ContentResolver round-trip.
     */
    private val metadata = ConcurrentHashMap<String, UploadMetadata>()

    /**
     * localKeys cancelled while their `upload_init` was still on the
     * wire. The server allocates a slot for such a request and nobody
     * would ever release it, so the driver coroutine finishes the init,
     * fires `upload_abort` with the id it just learned, and exits.
     */
    private val abortOnInit: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Wall-clock of the last time an upload actually moved: a server chunk
     * ack, or a fresh user intent (start / retry / a resume from disk).
     *
     * Read by [wireWorkSnapshot] so the connection layer can tell an upload
     * that is making headway from one that is merely *registered*. Without
     * it, an upload wedged in a retrying `Paused` would keep the reconnect
     * ladder — and the radio — alive indefinitely (CM-3).
     */
    @Volatile
    private var lastWireProgressAtMs: Long = 0L

    /** Stamp [lastWireProgressAtMs]. Safe from any thread. */
    private fun markWireProgress() {
        lastWireProgressAtMs = System.currentTimeMillis()
    }

    /**
     * Caps how many uploads may hold the socket at once. The WebSocket
     * writer is FIFO and shared with the heartbeat and every chat RPC,
     * so four concurrent pickers must not put four chunks in front of
     * the next ping.
     */
    private val chunkSlots = Semaphore(MAX_CONCURRENT_UPLOADS)

    private data class UploadMetadata(
        val localKey: String,
        val uri: Uri,
        val sessionId: String,
        val mime: String,
        val displayName: String,
        val totalSize: Long,
        /**
         * Server this upload was created against. `upload_id`s are
         * per-server, so a resume must never replay one server's uploads
         * against another's socket.
         */
        val serverId: String?,
        /** null while upload_init is in flight or hasn't started yet. */
        @Volatile var uploadId: Long? = null,
        /** Absolute path of the app-private copy of the picked bytes. */
        @Volatile var stagedPath: String? = null,
        /** Highest offset the server has confirmed; survives Failed for [retry]. */
        @Volatile var lastConfirmedOffset: Long = 0L,
        /** Current adaptive chunk size, shrunk / grown from the measured ack RTT. */
        @Volatile var chunkBytes: Int = UPLOAD_CHUNK_PAYLOAD_BYTES,
        /** Smoothed ack round-trip time, or null before the first ack. */
        @Volatile var ackRttMs: Long? = null,
    )

    /**
     * Start a fresh upload for [uri]. Returns the local key + the state
     * flow the caller stashes onto its PickedAttachment record.
     *
     * Idempotent on caller errors — if [start] is called twice for the
     * same Uri, the second call hands back a different localKey + a
     * second coroutine that does its own upload_init. The compose row
     * is responsible for not picking the same file twice.
     */
    fun start(
        uri: Uri,
        sessionId: String,
        mime: String,
        displayName: String,
        totalSize: Long,
    ): Pair<String, StateFlow<State>> {
        val localKey = UUID.randomUUID().toString()
        markWireProgress()
        val state = MutableStateFlow<State>(State.Queued(totalSize))
        states[localKey] = state
        metadata[localKey] = UploadMetadata(
            localKey = localKey,
            uri = uri,
            sessionId = sessionId,
            mime = mime,
            displayName = displayName,
            totalSize = totalSize,
            serverId = persistence.activeServerId(),
        )
        Log.i(
            TAG,
            "start: localKey=$localKey mime=$mime size=$totalSize displayName=$displayName",
        )
        launchUploadCoroutine(localKey, resumeFromDisk = false)
        return localKey to state.asStateFlow()
    }

    /**
     * Wait until the in-flight upload for [localKey] reaches a terminal
     * state — [State.Done] (returns the server handle) or [State.Failed]
     * (returns null). Returns null immediately if there's no upload for
     * [localKey] (already cancelled / forgotten).
     *
     * Used by the compose row's Send button: even though the user
     * gating is "every attachment must be Done", a race between
     * `state.value` being `Uploading` and the ack flipping it to `Done`
     * is possible — the Send path awaits this to be safe rather than
     * snapshotting `state.value`.
     */
    suspend fun awaitTerminal(localKey: String): String? {
        val flow = states[localKey] ?: return null
        val terminal = flow.first { it is State.Done || it is State.Failed }
        return (terminal as? State.Done)?.handle
    }

    /**
     * Snapshot accessor for the current state of [localKey]. Returns
     * null when no upload is registered (already forgotten / cancelled).
     */
    fun stateOf(localKey: String): State? = states[localKey]?.value

    /**
     * Whether any upload still needs the wire, plus when one last moved.
     *
     * Read synchronously by `ConnectionManager` before it parks the
     * reconnect ladder on a background edge (CM-3): parking while an
     * attachment is mid-flight would leave it stalled until the user next
     * opens the app. Derived from [states] on every call rather than
     * mirrored into a flow — the map is the source of truth and holds a
     * handful of entries, so a scan is cheaper than the drift risk of
     * keeping a copy in sync across a dozen mutation points.
     *
     * "Needs the wire" is deliberately narrow:
     *  - `Queued` / `Uploading` — obviously.
     *  - `Paused` — yes: every `Paused` still in [states] is retried by
     *    [resumeAll] on the next Connected edge, and the ladder is what
     *    produces that edge. This is the case the carve-out exists for.
     *  - `Done` is finished; `Failed` is terminal until the user taps
     *    retry, which is a foreground act.
     *  - anything belonging to a *different* server is excluded: its
     *    upload id means nothing on the current wire, so no amount of
     *    reconnecting will move it.
     */
    internal fun wireWorkSnapshot(): UploadWireWork {
        val activeServerId = runCatching { persistence.activeServerId() }.getOrNull()
        for ((localKey, flow) in states) {
            val needsWire = when (flow.value) {
                is State.Queued, is State.Uploading, is State.Paused -> true
                is State.Done, is State.Failed -> false
            }
            if (!needsWire) continue
            val serverId = metadata[localKey]?.serverId
            if (!UploadDecisions.belongsToActiveServer(serverId, activeServerId)) continue
            return UploadWireWork(active = true, lastProgressAtMs = lastWireProgressAtMs)
        }
        return UploadWireWork.IDLE
    }

    /**
     * Subscribe to per-upload state transitions — used by the deferred-
     * send coroutine to translate each chunk-ack tick into a byte-level
     * progress update in the chat bubble. `null` when the upload was
     * never started (or already forgotten); the caller should fall
     * through with no-progress in that case.
     */
    fun stateFlowOf(localKey: String): StateFlow<State>? =
        states[localKey]?.asStateFlow()

    /**
     * Restart a [State.Failed] upload in place, under the same
     * [localKey] and the same StateFlow the UI is already collecting —
     * this is what makes the card's "tap to retry" copy true.
     *
     * Resumes from the server-known offset when the upload still has a
     * server slot, and re-runs `upload_init` from zero when it doesn't.
     * Returns false when there is nothing to retry (unknown localKey) or
     * a driver coroutine is already running for it, so the caller can
     * fall back to "remove and re-attach".
     */
    fun retry(localKey: String): Boolean {
        val flow = states[localKey] ?: return false
        val meta = metadata[localKey] ?: return false
        // Only a Failed upload is retryable: restarting a Done one would
        // overwrite the handle the compose row is about to send.
        if (flow.value !is State.Failed) return false
        if (jobs[localKey]?.isActive == true) return false
        Log.i(TAG, "retry: localKey=$localKey uploadId=${meta.uploadId}")
        markWireProgress()
        flow.value = State.Paused(meta.lastConfirmedOffset, meta.totalSize, "retrying")
        launchUploadCoroutine(localKey, resumeFromDisk = meta.uploadId != null)
        return true
    }

    /**
     * Revive uploads persisted to disk by a previous process. Called by
     * the coordinator after [ConnectionLifecycle.onClientBound] when the
     * active server changes, so each server's uploads resume against
     * the correct wire.
     *
     * Each persisted entry becomes a coroutine that calls
     * `upload_status` first (server is authoritative on the offset),
     * then resumes the chunk loop from there. When the socket is not up
     * yet the coroutine publishes the persisted offset and exits without
     * spending an RPC — the Connected edge re-runs [resumeAll].
     */
    fun resumeAllFromDisk() {
        val serverId = persistence.activeServerId()
        val persisted = persistence.list()
        sweepOrphanedStagedFiles()
        for (entry in persisted) {
            // Skip if we already have a live record (e.g. resume fired
            // twice on rapid reconnects).
            if (states.containsKey(entry.localKey)) continue
            val uri = runCatching { entry.uriString.toUri() }.getOrNull()
            if (uri == null) {
                entry.stagedPath?.let { runCatching { File(it).delete() } }
                persistence.remove(entry.localKey, serverId)
                continue
            }
            val state = MutableStateFlow<State>(
                State.Paused(entry.lastConfirmedOffset, entry.totalSize, "resuming after restart"),
            )
            states[entry.localKey] = state
            metadata[entry.localKey] = UploadMetadata(
                localKey = entry.localKey,
                uri = uri,
                sessionId = entry.sessionId,
                mime = entry.mime,
                displayName = entry.displayName,
                totalSize = entry.totalSize,
                serverId = serverId,
                uploadId = entry.uploadId,
                stagedPath = entry.stagedPath,
                lastConfirmedOffset = entry.lastConfirmedOffset,
            )
            launchUploadCoroutine(entry.localKey, resumeFromDisk = true)
        }
    }

    /**
     * Pause every active upload — called from
     * [ConnectionLifecycle.onTearDown] (connection dropped or server
     * switch). Cancels each coroutine and flips its state to Paused
     * with [reason]. The disk record remains intact so [resumeAll] can
     * pick it up after Reconnected.
     */
    fun pauseAll(reason: String) {
        for ((localKey, job) in jobs) {
            job.cancel()
            val flow = states[localKey] ?: continue
            val (sent, total) = when (val current = flow.value) {
                is State.Uploading -> current.sent to current.total
                is State.Paused -> current.sent to current.total
                is State.Queued -> 0L to current.total
                // Done / Failed are already terminal — pause is a no-op
                // for them; the StateFlow keeps its terminal value so
                // the UI's Done badge / Failed banner stays put across
                // a connection blip.
                is State.Done, is State.Failed -> continue
            }
            flow.value = State.Paused(sent, total, reason)
        }
        jobs.clear()
    }

    /**
     * Resume every paused upload against the new active connection. Called
     * from [ConnectionLifecycle.onReconnected] after a Disconnected →
     * Connected edge. Uploads belonging to a different server are left
     * alone — their `upload_id`s mean nothing on this wire.
     */
    fun resumeAll() {
        // A Connected edge is fresh intent for every parked upload: they
        // are all about to be driven again, so the stall clock restarts.
        markWireProgress()
        val activeServerId = persistence.activeServerId()
        for ((localKey, flow) in states) {
            // Keyed off "nobody is driving this", not off the Paused
            // label: a lost update can strand an upload in Uploading with
            // a dead job, and only this check can rescue it.
            if (!UploadDecisions.needsDriver(flow.value, jobs[localKey]?.isActive == true)) continue
            val meta = metadata[localKey] ?: continue
            if (!UploadDecisions.belongsToActiveServer(meta.serverId, activeServerId)) continue
            launchUploadCoroutine(localKey, resumeFromDisk = true)
        }
    }

    /**
     * Abort the upload identified by [localKey] — cancel the coroutine,
     * fire `upload_abort` server-side (best-effort; ignored on
     * failure), wipe the on-disk record + staged copy, and remove the
     * StateFlow so the compose row's collector unbinds.
     */
    fun cancel(localKey: String) {
        // Flip state to Failed BEFORE removing the map entry so any
        // deferred-send waiter (`SessionDetailStore.runDeferredSend`)
        // observing this localKey's StateFlow unblocks immediately
        // instead of spinning on its 5-minute outer timeout. Done by
        // the same MutableStateFlow instance the waiter captured, so
        // the emission lands even after the map entry is gone.
        states[localKey]?.let { flow ->
            val current = flow.value
            if (current !is State.Done && current !is State.Failed) {
                flow.value = State.Failed("upload cancelled")
            }
        }
        val meta = metadata[localKey]
        val uploadId = meta?.uploadId
        if (uploadId == null && jobs[localKey]?.isActive == true) {
            // `upload_init` may be on the wire right now: killing the
            // coroutine here would leave the slot the server is about to
            // allocate held until its TTL expires. Let the driver finish
            // the init and abort with the id it gets back.
            abortOnInit.add(localKey)
        } else {
            jobs.remove(localKey)?.cancel()
        }
        states.remove(localKey)
        metadata.remove(localKey)
        if (uploadId != null) {
            uploadIdToLocalKey.remove(uploadId)
            chunkEvents.remove(uploadId)?.close()
            fireAbort(uploadId)
        }
        persistence.remove(localKey, meta?.serverId ?: persistence.activeServerId())
        deleteStagedCopy(meta)
    }

    /**
     * Drop our local state for [localKey] — called once the compose row
     * has consumed the `Done` handle in its `send_message_blocks`
     * payload, and by the deferred-send cleanup paths.
     *
     * [releaseServerSlot] controls whether `upload_abort` is fired. The
     * server holds a tmp file (and one of its four per-session upload
     * slots) for an hour unless it is aborted or consumed by a
     * successful send, so anything that gives up on an upload must
     * release it. The default is "release unless the handle was already
     * produced", because a `Done` upload is normally forgotten right
     * after its handle went out in a send and aborting would delete the
     * tmp file out from under that send. Callers that know the send
     * failed (or never happened) should pass `true` explicitly.
     */
    fun forget(
        localKey: String,
        releaseServerSlot: Boolean = states[localKey]?.value !is State.Done,
    ) {
        val meta = metadata[localKey]
        val uploadId = meta?.uploadId
        if (releaseServerSlot && uploadId == null && jobs[localKey]?.isActive == true) {
            // Same deferral as `cancel`: an `upload_init` already on the
            // wire will allocate a slot, so let the driver learn the id
            // and abort with it instead of killing the coroutine now.
            abortOnInit.add(localKey)
        } else {
            abortOnInit.remove(localKey)
            jobs.remove(localKey)?.cancel()
        }
        states.remove(localKey)
        metadata.remove(localKey)
        if (uploadId != null) {
            uploadIdToLocalKey.remove(uploadId)
            chunkEvents.remove(uploadId)?.close()
            if (releaseServerSlot) fireAbort(uploadId)
        }
        persistence.remove(localKey, meta?.serverId ?: persistence.activeServerId())
        deleteStagedCopy(meta)
    }

    /**
     * Wipe every upload belonging to [serverId] — the coordinator's
     * forgetAllServers / removeServer paths.
     *
     * Removing a *stale* pairing must not touch the uploads running
     * against the server the user is actually connected to, so every
     * in-memory map is filtered by [UploadMetadata.serverId] rather than
     * cleared. `upload_abort` is fired only when the server being
     * removed is the one currently bound — that is the only case where
     * there is a socket to fire it on, and the only case where the
     * server would otherwise hold the slot for its full TTL.
     */
    fun forgetAllForServer(serverId: String) {
        val activeServerId = persistence.activeServerId()
        val removingBoundServer = activeServerId == null || activeServerId == serverId
        for ((localKey, meta) in metadata.entries.toList()) {
            val ours = meta.serverId?.let { it == serverId } ?: removingBoundServer
            if (!ours) continue
            jobs.remove(localKey)?.cancel()
            states.remove(localKey)
            metadata.remove(localKey)
            abortOnInit.remove(localKey)
            meta.uploadId?.let { id ->
                uploadIdToLocalKey.remove(id)
                chunkEvents.remove(id)?.close()
                if (removingBoundServer) fireAbort(id)
            }
            deleteStagedCopy(meta)
        }
        // Records that only exist on disk (persisted by an earlier
        // process) own staged files too — drop those before the records.
        for (entry in persistence.listFor(serverId)) {
            entry.stagedPath?.let { runCatching { File(it).delete() } }
        }
        persistence.removeForServer(serverId)
    }

    /**
     * Notification dispatcher entry point for `upload_chunk_acked`.
     * Wired by the coordinator during construction via
     * [SessionListStore.uploadNotificationRouter]. Pushes
     * [payload.receivedBytes] into the per-upload event channel — the
     * coroutine waiting on the channel advances its offset to that
     * value.
     */
    fun onChunkAcked(payload: UploadChunkAckedPayload) {
        val channel = chunkEvents[payload.uploadId]
        if (channel == null) {
            Log.w(
                TAG,
                "onChunkAcked: no channel for uploadId=${payload.uploadId} received=${payload.receivedBytes}",
            )
            return
        }
        Log.i(
            TAG,
            "onChunkAcked: uploadId=${payload.uploadId} received=${payload.receivedBytes}",
        )
        // Bytes confirmed by the server is the one unambiguous "this
        // upload is moving" event, so it is what the stall bound counts.
        markWireProgress()
        // CONFLATED channels return false on offer when capacity is
        // full — we explicitly drop the older value in that case.
        channel.trySend(ChunkEvent.Acked(payload.receivedBytes))
    }

    /**
     * Notification dispatcher entry point for `upload_chunk_rejected` —
     * the symmetric counterpart of [onChunkAcked]. Lets the chunk loop
     * re-seek (or re-init, or fail) the moment the server refuses a
     * chunk instead of waiting out the ack budget.
     */
    fun onChunkRejected(payload: UploadChunkRejectedPayload) {
        val channel = chunkEvents[payload.uploadId]
        if (channel == null) {
            Log.w(
                TAG,
                "onChunkRejected: no channel for uploadId=${payload.uploadId} " +
                    "offset=${payload.offset} reason=${payload.reason}",
            )
            return
        }
        Log.w(
            TAG,
            "onChunkRejected: uploadId=${payload.uploadId} offset=${payload.offset} " +
                "expected=${payload.expectedOffset} reason=${payload.reason}",
        )
        channel.trySend(ChunkEvent.Rejected(payload.expectedOffset, payload.reason))
    }

    // ---- Internal driver coroutine ----

    private fun launchUploadCoroutine(localKey: String, resumeFromDisk: Boolean) {
        val job = scope.launch {
            runUploadLoop(localKey, resumeFromDisk)
        }
        jobs[localKey] = job
    }

    /**
     * Outcome of one attempt at a `upload_*` RPC. Only [ServerError] is
     * a verdict from the server; everything else means the request never
     * got an answer, so the upload stays resumable.
     */
    private sealed interface CallOutcome {
        data class Ok(val response: JsonRpcResponse) : CallOutcome
        data object NotConnected : CallOutcome
        data class Transport(val reason: String) : CallOutcome
        data class ServerError(val message: String) : CallOutcome
    }

    /**
     * Issue one `upload_*` RPC and classify the result.
     *
     * Job cancellation is re-thrown so [pauseAll] (which cancels the
     * job and then publishes `Paused`) can never be overwritten by a
     * bogus failure state. A [TimeoutCancellationException] is *not*
     * job cancellation — it is `RemoteClient.call`'s own 30 s budget
     * expiring, i.e. an unanswered request, so it is classified as a
     * transport failure and the upload stays resumable.
     */
    private suspend fun callUpload(
        client: RemoteClient,
        method: String,
        params: JsonElement,
    ): CallOutcome {
        val raw = runCatching { client.call(method, params) }
        val cause = raw.exceptionOrNull()
        if (cause != null) {
            when (UploadDecisions.classifyCallFailure(cause)) {
                CallFailureKind.CANCELLED -> throw cause
                CallFailureKind.NOT_CONNECTED -> {
                    currentCoroutineContext().ensureActive()
                    return CallOutcome.NotConnected
                }

                CallFailureKind.TRANSPORT -> {
                    currentCoroutineContext().ensureActive()
                    return CallOutcome.Transport(
                        cause.message ?: cause::class.simpleName ?: "transport error",
                    )
                }
            }
        }
        val resp = raw.getOrThrow()
        resp.error?.let { return CallOutcome.ServerError(it.message) }
        resp.toolError()?.let { return CallOutcome.ServerError(it) }
        return CallOutcome.Ok(resp)
    }

    private suspend fun runUploadLoop(localKey: String, resumeFromDisk: Boolean) {
        val flow = states[localKey] ?: return
        val meta = metadata[localKey] ?: return
        Log.i(TAG, "runUploadLoop start: localKey=$localKey resumeFromDisk=$resumeFromDisk")

        val activeServerId = persistence.activeServerId()
        if (!UploadDecisions.belongsToActiveServer(meta.serverId, activeServerId)) {
            // Server switch: this upload's id only means something on the
            // server it was created against. Leave it parked; it resumes
            // if the user switches back.
            flow.publish(pausedKeeping(flow.value, meta, "waiting for its server"))
            return
        }

        // Step 0: make the bytes independently readable ------------------
        // The picker's content:// grant dies with the process, so a
        // resume after a process kill can only read an app-private copy.
        if (!ensureStagedCopy(localKey, flow, meta)) return

        // Step 1: is there a usable socket at all? -----------------------
        // Answering "no" here (instead of firing an RPC that is certain
        // to fail) is what keeps a cold start from burning one doomed
        // upload_status per persisted upload and publishing a bogus
        // state; the Connected edge re-runs resumeAll.
        val client = context.activeClient()
        if (client == null || client.connectionState.value !is ConnectionState.Connected) {
            Log.i(TAG, "runUploadLoop: no live connection for $localKey — keeping previous state")
            flow.publish(pausedKeeping(flow.value, meta, "waiting for connection"))
            return
        }

        var resume = resumeFromDisk
        var offsetOverride: Long? = null
        var reinits = 0
        var reseeks = 0

        while (true) {
            // Step 2: upload_init (or upload_status when resuming) -------
            val uploadId: Long
            val startOffset: Long
            val existingId = if (resume) meta.uploadId else null
            if (existingId != null) {
                when (val status = fetchStatus(client, existingId)) {
                    is StatusOutcome.Ok -> {
                        uploadId = existingId
                        startOffset = offsetOverride ?: status.receivedBytes
                    }

                    StatusOutcome.Expired -> {
                        // The desktop restarted, the TTL sweep collected
                        // the slot, or it belongs to another server. We
                        // still hold the file: start over from zero.
                        if (++reinits > MAX_REINITS) {
                            failTerminal(localKey, flow, "couldn't restart upload — tap to retry")
                            return
                        }
                        Log.i(TAG, "upload $localKey expired server-side — re-initialising")
                        discardServerSlot(localKey, meta)
                        resume = false
                        offsetOverride = null
                        continue
                    }

                    StatusOutcome.NotConnected -> {
                        flow.publish(pausedKeeping(flow.value, meta, "waiting for connection"))
                        return
                    }

                    is StatusOutcome.Transport -> {
                        flow.publish(pausedKeeping(flow.value, meta, status.reason))
                        return
                    }

                    is StatusOutcome.ServerError -> {
                        failTerminal(localKey, flow, "couldn't resume upload — tap to retry")
                        return
                    }
                }
            } else {
                val init = initUpload(client, localKey, flow, meta) ?: return
                uploadId = init
                startOffset = 0L
                offsetOverride = null
            }

            uploadIdToLocalKey[uploadId] = localKey
            // Conflated channel so back-to-back verdicts don't block the
            // notification dispatcher — the loop is stop-and-wait, so
            // only the most-recent verdict can matter.
            val events = Channel<ChunkEvent>(Channel.CONFLATED)
            chunkEvents.put(uploadId, events)?.close()

            flow.publish(State.Uploading(startOffset, meta.totalSize))

            // Step 3: chunk loop ----------------------------------------
            val pumped = runCatching {
                chunkSlots.withPermit {
                    withContext(Dispatchers.IO) {
                        pumpFrom(
                            client = client,
                            flow = flow,
                            meta = meta,
                            uploadId = uploadId,
                            startOffset = startOffset,
                            events = events,
                        )
                    }
                }
            }
            val result = pumped.getOrElse { cause ->
                if (cause is CancellationException) throw cause
                currentCoroutineContext().ensureActive()
                if (UploadDecisions.isUnreadableSource(cause)) {
                    // Lapsed URI grant / deleted file with no staged copy:
                    // nothing to retry, the user must re-attach.
                    failTerminal(
                        localKey,
                        flow,
                        UploadDecisions.unreadableSourceReason(isStagingConfigured),
                    )
                    return
                }
                PumpResult.Paused(
                    (flow.value as? State.Uploading)?.sent ?: startOffset,
                    cause.message ?: "chunk loop failed",
                )
            }

            when (result) {
                is PumpResult.Paused -> {
                    flow.publish(State.Paused(result.offset, meta.totalSize, result.reason))
                    return
                }

                is PumpResult.Failed -> {
                    failTerminal(localKey, flow, result.reason)
                    return
                }

                is PumpResult.Reseek -> {
                    if (++reseeks > MAX_RESEEKS) {
                        failTerminal(localKey, flow, "upload kept losing its place — tap to retry")
                        return
                    }
                    resume = true
                    offsetOverride = result.offset
                    continue
                }

                PumpResult.Reinit -> {
                    if (++reinits > MAX_REINITS) {
                        failTerminal(localKey, flow, "couldn't restart upload — tap to retry")
                        return
                    }
                    discardServerSlot(localKey, meta)
                    resume = false
                    offsetOverride = null
                    continue
                }

                is PumpResult.Complete -> {
                    // Step 4: upload_finish ------------------------------
                    // The server has every byte and `upload_finish` is
                    // idempotent, so a request that never got an answer
                    // must stay resumable — only a verdict from the
                    // server is allowed to be terminal here.
                    val params = JsonRpc.json.encodeToJsonElement(
                        UploadFinishParams.serializer(),
                        UploadFinishParams(uploadId = uploadId),
                    )
                    when (val outcome =
                        callUpload(client, "remote.solution_agent.upload_finish", params)) {
                        is CallOutcome.Ok -> {
                            val structured = outcome.response.structuredContent()
                            val finish = structured?.let {
                                runCatching {
                                    JsonRpc.json.decodeFromJsonElement(
                                        UploadFinishResult.serializer(),
                                        it,
                                    )
                                }.getOrNull()
                            }
                            if (finish == null) {
                                failTerminal(
                                    localKey,
                                    flow,
                                    "upload didn't complete — tap to retry",
                                )
                                return
                            }
                            flow.publish(State.Done(finish.handle))
                            // Don't remove from persistence — forget() is
                            // the consumer's ack that the handle was used
                            // in a send.
                            return
                        }

                        CallOutcome.NotConnected -> {
                            flow.publish(
                                State.Paused(
                                    meta.totalSize,
                                    meta.totalSize,
                                    "waiting for connection to finish upload",
                                ),
                            )
                            return
                        }

                        is CallOutcome.Transport -> {
                            flow.publish(
                                State.Paused(
                                    meta.totalSize,
                                    meta.totalSize,
                                    "finishing upload — ${outcome.reason}",
                                ),
                            )
                            return
                        }

                        is CallOutcome.ServerError -> {
                            if (UploadDecisions.isExpiredSlot(outcome.message)) {
                                if (++reinits > MAX_REINITS) {
                                    failTerminal(
                                        localKey,
                                        flow,
                                        "couldn't restart upload — tap to retry",
                                    )
                                    return
                                }
                                discardServerSlot(localKey, meta)
                                resume = false
                                offsetOverride = null
                                continue
                            }
                            Log.e(
                                TAG,
                                "upload_finish refused for $localKey: ${outcome.message}",
                            )
                            failTerminal(localKey, flow, "upload didn't complete — tap to retry")
                            return
                        }
                    }
                }
            }
        }
    }

    // ---- Step helpers ----

    private sealed interface StatusOutcome {
        data class Ok(val receivedBytes: Long) : StatusOutcome
        data object Expired : StatusOutcome
        data object NotConnected : StatusOutcome
        data class Transport(val reason: String) : StatusOutcome
        data class ServerError(val message: String) : StatusOutcome
    }

    private suspend fun fetchStatus(client: RemoteClient, uploadId: Long): StatusOutcome {
        val params = JsonRpc.json.encodeToJsonElement(
            UploadStatusParams.serializer(),
            UploadStatusParams(uploadId = uploadId),
        )
        return when (val outcome =
            callUpload(client, "remote.solution_agent.upload_status", params)) {
            CallOutcome.NotConnected -> StatusOutcome.NotConnected
            is CallOutcome.Transport -> StatusOutcome.Transport("resume failed: ${outcome.reason}")
            is CallOutcome.ServerError ->
                if (UploadDecisions.isExpiredSlot(outcome.message)) {
                    StatusOutcome.Expired
                } else {
                    StatusOutcome.ServerError(outcome.message)
                }

            is CallOutcome.Ok -> {
                val structured = outcome.response.structuredContent()
                    ?: return StatusOutcome.ServerError("upload_status returned no structuredContent")
                val parsed = runCatching {
                    JsonRpc.json.decodeFromJsonElement(
                        UploadStatusResult.serializer(),
                        structured,
                    )
                }.getOrNull()
                    ?: return StatusOutcome.ServerError("upload_status returned malformed content")
                StatusOutcome.Ok(parsed.receivedBytes)
            }
        }
    }

    /**
     * Run `upload_init` until the server answers. Returns the new upload
     * id, or null when the upload is over (refused, or cancelled while
     * the init was on the wire — both already published their state).
     *
     * Network blips between the [RemoteClient] socket and the server
     * surface from [RemoteClient.call] as a raised exception before we
     * ever look at the response envelope; those are retried with
     * exponential backoff (capped at 30 s, no attempt cap) so an upload
     * survives a connection blip without the user re-attaching. A parsed
     * response carrying an error, by contrast, is a deterministic
     * verdict — retrying would reproduce it, so it goes to [State.Failed].
     */
    private suspend fun initUpload(
        client: RemoteClient,
        localKey: String,
        flow: MutableStateFlow<State>,
        meta: UploadMetadata,
    ): Long? {
        val params = JsonRpc.json.encodeToJsonElement(
            UploadInitParams.serializer(),
            UploadInitParams(
                sessionId = meta.sessionId,
                mime = meta.mime,
                displayName = meta.displayName,
                totalSize = meta.totalSize,
            ),
        )
        var attempt = 0
        while (true) {
            if (abortOnInit.contains(localKey)) {
                abortOnInit.remove(localKey)
                jobs.remove(localKey)
                return null
            }
            when (val outcome = callUpload(client, "remote.solution_agent.upload_init", params)) {
                CallOutcome.NotConnected, is CallOutcome.Transport -> {
                    // `upload_init` is NOT idempotent — the server
                    // allocates a fresh id and tmp file per request — so
                    // a request whose response was lost has already
                    // burned one of the session's four slots that the
                    // client can never learn about, let alone abort.
                    // Retrying forever therefore exhausts the cap with a
                    // single attachment; cap the attempts and fall back
                    // to Paused, which the Connected edge and the
                    // watchdog both pick up.
                    if (attempt + 1 >= MAX_INIT_ATTEMPTS) {
                        Log.w(
                            TAG,
                            "upload_init unanswered $MAX_INIT_ATTEMPTS times for $localKey " +
                                "— pausing instead of allocating more orphan slots",
                        )
                        flow.publish(
                            State.Paused(
                                0L,
                                meta.totalSize,
                                "couldn't reach the server to start the upload",
                            ),
                        )
                        jobs.remove(localKey)
                        return null
                    }
                    val backoffMs = uploadInitBackoffMs(attempt)
                    Log.w(
                        TAG,
                        "upload_init transient for $localKey (attempt ${attempt + 1}) " +
                            "— retry in ${backoffMs}ms",
                    )
                    attempt++
                    delay(backoffMs)
                }

                is CallOutcome.ServerError -> {
                    abortOnInit.remove(localKey)
                    Log.e(
                        TAG,
                        "upload_init FAILED (non-retryable) for $localKey: ${outcome.message}",
                    )
                    // Human-facing reason (no `upload_init` RPC jargon);
                    // the raw cause is in the Log line above.
                    failTerminal(localKey, flow, "couldn't start upload — tap to retry")
                    return null
                }

                is CallOutcome.Ok -> {
                    val structured = outcome.response.structuredContent()
                    val init = structured?.let {
                        runCatching {
                            JsonRpc.json.decodeFromJsonElement(UploadInitResult.serializer(), it)
                        }.getOrNull()
                    }
                    if (init == null) {
                        Log.e(TAG, "upload_init returned malformed content for $localKey")
                        failTerminal(localKey, flow, "couldn't start upload — tap to retry")
                        return null
                    }
                    val uploadId = init.uploadId
                    if (abortOnInit.remove(localKey)) {
                        // Cancelled while this init was on the wire — the
                        // server allocated a slot nobody else would ever
                        // release.
                        Log.i(TAG, "upload_init raced cancel for $localKey — aborting $uploadId")
                        fireAbort(uploadId)
                        jobs.remove(localKey)
                        persistence.remove(localKey)
                        return null
                    }
                    meta.uploadId = uploadId
                    meta.lastConfirmedOffset = 0L
                    Log.i(TAG, "upload_init OK: localKey=$localKey uploadId=$uploadId")
                    persistUpload(localKey, meta, uploadId, 0L)
                    return uploadId
                }
            }
        }
    }

    private sealed interface PumpResult {
        data class Complete(val offset: Long) : PumpResult
        data class Paused(val offset: Long, val reason: String) : PumpResult
        data class Reseek(val offset: Long) : PumpResult
        data object Reinit : PumpResult
        data class Failed(val reason: String) : PumpResult
    }

    /**
     * Open the source at [startOffset] and push chunks until the stream
     * ends, the server changes its mind about the offset, or the loop
     * pauses itself.
     */
    private suspend fun pumpFrom(
        client: RemoteClient,
        flow: MutableStateFlow<State>,
        meta: UploadMetadata,
        uploadId: Long,
        startOffset: Long,
        events: Channel<ChunkEvent>,
    ): PumpResult = openUploadStream(meta).use { stream ->
        if (startOffset > 0L && !UploadStreamIo.skipFully(stream, startOffset)) {
            return PumpResult.Failed("attachment is shorter than its reported size")
        }
        pumpChunks(
            client = client,
            flow = flow,
            meta = meta,
            uploadId = uploadId,
            events = events,
            stream = stream,
            startOffset = startOffset,
        )
    }

    private suspend fun pumpChunks(
        client: RemoteClient,
        flow: MutableStateFlow<State>,
        meta: UploadMetadata,
        uploadId: Long,
        events: Channel<ChunkEvent>,
        stream: InputStream,
        startOffset: Long,
    ): PumpResult {
        val localKey = meta.localKey
        val totalSize = meta.totalSize
        var offset = startOffset
        while (offset < totalSize) {
            val chunkBytes = meta.chunkBytes
            // Never read past the declared size: an over-long source
            // (a stale `_size` from a cloud provider) would otherwise
            // overrun the server's slot and loop on rejected chunks.
            val want = minOf(chunkBytes.toLong(), totalSize - offset).toInt()
            val buf = ByteArray(want)
            val n = UploadStreamIo.readFully(stream, buf)
            if (n <= 0) {
                // Stream ended before we hit totalSize — the picker's
                // size hint was wrong. Treat as failure rather than
                // silently truncating the upload.
                return PumpResult.Failed("attachment is shorter than its reported size")
            }
            val chunk = if (n == buf.size) buf else buf.copyOf(n)

            // The WebSocket writer is strictly FIFO and shared with the
            // heartbeat and every chat RPC. Only hand it a chunk once its
            // queue has drained, otherwise a few uploads put ~1 MiB in
            // front of the next ping and the connection gets torn down as
            // unresponsive mid-upload.
            var gateWaitedMs = 0L
            while (!UploadDecisions.canQueueChunk(client.queuedBytes())) {
                if (gateWaitedMs >= CHUNK_GATE_MAX_WAIT_MS) {
                    return PumpResult.Paused(offset, "connection backed up — will resume")
                }
                delay(CHUNK_GATE_POLL_MS)
                gateWaitedMs += CHUNK_GATE_POLL_MS
            }

            val frame = buildUploadChunkFrame(uploadId, offset, chunk)
            Log.i(
                TAG,
                "sendBinary: uploadId=$uploadId offset=$offset chunk=$n frame=${frame.size}",
            )
            val sentAtMs = System.currentTimeMillis()
            if (!client.sendBinary(frame)) {
                return PumpResult.Paused(offset, "websocket refused binary frame")
            }

            // Wait for the server's verdict on this chunk. Bounded by an
            // ack budget that scales with the chunk size and the RTT this
            // upload has actually measured, so a slow-but-healthy link
            // keeps going instead of flapping into Paused.
            val budgetMs = UploadDecisions.ackTimeoutMs(chunk.size, meta.ackRttMs)
            val target = offset + n
            val event = withTimeoutOrNull(budgetMs) {
                var latest = -1L
                var seen: ChunkEvent? = null
                while (latest < target) {
                    val next = events.receiveCatching().getOrNull() ?: return@withTimeoutOrNull null
                    if (next is ChunkEvent.Rejected) {
                        seen = next
                        break
                    }
                    val acked = (next as ChunkEvent.Acked).receivedBytes
                    if (acked > latest) latest = acked
                    seen = ChunkEvent.Acked(latest)
                }
                seen
            }

            when (event) {
                null -> {
                    val connected =
                        client.connectionState.value is ConnectionState.Connected
                    Log.w(
                        TAG,
                        "ack timeout: uploadId=$uploadId offset=$offset (waited ${budgetMs}ms)",
                    )
                    // A chunk that took the whole budget is evidence the
                    // link is slow, not that it is broken — shrink the
                    // chunk so the retry has a chance to fit. The expired
                    // budget is not an RTT measurement, so it must not
                    // enter the EWMA (that would ratchet the next budget
                    // up towards the ceiling).
                    meta.chunkBytes = UploadDecisions.shrinkChunkBytes(chunkBytes)
                    return PumpResult.Paused(
                        offset,
                        UploadDecisions.ackTimeoutReason(connected),
                    )
                }

                is ChunkEvent.Rejected -> {
                    if (UploadDecisions.isExpiredSlot(event.reason)) return PumpResult.Reinit
                    val expected = event.expectedOffset
                        ?: return PumpResult.Failed("server rejected the upload (${event.reason})")
                    return PumpResult.Reseek(expected)
                }

                is ChunkEvent.Acked -> {
                    val rtt = System.currentTimeMillis() - sentAtMs
                    meta.ackRttMs = UploadDecisions.smoothAckRttMs(meta.ackRttMs, rtt)
                    meta.chunkBytes = UploadDecisions.nextChunkBytes(chunkBytes, meta.ackRttMs)
                    offset = event.receivedBytes
                    meta.lastConfirmedOffset = offset
                    Log.i(TAG, "ack received: uploadId=$uploadId new_offset=$offset rtt=${rtt}ms")
                    persistUpload(localKey, meta, uploadId, offset)
                    flow.publish(State.Uploading(offset, totalSize))
                }
            }
        }
        return PumpResult.Complete(offset)
    }

    // ---- Local-source staging (the picker grant does not survive us) ----

    /**
     * Copy the picked bytes into [stagingDir] once, so every later read
     * (including a resume in a new process) is independent of the
     * picker's `content://` grant.
     *
     * Returns false when the upload is over — the source could not be
     * read at all and the terminal state has already been published.
     */
    private suspend fun ensureStagedCopy(
        localKey: String,
        flow: MutableStateFlow<State>,
        meta: UploadMetadata,
    ): Boolean {
        val dir = stagingDir ?: run {
            warnStagingUnconfigured()
            return true
        }
        if (meta.totalSize <= 0L || meta.totalSize > MAX_STAGED_BYTES) return true
        val existing = meta.stagedPath?.let { File(it) }
        if (existing != null && existing.isFile && existing.length() >= meta.totalSize) return true

        val staged = runCatching {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                val target = File(dir, "$localKey$STAGED_SUFFIX")
                // Copy into a .part file and rename it into place. The
                // rename is atomic, so the staged copy either does not
                // exist or is complete — a half-written file must never be
                // picked up as the upload source by [openUploadStream],
                // and a process killed mid-copy must not leave one behind.
                val part = File(dir, "$localKey$STAGED_SUFFIX$PART_SUFFIX")
                val source = contentResolver.openInputStream(meta.uri)
                    ?: throw FileNotFoundException("openInputStream returned null for ${meta.uri}")
                source.use { input -> part.outputStream().use { out -> input.copyTo(out) } }
                if (!part.renameTo(target)) {
                    part.delete()
                    throw IOException("could not move the staged copy into place for $localKey")
                }
                target
            }
        }
        val cause = staged.exceptionOrNull()
        if (cause != null) {
            if (cause is CancellationException) throw cause
            currentCoroutineContext().ensureActive()
            if (UploadDecisions.isUnreadableSource(cause)) {
                failTerminal(
                    localKey,
                    flow,
                    UploadDecisions.unreadableSourceReason(isStagingConfigured),
                )
                return false
            }
            // Transient I/O problem — fall back to streaming straight
            // from the URI while the grant is still alive.
            Log.w(TAG, "staging failed for $localKey; streaming from the picker URI", cause)
            return true
        }
        val file = staged.getOrThrow()
        if (metadata[localKey] !== meta) {
            // forget() / cancel() landed while we were copying. Their
            // deleteStagedCopy ran against a path this upload had not
            // recorded yet, so without this the copy we just finished
            // would outlive every record of it.
            Log.i(TAG, "staging for $localKey finished after it was dropped — discarding the copy")
            runCatching { file.delete() }
            return false
        }
        meta.stagedPath = file.absolutePath
        meta.uploadId?.let { persistUpload(localKey, meta, it, meta.lastConfirmedOffset) }
        return true
    }

    private fun openUploadStream(meta: UploadMetadata): InputStream {
        val staged = meta.stagedPath?.let { File(it) }
        if (staged != null && staged.isFile) return staged.inputStream()
        return contentResolver.openInputStream(meta.uri)
            ?: throw FileNotFoundException("openInputStream returned null for ${meta.uri}")
    }

    private fun deleteStagedCopy(meta: UploadMetadata?) {
        val path = meta?.stagedPath ?: return
        runCatching { File(path).delete() }
    }

    /**
     * Say — once, loudly, and on the channel the UI actually renders —
     * that this build cannot resume an upload across a process restart.
     * A silent `stagingDir ?: return` is exactly how the staging path
     * shipped as dead code.
     */
    private fun warnStagingUnconfigured() {
        if (!stagingWarningEmitted.compareAndSet(false, true)) return
        Log.e(
            TAG,
            "stagingDir is not configured: attachments stream straight from their " +
                "content:// URI, so any upload interrupted by process death will fail " +
                "terminally on resume. Pass a staging directory when constructing UploadManager.",
        )
        context.emitError(
            "Attachments can't be resumed if the app is closed mid-upload " +
                "(attachment staging isn't configured in this build).",
        )
    }

    /**
     * Delete staged copies whose persisted record is gone — an upload
     * that was cancelled while its copy was still being written, a record
     * dropped for an unparseable URI, or a process death between the copy
     * finishing and the first record write. Nothing else ever looks at
     * these files, so without this sweep they accumulate in the cache
     * directory for the life of the install.
     */
    private fun sweepOrphanedStagedFiles() {
        val dir = stagingDir ?: return
        runCatching {
            val known = persistence.allLocalKeys()
            val live = metadata.keys
            dir.listFiles()?.forEach { file ->
                val name = file.name
                if (name.endsWith(PART_SUFFIX)) {
                    // A copy interrupted by process death; it was never
                    // renamed into place, so nothing can refer to it.
                    Log.i(TAG, "sweeping interrupted staged copy $name")
                    file.delete()
                    return@forEach
                }
                if (!name.endsWith(STAGED_SUFFIX)) return@forEach
                val localKey = name.removeSuffix(STAGED_SUFFIX)
                if (localKey in known || localKey in live) return@forEach
                Log.i(TAG, "sweeping orphaned staged upload $name")
                file.delete()
            }
        }.onFailure { Log.w(TAG, "staged-file sweep failed", it) }
    }

    // ---- Small shared helpers ----

    /**
     * Publish [next] unless this coroutine has already been cancelled —
     * without the guard, a cancelled driver could overwrite the `Paused`
     * state [pauseAll] just published with a failure state derived from
     * its own cancellation.
     */
    private suspend fun MutableStateFlow<State>.publish(next: State) {
        currentCoroutineContext().ensureActive()
        value = next
    }

    /**
     * A `Paused` state that keeps whatever progress the UI is already
     * showing. Used on every "we could not even try" path so a chip
     * never regresses to 0 % (or, worse, to a number derived from
     * something that is not a byte count).
     */
    private fun pausedKeeping(
        current: State,
        meta: UploadMetadata,
        reason: String,
    ): State {
        val sent = UploadDecisions.resumeOffset(current, meta.lastConfirmedOffset, meta.totalSize)
        return State.Paused(sent, meta.totalSize, reason)
    }

    private suspend fun failTerminal(
        localKey: String,
        flow: MutableStateFlow<State>,
        reason: String,
    ) {
        flow.publish(State.Failed(reason))
        cleanupOnTerminal(localKey)
    }

    /**
     * Forget the server-side slot for [localKey] so the next pass runs
     * `upload_init` from zero. Used when the server no longer knows the
     * upload id (desktop restart, TTL sweep) — the client still holds
     * the file, so this is a restart, not a failure.
     */
    private fun discardServerSlot(localKey: String, meta: UploadMetadata) {
        meta.uploadId?.let { id ->
            uploadIdToLocalKey.remove(id)
            chunkEvents.remove(id)?.close()
        }
        meta.uploadId = null
        meta.lastConfirmedOffset = 0L
        persistence.remove(localKey, meta.serverId ?: persistence.activeServerId())
    }

    private fun persistUpload(
        localKey: String,
        meta: UploadMetadata,
        uploadId: Long,
        offset: Long,
    ) {
        // Pinned to the server this upload was created against: a pump
        // still winding down after a server switch must not write its
        // record under the newly-bound server's key prefix.
        persistence.saveOrUpdate(
            PersistedUpload(
                localKey = localKey,
                uploadId = uploadId,
                uriString = meta.uri.toString(),
                sessionId = meta.sessionId,
                mime = meta.mime,
                displayName = meta.displayName,
                totalSize = meta.totalSize,
                lastConfirmedOffset = offset,
                stagedPath = meta.stagedPath,
            ),
            serverId = meta.serverId ?: persistence.activeServerId(),
        )
    }

    private fun fireAbort(uploadId: Long) {
        val client = context.activeClient() ?: return
        // Best-effort fire-and-forget — the server also drops the slot on
        // its TTL sweep, and a failed abort RPC isn't worth surfacing.
        scope.launch {
            runCatching {
                client.call(
                    "remote.solution_agent.upload_abort",
                    JsonRpc.json.encodeToJsonElement(
                        UploadAbortParams.serializer(),
                        UploadAbortParams(uploadId = uploadId),
                    ),
                )
            }
        }
    }

    /**
     * Drop per-upload bookkeeping when an upload fails terminally.
     *
     * The server slot is released (`upload_abort`) and the disk record
     * dropped: a failed upload holds one of the session's four slots for
     * a full hour otherwise, and its persisted record would be revived —
     * and fail again, one `upload_status` per reconnect — for as long as
     * the user never taps the card. [retry] still works: `metadata` and
     * the StateFlow survive, and clearing `uploadId` makes the retry
     * re-run `upload_init` from zero instead of resuming a slot that is
     * no longer there.
     */
    private fun cleanupOnTerminal(localKey: String) {
        val meta = metadata[localKey]
        val uploadId = meta?.uploadId
        if (uploadId != null) {
            uploadIdToLocalKey.remove(uploadId)
            chunkEvents.remove(uploadId)?.close()
            fireAbort(uploadId)
            meta.uploadId = null
            meta.lastConfirmedOffset = 0L
        }
        jobs.remove(localKey)
        persistence.remove(localKey, meta?.serverId ?: persistence.activeServerId())
        // Don't remove `states` here — UI is collecting it for the
        // Failed banner, and `retry` restarts through the same flow.
        // Don't remove `metadata` here for the same reason.
    }

    /**
     * Backoff schedule for retrying [upload_init] on transient
     * transport failures. Exponential (500ms × 2^attempt) up to 30s,
     * jittered ±10% so a herd of attachments queued at the same
     * disconnect edge doesn't synchronise their wakeups against the
     * reconnect handshake. Bounded by [MAX_INIT_ATTEMPTS] because
     * `upload_init` is not idempotent — see the call site.
     */
    private fun uploadInitBackoffMs(attempt: Int): Long {
        val exp = 500L shl attempt.coerceAtMost(6)
        val capped = exp.coerceAtMost(30_000L)
        val jitter = (capped.toDouble() * 0.1 * (Math.random() * 2 - 1)).toLong()
        return (capped + jitter).coerceAtLeast(100L)
    }

    /**
     * Periodic safety net: if any upload sits in Paused while the
     * client claims Connected for [STUCK_PAUSED_THRESHOLD_MS], force a
     * resumeAll. Covers the corner case where the proactive
     * `onConnectionInterrupted` hook + the reactive `sendBinary=false`
     * pause both missed somehow, or the lifecycle layer didn't
     * surface the reconnect edge cleanly (rare but possible after a
     * long Doze suspension where state transitions get coalesced).
     *
     * Bound to the manager's [scope]; cancelled implicitly when the
     * ViewModel scope is cleared.
     */
    @Volatile
    private var stuckPausedWatchdogJob: Job? = null

    fun installStuckPausedWatchdog(connectionState: StateFlow<ConnectionState>) {
        // Idempotent: a second install (test rig, hot-reload, refactor that
        // wires it from a different lifecycle) cancels the previous loop
        // before starting a fresh one — without this, two infinite-loop
        // watchdog coroutines would race on `states` and double-fire
        // resumeAll.
        stuckPausedWatchdogJob?.cancel()
        stuckPausedWatchdogJob = scope.launch {
            val pausedSince = ConcurrentHashMap<String, Long>()
            while (true) {
                delay(STUCK_PAUSED_POLL_MS)
                val connected = connectionState.value is ConnectionState.Connected
                if (!connected) {
                    // Drop the timers — uploads are legitimately paused while
                    // the wire is down. We don't want to count that time.
                    pausedSince.clear()
                    continue
                }
                val activeServerId = persistence.activeServerId()
                val now = System.currentTimeMillis()
                var anyStuck = false
                for ((localKey, flow) in states) {
                    val meta = metadata[localKey]
                    val ours = UploadDecisions.belongsToActiveServer(meta?.serverId, activeServerId)
                    val stalled = UploadDecisions.needsDriver(
                        flow.value,
                        jobs[localKey]?.isActive == true,
                    )
                    if (ours && stalled) {
                        val since = pausedSince.getOrPut(localKey) { now }
                        if (now - since >= STUCK_PAUSED_THRESHOLD_MS) {
                            anyStuck = true
                        }
                    } else {
                        pausedSince.remove(localKey)
                    }
                }
                if (anyStuck) {
                    Log.w(TAG, "stuck-Paused watchdog: forcing resumeAll while Connected")
                    resumeAll()
                    pausedSince.clear()
                }
            }
        }
    }

    companion object {
        private const val TAG = "UploadManager"

        /** Poll cadence for the stuck-Paused watchdog. */
        private const val STUCK_PAUSED_POLL_MS: Long = 5_000L

        /**
         * How long an upload can sit in Paused while the wire claims
         * Connected before we treat it as stuck and force a resume.
         * 15s is large enough to ride out the natural Paused→Uploading
         * settle after a reconnect (upload_status RPC + first chunk
         * round-trip), short enough that a genuinely stuck upload
         * recovers without the user noticing.
         */
        private const val STUCK_PAUSED_THRESHOLD_MS: Long = 15_000L

        /** How many uploads may hold the shared socket at once. */
        private const val MAX_CONCURRENT_UPLOADS: Int = 2

        /** Poll cadence while waiting for the transport's send queue to drain. */
        private const val CHUNK_GATE_POLL_MS: Long = 100L

        /** Give up on a backed-up send queue after this and pause instead. */
        private const val CHUNK_GATE_MAX_WAIT_MS: Long = 30_000L

        /**
         * Largest picked file we copy into app-private storage. The
         * server caps attachments at 5 MB, so this only has to be
         * comfortably above that.
         */
        private const val MAX_STAGED_BYTES: Long = 8L * 1024L * 1024L

        /** Suffix of a staged attachment copy inside `stagingDir`. */
        private const val STAGED_SUFFIX: String = ".upload"

        /** Extension of a staged copy that is still being written. */
        private const val PART_SUFFIX: String = ".part"

        /**
         * How many unanswered `upload_init` requests we issue before
         * pausing. Each lost response orphans one of the server's four
         * per-session slots for an hour, so this cannot be unbounded.
         */
        private const val MAX_INIT_ATTEMPTS: Int = 3

        /** How many times an upload may restart from zero before giving up. */
        private const val MAX_REINITS: Int = 2

        /** How many server-driven re-seeks we follow before giving up. */
        private const val MAX_RESEEKS: Int = 8
    }
}
