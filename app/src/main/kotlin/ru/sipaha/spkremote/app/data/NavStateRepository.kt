package ru.sipaha.spkremote.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit

/**
 * Persists the active navigation route so cold start can land the user
 * back where they were (R-6d).
 *
 * **R-6c-multi per-server scoping:** the route key embeds the active
 * server id (`route:<serverId>`), so switching from server A → server B
 * doesn't carry over A's deep-link target. The cold-start replay reads
 * the route for whichever server is being auto-resumed.
 *
 * **Why disk:** R-6b's auto-resume lands the user on `solutions` after
 * a successful pairing replay, which is correct when they last had the
 * solutions list open — but jarring when they were mid-chat on a deep
 * route like `solutions/{id}/sessions/{sid}`. Persisting the most-recent
 * resolved route closes that loop.
 *
 * **Route format:** the saved route string is the *resolved* navigation
 * route with arguments substituted (e.g. `solutions/abc/sessions/xyz`),
 * not the template (`solutions/{solutionId}/sessions/{sessionId}`). The
 * caller in `AppNavGraph` builds the resolved form from the
 * `NavBackStackEntry`'s arguments before saving — see [saveRoute] kdoc.
 *
 * **Validation on resume:** the cold-start path in `MainActivity` reads
 * the saved route via [loadRoute], hands it to the nav controller, and
 * — if the resulting destination 404s on lookup (e.g. the user deleted
 * the session from the desktop in the meantime) — falls back to
 * `solutions` and clears the saved route. We do NOT pre-validate
 * (session_exists round-trip before navigating) because the validation
 * happens naturally inside the screen anyway: SessionDetailScreen calls
 * `openSession` which exercises `get_session`, and a 404 there shows
 * the error state and the user pops back to a working route.
 *
 * **Storage:** plain [SharedPreferences]. The route string is purely
 * cosmetic (no auth secret), and encrypting it would slow cold-start
 * unnecessarily.
 */
class NavStateRepository(
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
    }.onFailure { Log.w(TAG, "openPrefs() failed; nav state won't persist", it) }
        .getOrNull()

    /**
     * Persist [route] as the last-known nav destination for the active
     * server. Routes shallower than `solutions` (i.e. `pairing` /
     * `connecting` / `servers`) are deliberately NOT saved — those
     * screens already auto-resolve on cold start from the
     * pairing-replay path, and persisting them would cause harmless
     * but ugly thrash where the nav controller "navigates" to itself.
     */
    fun saveRoute(route: String) {
        if (route.isBlank()) return
        if (route == "pairing" || route == "connecting" || route == "servers") return
        val p = prefs ?: return
        val key = scopedKey() ?: return
        runCatching { p.edit {putString(key, route)} }
            .onFailure { Log.w(TAG, "saveRoute() failed", it) }
    }

    /**
     * The most-recently-saved route for the active server, or `null`
     * if none. Falls back to the legacy R-6d unscoped key when no
     * scoped value exists — gives the upgrade path one free resume
     * before being overwritten by the next [saveRoute].
     *
     * **F1 unified-workspace migration:** legacy `solutions/...` routes
     * are rewritten to their `workspace/...` equivalents and persisted in
     * place. After F1 the nav graph no longer registers any
     * `solutions/...` destinations, so handing the controller a legacy
     * string would deep-link into a 404 and clear the saved state. The
     * migration is one-shot per stored value — once rewritten, the next
     * [loadRoute] sees the already-migrated form and returns it as-is.
     */
    fun loadRoute(): String? {
        val p = prefs ?: return null
        val key = scopedKey() ?: return null
        val scoped = runCatching { p.getString(key, null) }
            .onFailure { Log.w(TAG, "loadRoute() failed", it) }
            .getOrNull()
        val raw = scoped
            ?: runCatching { p.getString(LEGACY_KEY_ROUTE, null) }.getOrNull()
            ?: return null
        val migrated = migrate(raw)
        if (migrated != raw) saveRoute(migrated)
        return migrated
    }

    /**
     * Rewrite legacy `solutions/...` route strings into the unified
     * `workspace/...` graph introduced by the unified-open-workspace
     * feature (F1). Pass-through for any string that already targets
     * `workspace/...` or any non-`solutions/...` route.
     *
     * Mapping:
     *   - `solutions`                              → `workspace`
     *   - `solutions/{id}`                         → `workspace`
     *   - `solutions/{id}/projects`                → `workspace/solutions/{id}/projects`
     *   - `solutions/{id}/sessions/{sid}`          → `workspace/sessions/{sid}`
     */
    private fun migrate(raw: String): String {
        val sessionMatch = SESSION_RE.find(raw)
        if (sessionMatch != null) {
            return "workspace/sessions/${sessionMatch.groupValues[1]}"
        }
        val projectsMatch = PROJECTS_RE.find(raw)
        if (projectsMatch != null) {
            return "workspace/solutions/${projectsMatch.groupValues[1]}/projects"
        }
        if (raw == "solutions" || SOLUTION_RE.matches(raw)) {
            return "workspace"
        }
        return raw
    }

    /**
     * Drop the saved route for the active server. Called when the resume
     * target 404s. Also clears the legacy R-6d unscoped key so a follow-up
     * cold-start doesn't fall back into it.
     */
    fun clear() {
        clearFor(activeServerProvider())
    }

    /**
     * Explicit-scope variant of [clear]. Mirrors the same rationale as
     * `DraftRepository.clearAllFor` — callers in `removeServer` can wipe
     * a non-active server's saved route without the temporary
     * `_activeServerId` mutation trick.
     *
     * Also clears the legacy R-6d unscoped key so a follow-up cold-start
     * doesn't fall back into it.
     */
    fun clearFor(serverId: String?) {
        val p = prefs ?: return
        runCatching {
            p.edit {
                if (serverId != null) remove("route:$serverId")
                remove(LEGACY_KEY_ROUTE)
            }
        }.onFailure { Log.w(TAG, "clearFor() failed", it) }
    }

    /**
     * Wipe every saved route across every server (and the legacy
     * unscoped key). Used by `forgetAllServers`.
     */
    fun clearAllServers() {
        val p = prefs ?: return
        runCatching { p.edit {clear()} }
            .onFailure { Log.w(TAG, "clearAllServers() failed", it) }
    }

    private fun scopedKey(): String? {
        val serverId = activeServerProvider() ?: return null
        return "route:$serverId"
    }

    companion object {
        private const val TAG = "NavStateRepository"
        private const val PREFS_NAME = "spk_nav_state"
        private const val LEGACY_KEY_ROUTE = "route"

        private val SESSION_RE = Regex("""^solutions/[^/]+/sessions/(.+)$""")
        private val PROJECTS_RE = Regex("""^solutions/([^/]+)/projects$""")
        private val SOLUTION_RE = Regex("""^solutions/[^/]+$""")

        @Volatile
        private var instance: NavStateRepository? = null

        fun get(context: Context, activeServerProvider: () -> String?): NavStateRepository =
            synchronized(this) {
                val store = instance
                    ?: NavStateRepository(context.applicationContext).also { instance = it }
                store.activeServerProvider = activeServerProvider
                store
            }
    }
}
