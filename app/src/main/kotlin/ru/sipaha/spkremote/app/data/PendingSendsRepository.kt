package ru.sipaha.spkremote.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
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
 */
@Serializable
data class PersistedPendingSend(
    @SerialName("csid") val csid: Long,
    @SerialName("local_id") val localId: Long,
    @SerialName("session_id") val sessionId: String,
    @SerialName("text") val text: String?,
    @SerialName("attachments") val attachments: List<PersistedPendingAttachment>,
)

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
 * **Storage:** one [EncryptedSharedPreferences] file
 * (`spk_pending_sends`) with per-(server, csid) keys
 * `pending-send:<serverId>:<csid>`, each holding a JSON-serialised
 * [PersistedPendingSend]. Mirrors [InFlightUploadsRepository] /
 * [EncryptedQueueStore]'s "single prefs file, prefixed keys" pattern.
 *
 * **Failure path:** if [EncryptedSharedPreferences] is unavailable
 * (keystore migration, factory-reset credential store) every method
 * becomes a no-op and [list] returns an empty list. The caller falls
 * through to "no pending sends to resume" — degraded but non-crashing.
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

    private fun openPrefs(): SharedPreferences? = runCatching {
        val masterKey = AppMasterKey.get(context) ?: return@runCatching null
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.onFailure {
        Log.w(TAG, "EncryptedSharedPreferences unavailable; pending sends not persisted", it)
    }.getOrNull()

    fun saveOrUpdate(record: PersistedPendingSend) {
        val p = prefs ?: return
        val key = scopedKey(record.csid) ?: return
        runCatching {
            val raw = JSON.encodeToString(PersistedPendingSend.serializer(), record)
            p.edit {putString(key, raw)}
        }.onFailure { Log.w(TAG, "saveOrUpdate() failed for csid=${record.csid}", it) }
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
        private const val PREFS_NAME = "spk_pending_sends"
        private const val KEY_PREFIX = "pending-send"

        private val JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        @Volatile
        private var instance: PendingSendsRepository? = null

        fun get(
            context: Context,
            activeServerProvider: () -> String?,
        ): PendingSendsRepository = synchronized(this) {
            val store = instance
                ?: PendingSendsRepository(context.applicationContext).also { instance = it }
            store.activeServerProvider = activeServerProvider
            store
        }
    }
}
