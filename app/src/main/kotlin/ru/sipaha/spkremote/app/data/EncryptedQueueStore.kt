package ru.sipaha.spkremote.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import ru.sipaha.spkremote.core.QueueStore
import ru.sipaha.spkremote.core.QueuedMessage

/**
 * Disk-backed [QueueStore] using Android-Keystore-encrypted shared
 * preferences (R-6d).
 *
 * **Storage layout:** one entry per paired server, keyed
 * `queued_messages_v2:<serverId>`, holding a JSON array of
 * [QueuedMessage]. We rewrite the full blob on every mutation
 * (`add` / `remove` / `clear`) because the typical queue depth is
 * 0-3 entries and a real production app rarely sees more than ~10:
 * partial updates would add complexity for negligible I/O savings.
 *
 * **R-6c-multi per-server scoping:** all operations consult
 * [activeServerProvider] for the active server id and route the read /
 * write to that server's blob. When the active server changes (via
 * [ru.sipaha.spkremote.app.vm.MainViewModel.switchToServer]) the
 * in-memory cache is invalidated lazily on the next access — see
 * [refreshCacheIfServerChanged]. The legacy R-6d single-blob key
 * `queued_messages_v1` is migrated lazily into the active server's
 * keyed blob on the first read after upgrade.
 *
 * **Why encrypted:** queued sends contain user content (potentially
 * sensitive — code snippets, work-in-progress text, debugging info)
 * and the session id which authorises follow-ups to the same chat. Same
 * threat model as the pairing URL itself — adversary with file-system
 * access shouldn't be able to read drafts off a stolen unlocked device.
 *
 * **Concurrency:** [SharedPreferences.Editor.commit] is called for every
 * mutation so a process kill *after* `add` returns is guaranteed to
 * preserve the entry. The slower commit (vs apply) is acceptable here —
 * the queue mutation rate is bounded by user tap rate (< 1/sec) and we
 * want guaranteed-on-disk semantics for the "tap Send → force-kill"
 * crash safety story.
 *
 * **Failure path:** if EncryptedSharedPreferences is unavailable
 * (keystore migration, factory reset of credential store), every
 * method falls back to a no-op except [loadAll] which returns the
 * cached in-memory list — equivalent to [ru.sipaha.spkremote.core.InMemoryQueueStore].
 * The app keeps working, just without restart durability.
 */
