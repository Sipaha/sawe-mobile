package ru.sipaha.sawe.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One pending-send record. Stored on disk so a Send tap whose
 * attachments are still uploading survives a process kill: on next
 * launch the deferred-send coroutine resumes, awaits the (also-persisted)
 * uploads via [UploadManager.awaitTerminal], and fires `queueCall` once
 * every handle is available.
 *
 * [csid] is the same `client_send_id` we stamp onto the outgoing
 * `_meta.spk_client_send_id`. Identifies the record across process
 * restarts AND lets reconcile fast-pop the optimistic bubble when the
 * server echoes the message back.
 *
 * [localId] is the SessionDetailStore-private optimistic-bubble id —
 * persisted so the rehydrated bubble keeps a stable identity across
 * restarts (in case some surface keys off it).
 *
 * [enqueuedAtMs] is the wall clock at which the marker was first written.
 * It exists so orphaned markers can be garbage-collected by age
 * ([PendingSendsRepository.gcOrphans]) — without it a marker whose send
 * coroutine died with the process would re-materialise a "Sending" bubble
 * forever. Records written by builds that predate the field decode to
 * [UNKNOWN_ENQUEUED_AT]; see [PendingSendsRepository.selectOrphans] for
 * how an unknown age is treated.
 *
 * **Dispatch provenance does NOT live here.** It lives on
 * [ru.sipaha.sawe.core.QueuedMessage.attempt], which the queue stamps
 * write-ahead of each frame. This record only ever knew the editor process
 * as of the Send TAP, and those differ whenever a message waits in the
 * offline queue across a reconnect; it is also absent entirely for queue
 * records written by builds that predate it, while the queue record is by
 * definition present for anything the queue can replay. An earlier revision
 * carried a `server_instance_id` here in anticipation of the cross-process
 * replay gate; the gate that shipped reads the queue record instead and the
 * field was retired. `ignoreUnknownKeys` keeps old markers decoding.
 */
@Serializable
data class PersistedPendingSend(
    @SerialName("csid") val csid: Long,
    @SerialName("local_id") val localId: Long,
    @SerialName("session_id") val sessionId: String,
    @SerialName("text") val text: String?,
    @SerialName("attachments") val attachments: List<PersistedPendingAttachment>,
    @SerialName("enqueued_at") val enqueuedAtMs: Long = UNKNOWN_ENQUEUED_AT,
) {
    companion object {
        /**
         * Sentinel for "written by a build that had no `enqueued_at`".
         * Zero rather than a real epoch value so it cannot be confused with
         * a genuine timestamp, and so the JSON key stays absent under
         * `encodeDefaults = false` when nothing better is known.
         */
        const val UNKNOWN_ENQUEUED_AT: Long = 0L
    }
}

@Serializable
data class PersistedPendingAttachment(
    @SerialName("local_key") val localKey: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("mime") val mime: String,
)

/**
 * Encrypted-on-disk per-server registry of pending sends — the
 * outbound-side companion to [InFlightUploadsRepository]: that one
 * persists the chunk-upload state, this one persists the "send fires
 * after every upload reaches Done" intent.
 *
 * **Storage:** one [TinkEncryptedPrefs] file
 * (`spk_pending_sends`) with per-(server, csid) keys
 * `pending-send:<serverId>:<csid>`, each holding a JSON-serialised
 * [PersistedPendingSend]. Mirrors [InFlightUploadsRepository] /
 * [EncryptedQueueStore]'s "single prefs file, prefixed keys" pattern.
 *
 * **Threading.** Opening the file unwraps a Tink keyset through the
 * Android Keystore and blocks; call [warmUp] from a coroutine at
 * ViewModel construction so the first tap doesn't pay for it. The
 * synchronous [saveOrUpdate] / [list] / [remove] entry points stay for
 * compatibility with existing call sites, but each has a `…OnIo` suspend
 * twin that should be preferred from any main-thread caller. Writes go
 * out via `apply()` (SharedPreferences' own background thread), so the
 * caller only pays for the AES-SIV/GCM encryption of a small record.
 *
 * **Failure path:** if [TinkEncryptedPrefs] is unavailable
 * (keystore migration, factory-reset credential store) every method
 * becomes a no-op and [list] returns an empty list. An *unreadable
 * keyset* is recovered rather than tolerated — see [EncryptedPrefs.open]
 * and [PersistenceHealth.droppedStores].
 *
 * **Per-server scoping:** every key is prefixed with the active server
 * id (read via [activeServerProvider]) so two paired servers' pending
 * sends never cross-replay onto the wrong wire.
 */
