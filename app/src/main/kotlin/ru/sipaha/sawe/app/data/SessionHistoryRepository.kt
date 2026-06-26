package ru.sipaha.sawe.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import ru.sipaha.sawe.core.EntrySummary

/**
 * Encrypted-on-disk per-session transcript cache. Lets
 * `SessionDetailStore.openSession` render the chat surface from disk
 * immediately and then ask the server only for the diff via
 * `get_session(after_index=cache.lastIndex)`.
 *
 * **Storage:** one [EncryptedSharedPreferences] file (`spk_history_cache`)
 * with per-(server, session) keys `history-v1:<serverId>:<sessionId>`,
 * each holding a JSON-serialised [CachedSessionHistory]. Mirrors
 * [EncryptedQueueStore]'s "single prefs file, prefixed keys" pattern —
 * the plan-doc's per-server filename remark was aspirational; the
 * codebase precedent is one file, prefixed keys.
 *
 * **Why encrypted:** transcripts contain user content (source code, work
 * in progress, file paths, model output that quotes the same). Same
 * threat model as [EncryptedQueueStore] / the pairing URL — adversary
 * with file-system access on an unlocked device shouldn't recover the
 * conversation history.
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
 * **Failure path:** if [EncryptedSharedPreferences] is unavailable
 * (keystore migration etc.) every method becomes a no-op and
 * [load] returns `null`. The caller falls through to its usual full
 * fetch path — identical pre-cache behaviour.
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
        Log.w(TAG, "EncryptedSharedPreferences unavailable; history cache disabled", it)
    }.getOrNull()

    /** Read the cached transcript for [sessionId], or null when absent / corrupt. */
    fun load(sessionId: String): CachedSessionHistory? {
        val p = prefs ?: return null
        val key = scopedKey(sessionId) ?: return null
        return runCatching {
            val raw = p.getString(key, null) ?: return@runCatching null
            val cached = JSON.decodeFromString(CachedSessionHistory.serializer(), raw)
            gateBySchema(cached) ?: run {
                // Stale schema — evict so the next open does a full fetch.
                runCatching { p.edit().remove(key).apply() }
                null
            }
        }.onFailure {
            Log.w(TAG, "load() failed; ignoring corrupt blob for $sessionId", it)
            // Drop the corrupt blob so the next save doesn't keep re-trying it.
            runCatching { p.edit().remove(key).apply() }
        }.getOrNull()
    }

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

    /**
     * Merge [newEntries] into the cached transcript for [sessionId] +
     * persist. Convenience used by the live-update path
     * (`agent_session_message_appended` splice) so the caller doesn't
     * have to round-trip through `load` itself.
     *
     * When no cache exists for [sessionId], no write is performed — a
     * partial transcript without the prior history is a worse cache
     * than no cache (it would defeat gap-detection on the next open).
     */
    fun appendEntries(
        sessionId: String,
        solutionId: String,
        agentId: String,
        newEntries: List<EntrySummary>,
        newTotalCount: Int,
    ) {
        if (newEntries.isEmpty()) return
        val existing = load(sessionId) ?: return
        val merged = existing.entries + newEntries
        val newLastIndex = merged.mapNotNull { it.index.takeIf { i -> i >= 0 } }.maxOrNull()
        save(
            CachedSessionHistory(
                sessionId = sessionId,
                solutionId = solutionId,
                agentId = agentId,
                entries = stripImages(merged),
                lastIndex = newLastIndex,
                totalCountAtLastWrite = newTotalCount,
            ),
        )
    }

    /** Drop the cache entry for [sessionId] and cancel any pending debounced write. */
    fun evict(sessionId: String) {
        synchronized(pendingWritesLock) {
            pendingWrites.remove(sessionId)?.cancel()
        }
        val p = prefs ?: return
        val key = scopedKey(sessionId) ?: return
        runCatching { p.edit().remove(key).apply() }
            .onFailure { Log.w(TAG, "evict() failed", it) }
    }

    /**
     * Wipe every cached transcript belonging to [serverId] (the
     * currently-active server when [serverId] is null). Cancels every
     * pending debounced write — a teardown across all sessions makes
     * lingering writes meaningless and dangerous (they could resurrect
     * an evicted blob).
     */
    fun evictAll(serverId: String? = activeServerProvider()) {
        synchronized(pendingWritesLock) {
            pendingWrites.values.forEach { it.cancel() }
            pendingWrites.clear()
        }
        val p = prefs ?: return
        runCatching {
            if (serverId == null) {
                p.edit().clear().apply()
                return@runCatching
            }
            val prefix = "$KEY_PREFIX:$serverId:"
            val editor = p.edit()
            for (key in p.all.keys) {
                if (key.startsWith(prefix)) editor.remove(key)
            }
            editor.apply()
        }.onFailure { Log.w(TAG, "evictAll() failed", it) }
    }

    /**
     * Garbage-collect cache entries for sessions that no longer exist in
     * [keepSessionIds] (typically `list_sessions` result), scoped to
     * [scopeSolutionId] so a stale-cache sweep triggered by one solution's
     * refresh doesn't drop entries belonging to a different solution on
     * the same server.
     */
    fun prune(keepSessionIds: Set<String>, scopeSolutionId: String) {
        val p = prefs ?: return
        val serverId = activeServerProvider() ?: return
        val prefix = "$KEY_PREFIX:$serverId:"
        runCatching {
            val editor = p.edit()
            var changed = false
            for ((key, raw) in p.all) {
                if (!key.startsWith(prefix)) continue
                val sessionId = key.removePrefix(prefix)
                if (keepSessionIds.contains(sessionId)) continue
                val rawStr = raw as? String ?: continue
                val cached = runCatching {
                    JSON.decodeFromString(CachedSessionHistory.serializer(), rawStr)
                }.getOrNull() ?: continue
                if (cached.solutionId != scopeSolutionId) continue
                // Cancel any pending write for this session so we don't
                // resurrect the entry we're about to drop.
                synchronized(pendingWritesLock) {
                    pendingWrites.remove(sessionId)?.cancel()
                }
                editor.remove(key)
                changed = true
            }
            if (changed) editor.apply()
        }.onFailure { Log.w(TAG, "prune() failed", it) }
    }

    private fun writeNow(history: CachedSessionHistory) {
        val p = prefs ?: return
        val key = scopedKey(history.sessionId) ?: return
        // Defensive strip — most call sites already pass image-free
        // entries but appendEntries / direct save can leak them.
        val sanitised = history.copy(entries = stripImages(history.entries))
        runCatching {
            val raw = JSON.encodeToString(CachedSessionHistory.serializer(), sanitised)
            p.edit().putString(key, raw).apply()
        }.onFailure { Log.w(TAG, "writeNow() failed for ${history.sessionId}", it) }
    }

    private fun scopedKey(sessionId: String): String? {
        val serverId = activeServerProvider() ?: return null
        return "$KEY_PREFIX:$serverId:$sessionId"
    }

    companion object {
        private const val TAG = "SessionHistoryRepo"
        private const val PREFS_NAME = "spk_history_cache"
        private const val KEY_PREFIX = "history-v1"
        private const val DEBOUNCE_MS = 500L

        /**
         * Strip oversized inline-image base64 blobs from cached entries.
         * Threshold = 4 KB — chunks below that (200-char previews etc.)
         * pass through; anything larger gets dropped. The mobile UI
         * lazy-fetches the full image via `get_session_entry(include_images=true)`
         * when the bubble actually renders, so the cache layer never
         * needed the bytes in the first place.
         */
        /**
         * Returns [cached] unchanged when its [CachedSessionHistory.schemaVersion] matches
         * [CachedSessionHistory.CACHE_SCHEMA_VERSION], or `null` for any older schema so
         * the caller can evict the stale blob and fall through to a full `get_session` fetch.
         */
        internal fun gateBySchema(cached: CachedSessionHistory?): CachedSessionHistory? {
            cached ?: return null
            return if (cached.schemaVersion == CachedSessionHistory.CACHE_SCHEMA_VERSION) cached else null
        }

        internal fun stripImages(entries: List<EntrySummary>): List<EntrySummary> =
            entries.map { entry ->
                val images = entry.images ?: return@map entry
                val keep = images.filter { it.dataBase64.length <= IMAGE_INLINE_THRESHOLD_BYTES }
                if (keep.size == images.size) entry else entry.copy(images = keep.ifEmpty { null })
            }

        internal const val IMAGE_INLINE_THRESHOLD_BYTES: Int = 4 * 1024

        private val JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        @Volatile
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
