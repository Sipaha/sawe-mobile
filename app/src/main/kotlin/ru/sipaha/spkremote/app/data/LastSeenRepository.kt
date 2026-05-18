package ru.sipaha.spkremote.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Persists per-session "last seen entry index" markers across process
 * death (R-6d).
 *
 * The marker tracks the highest `agent_session_message_appended` index
 * we've rendered for each session, so on a cold start the app can decide
 * whether to do a full `get_session` refetch (the only path implemented
 * today) or — once R-6e lands the incremental-resume RPC — fetch just
 * the entries past the marker.
 *
 * **R-6c-multi per-server scoping:** every marker key is prefixed with
 * `<serverId>:` so two paired servers with overlapping session ids don't
 * step on each other's marker. The active server is read on every call
 * via [activeServerProvider].
 *
 * **Why disk:** without persistence, every restart resets the marker
 * to "we know nothing" and forces a full transcript refetch, which on a
 * long session (hundreds of entries) is bandwidth-happy and visibly
 * laggy. Persisting the marker reduces the average reconnect refetch
 * to "everything since `lastSeen + 1`" — small even for active sessions.
 *
 * **Storage:** plain [SharedPreferences]. The marker is an integer index,
 * not user content — no encryption value-add.
 */
class LastSeenRepository(
    private val context: Context,
) {

    /**
     * Provider for the currently-active server id. Rebound on every
     * [get] call — see audit Fix B / [DraftRepository.activeServerProvider]
     * for the rationale.
     */
    @Volatile
    var activeServerProvider: () -> String? = { null }
        internal set

    private val prefs: SharedPreferences? by lazy { openPrefs() }

    private fun openPrefs(): SharedPreferences? = runCatching {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }.onFailure { Log.w(TAG, "openPrefs() failed; last-seen markers won't persist", it) }
        .getOrNull()

    /** Returns the persisted marker for [sessionId], or `null` if absent. */
    fun get(sessionId: String): Int? {
        val p = prefs ?: return null
        val key = scopedKey(sessionId) ?: return null
        return runCatching {
            // SharedPreferences.getInt requires a default — sentinel -1
            // disambiguates "absent" from "0" since entry indices are
            // 0-based and 0 is a legitimate marker after the first append.
            val v = p.getInt(key, SENTINEL)
            if (v == SENTINEL) null else v
        }.onFailure { Log.w(TAG, "get() failed", it) }
            .getOrNull()
    }

    /**
     * Persist [index] as the new high-water mark for [sessionId], but
     * only if it's strictly greater than what's already stored. This
     * monotonic discipline matches `MainViewModel`'s existing
     * lastSeenEntryIndex semantics — out-of-order frames mustn't roll
     * the marker backwards.
     */
    fun set(sessionId: String, index: Int) {
        val p = prefs ?: return
        val key = scopedKey(sessionId) ?: return
        val current = runCatching { p.getInt(key, SENTINEL) }.getOrDefault(SENTINEL)
        if (current != SENTINEL && index <= current) return
        runCatching { p.edit().putInt(key, index).apply() }
            .onFailure { Log.w(TAG, "set() failed", it) }
    }

    /** Drop the marker for [sessionId] — used when a session is deleted. */
    fun clear(sessionId: String) {
        val p = prefs ?: return
        val key = scopedKey(sessionId) ?: return
        runCatching { p.edit().remove(key).apply() }
            .onFailure { Log.w(TAG, "clear() failed", it) }
    }

    /**
     * Wipe every marker belonging to the currently-active server.
     * Called when that server is removed via the Servers screen
     * (R-6c-multi).
     *
     * If no server is active (all paired servers removed) this falls
     * back to clearing the whole prefs file — equivalent to the R-6d
     * forget-pairing behavior.
     */
    fun clearAll() {
        clearAllFor(activeServerProvider())
    }

    /**
     * Explicit-scope variant of [clearAll] — used by
     * `MainViewModel.removeServer` when wiping markers for a server that
     * isn't currently active. Avoids briefly mutating
     * `MainViewModel._activeServerId` to coerce the provider, which would
     * leak through the StateFlow to Compose observers.
     */
    fun clearAllFor(serverId: String?) {
        val p = prefs ?: return
        runCatching {
            if (serverId == null) {
                p.edit().clear().apply()
                return@runCatching
            }
            val prefix = "$serverId:"
            val editor = p.edit()
            for (key in p.all.keys) {
                if (key.startsWith(prefix)) editor.remove(key)
            }
            editor.apply()
        }.onFailure { Log.w(TAG, "clearAllFor() failed", it) }
    }

    /** Wipe every marker regardless of server. Used by `forgetAllServers`. */
    fun clearAllServers() {
        val p = prefs ?: return
        runCatching { p.edit().clear().apply() }
            .onFailure { Log.w(TAG, "clearAllServers() failed", it) }
    }

    private fun scopedKey(sessionId: String): String? {
        val serverId = activeServerProvider() ?: return null
        return "$serverId:$sessionId"
    }

    companion object {
        private const val TAG = "LastSeenRepository"
        private const val PREFS_NAME = "spk_last_seen"
        private const val SENTINEL = Int.MIN_VALUE

        @Volatile
        private var instance: LastSeenRepository? = null

        fun get(context: Context, activeServerProvider: () -> String?): LastSeenRepository =
            synchronized(this) {
                val store = instance
                    ?: LastSeenRepository(context.applicationContext).also { instance = it }
                store.activeServerProvider = activeServerProvider
                store
            }
    }
}
