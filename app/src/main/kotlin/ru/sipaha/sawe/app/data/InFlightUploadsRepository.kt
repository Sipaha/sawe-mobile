package ru.sipaha.sawe.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Snapshot of one in-flight chunked upload written to disk so a process
 * kill or network drop mid-upload can be resumed on next launch.
 *
 * Mirrors the per-upload state held inside `UploadManager` — the
 * coroutine that drives the chunk loop persists this on every ack so
 * the worst-case lost work after a force-kill is one chunk
 * ([UPLOAD_CHUNK_PAYLOAD_BYTES]). On resume, the manager calls
 * `upload_status` against the server (which is authoritative); the
 * persisted [lastConfirmedOffset] is only used as a hint for the UI's
 * percent label before the status round-trip returns.
 */
@Serializable
data class PersistedUpload(
    @SerialName("local_key") val localKey: String,
    @SerialName("upload_id") val uploadId: Long,
    @SerialName("uri") val uriString: String,
    @SerialName("session_id") val sessionId: String,
    val mime: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("total_size") val totalSize: Long,
    @SerialName("last_confirmed_offset") val lastConfirmedOffset: Long,
    /**
     * Absolute path of the app-private copy of the picked bytes, or null
     * when the upload streams straight from its `content://` URI. The
     * picker's URI grant dies with the process, so only a record with a
     * staged path can actually be resumed after a process kill.
     */
    @SerialName("staged_path") val stagedPath: String? = null,
)

/**
 * Everything `UploadManager` needs from the in-flight-upload registry.
 *
 * The upload state machine is the only consumer, and its most interesting
 * behaviour (what survives a server switch, what a terminal failure
 * removes, which staged copies are orphaned) is defined by these calls —
 * so they live behind an interface that a unit test can implement
 * in-memory. The production implementation, [InFlightUploadsRepository],
 * is backed by [TinkEncryptedPrefs], which needs an Android
 * Keystore that neither the JVM nor Robolectric provides.
 */
interface InFlightUploadStore {

    /** Server every un-suffixed call below is scoped to, or null before pairing. */
    fun activeServerId(): String?

    /** Persist (or replace) [persisted] under [serverId]. */
    fun saveOrUpdate(persisted: PersistedUpload, serverId: String?)

    /** Every persisted upload recorded against [serverId]. */
    fun listFor(serverId: String?): List<PersistedUpload>

    /** Drop the persisted entry for [localKey] recorded against [serverId]. */
    fun remove(localKey: String, serverId: String?)

    /** Wipe every persisted entry for [serverId]. */
    fun removeForServer(serverId: String)

    /**
     * Every localKey that still has a persisted record, across **all**
     * servers. Used to garbage-collect staged attachment copies whose
     * record is gone — those files are invisible to every per-server code
     * path and would otherwise accumulate for the life of the install.
     */
    fun allLocalKeys(): Set<String>

    fun saveOrUpdate(persisted: PersistedUpload) = saveOrUpdate(persisted, activeServerId())

    fun list(): List<PersistedUpload> = listFor(activeServerId())

    fun remove(localKey: String) = remove(localKey, activeServerId())
}

/**
 * Encrypted-on-disk per-server registry of in-flight chunked uploads.
 *
 * **Storage:** one [TinkEncryptedPrefs] store (`spk_inflight_uploads`)
 * with per-(server, localKey) keys
 * `inflight-upload:<serverId>:<localKey>`, each holding a JSON-serialised
 * [PersistedUpload]. Mirrors [EncryptedQueueStore] /
 * [SessionHistoryRepository]'s "single prefs file, prefixed keys"
 * pattern — the closest existing analog after a quick repo audit.
 *
 * **Why encrypted:** uploads carry user attachments (photos, files,
 * source). Same threat model as [EncryptedQueueStore] — adversary with
 * file-system access on an unlocked device shouldn't recover what the
 * user attached.
 *
 * **Failure path:** if [TinkEncryptedPrefs] is unavailable
 * (keystore migration, factory-reset credential store) every method
 * becomes a no-op and [list] returns an empty list. The caller falls
 * through to "no in-flight uploads to resume" — degraded but
 * non-crashing.
 *
 * **Per-server scoping:** every key is prefixed with the active server
 * id (read via [activeServerProvider]) so two paired servers' in-flight
 * uploads can't accidentally cross-replay onto the wrong wire.
 */