class EncryptedQueueStore(
    private val context: Context,
) : QueueStore {

    /**
     * Provider for the currently-active server id. Rebound on every
     * [get] call — see audit Fix B / [ru.sipaha.spkremote.app.data.DraftRepository.activeServerProvider].
     */
    @Volatile
    var activeServerProvider: () -> String? = { null }
        internal set

    private val prefs: SharedPreferences? by lazy { openPrefs() }
    // Defensive in-memory cache so a transient prefs-open failure doesn't
    // strand the user. Mutations write through both — disk is canonical.
    private val cache: MutableList<QueuedMessage> = mutableListOf()
    private var cacheServerId: String? = null
    private var cacheInitialised = false

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
        Log.w(TAG, "EncryptedSharedPreferences unavailable; queue persistence disabled", it)
    }.getOrNull()

    @Synchronized
    override fun loadAll(): List<QueuedMessage> {
        refreshCacheIfServerChanged()
        return cache.sortedBy { it.enqueuedAtMs }
    }

    @Synchronized
    override fun add(message: QueuedMessage) {
        refreshCacheIfServerChanged()
        // id is the key — re-adding the same id replaces in place.
        cache.removeAll { it.id == message.id }
        cache.add(message)
        writeBlob()
    }

    @Synchronized
    override fun remove(id: String) {
        refreshCacheIfServerChanged()
        val removed = cache.removeAll { it.id == id }
        if (removed) writeBlob()
    }

    @Synchronized
    override fun clear() {
        refreshCacheIfServerChanged()
        cache.clear()
        writeBlob()
    }

    /**
     * Explicit-scope variant of [clear] — drop the queue blob for
     * [serverId] directly without consulting [activeServerProvider]. Used
     * by `MainViewModel.removeServer` when removing a non-active server,
     * so we don't have to temporarily mutate `_activeServerId` to coerce
     * the right blob key.
     *
     * If [serverId] is the currently-cached one, the in-memory cache is
     * also wiped to keep memory and disk consistent.
     */
    @Synchronized
    fun clearFor(serverId: String?) {
        val p = prefs ?: return
        if (serverId == null) return
        val key = blobKey(serverId)
        runCatching { p.edit().remove(key).commit() }
            .onFailure { Log.w(TAG, "clearFor() failed", it) }
        if (cacheServerId == serverId) {
            cache.clear()
        }
    }

    /**
     * Wipe every queued-messages blob across every server, including
     * the legacy R-6d v1 single-key blob. Used by `forgetAllServers`
     * (audit Fix A). The previous implementation called the per-active-
     * server [clear] here, which left non-active servers' blobs on
     * disk after a "Forget all servers" action.
     */
    @Synchronized
    fun clearAllServers() {
        val p = prefs ?: return
        runCatching { p.edit().clear().commit() }
            .onFailure { Log.w(TAG, "clearAllServers() failed", it) }
        cache.clear()
        cacheServerId = null
        cacheInitialised = false
    }

    /**
     * Reload the in-memory cache from disk when the active server has
     * changed since the previous access. Also performs the one-shot
     * R-6d → R-6c-multi migration of the legacy [KEY_BLOB_V1] blob
     * into the active server's keyed slot when it appears alongside
     * an active server id for the first time.
     *
     * Must be called while holding the instance monitor — `@Synchronized`
     * is applied here as defense-in-depth in case a future non-synchronized
     * caller reaches it; today only the `@Synchronized` public-facing
     * methods invoke this.
     */
    @Synchronized
    private fun refreshCacheIfServerChanged() {
        val serverId = activeServerProvider()
        if (cacheInitialised && cacheServerId == serverId) return
        cache.clear()
        cache.addAll(readBlobFor(serverId))
        cacheServerId = serverId
        cacheInitialised = true
    }

    @Synchronized
    private fun readBlobFor(serverId: String?): List<QueuedMessage> {
        val p = prefs ?: return emptyList()
        if (serverId == null) return emptyList()
        val key = blobKey(serverId)
        val raw = runCatching { p.getString(key, null) }.getOrNull()
            ?: return migrateFromV1IfPossible(p, serverId)
        return runCatching {
            JSON.decodeFromString(ListSerializer(QueuedMessage.serializer()), raw)
        }.onFailure {
            // Corrupt blob (schema migration, partial write) — wipe and
            // start fresh. We deliberately do NOT propagate the error
            // because the user-visible recovery (one lost queued message
            // after a version upgrade) is better than crashing on cold
            // start.
            Log.w(TAG, "queue blob corrupt; resetting", it)
            runCatching { p.edit().remove(key).commit() }
        }.getOrDefault(emptyList())
    }

    /**
     * One-shot lift of the R-6d single-server [KEY_BLOB_V1] blob into
     * the active server's slot. Only fires when:
     *   - the v2 key for [serverId] is empty (no entries written yet);
     *   - AND a v1 blob exists.
     *
     * On a successful migration the v1 key is removed so subsequent
     * reads short-circuit on the empty v2 lookup.
     */
    private fun migrateFromV1IfPossible(prefs: SharedPreferences, serverId: String): List<QueuedMessage> {
        val v1 = runCatching { prefs.getString(KEY_BLOB_V1, null) }.getOrNull() ?: return emptyList()
        val entries = runCatching {
            JSON.decodeFromString(ListSerializer(QueuedMessage.serializer()), v1)
        }.getOrNull() ?: emptyList()
        val payload = runCatching {
            JSON.encodeToString(ListSerializer(QueuedMessage.serializer()), entries)
        }.getOrNull()
        runCatching {
            val edit = prefs.edit().remove(KEY_BLOB_V1)
            if (payload != null && entries.isNotEmpty()) {
                edit.putString(blobKey(serverId), payload)
            }
            edit.commit()
        }.onFailure { Log.w(TAG, "v1→v2 queue migration failed", it) }
        return entries
    }

    /**
     * Persist [cache] under the currently-cached server id. Must be called
     * while holding the instance monitor so that `cacheServerId` does NOT
     * shift mid-write (e.g. a concurrent `switchToServer` cannot redirect
     * this write to the next server's slot). We snapshot the server id
     * once at the top and use that local for the entire method.
     */
    @Synchronized
    private fun writeBlob() {
        val p = prefs ?: return
        val serverId = cacheServerId ?: return
        val payload = runCatching {
            JSON.encodeToString(ListSerializer(QueuedMessage.serializer()), cache.toList())
        }.getOrNull() ?: return
        // commit() (synchronous) — see kdoc on the class. The R8-shrunk
        // release build inlines this into the call site, so the cost is
        // the EncryptedSharedPreferences write only.
        runCatching {
            val key = blobKey(serverId)
            if (cache.isEmpty()) {
                p.edit().remove(key).commit()
            } else {
                p.edit().putString(key, payload).commit()
            }
        }.onFailure { Log.w(TAG, "writeBlob() failed; queue mutation not persisted", it) }
    }

    private fun blobKey(serverId: String) = "queued_messages_v2:$serverId"

    companion object {
        private const val TAG = "EncryptedQueueStore"
        private const val PREFS_NAME = "spk_queue"
        private const val KEY_BLOB_V1 = "queued_messages_v1"

        private val JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        @Volatile
        private var instance: EncryptedQueueStore? = null

        fun get(context: Context, activeServerProvider: () -> String?): EncryptedQueueStore =
            synchronized(this) {
                val store = instance
                    ?: EncryptedQueueStore(context.applicationContext).also { instance = it }
                store.activeServerProvider = activeServerProvider
                store
            }
    }
}
