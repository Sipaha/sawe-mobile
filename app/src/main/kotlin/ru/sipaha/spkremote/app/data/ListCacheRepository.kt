package ru.sipaha.spkremote.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import ru.sipaha.spkremote.core.SessionSummary
import ru.sipaha.spkremote.core.SolutionSummary

/**
 * Disk cache for the two read-only-on-the-mobile lists that drive the
 * navigation surface: per-server **solutions** and per-(server, solution)
 * **sessions**.
 *
 * **Why cache:** the user needs to navigate solutions/sessions even when
 * the WebSocket is down (commute / weak Wi-Fi / desktop sleeping). The
 * live `solutions.list` / `solution_agent.list_sessions` calls already
 * fail through `_solutions.value = UiData.Error(...)` once the 30 s
 * timeout fires — that wiped the last-known list off the screen and
 * blocked the user from tapping into a session whose chat surface they
 * still wanted to type a queued reply into. With this cache, the lists
 * survive disconnects: a fresh response overwrites; a failed refresh
 * leaves the cached entries in place.
 *
 * **Storage:** plain [SharedPreferences]. The lists contain server-shaped
 * metadata (ids, display names, member paths, session titles) — not
 * pairing secrets or anything we'd encrypt at rest. Same reasoning as
 * [DraftRepository]; the encrypted-prefs flow is reserved for
 * authoritative session ids and pairing URLs.
 *
 * **Per-server scoping:** every key embeds the active server id (resolved
 * via [activeServerProvider] on each call), so two paired servers with
 * overlapping solution ids never trample each other's cache.
 *
 * **Format:** kotlinx.serialization JSON of `List<SolutionSummary>` /
 * `List<SessionSummary>`. The DTOs already declare every snake_case
 * `SerialName` we need for wire compatibility; reusing them keeps the
 * cache format identical to what came off the wire, so a future schema
 * change on the server side is a single edit-point.
 */
class ListCacheRepository(
    private val context: Context,
) {
    /**
     * Provider for the currently-active server id. Rebound on every
     * [get] call — see audit Fix B / [DraftRepository.activeServerProvider].
     */
    @Volatile
    var activeServerProvider: () -> String? = { null }
        internal set

    private val prefs: SharedPreferences? by lazy { openPrefs() }

    private fun openPrefs(): SharedPreferences? = runCatching {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }.onFailure { Log.w(TAG, "openPrefs() failed; list cache disabled", it) }
        .getOrNull()

    fun loadSolutions(): List<SolutionSummary>? {
        val p = prefs ?: return null
        val key = solutionsKey() ?: return null
        return runCatching {
            val raw = p.getString(key, null) ?: return@runCatching null
            json.decodeFromString(ListSerializer(SolutionSummary.serializer()), raw)
        }.onFailure { Log.w(TAG, "loadSolutions() failed; ignoring corrupt blob", it) }
            .getOrNull()
    }

    fun saveSolutions(list: List<SolutionSummary>) {
        val p = prefs ?: return
        val key = solutionsKey() ?: return
        runCatching {
            val encoded = json.encodeToString(ListSerializer(SolutionSummary.serializer()), list)
            p.edit().putString(key, encoded).apply()
        }.onFailure { Log.w(TAG, "saveSolutions() failed", it) }
    }

    fun loadSessions(solutionId: String): List<SessionSummary>? {
        val p = prefs ?: return null
        val key = sessionsKey(solutionId) ?: return null
        return runCatching {
            val raw = p.getString(key, null) ?: return@runCatching null
            json.decodeFromString(ListSerializer(SessionSummary.serializer()), raw)
        }.onFailure { Log.w(TAG, "loadSessions() failed; ignoring corrupt blob", it) }
            .getOrNull()
    }

    fun saveSessions(solutionId: String, list: List<SessionSummary>) {
        val p = prefs ?: return
        val key = sessionsKey(solutionId) ?: return
        runCatching {
            val encoded = json.encodeToString(ListSerializer(SessionSummary.serializer()), list)
            p.edit().putString(key, encoded).apply()
        }.onFailure { Log.w(TAG, "saveSessions() failed", it) }
    }

    /**
     * Wipe every cached list belonging to the currently-active server.
     * Called from `removeServer` so the next pairing of a different
     * server with overlapping ids doesn't surface stale entries.
     */
    fun clearAll() {
        clearAllFor(activeServerProvider())
    }

    /**
     * Explicit-scope variant of [clearAll] — used by
     * `MainViewModel.removeServer` when wiping the list cache of a
     * non-active server. Mirrors the same intent as
     * `DraftRepository.clearAllFor`.
     */
    fun clearAllFor(serverId: String?) {
        val p = prefs ?: return
        runCatching {
            if (serverId == null) {
                p.edit().clear().apply()
                return@runCatching
            }
            val solutionsPrefix = "solutions:$serverId"
            val sessionsPrefix = "sessions:$serverId:"
            val editor = p.edit()
            for (key in p.all.keys) {
                if (key == solutionsPrefix || key.startsWith(sessionsPrefix)) {
                    editor.remove(key)
                }
            }
            editor.apply()
        }.onFailure { Log.w(TAG, "clearAllFor() failed", it) }
    }

    fun clearAllServers() {
        val p = prefs ?: return
        runCatching { p.edit().clear().apply() }
            .onFailure { Log.w(TAG, "clearAllServers() failed", it) }
    }

    private fun solutionsKey(): String? {
        val serverId = activeServerProvider() ?: return null
        return "solutions:$serverId"
    }

    private fun sessionsKey(solutionId: String): String? {
        val serverId = activeServerProvider() ?: return null
        return "sessions:$serverId:$solutionId"
    }

    companion object {
        private const val TAG = "ListCacheRepository"
        private const val PREFS_NAME = "spk_list_cache"

        // ignoreUnknownKeys keeps an older cache blob readable after a
        // future server-side schema addition; a model expansion shouldn't
        // require a one-time cache wipe on every paired device.
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        @Volatile
        private var instance: ListCacheRepository? = null

        fun get(context: Context, activeServerProvider: () -> String?): ListCacheRepository =
            synchronized(this) {
                val store = instance
                    ?: ListCacheRepository(context.applicationContext).also { instance = it }
                store.activeServerProvider = activeServerProvider
                store
            }
    }
}