class InFlightUploadsRepository(
    private val context: Context,
) : InFlightUploadStore {

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
     * Persist (or replace) a snapshot for [persisted.localKey] under the
     * currently-active server. Re-saving an existing localKey overwrites
     * the prior blob — the typical write pattern is "save once on init,
     * re-save on each chunk ack with the new offset".
     */
    override fun saveOrUpdate(persisted: PersistedUpload) =
        saveOrUpdate(persisted, activeServerProvider())

    /**
     * Same as [saveOrUpdate] but pinned to [serverId] instead of
     * whatever happens to be bound right now. An upload coroutine that is
     * still winding down after a server switch must keep writing under
     * the server it was created against — otherwise its record lands
     * under the new server's prefix and gets replayed against the wrong
     * socket.
     */
    override fun saveOrUpdate(persisted: PersistedUpload, serverId: String?) {
        val p = prefs ?: return
        val key = scopedKey(persisted.localKey, serverId) ?: return
        runCatching {
            val raw = JSON.encodeToString(PersistedUpload.serializer(), persisted)
            p.edit().putString(key, raw).apply()
        }.onFailure { Log.w(TAG, "saveOrUpdate() failed for ${persisted.localKey}", it) }
    }

    /**
     * List every persisted upload for the currently-active server. Used
     * by `UploadManager.resumeAllFromDisk` at startup to revive the
     * coroutines for uploads that were mid-stream when the process died.
     */
    override fun list(): List<PersistedUpload> = listFor(activeServerProvider())

    /** List every persisted upload recorded against [serverId]. */
    override fun listFor(serverId: String?): List<PersistedUpload> {
        val p = prefs ?: return emptyList()
        if (serverId == null) return emptyList()
        val prefix = "$KEY_PREFIX:$serverId:"
        return runCatching {
            p.all.entries
                .filter { it.key.startsWith(prefix) }
                .mapNotNull { (_, raw) ->
                    val s = raw as? String ?: return@mapNotNull null
                    runCatching {
                        JSON.decodeFromString(PersistedUpload.serializer(), s)
                    }.getOrNull()
                }
        }.onFailure { Log.w(TAG, "list() failed", it) }
            .getOrDefault(emptyList())
    }

    /**
     * Id of the server every method on this repository is scoped to
     * right now, or null before a server is bound. Exposed so callers
     * that hold upload state in memory (`UploadManager`) can tag it with
     * the same server and avoid replaying one server's uploads against
     * another's socket.
     */
    override fun activeServerId(): String? = activeServerProvider()

    /** Drop the persisted entry for [localKey] under the currently-active server. */
    override fun remove(localKey: String) = remove(localKey, activeServerProvider())

    /** Drop the persisted entry for [localKey] recorded against [serverId]. */
    override fun remove(localKey: String, serverId: String?) {
        val p = prefs ?: return
        val key = scopedKey(localKey, serverId) ?: return
        runCatching { p.edit().remove(key).apply() }
            .onFailure { Log.w(TAG, "remove() failed for $localKey", it) }
    }

    /**
     * Every localKey that still has a persisted record, across **all**
     * servers. Used to garbage-collect staged attachment copies whose
     * record is gone — those files are otherwise invisible to every
     * per-server code path and would accumulate in the cache directory.
     */
    override fun allLocalKeys(): Set<String> {
        val p = prefs ?: return emptySet()
        return runCatching {
            p.all.keys
                .filter { it.startsWith("$KEY_PREFIX:") }
                .mapNotNull { it.substringAfterLast(':').ifEmpty { null } }
                .toSet()
        }.onFailure { Log.w(TAG, "allLocalKeys() failed", it) }
            .getOrDefault(emptySet())
    }

    /**
     * Wipe every persisted entry for [serverId]. Called from
     * `MainViewModel.removeServer` / `forgetAllServers` so pairing
     * deletion drops dangling upload state alongside drafts /
     * queue blobs.
     */
    override fun removeForServer(serverId: String) {
        val p = prefs ?: return
        val prefix = "$KEY_PREFIX:$serverId:"
        runCatching {
            val editor = p.edit()
            for (key in p.all.keys) {
                if (key.startsWith(prefix)) editor.remove(key)
            }
            editor.apply()
        }.onFailure { Log.w(TAG, "removeForServer() failed", it) }
    }

    private fun scopedKey(localKey: String, serverId: String?): String? {
        if (serverId == null) return null
        return "$KEY_PREFIX:$serverId:$localKey"
    }

    companion object {
        private const val TAG = "InFlightUploadsRepo"
        /** Prefs-file name; also the [PersistenceHealth] identifier for this store. */
        internal const val PREFS_NAME = "spk_inflight_uploads"
        private const val KEY_PREFIX = "inflight-upload"

        private val JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        private val holder = SingletonHolder(::InFlightUploadsRepository)

        /** Process-wide instance; provider rebound per call ([SingletonHolder]). */
        fun get(
            context: Context,
            activeServerProvider: () -> String?,
        ): InFlightUploadsRepository =
            holder.get(context) { it.activeServerProvider = activeServerProvider }
    }
}
