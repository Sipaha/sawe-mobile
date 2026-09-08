package ru.sipaha.sawe.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import ru.sipaha.sawe.core.QueueStore
import ru.sipaha.sawe.core.QueuedMessage
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

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
 * [ru.sipaha.sawe.app.vm.MainViewModel.switchToServer]) the
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
 * **Threading + durability.** The queue's whole reason to exist is
 * surviving process death, so every mutation is still published with
 * [SharedPreferences.Editor.commit] — an actual fsync, not
 * [SharedPreferences.Editor.apply], whose write the framework only
 * guarantees to have drained at an `onPause`/`onStop` transition and not
 * against an LMK kill. What changed (N-56) is *where* it runs: the caller
 * is the Main thread holding `QueueController.stateLock`, so the fsync is
 * handed to [writeExecutor], a single daemon thread that serialises every
 * write in submission order. `add` therefore returns without blocking, the
 * bytes hit disk within milliseconds rather than at the next lifecycle
 * transition, and a caller that needs the guarantee at a checkpoint can
 * [awaitPersisted]. Opening the file unwraps a Tink keyset through the
 * Android Keystore and also blocks — call [warmUp] from a coroutine first.
 *
 * **Failure path:** if [TinkEncryptedPrefs] is unavailable
 * (keystore migration, factory reset of credential store), every
 * method falls back to a no-op except [loadAll] which returns the
 * cached in-memory list — equivalent to [ru.sipaha.sawe.core.InMemoryQueueStore].
 * The app keeps working, just without restart durability. An
 * *unreadable keyset* is a different case and is recovered rather than
 * tolerated — see [EncryptedPrefs.open] and
 * [PersistenceHealth.droppedStores].
 */
class EncryptedQueueStore(
    private val context: Context,
) : QueueStore {

    /**
     * Provider for the currently-active server id. Rebound on every
     * [get] call — see audit Fix B / [ru.sipaha.sawe.app.data.DraftRepository.activeServerProvider].
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

    // LegacyPrefsFormat.NONE: this store's pre-Tink file was an
    // `EncryptedSharedPreferences` one, and the only reader for that shape
    // went with the androidx.security:security-crypto dependency on
    // 2026-09-08 — there is nothing left for the importer to drain.
    private fun openPrefs(): SharedPreferences? =
        EncryptedPrefs.open(context, PREFS_NAME, LegacyPrefsFormat.NONE)

    /**
     * Single-threaded writer. Serialises every `commit()` in submission
     * order — the in-memory [cache] is the source of truth for this process,
     * so a caller never reads through this thread; it exists purely to keep
     * the fsync off Main while preserving write ordering and error
     * reporting (which `apply()` gives up).
     */
    private val writeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "spk-queue-writer").apply { isDaemon = true }
    }

    /** Handle of the most recently submitted write, for [awaitPersisted]. */
    @Volatile
    private var lastWrite: Future<*>? = null

    /**
     * Suspend until every write submitted so far has been committed to disk.
     * Use at a durability checkpoint (backgrounding, teardown); the normal
     * mutation path does not need it.
     */
    suspend fun awaitPersisted() {
        val pending = lastWrite ?: return
        withContext(Dispatchers.IO) {
            runCatching { pending.get(AWAIT_PERSIST_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                .onFailure { Log.w(TAG, "awaitPersisted() did not complete", it) }
        }
    }

    /**
     * Queue [block] onto [writeExecutor]. The prefs handle is resolved on
     * the writer thread so a cold [warmUp]-less first mutation doesn't pay
     * the Keystore unwrap on the caller either.
     */
    private fun submitWrite(what: String, block: (SharedPreferences) -> Unit) {
        lastWrite = runCatching {
            writeExecutor.submit {
                val p = prefs ?: return@submit
                runCatching { block(p) }
                    .onFailure { Log.w(TAG, "$what failed; queue mutation not persisted", it) }
            }
        }.onFailure { Log.w(TAG, "$what could not be scheduled", it) }.getOrNull()
    }

    /**
     * Resolve the encrypted prefs file on [Dispatchers.IO]. Idempotent —
     * the `by lazy` delegate does the work once, so a later synchronous
     * [loadAll] on the main thread is just a field read.
     */
    suspend fun warmUp() {
        withContext(Dispatchers.IO) { prefs }
    }

    /** [loadAll] off the main thread — the first call decrypts the blob. */
    suspend fun loadAllOnIo(): List<QueuedMessage> = withContext(Dispatchers.IO) { loadAll() }

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
        if (serverId == null) return
        val key = blobKey(serverId)
        submitWrite("clearFor()") { it.edit().remove(key).commit() }
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
        submitWrite("clearAllServers()") { it.edit().clear().commit() }
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
            submitWrite("corrupt-blob reset") { sp -> sp.edit().remove(key).commit() }
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
        // Committed inline (not via [submitWrite]): this is a one-shot
        // upgrade path already running inside a blocking read, and the
        // caller must not observe the v1 key again on a re-read.
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
        val serverId = cacheServerId ?: return
        val isEmpty = cache.isEmpty()
        val payload = runCatching {
            JSON.encodeToString(ListSerializer(QueuedMessage.serializer()), cache.toList())
        }.getOrNull() ?: return
        // The payload is snapshotted here, under the monitor, so the write
        // cannot observe a later mutation; the commit() itself (AES-GCM +
        // fsync) runs on [writeExecutor] because the caller is Main holding
        // QueueController.stateLock. See the "Threading + durability" note.
        val key = blobKey(serverId)
        submitWrite("writeBlob()") { p ->
            if (isEmpty) {
                p.edit().remove(key).commit()
            } else {
                p.edit().putString(key, payload).commit()
            }
        }
    }

    private fun blobKey(serverId: String) = "queued_messages_v2:$serverId"

    companion object {
        private const val TAG = "EncryptedQueueStore"

        /** Prefs-file name; also the [PersistenceHealth] identifier for this store. */
        internal const val PREFS_NAME = "spk_queue"

        /** Upper bound on how long [awaitPersisted] waits for the writer thread. */
        private const val AWAIT_PERSIST_TIMEOUT_MS = 2_000L
        private const val KEY_BLOB_V1 = "queued_messages_v1"

        private val JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private val holder = SingletonHolder(::EncryptedQueueStore)

        /** Process-wide instance; provider rebound per call ([SingletonHolder]). */
        fun get(context: Context, activeServerProvider: () -> String?): EncryptedQueueStore =
            holder.get(context) { it.activeServerProvider = activeServerProvider }
    }
}
