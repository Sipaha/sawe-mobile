package ru.sipaha.spkremote.app.vm

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.sipaha.spkremote.app.data.InFlightUploadsRepository
import ru.sipaha.spkremote.app.data.PersistedUpload
import ru.sipaha.spkremote.core.JsonRpc
import ru.sipaha.spkremote.core.RemoteClient
import ru.sipaha.spkremote.core.UPLOAD_CHUNK_PAYLOAD_BYTES
import ru.sipaha.spkremote.core.UploadAbortParams
import ru.sipaha.spkremote.core.UploadChunkAckedPayload
import ru.sipaha.spkremote.core.UploadFinishParams
import ru.sipaha.spkremote.core.UploadFinishResult
import ru.sipaha.spkremote.core.UploadInitParams
import ru.sipaha.spkremote.core.UploadInitResult
import ru.sipaha.spkremote.core.UploadStatusParams
import ru.sipaha.spkremote.core.UploadStatusResult
import ru.sipaha.spkremote.core.buildUploadChunkFrame

/**
 * Per-attachment chunked-upload state machine for the mobile attach
 * flow.
 *
 * Each `start()` call returns a `(localKey, StateFlow<State>)` pair —
 * the compose row stashes both on the [ru.sipaha.spkremote.app.ui.solutions.PickedAttachment]
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
 *   Failed                                  (terminal, user can retry by
 *                                            re-attaching)
 *
 *  * `Queued` — `start()` was called but the chunk loop hasn't begun
 *    (no active connection yet, or `upload_init` is in flight).
 *  * `Uploading(sent, total)` — chunk loop is making progress.
 *  * `Paused` — connection dropped mid-stream OR an ack timed out; the
 *    coroutine is dead, ready for `resumeAll()` to start a fresh loop
 *    against the server's authoritative offset.
 *  * `Done` — `upload_finish` returned a `spk-upload://<id>` handle the
 *    compose row attaches to its outbound `send_message_blocks` payload.
 *  * `Failed` — terminal; `start()` won't recover automatically (e.g.
 *    upload_init refused, server reported `unknown_upload_id` on
 *    resume because the slot was GC'd).
 *
 * ### Notification plumbing
 *
 * Acks (`upload_chunk_acked`) are forwarded to UploadManager by the
 * single shared notification collector in
 * [SessionListStore.ensureNotificationsObserver] via the
 * [SessionListStore.uploadNotificationRouter] callback hook. Each
 * forwarded payload is routed to the per-upload [ackChannels] map; the
 * matching coroutine awaits the next ack and advances its offset.
 *
 * ### Persistence
 *
 * Every successful ack is written to [persistence] before the
 * StateFlow advances. The on-disk record is the only thing that
 * survives a process kill — on next launch, [resumeAllFromDisk] is
 * called by the coordinator and revives one coroutine per persisted
 * upload, each of which calls `upload_status` against the server to
 * get the authoritative offset before restarting the chunk loop.
 */
