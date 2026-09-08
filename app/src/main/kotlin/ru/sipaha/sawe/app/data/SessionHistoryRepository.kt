package ru.sipaha.sawe.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import ru.sipaha.sawe.core.EntrySummary

/**
 * Advisory index entry for one cached transcript.
 *
 * @property solutionId the owning solution, so [SessionHistoryRepository.prune]
 *   can scope a sweep without decrypting every blob.
 * @property updatedAtMs wall clock of the last write, used as the LRU key for
 *   the session-count cap.
 */
@Serializable
internal data class HistoryIndexEntry(
    @SerialName("sol") val solutionId: Long,
    @SerialName("ts") val updatedAtMs: Long,
)

/**
 * Encrypted-on-disk per-session transcript cache. Lets
 * `SessionDetailStore.openSession` render the chat surface from disk
 * immediately and then ask the server only for the diff via
 * `get_session(after_index=cache.lastIndex)`.
 *
 * **Storage:** one [TinkEncryptedPrefs] file (`spk_history_cache`)
 * with per-(server, session) keys `history-v1:<serverId>:<sessionId>`,
 * each holding a JSON-serialised [CachedSessionHistory], plus one
 * `history-index-v1:<serverId>` key holding a `sessionId → `
 * [HistoryIndexEntry] map. Mirrors [EncryptedQueueStore]'s "single prefs
 * file, prefixed keys" pattern — the plan-doc's per-server filename
 * remark was aspirational; the codebase precedent is one file, prefixed
 * keys.
 *
 * **The index is advisory.** Nothing reads a transcript through it:
 * [load] goes straight to the session's own key. It exists purely so
 * [prune] and the session-count cap can decide *which* keys to drop
 * without calling `getAll()`, which on a [TinkEncryptedPrefs]
 * decrypts every key **and every value** in the file — i.e. every cached
 * transcript on the device, for what is only a GC sweep (N-32). A missing
 * or stale index therefore costs a skipped sweep or a no-op remove, never
 * a wrong read. It is rebuilt from `getAll()` once per process when
 * absent, which is also the migration path for caches written by builds
 * that predate it.
 *
 * **Bounded on disk.** SharedPreferences rewrites the whole file on every
 * write, so the cost of a single write is the size of the whole cache.
 * Two caps keep that bounded and therefore keep one write cheap:
 * [MAX_CACHED_ENTRIES] / [MAX_BLOB_BYTES] per session (the newest entries
 * win — the cache is a tail window and the UI pages older entries in from
 * the server), and [MAX_CACHED_SESSIONS] transcripts per server, evicting
 * the least-recently-written. A write whose payload is byte-identical to
 * the last one is skipped outright.
 *
 * **Why encrypted:** transcripts contain user content (source code, work
 * in progress, file paths, model output that quotes the same). Same
 * threat model as [EncryptedQueueStore] / the pairing URL — adversary
 * with file-system access on an unlocked device shouldn't recover the
 * conversation history.
 *
 * **Threading.** Every disk touch is blocking (Keystore keyset unwrap on
 * open, AES-GCM per read/write). [save] and [prune] are already
 * non-blocking for their callers — they hand the work to [scope] on
 * [Dispatchers.IO]. [load] stays synchronous for its existing call site
 * but answers a repeat open of the same session from [memo] without
 * touching disk; a main-thread caller should prefer [loadOnIo], and
 * [warmUp] resolves the prefs open ahead of the first read.
 *
 * **Debounce:** [save] calls are coalesced per session_id on a 500 ms
 * debounce ([DraftRepository] uses the same window for its compose-bar
 * writes, applied caller-side; here we own the debouncer because the
 * write happens from multiple call sites — initial fetch, diff fetch,
 * live-update splice). The most-recent value wins; [evict] and
 * [evictAll] cancel any pending debounced write to avoid a stale write
 * resurrecting the cache after a delete.
 *
 * **Base64-blob strip:** at write time, every entry's `images` field is
 * dropped — inline-image bytes can be > 4 KB each and the mobile already
 * lazy-fetches them via `get_session_entry(include_images=true)` when
 * the user actually scrolls to that bubble. Caching base64 in the prefs
 * file would balloon disk + each subsequent read's deserialisation cost.
 *
 * **Failure path:** if [TinkEncryptedPrefs] is unavailable
 * (keystore migration etc.) every method becomes a no-op and
 * [load] returns `null`. The caller falls through to its usual full
 * fetch path — identical pre-cache behaviour. An *unreadable keyset* is
 * recovered rather than tolerated — see [EncryptedPrefs.open].
 */
class SessionHistoryRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    /**
     * Provider for the currently-active server id. Mutable + volatile so
     * the singleton can rebind on every [get] call without leaking the
     * first caller's lambda (audit Fix B — mirrors [DraftRepository]).
     */
    @Volatile
    var activeServerProvider: () -> String? = { null }
        internal set

    private val prefs: SharedPreferences? by lazy { openPrefs() }

    /** In-flight debounced-write coroutines, keyed by session_id. */
    private val pendingWrites: MutableMap<String, Job> = mutableMapOf()
    private val pendingWritesLock = Any()

    /**
     * Last transcript read or written, keyed by its scoped prefs key. One
     * entry only: it exists so a re-open of the session the user just left
     * costs no decrypt, not as a general cache (a transcript is hundreds of
     * KB and this map would otherwise be unbounded heap).
     */
    @Volatile
    private var memo: Pair<String, CachedSessionHistory>? = null

    /** Advisory `sessionId → entry` index per server; see the class kdoc. */
    private val indexCache: MutableMap<String, MutableMap<String, HistoryIndexEntry>> = mutableMapOf()
    private val indexLock = Any()

    /**
     * Wall clock of the last started [prune] per solution id — throttle
     * state. Guarded by its own lock, NOT [indexLock]: [prune] is called
     * from Main and must never block behind an IO thread that is rebuilding
     * the index.
     */
    private val lastPruneMs: MutableMap<Long, Long> = mutableMapOf()
    private val pruneThrottleLock = Any()

    // LegacyPrefsFormat.NONE: this store's pre-Tink file was an
    // `EncryptedSharedPreferences` one, and the only reader for that shape
    // went with the androidx.security:security-crypto dependency on
    // 2026-09-08 — there is nothing left for the importer to drain.
    private fun openPrefs(): SharedPreferences? =
        EncryptedPrefs.open(context, PREFS_NAME, LegacyPrefsFormat.NONE)

    /**
     * Resolve the encrypted prefs file on [Dispatchers.IO]. Idempotent —
     * the `by lazy` delegate does the work once, so the first
     * `openSession` no longer pays the Keystore unwrap on Main.
     */
    suspend fun warmUp() {
        withContext(Dispatchers.IO) { prefs }
    }

    /**
     * Read the cached transcript for [sessionId], or null when absent /
     * corrupt. Answered from [memo] without disk I/O when this is the same
     * session that was last read or written by this process.
     */
    fun load(sessionId: String): CachedSessionHistory? {
        val key = scopedKey(sessionId) ?: return null
        memo?.let { (memoKey, value) -> if (memoKey == key) return value }
        val p = prefs ?: return null
        return runCatching {
            val raw = p.getString(key, null) ?: return@runCatching null
            val cached = JSON.decodeFromString(CachedSessionHistory.serializer(), raw)
            gateBySchema(cached)?.also { memo = key to it } ?: run {
                // Stale schema — evict so the next open does a full fetch.
                runCatching { p.edit().remove(key).apply() }
                dropFromIndex(sessionId)
                null
            }
        }.onFailure {
            Log.w(TAG, "load() failed; ignoring corrupt blob for $sessionId", it)
            // Drop the corrupt blob so the next save doesn't keep re-trying it.
            runCatching { p.edit().remove(key).apply() }
            dropFromIndex(sessionId)
        }.getOrNull()
    }

    /** [load] off the main thread — preferred from any Main-confined caller. */
    suspend fun loadOnIo(sessionId: String): CachedSessionHistory? =
        withContext(Dispatchers.IO) { load(sessionId) }

    /**
     * Schedule [history] to be persisted after the 500 ms debounce window
     * expires. A subsequent [save] for the same session id cancels the
     * pending write and re-arms the timer with the new payload.
     */
    fun save(history: CachedSessionHistory) {
        val sessionId = history.sessionId
        synchronized(pendingWritesLock) {
            pendingWrites[sessionId]?.cancel()
            pendingWrites[sessionId] = scope.launch(Dispatchers.IO) {
                delay(DEBOUNCE_MS)
                writeNow(history)
                synchronized(pendingWritesLock) {
                    pendingWrites.remove(sessionId)
                }
            }
        }
    }

    /** Drop the cache entry for [sessionId] and cancel any pending debounced write. */
    fun evict(sessionId: String) {
        synchronized(pendingWritesLock) {
            pendingWrites.remove(sessionId)?.cancel()
        }
        val p = prefs ?: return
        val key = scopedKey(sessionId) ?: return
        forgetWritten(key)
        runCatching { p.edit().remove(key).apply() }
            .onFailure { Log.w(TAG, "evict() failed", it) }
        dropFromIndex(sessionId)
    }

    /**
     * Wipe every cached transcript belonging to [serverId]. Cancels every
     * pending debounced write — a teardown across all sessions makes
     * lingering writes meaningless and dangerous (they could resurrect
     * an evicted blob).
     *
     * [serverId] is required rather than defaulted to the active server:
     * this is a destructive multi-session wipe, and a default that
     * silently became "every server" whenever no server was active is a
     * footgun. Use [evictAllServers] when that really is the intent.
     */
    fun evictAll(serverId: String) {
        cancelAllPendingWrites()
        synchronized(indexLock) { indexCache.remove(serverId) }
        synchronized(pruneThrottleLock) { lastPruneMs.clear() }
        val p = prefs ?: return
        runCatching {
            val prefix = "$KEY_PREFIX:$serverId:"
            val editor = p.edit()
            // The index is the cheap enumeration; fall back to getAll() only
            // when it has never been built for this server.
            val known = indexSnapshotOrNull(p, serverId)
            if (known != null) {
                for (sessionId in known.keys) editor.remove(prefix + sessionId)
            } else {
                for (key in p.all.keys) {
                    if (key.startsWith(prefix)) editor.remove(key)
                }
            }
            editor.remove(indexKey(serverId))
            editor.apply()
        }.onFailure { Log.w(TAG, "evictAll() failed", it) }
    }

    /** Wipe the whole cache file — every session of every paired server. */
    fun evictAllServers() {
        cancelAllPendingWrites()
        synchronized(indexLock) { indexCache.clear() }
        synchronized(pruneThrottleLock) { lastPruneMs.clear() }
        val p = prefs ?: return
        runCatching { p.edit().clear().apply() }
            .onFailure { Log.w(TAG, "evictAllServers() failed", it) }
    }

    private fun cancelAllPendingWrites() {
        synchronized(pendingWritesLock) {
            pendingWrites.values.forEach { it.cancel() }
            pendingWrites.clear()
        }
        // Clears the memo and the identical-write skip for every session.
        forgetWritten(key = null)
    }

    /**
     * Garbage-collect cache entries for sessions that no longer exist in
     * [keepSessionIds] (typically `list_sessions` result), scoped to
     * [scopeSolutionId] so a stale-cache sweep triggered by one solution's
     * refresh doesn't drop entries belonging to a different solution on
     * the same server.
     *
     * Returns immediately: the sweep runs on [Dispatchers.IO] (N-32 — it
     * used to decrypt every cached transcript on the caller's thread, and
     * the caller is Main), and is throttled to at most one pass per
     * solution per [PRUNE_MIN_INTERVAL_MS]. Pruning is opportunistic GC, so
     * a skipped pass only defers reclaiming disk.
     */
    fun prune(keepSessionIds: Set<String>, scopeSolutionId: Long) {
        val now = System.currentTimeMillis()
        synchronized(pruneThrottleLock) {
            val last = lastPruneMs[scopeSolutionId]
            if (last != null && now - last < PRUNE_MIN_INTERVAL_MS) return
            lastPruneMs[scopeSolutionId] = now
        }
        scope.launch(Dispatchers.IO) { pruneNow(keepSessionIds, scopeSolutionId) }
    }

    /**
     * Blocking body of [prune]. Exposed for callers that need the sweep to
     * have finished (tests, teardown) — it ignores the throttle.
     */
    suspend fun pruneNow(keepSessionIds: Set<String>, scopeSolutionId: Long): Unit =
        withContext(Dispatchers.IO) {
            val p = prefs ?: return@withContext
            val serverId = activeServerProvider() ?: return@withContext
            runCatching {
                val index = synchronized(indexLock) { loadIndexLocked(p, serverId) }
                val doomed = selectPrunable(index, keepSessionIds, scopeSolutionId)
                if (doomed.isEmpty()) return@runCatching
                removeSessions(p, serverId, doomed)
            }.onFailure { Log.w(TAG, "prune() failed", it) }
        }

    private fun writeNow(history: CachedSessionHistory) {
        val p = prefs ?: return
        val serverId = activeServerProvider() ?: return
        val key = "$KEY_PREFIX:$serverId:${history.sessionId}"
        // Defensive strip — most call sites already pass image-free
        // entries but appendEntries / direct save can leak them.
        // Stamp the current schema version on every write: the data-class
        // default is the legacy sentinel `1`, so without this stamp
        // `encodeDefaults = false` would drop the key and the blob would decode
        // back to `1` and fail the gate on its own next load. Because
        // CACHE_SCHEMA_VERSION (3) != the default (1), the key is persisted.
        val sanitised = history.copy(
            entries = stripImages(history.entries),
            schemaVersion = CachedSessionHistory.CACHE_SCHEMA_VERSION,
        )
        runCatching {
            val (capped, raw) = encodeCapped(sanitised)
            // Stamp the LRU *before* the identical-payload check: an open but
            // idle session keeps calling save() with the same window, and if
            // its timestamp never advanced the MAX_CACHED_SESSIONS cap would
            // pick the session the user is actually reading as its victim.
            val overflow = touchIndex(p, serverId, history.sessionId, capped.solutionId)
            // SharedPreferences rewrites the whole file per write, so
            // re-persisting an unchanged transcript is pure cost — and the
            // delta poller re-saves the same window whenever a poke carried
            // nothing new. Skip the disk touch entirely in that case: the LRU
            // stamp [touchIndex] just advanced is advisory in-memory state
            // that protects the read-but-idle session from the cap within
            // this process, and it rides out to disk on the next real write.
            val unchanged = raw == lastWrittenRaw && key == lastWrittenKey
            if (unchanged && overflow.isEmpty()) return@runCatching
            val editor = p.edit()
            if (!unchanged) editor.putString(key, raw)
            for (sessionId in overflow) {
                val doomed = "$KEY_PREFIX:$serverId:$sessionId"
                editor.remove(doomed)
                forgetWritten(doomed)
            }
            editor.putString(indexKey(serverId), encodeIndex(serverId))
            editor.apply()
            lastWrittenKey = key
            lastWrittenRaw = raw
            memo = key to capped
        }.onFailure { Log.w(TAG, "writeNow() failed for ${history.sessionId}", it) }
    }

    // --- index plumbing -------------------------------------------------

    /**
     * Remove [sessionId] from the index, on disk as well as in memory.
     * Persisting matters: a phantom entry for a blob that was already
     * deleted still counts toward [MAX_CACHED_SESSIONS], so leaving it
     * behind lets the LRU cap evict a real transcript in its place.
     */
    private fun dropFromIndex(sessionId: String) {
        val serverId = activeServerProvider() ?: return
        val p = prefs ?: return
        val payload = synchronized(indexLock) {
            val index = indexCache[serverId] ?: return
            if (index.remove(sessionId) == null) return
            JSON.encodeToString(INDEX_SERIALIZER, index.toMap())
        }
        runCatching { p.edit().putString(indexKey(serverId), payload).apply() }
            .onFailure { Log.w(TAG, "dropFromIndex() failed", it) }
    }

    /** The index for [serverId] if already materialised, else null — never rebuilds. */
    private fun indexSnapshotOrNull(prefs: SharedPreferences, serverId: String): Map<String, HistoryIndexEntry>? =
        synchronized(indexLock) {
            indexCache[serverId]?.let { return it.toMap() }
            val decoded = readPersistedIndex(prefs, serverId) ?: return null
            indexCache[serverId] = decoded.toMutableMap()
            decoded
        }

    /**
     * The index for [serverId], rebuilding it from `getAll()` when absent.
     * The rebuild is the one place that decrypts every blob; it happens once
     * per process for caches written before the index existed.
     *
     * **Caller must hold [indexLock].** Two reasons: the returned map is an
     * immutable snapshot that must be taken while no writer can mutate the
     * live map (a caller iterating it on IO while `touchIndex` mutates it on
     * another IO thread would otherwise hit `ConcurrentModificationException`),
     * and holding across the load prevents two threads racing into the same
     * full `getAll()` rebuild.
     */
    private fun loadIndexLocked(prefs: SharedPreferences, serverId: String): Map<String, HistoryIndexEntry> {
        indexCache[serverId]?.let { return it.toMap() }
        readPersistedIndex(prefs, serverId)?.let { decoded ->
            indexCache[serverId] = decoded.toMutableMap()
            return decoded
        }
        val prefix = "$KEY_PREFIX:$serverId:"
        val rebuilt = mutableMapOf<String, HistoryIndexEntry>()
        val now = System.currentTimeMillis()
        runCatching {
            for ((key, raw) in prefs.all) {
                if (!key.startsWith(prefix)) continue
                val rawStr = raw as? String ?: continue
                val cached = runCatching {
                    JSON.decodeFromString(CachedSessionHistory.serializer(), rawStr)
                }.getOrNull() ?: continue
                rebuilt[key.removePrefix(prefix)] = HistoryIndexEntry(cached.solutionId, now)
            }
        }.onFailure { Log.w(TAG, "index rebuild failed", it) }
        indexCache[serverId] = rebuilt
        runCatching {
            prefs.edit().putString(indexKey(serverId), JSON.encodeToString(INDEX_SERIALIZER, rebuilt)).apply()
        }
        return rebuilt.toMap()
    }

    private fun readPersistedIndex(prefs: SharedPreferences, serverId: String): Map<String, HistoryIndexEntry>? {
        val raw = runCatching { prefs.getString(indexKey(serverId), null) }.getOrNull() ?: return null
        return runCatching { JSON.decodeFromString(INDEX_SERIALIZER, raw) }.getOrNull()
    }

    /**
     * Record a write of [sessionId] and return the session ids evicted by
     * the [MAX_CACHED_SESSIONS] cap (least-recently-written first).
     */
    private fun touchIndex(
        prefs: SharedPreferences,
        serverId: String,
        sessionId: String,
        solutionId: Long,
    ): List<String> = synchronized(indexLock) {
        loadIndexLocked(prefs, serverId)
        val index = indexCache.getOrPut(serverId) { mutableMapOf() }
        index[sessionId] = HistoryIndexEntry(solutionId, System.currentTimeMillis())
        val overflow = selectSessionsOverCap(index, MAX_CACHED_SESSIONS)
        for (id in overflow) index.remove(id)
        overflow
    }

    private fun encodeIndex(serverId: String): String {
        val snapshot = synchronized(indexLock) { indexCache[serverId]?.toMap() ?: emptyMap() }
        return JSON.encodeToString(INDEX_SERIALIZER, snapshot)
    }

    private fun removeSessions(prefs: SharedPreferences, serverId: String, sessionIds: Collection<String>) {
        val editor = prefs.edit()
        for (sessionId in sessionIds) {
            // Cancel any pending write for this session so we don't
            // resurrect the entry we're about to drop.
            synchronized(pendingWritesLock) { pendingWrites.remove(sessionId)?.cancel() }
            val key = "$KEY_PREFIX:$serverId:$sessionId"
            editor.remove(key)
            forgetWritten(key)
        }
        synchronized(indexLock) {
            indexCache[serverId]?.let { index -> for (id in sessionIds) index.remove(id) }
        }
        editor.putString(indexKey(serverId), encodeIndex(serverId))
        editor.apply()
    }

    private fun scopedKey(sessionId: String): String? {
        val serverId = activeServerProvider() ?: return null
        return "$KEY_PREFIX:$serverId:$sessionId"
    }

    /** Payload of the last successful [writeNow], for the identical-write skip. */
    @Volatile
    private var lastWrittenKey: String? = null

    @Volatile
    private var lastWrittenRaw: String? = null

    /**
     * Forget the memo and the identical-write skip for [key] (everything
     * when [key] is null). Every path that removes a blob from disk must
     * call this: otherwise a later `save()` of byte-identical content is
     * skipped as "already on disk" and the entry silently stays deleted.
     */
    private fun forgetWritten(key: String?) {
        if (key == null) {
            memo = null
            lastWrittenKey = null
            lastWrittenRaw = null
            return
        }
        memo?.let { (memoKey, _) -> if (memoKey == key) memo = null }
        if (lastWrittenKey == key) {
            lastWrittenKey = null
            lastWrittenRaw = null
        }
    }

    companion object {
        private const val TAG = "SessionHistoryRepo"

        /** Prefs-file name; also the [PersistenceHealth] identifier for this store. */
        internal const val PREFS_NAME = "spk_history_cache"
        private const val KEY_PREFIX = "history-v1"
        private const val INDEX_KEY_PREFIX = "history-index-v1"
        private const val DEBOUNCE_MS = 500L

        /** At most one [prune] sweep per solution in this window. */
        internal const val PRUNE_MIN_INTERVAL_MS = 60_000L

        /** Newest entries kept per cached transcript. The cache is a tail window. */
        internal const val MAX_CACHED_ENTRIES = 200

        /** Hard ceiling on one serialised transcript, before encryption. */
        internal const val MAX_BLOB_BYTES = 128 * 1024

        /** Transcripts kept per server; the least-recently-written are evicted. */
        internal const val MAX_CACHED_SESSIONS = 24

        private fun indexKey(serverId: String) = "$INDEX_KEY_PREFIX:$serverId"

        /**
         * Returns [cached] unchanged when its [CachedSessionHistory.schemaVersion] matches
         * [CachedSessionHistory.CACHE_SCHEMA_VERSION], or `null` for any older schema so
         * the caller can evict the stale blob and fall through to a full `get_session` fetch.
         */
        internal fun gateBySchema(cached: CachedSessionHistory?): CachedSessionHistory? {
            cached ?: return null
            return if (cached.schemaVersion == CachedSessionHistory.CACHE_SCHEMA_VERSION) cached else null
        }

        /**
         * Strip oversized inline-image base64 blobs from cached entries.
         * Threshold = 4 KB — chunks below that (200-char previews etc.)
         * pass through; anything larger gets dropped. The mobile UI
         * lazy-fetches the full image via `get_session_entry(include_images=true)`
         * when the bubble actually renders, so the cache layer never
         * needed the bytes in the first place.
         */
        internal fun stripImages(entries: List<EntrySummary>): List<EntrySummary> =
            entries.map { entry ->
                val images = entry.images ?: return@map entry
                val keep = images.filter { it.dataBase64.length <= IMAGE_INLINE_THRESHOLD_BYTES }
                if (keep.size == images.size) entry else entry.copy(images = keep.ifEmpty { null })
            }

        internal const val IMAGE_INLINE_THRESHOLD_BYTES: Int = 4 * 1024

        /**
         * Serialise [history] under the per-session caps, dropping entries
         * from the **head** until both [maxEntries] and [maxBytes] hold.
         *
         * Head-first because the cache is the tail window the chat surface
         * renders on reopen: `lastIndex` (and therefore the `after_index`
         * diff cursor) is the max index in the window and survives the trim,
         * while anything older is paged back in from the server on scroll.
         * At least one entry is always kept so a single oversized entry
         * degrades to "one big blob" rather than an empty, cursor-less cache.
         */
        internal fun encodeCapped(
            history: CachedSessionHistory,
            maxEntries: Int = MAX_CACHED_ENTRIES,
            maxBytes: Int = MAX_BLOB_BYTES,
        ): Pair<CachedSessionHistory, String> {
            var current = if (history.entries.size > maxEntries) {
                history.copy(entries = history.entries.takeLast(maxEntries))
            } else {
                history
            }
            var raw = JSON.encodeToString(CachedSessionHistory.serializer(), current)
            while (raw.length > maxBytes && current.entries.size > 1) {
                // Halve, then converge — a transcript of 200 large entries
                // would otherwise re-encode 200 times.
                val keep = maxOf(1, current.entries.size / 2)
                current = current.copy(entries = current.entries.takeLast(keep))
                raw = JSON.encodeToString(CachedSessionHistory.serializer(), current)
            }
            return current to raw
        }

        /**
         * Session ids in [index] that belong to [scopeSolutionId] and are no
         * longer listed in [keepSessionIds] — the [prune] removal set.
         */
        internal fun selectPrunable(
            index: Map<String, HistoryIndexEntry>,
            keepSessionIds: Set<String>,
            scopeSolutionId: Long,
        ): List<String> = index.entries
            .filter { (sessionId, entry) ->
                entry.solutionId == scopeSolutionId && sessionId !in keepSessionIds
            }
            .map { it.key }

        /**
         * Session ids to evict so [index] holds at most [max] transcripts,
         * least-recently-written first.
         */
        internal fun selectSessionsOverCap(
            index: Map<String, HistoryIndexEntry>,
            max: Int = MAX_CACHED_SESSIONS,
        ): List<String> {
            if (index.size <= max) return emptyList()
            return index.entries
                .sortedBy { it.value.updatedAtMs }
                .take(index.size - max)
                .map { it.key }
        }

        private val JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        private val INDEX_SERIALIZER =
            MapSerializer(String.serializer(), HistoryIndexEntry.serializer())

        /**
         * Deliberately NOT [SingletonHolder]: this is the one repository
         * whose constructor takes more than a [Context] — it needs the
         * [CoroutineScope] that owns its debounced writer — and the holder
         * builds its instance from a context alone. Widening the helper
         * with an optional second argument for a single call site would
         * cost more than the twelve lines it saved. The rules are the same
         * ones the helper documents: create once under the monitor, rebind
         * [activeServerProvider] on every call.
         *
         * Note that only the **first** caller's [scope] is used, by
         * construction — the writer it starts outlives any one ViewModel,
         * which is the same reason this is a singleton in the first place.
         */
        private var instance: SessionHistoryRepository? = null

        fun get(
            context: Context,
            scope: CoroutineScope,
            activeServerProvider: () -> String?,
        ): SessionHistoryRepository = synchronized(this) {
            val store = instance
                ?: SessionHistoryRepository(context.applicationContext, scope).also { instance = it }
            store.activeServerProvider = activeServerProvider
            store
        }
    }
}