class PendingSendsRepository(
    private val context: Context,
) {

    @Volatile
    var activeServerProvider: () -> String? = { null }
        internal set

    private val prefs: SharedPreferences? by lazy { openPrefs() }

    // LegacyPrefsFormat.NONE: this store's pre-Tink file was an
    // `EncryptedSharedPreferences` one, and the only reader for that shape
    // went with the androidx.security:security-crypto dependency on
    // 2026-09-08 — there is nothing left for the importer to drain.
    private fun openPrefs(): SharedPreferences? =
        EncryptedPrefs.open(context, PREFS_NAME, LegacyPrefsFormat.NONE)

    /**
     * Resolve the encrypted prefs file on [Dispatchers.IO]. Idempotent —
     * the `by lazy` delegate does the work once and every later access
     * (including a synchronous one on Main) is a field read.
     */
    suspend fun warmUp() {
        withContext(Dispatchers.IO) { prefs }
    }

    /**
     * Persist [record]. When [record] carries no [PersistedPendingSend.enqueuedAtMs]
     * the current wall clock is stamped in, so every marker written by this
     * build is GC-able by age.
     */
    fun saveOrUpdate(record: PersistedPendingSend) {
        val p = prefs ?: return
        val key = scopedKey(record.csid) ?: return
        val stamped = if (record.enqueuedAtMs == PersistedPendingSend.UNKNOWN_ENQUEUED_AT) {
            record.copy(enqueuedAtMs = System.currentTimeMillis())
        } else {
            record
        }
        runCatching {
            val raw = JSON.encodeToString(PersistedPendingSend.serializer(), stamped)
            p.edit {putString(key, raw)}
        }.onFailure { Log.w(TAG, "saveOrUpdate() failed for csid=${record.csid}", it) }
    }

    /** [saveOrUpdate] off the main thread — preferred from a tap handler. */
    suspend fun saveOrUpdateOnIo(record: PersistedPendingSend) {
        withContext(Dispatchers.IO) { saveOrUpdate(record) }
    }

    fun list(): List<PersistedPendingSend> {
        val p = prefs ?: return emptyList()
        val serverId = activeServerProvider() ?: return emptyList()
        val prefix = "$KEY_PREFIX:$serverId:"
        return runCatching {
            p.all.entries
                .filter { it.key.startsWith(prefix) }
                .mapNotNull { (_, raw) ->
                    val s = raw as? String ?: return@mapNotNull null
                    runCatching {
                        JSON.decodeFromString(PersistedPendingSend.serializer(), s)
                    }.getOrNull()
                }
        }.onFailure { Log.w(TAG, "list() failed", it) }
            .getOrDefault(emptyList())
    }

    /** [list] off the main thread — decrypts every marker for the active server. */
    suspend fun listOnIo(): List<PersistedPendingSend> =
        withContext(Dispatchers.IO) { list() }

    /**
     * Garbage-collect orphaned markers (N-03) and return the csids removed.
     *
     * A text-only marker means "a send is queued for delivery". Once its
     * message has left the offline queue — sent, TTL-expired, drained on
     * close — nothing on a restarted process will ever remove the marker,
     * and `openSession` re-materialises it as a permanent phantom "Sending"
     * bubble. This is the sweep that removes those.
     *
     * **Attachment-bearing markers are exempt from the queue rule** — see
     * [selectOrphans]. They are the deferred-send records this store exists
     * for, and by construction they are never queued.
     *
     * @param liveCsids csids still present in the offline queue
     *   ([EncryptedQueueStore.loadAll], mapped through
     *   `params.blocks[0]._meta.spk_client_send_id`).
     * @param inFlightCsids csids owned by a send coroutine alive in *this*
     *   process. Those have no queue entry yet and must not be swept; pass
     *   an empty set when calling at cold start before any send exists.
     * @param ttlMs maximum marker age; defaults to the offline queue's own
     *   24 h TTL, past which a marker cannot possibly still resolve.
     */
    fun gcOrphans(
        liveCsids: Set<Long>,
        inFlightCsids: Set<Long> = emptySet(),
        nowMs: Long = System.currentTimeMillis(),
        ttlMs: Long = MARKER_TTL_MS,
    ): List<Long> {
        val p = prefs ?: return emptyList()
        val serverId = activeServerProvider() ?: return emptyList()
        val records = list()
        val orphans = selectOrphans(
            records = records,
            liveCsids = liveCsids,
            inFlightCsids = inFlightCsids,
            nowMs = nowMs,
            ttlMs = ttlMs,
        )
        if (orphans.isEmpty()) return emptyList()
        runCatching {
            p.edit {
                for (csid in orphans) remove("$KEY_PREFIX:$serverId:$csid")
            }
        }.onFailure { Log.w(TAG, "gcOrphans() failed", it) }
        Log.i(TAG, "gcOrphans(): dropped ${orphans.size} orphaned pending-send marker(s)")
        return orphans
    }

    /** [gcOrphans] off the main thread. */
    suspend fun gcOrphansOnIo(
        liveCsids: Set<Long>,
        inFlightCsids: Set<Long> = emptySet(),
        nowMs: Long = System.currentTimeMillis(),
        ttlMs: Long = MARKER_TTL_MS,
    ): List<Long> = withContext(Dispatchers.IO) {
        gcOrphans(liveCsids, inFlightCsids, nowMs, ttlMs)
    }

    fun remove(csid: Long) {
        val p = prefs ?: return
        val key = scopedKey(csid) ?: return
        runCatching { p.edit {remove(key)} }
            .onFailure { Log.w(TAG, "remove() failed for csid=$csid", it) }
    }

    fun removeForServer(serverId: String) {
        val p = prefs ?: return
        val prefix = "$KEY_PREFIX:$serverId:"
        runCatching {
            p.edit {
                for (key in p.all.keys) {
                    if (key.startsWith(prefix)) remove(key)
                }
            }
        }.onFailure { Log.w(TAG, "removeForServer() failed", it) }
    }

    private fun scopedKey(csid: Long): String? {
        val serverId = activeServerProvider() ?: return null
        return "$KEY_PREFIX:$serverId:$csid"
    }

    companion object {
        private const val TAG = "PendingSendsRepo"

        /** Prefs-file name; also the [PersistenceHealth] identifier for this store. */
        internal const val PREFS_NAME = "spk_pending_sends"
        private const val KEY_PREFIX = "pending-send"

        /** Mirrors the offline queue's own 24 h TTL. */
        internal const val MARKER_TTL_MS: Long = 24L * 60 * 60 * 1000

        /**
         * Pure GC policy — which of [records] can no longer resolve.
         *
         * A record is kept while it is *backed*: still in the offline queue
         * ([liveCsids]), owned by a live send coroutine in this process
         * ([inFlightCsids]), or **carrying attachments**. Anything else is an
         * orphan, and so is any backed record older than [ttlMs].
         *
         * **Why attachments are exempt.** A deferred send — the case this
         * store exists for — has no queue entry *by construction*:
         * `SessionDetailStore.runDeferredSend` calls `queueCall` only after
         * every upload reaches `Done`, and rewrites the record with
         * `attachments = emptyList()` immediately before it does. So a
         * non-empty attachment list means "uploads are still in flight",
         * which is precisely the state that must survive a process kill.
         * At cold start such a record is not in [inFlightCsids] either — the
         * caller derives that set from in-memory registries the sweep runs
         * before. Judging it by queue membership would delete 100 % of them
         * on every launch, i.e. exactly the message loss the marker prevents.
         * The TTL still reaps one whose uploads genuinely died.
         *
         * **Legacy records.** A marker written before `enqueued_at` existed
         * decodes to [PersistedPendingSend.UNKNOWN_ENQUEUED_AT]. An unknown
         * age is never treated as expired — dropping a record purely because
         * an older build didn't stamp it would delete live sends on the first
         * launch after an upgrade. A legacy *text* record is still swept by
         * the queue-membership rule; a legacy *attachment* record survives
         * until `runDeferredSend` re-saves it (which stamps it) or the user
         * resolves it, which is strictly better than deleting it blind.
         */
        internal fun selectOrphans(
            records: List<PersistedPendingSend>,
            liveCsids: Set<Long>,
            inFlightCsids: Set<Long> = emptySet(),
            nowMs: Long,
            ttlMs: Long = MARKER_TTL_MS,
        ): List<Long> = records.filter { record ->
            val backed = record.attachments.isNotEmpty() ||
                record.csid in liveCsids ||
                record.csid in inFlightCsids
            val ageKnown = record.enqueuedAtMs != PersistedPendingSend.UNKNOWN_ENQUEUED_AT
            val expired = ageKnown && nowMs - record.enqueuedAtMs > ttlMs
            !backed || expired
        }.map { it.csid }

        private val JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        private val holder = SingletonHolder(::PendingSendsRepository)

        /** Process-wide instance; provider rebound per call ([SingletonHolder]). */
        fun get(
            context: Context,
            activeServerProvider: () -> String?,
        ): PendingSendsRepository =
            holder.get(context) { it.activeServerProvider = activeServerProvider }
    }
}