class UploadManager internal constructor(
    private val scope: CoroutineScope,
    private val context: ConnectionContext,
    private val persistence: InFlightUploadsRepository,
    /**
     * Application-scoped resolver — same instance lives for the entire
     * process lifetime, safe to capture. Lets pause/resume hooks (which
     * fire from connection-lifecycle callbacks without a UI context)
     * still drive the I/O stream open on resume without having to
     * round-trip the Activity's resolver back to the lifecycle layer.
     */
    private val contentResolver: ContentResolver,
) {

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
     * Per-upload ack channel. Each chunk send awaits one element here;
     * the notification dispatcher pushes the latest `received_bytes`
     * via [onChunkAcked]. Capacity = Channel.CONFLATED so a fast
     * server emitting two acks back-to-back doesn't block the
     * dispatcher thread — only the most-recent value matters
     * (each ack supersedes the previous one for offset advancement).
     */
    private val ackChannels = ConcurrentHashMap<Long, Channel<Long>>()

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
     * ContentResolver round-trip (which would fail if the URI permission
     * has lapsed across a process restart — see [resumeAllFromDisk]).
     */
    private val metadata = ConcurrentHashMap<String, UploadMetadata>()

    private data class UploadMetadata(
        val localKey: String,
        val uri: Uri,
        val sessionId: String,
        val mime: String,
        val displayName: String,
        val totalSize: Long,
        /** null while upload_init is in flight or hasn't started yet. */
        @Volatile var uploadId: Long? = null,
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
        val state = MutableStateFlow<State>(State.Queued(totalSize))
        states[localKey] = state
        metadata[localKey] = UploadMetadata(
            localKey = localKey,
            uri = uri,
            sessionId = sessionId,
            mime = mime,
            displayName = displayName,
            totalSize = totalSize,
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
     * Subscribe to per-upload state transitions — used by the deferred-
     * send coroutine to translate each chunk-ack tick into a byte-level
     * progress update in the chat bubble. `null` when the upload was
     * never started (or already forgotten); the caller should fall
     * through with no-progress in that case.
     */
    fun stateFlowOf(localKey: String): StateFlow<State>? =
        states[localKey]?.asStateFlow()

    /**
     * Revive uploads persisted to disk by a previous process. Called by
     * the coordinator after [ConnectionLifecycle.onClientBound] when the
     * active server changes, so each server's uploads resume against
     * the correct wire.
     *
     * Each persisted entry becomes a coroutine that calls
     * `upload_status` first (server is authoritative on the offset),
     * then resumes the chunk loop from there.
     */
    fun resumeAllFromDisk() {
        val persisted = persistence.list()
        for (entry in persisted) {
            // Skip if we already have a live record (e.g. resume fired
            // twice on rapid reconnects).
            if (states.containsKey(entry.localKey)) continue
            val uri = runCatching { Uri.parse(entry.uriString) }.getOrNull()
            if (uri == null) {
                persistence.remove(entry.localKey)
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
                uploadId = entry.uploadId,
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
     * Connected edge.
     */
    fun resumeAll() {
        for ((localKey, flow) in states) {
            if (flow.value !is State.Paused) continue
            if (jobs[localKey]?.isActive == true) continue
            launchUploadCoroutine(localKey, resumeFromDisk = true)
        }
    }

    /**
     * Abort the upload identified by [localKey] — cancel the coroutine,
     * fire `upload_abort` server-side (best-effort; ignored on
     * failure), wipe the on-disk record, and remove the StateFlow so
     * the compose row's collector unbinds.
     */
    fun cancel(localKey: String) {
        jobs.remove(localKey)?.cancel()
        states.remove(localKey)
        val meta = metadata.remove(localKey)
        val uploadId = meta?.uploadId
        if (uploadId != null) {
            uploadIdToLocalKey.remove(uploadId)
            ackChannels.remove(uploadId)?.close()
            val client = context.activeClient()
            if (client != null) {
                // Best-effort fire-and-forget — server cleans up the slot
                // either way (TTL GC), and a failed abort RPC isn't worth
                // surfacing to the user.
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
        }
        persistence.remove(localKey)
    }

    /**
     * The compose row consumed the Done state's handle in its
     * `send_message_blocks` payload — drop our local state so the next
     * pick gets a clean slate. Server tmp file is consumed when the
     * blocks send resolves, so we don't need to fire `upload_abort`.
     */
    fun forget(localKey: String) {
        jobs.remove(localKey)?.cancel()
        states.remove(localKey)
        val meta = metadata.remove(localKey)
        meta?.uploadId?.let { id ->
            uploadIdToLocalKey.remove(id)
            ackChannels.remove(id)?.close()
        }
        persistence.remove(localKey)
    }

    /**
     * Wipe every upload on a server-id boundary (used by the
     * coordinator's forgetAllServers / removeServer paths). Doesn't
     * fire upload_abort — by the time this runs the connection is
     * already torn down.
     */
    fun forgetAllForServer(serverId: String) {
        // Cancel every coroutine (we don't track which upload belongs
        // to which server in memory — the active client is single-server
        // at a time, so every in-flight job already belonged to the
        // server about to be removed if it was the active one).
        for ((_, job) in jobs) job.cancel()
        jobs.clear()
        states.clear()
        metadata.clear()
        uploadIdToLocalKey.clear()
        ackChannels.values.forEach { it.close() }
        ackChannels.clear()
        persistence.removeForServer(serverId)
    }

    /**
     * Notification dispatcher entry point. Wired by the coordinator
     * during construction via [SessionListStore.uploadNotificationRouter].
     * Pushes [payload.receivedBytes] into the per-upload ack channel —
     * the coroutine waiting on the channel advances its offset to that
     * value.
     */
    fun onChunkAcked(payload: UploadChunkAckedPayload) {
        val channel = ackChannels[payload.uploadId]
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
        // CONFLATED channels return false on offer when capacity is
        // full — we explicitly drop the older value in that case.
        channel.trySend(payload.receivedBytes)
    }

    // ---- Internal driver coroutine ----

    private fun launchUploadCoroutine(localKey: String, resumeFromDisk: Boolean) {
        val job = scope.launch {
            runUploadLoop(localKey, resumeFromDisk)
        }
        jobs[localKey] = job
    }

    private suspend fun runUploadLoop(localKey: String, resumeFromDisk: Boolean) {
        val state = states[localKey] ?: return
        val meta = metadata[localKey] ?: return
        Log.i(TAG, "runUploadLoop start: localKey=$localKey resumeFromDisk=$resumeFromDisk")
        val client = context.activeClient()
        if (client == null) {
            Log.w(TAG, "runUploadLoop: no active client for $localKey — pausing")
            state.value = State.Paused(0L, meta.totalSize, "no connection")
            // Persist on Queued→Paused transition only when we already
            // had an upload_id (came back from disk). Brand-new
            // attachments without an upload_id stay in memory until
            // either start() can dispatch upload_init OR pauseAll fires.
            meta.uploadId?.let { uploadId ->
                persistence.saveOrUpdate(
                    PersistedUpload(
                        localKey = localKey,
                        uploadId = uploadId,
                        uriString = meta.uri.toString(),
                        sessionId = meta.sessionId,
                        mime = meta.mime,
                        displayName = meta.displayName,
                        totalSize = meta.totalSize,
                        lastConfirmedOffset = 0L,
                    ),
                )
            }
            return
        }

        // Step 1: upload_init (or upload_status when resuming) ---------
        val uploadId: Long
        var startOffset: Long
        if (resumeFromDisk && meta.uploadId != null) {
            val existingId = meta.uploadId
            if (existingId == null) {
                state.value = State.Failed("resume metadata corrupt")
                cleanupOnTerminal(localKey)
                return
            }
            uploadId = existingId
            val statusOutcome = runCatching {
                client.call(
                    "remote.solution_agent.upload_status",
                    JsonRpc.json.encodeToJsonElement(
                        UploadStatusParams.serializer(),
                        UploadStatusParams(uploadId = uploadId),
                    ),
                )
            }.mapCatching { resp ->
                val err = resp.error
                if (err != null) error(err.message)
                val toolErr = resp.toolError()
                if (toolErr != null) error(toolErr)
                val structured = resp.structuredContent()
                    ?: error("upload_status returned no structuredContent")
                JsonRpc.json.decodeFromJsonElement(
                    UploadStatusResult.serializer(),
                    structured,
                )
            }
            val status = statusOutcome.getOrElse {
                val msg = it.message.orEmpty()
                // The server signals an expired / GC'd slot with a
                // distinctive substring; the user has to re-attach.
                val expired = msg.contains("unknown_upload_id", ignoreCase = true) ||
                    msg.contains("not found", ignoreCase = true)
                state.value = if (expired) {
                    State.Failed("upload expired on server — re-attach to retry")
                } else {
                    State.Paused(meta.uploadId ?: 0L, meta.totalSize, "resume failed: $msg")
                }
                if (expired) cleanupOnTerminal(localKey)
                return
            }
            startOffset = status.receivedBytes
        } else {
            val initParams = UploadInitParams(
                sessionId = meta.sessionId,
                mime = meta.mime,
                displayName = meta.displayName,
                totalSize = meta.totalSize,
            )
            // Retry-on-transient loop. Network blips between the
            // [RemoteClient] socket and the server (Software-caused
            // connection abort, dropped Wi-Fi, OS-level TCP reset,
            // [NotConnectedException] during a reconnect cycle, frame
            // refused, request-side [TimeoutCancellationException]) all
            // surface from [RemoteClient.call] as a raised exception
            // BEFORE we ever look at the response envelope. Treat all
            // of those as retryable: keep banging on upload_init with
            // exponential backoff (capped at 30 s, no attempt cap) so
            // the upload survives a connection blip without forcing
            // the user to re-attach and re-send. Cancellation of the
            // job by the user (× on the card → abort(localKey) cancels
            // [job]) propagates via CancellationException — we re-throw
            // explicitly because [runCatching] otherwise swallows it.
            //
            // The MAPCatching block, by contrast, only fires once we
            // have a parsed [JsonRpcResponse] back from the server.
            // Anything caught there is a deterministic server-side or
            // protocol-level failure (`error.message` from the server,
            // tool error, malformed structured content) — re-trying
            // would just reproduce the same response. Those go straight
            // to [State.Failed].
            val init: UploadInitResult = run {
                var attempt = 0
                while (true) {
                    val callResult = runCatching {
                        client.call(
                            "remote.solution_agent.upload_init",
                            JsonRpc.json.encodeToJsonElement(
                                UploadInitParams.serializer(),
                                initParams,
                            ),
                        )
                    }
                    if (callResult.isFailure) {
                        val cause = callResult.exceptionOrNull()!!
                        if (cause is CancellationException) throw cause
                        val backoffMs = uploadInitBackoffMs(attempt)
                        Log.w(
                            TAG,
                            "upload_init transient for $localKey " +
                                "(attempt ${attempt + 1}): ${cause.message ?: cause::class.simpleName} " +
                                "— retry in ${backoffMs}ms",
                        )
                        attempt++
                        delay(backoffMs)
                        continue
                    }
                    val parsed = runCatching {
                        val resp = callResult.getOrThrow()
                        val err = resp.error
                        if (err != null) error(err.message)
                        val toolErr = resp.toolError()
                        if (toolErr != null) error(toolErr)
                        val structured = resp.structuredContent()
                            ?: error("upload_init returned no structuredContent")
                        JsonRpc.json.decodeFromJsonElement(
                            UploadInitResult.serializer(),
                            structured,
                        )
                    }
                    val ok = parsed.getOrElse {
                        if (it is CancellationException) throw it
                        Log.e(
                            TAG,
                            "upload_init FAILED (non-retryable) for $localKey: ${it.message}",
                            it,
                        )
                        state.value =
                            State.Failed("upload_init failed: ${it.message ?: "?"}")
                        cleanupOnTerminal(localKey)
                        return
                    }
                    return@run ok
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
            uploadId = init.uploadId
            startOffset = 0L
            meta.uploadId = uploadId
            Log.i(TAG, "upload_init OK: localKey=$localKey uploadId=$uploadId")
            persistence.saveOrUpdate(
                PersistedUpload(
                    localKey = localKey,
                    uploadId = uploadId,
                    uriString = meta.uri.toString(),
                    sessionId = meta.sessionId,
                    mime = meta.mime,
                    displayName = meta.displayName,
                    totalSize = meta.totalSize,
                    lastConfirmedOffset = 0L,
                ),
            )
        }
        uploadIdToLocalKey[uploadId] = localKey
        // Conflated channel so back-to-back acks don't block the
        // notification dispatcher — only the most-recent offset matters.
        val ackChannel = Channel<Long>(Channel.CONFLATED)
        ackChannels[uploadId] = ackChannel

        state.value = State.Uploading(startOffset, meta.totalSize)

        // Step 2: chunk loop -------------------------------------------
        val transferred = runCatching {
            withContext(Dispatchers.IO) {
                contentResolver.openInputStream(meta.uri)?.use { stream ->
                    if (startOffset > 0L) {
                        val skipped = stream.skip(startOffset)
                        if (skipped < startOffset) {
                            error("could not skip to offset $startOffset (skipped only $skipped)")
                        }
                    }
                    pumpChunks(
                        client = client,
                        state = state,
                        localKey = localKey,
                        uploadId = uploadId,
                        ackChannel = ackChannel,
                        stream = stream,
                        startOffset = startOffset,
                        totalSize = meta.totalSize,
                    )
                } ?: error("openInputStream returned null for ${meta.uri}")
            }
        }
        val finalOffset = transferred.getOrElse {
            // Cancellation here is the pauseAll path — coroutine was
            // explicitly cancelled and state.value was set to Paused by
            // the caller. Anything else is a real error.
            if (it is kotlinx.coroutines.CancellationException) {
                throw it
            }
            state.value = State.Paused(
                (state.value as? State.Uploading)?.sent ?: startOffset,
                meta.totalSize,
                it.message ?: "chunk loop failed",
            )
            return
        }
        if (finalOffset == null) {
            // Pause path returned null — pumpChunks already flipped
            // state to Paused.
            return
        }

        // Step 3: upload_finish ----------------------------------------
        val finishOutcome = runCatching {
            client.call(
                "remote.solution_agent.upload_finish",
                JsonRpc.json.encodeToJsonElement(
                    UploadFinishParams.serializer(),
                    UploadFinishParams(uploadId = uploadId),
                ),
            )
        }.mapCatching { resp ->
            val err = resp.error
            if (err != null) error(err.message)
            val toolErr = resp.toolError()
            if (toolErr != null) error(toolErr)
            val structured = resp.structuredContent()
                ?: error("upload_finish returned no structuredContent")
            JsonRpc.json.decodeFromJsonElement(UploadFinishResult.serializer(), structured)
        }
        val finish = finishOutcome.getOrElse {
            state.value = State.Failed("upload_finish failed: ${it.message ?: "?"}")
            cleanupOnTerminal(localKey)
            return
        }
        state.value = State.Done(finish.handle)
        // Don't remove from persistence — forget() is the consumer's
        // ack that the handle was used in a send. If the user picks +
        // never sends, cancel() / a future GC sweep should clean up.
    }

    /**
     * Run the chunk loop until either the stream ends or an
     * ack-channel wait fails. Returns the final offset on success
     * (caller fires upload_finish), or null when the loop paused
     * itself (caller bails out).
     *
     * Splits out the per-chunk read + send + ack-wait so the surrounding
     * coroutine can keep `runUploadLoop` reading clean.
     */
    private suspend fun pumpChunks(
        client: RemoteClient,
        state: MutableStateFlow<State>,
        localKey: String,
        uploadId: Long,
        ackChannel: Channel<Long>,
        stream: java.io.InputStream,
        startOffset: Long,
        totalSize: Long,
    ): Long? {
        val buf = ByteArray(UPLOAD_CHUNK_PAYLOAD_BYTES)
        var offset = startOffset
        while (offset < totalSize) {
            val n = stream.read(buf, 0, buf.size)
            if (n <= 0) {
                // Stream ended before we hit totalSize — the picker's
                // size hint was wrong. Treat as failure rather than
                // silently truncating the upload.
                state.value = State.Failed("attachment shorter than reported size")
                cleanupOnTerminal(localKey)
                return null
            }
            val chunk = if (n == buf.size) buf else buf.copyOf(n)
            val frame = buildUploadChunkFrame(uploadId, offset, chunk)
            Log.i(
                TAG,
                "sendBinary: uploadId=$uploadId offset=$offset chunk=$n frame=${frame.size}",
            )
            val sent = client.sendBinary(frame)
            Log.i(TAG, "sendBinary result: uploadId=$uploadId offset=$offset sent=$sent")
            if (!sent) {
                state.value = State.Paused(offset, totalSize, "websocket refused binary frame")
                return null
            }
            // Wait for an ack that confirms we're at least at offset+n.
            // Bound by ACK_TIMEOUT_MS so a half-open socket pauses the
            // upload rather than hanging the coroutine forever.
            val acked = withTimeoutOrNull(ACK_TIMEOUT_MS) {
                var latest = -1L
                while (latest < offset + n) {
                    val next = ackChannel.receiveCatching().getOrNull() ?: return@withTimeoutOrNull null
                    if (next > latest) latest = next
                }
                latest
            }
            if (acked == null) {
                Log.w(
                    TAG,
                    "ack timeout: uploadId=$uploadId offset=$offset (waited ${ACK_TIMEOUT_MS}ms)",
                )
                state.value = State.Paused(offset, totalSize, "ack timeout — server may be unreachable")
                return null
            }
            Log.i(TAG, "ack received: uploadId=$uploadId new_offset=$acked")
            offset = acked
            persistence.saveOrUpdate(
                PersistedUpload(
                    localKey = localKey,
                    uploadId = uploadId,
                    uriString = metadata[localKey]?.uri?.toString().orEmpty(),
                    sessionId = metadata[localKey]?.sessionId.orEmpty(),
                    mime = metadata[localKey]?.mime.orEmpty(),
                    displayName = metadata[localKey]?.displayName.orEmpty(),
                    totalSize = totalSize,
                    lastConfirmedOffset = offset,
                ),
            )
            state.value = State.Uploading(offset, totalSize)
        }
        return offset
    }

    /** Drop per-upload bookkeeping when state transitions to Done / Failed. */
    private fun cleanupOnTerminal(localKey: String) {
        val meta = metadata[localKey]
        val uploadId = meta?.uploadId
        if (uploadId != null) {
            uploadIdToLocalKey.remove(uploadId)
            ackChannels.remove(uploadId)?.close()
        }
        jobs.remove(localKey)
        // Don't remove `states` here — UI is collecting it for the
        // Failed banner. forget() / cancel() does the final cleanup.
        // Don't remove `metadata` here for the same reason.
        // Do NOT touch `persistence` on Done — forget() handles it.
    }

    /**
     * Backoff schedule for retrying [upload_init] on transient
     * transport failures. Exponential (500ms × 2^attempt) up to 30s,
     * jittered ±10% so a herd of attachments queued at the same
     * disconnect edge doesn't synchronise their wakeups against the
     * reconnect handshake. No attempt cap — the user explicitly asked
     * for indefinite retry; the only path out is a non-transient
     * server response or job cancellation via [abort].
     */
    private fun uploadInitBackoffMs(attempt: Int): Long {
        val exp = 500L shl attempt.coerceAtMost(6)
        val capped = exp.coerceAtMost(30_000L)
        val jitter = (capped.toDouble() * 0.1 * (Math.random() * 2 - 1)).toLong()
        return (capped + jitter).coerceAtLeast(100L)
    }

    companion object {
        /**
         * Maximum wall time to wait for one chunk's ack before
         * transitioning to Paused. 30s suits residential LTE / Wi-Fi
         * with one chunk in flight; tighter than this and a slow
         * disk write on the server side spuriously pauses uploads,
         * looser and a half-open NAT pinch silently strands the UI.
         */
        private const val TAG = "UploadManager"
        private const val ACK_TIMEOUT_MS: Long = 30_000L
    }
}
