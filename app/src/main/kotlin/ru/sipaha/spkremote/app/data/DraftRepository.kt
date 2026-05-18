package ru.sipaha.spkremote.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Persists per-session compose-bar drafts across process death (R-6d).
 *
 * **Two channels, one prefs file:**
 *   - `draft:<serverId>:<sessionId>` — the live typing buffer. Saved on
 *     every keystroke (debounced 500 ms by the caller), cleared after a
 *     successful send.
 *   - `bounced:<serverId>:<sessionId>` — the read-once recovery slot for
 *     TTL-expired or terminally-failed messages. Written by
 *     [ru.sipaha.spkremote.app.vm.MainViewModel.handleExpiredMessage] when
 *     [ru.sipaha.spkremote.core.RemoteClient.onMessageExpired] fires. Read
 *     once via [bouncedFor] when the user next opens the session, which
 *     also clears the slot — so the bounce surfaces exactly once per
 *     expiry event.
 *
 * **R-6c-multi per-server scoping:** every key now embeds the active
 * server id (provided via [activeServerProvider]). Same session id on
 * two paired servers no longer collide. The provider is read on every
 * call rather than captured at construction, so a `switchToServer` in
 * [MainViewModel] is reflected immediately in subsequent
 * [save] / [load] / [bouncedFor].
 *
 * **Storage:** plain [SharedPreferences] (not encrypted). Drafts aren't
 * secrets in the threat model — the server has the same text the moment
 * Send is tapped, and the encryption overhead would just bloat startup
 * time. The encrypted-prefs flow is reserved for the pairing URL (which
 * embeds the HMAC secret) and the queued-messages blob (which carries
 * authoritative session ids).
 *
 * **Lifecycle:** singleton tied to `applicationContext`. Same rationale
 * as [PairingRepository] — opening a SharedPreferences file is cheap,
 * but the JVM-level instance also serves as a process-wide synchronization
 * point if multiple ViewModels ever share a draft (today, only [MainViewModel]).
 */
class DraftRepository(
    private val context: Context,
) {

    /**
     * Provider for the currently-active server id. Mutable + volatile so
     * the singleton can rebind on every [get] call without leaking the
     * first caller's lambda for the lifetime of the JVM (audit Fix B).
     * Reads in scoped operations consult the current value each time.
     */
    @Volatile
    var activeServerProvider: () -> String? = { null }
        internal set

    private val prefs: SharedPreferences? by lazy { openPrefs() }

    private fun openPrefs(): SharedPreferences? = runCatching {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }.onFailure { Log.w(TAG, "openPrefs() failed; drafts won't persist", it) }
        .getOrNull()

    /** Save the in-progress draft for [sessionId]. Empty string clears it. */
    fun save(sessionId: String, text: String) {
        val p = prefs ?: return
        val key = draftKey(sessionId) ?: return
        runCatching {
            if (text.isEmpty()) {
                p.edit().remove(key).apply()
            } else {
                p.edit().putString(key, text).apply()
            }
        }.onFailure { Log.w(TAG, "save() failed", it) }
    }

    /** Return the typing-buffer draft for [sessionId], or `""` if absent. */
    fun load(sessionId: String): String {
        val p = prefs ?: return ""
        val key = draftKey(sessionId) ?: return ""
        return runCatching { p.getString(key, null) ?: "" }
            .onFailure { Log.w(TAG, "load() failed", it) }
            .getOrDefault("")
    }

    /** Drop any saved draft for [sessionId]. Called after a successful send. */
    fun clear(sessionId: String) {
        val p = prefs ?: return
        val key = draftKey(sessionId) ?: return
        runCatching { p.edit().remove(key).apply() }
            .onFailure { Log.w(TAG, "clear() failed", it) }
    }

    /**
     * Read-and-clear the bounced-message slot for [sessionId]. Returns
     * `null` when no bounce is pending; the slot is wiped before this
     * function returns so a subsequent call sees a clean state.
     *
     * Caller (the session-detail screen) is responsible for surfacing the
     * snackbar / pre-filling the compose field — see SessionDetailScreen.
     */
    fun bouncedFor(sessionId: String): String? {
        val p = prefs ?: return null
        val key = bouncedKey(sessionId) ?: return null
        return runCatching {
            val text = p.getString(key, null) ?: return@runCatching null
            // apply() vs commit(): we don't strictly need a barrier here —
            // a duplicate snackbar on a race is benign (worst case: same
            // text appears in the field twice in a row).
            p.edit().remove(key).apply()
            text
        }.onFailure { Log.w(TAG, "bouncedFor() failed", it) }
            .getOrNull()
    }

    /**
     * Stash [text] as the pending bounce for [sessionId]. Called by the
     * ViewModel when a queued send expires or fails terminally — the
     * next time the user opens the session, the OutlinedTextField shows
     * this text + a "couldn't send earlier" snackbar.
     *
     * If a bounce is already pending we **append** the new text on a
     * fresh paragraph (separated by `\n\n`) rather than overwriting —
     * losing the user's previous-typed-but-failed message because a
     * second send also failed would compound the original frustration.
     */
    fun setBounced(sessionId: String, text: String) {
        val p = prefs ?: return
        val key = bouncedKey(sessionId) ?: return
        runCatching {
            val existing = p.getString(key, null)
            val merged = if (existing.isNullOrBlank()) text else "$existing\n\n$text"
            p.edit().putString(key, merged).apply()
        }.onFailure { Log.w(TAG, "setBounced() failed", it) }
    }

    /**
     * Wipe every draft + bounce belonging to the currently-active server.
     * Called when a single server is removed from the multi-server list
     * (R-6c-multi).
     *
     * If no server is active (the user removed the last one) this falls
     * back to clearing the entire prefs file — equivalent to the R-6d
     * forget-pairing behavior.
     */
    fun clearAll() {
        clearAllFor(activeServerProvider())
    }

    /**
     * Explicit-scope variant of [clearAll] that takes the target server id
     * directly. Used by `MainViewModel.removeServer` when wiping the
     * keys of a NON-active server — passing the id avoids the
     * temporarily-shift-the-active-server trick which leaked the wrong
     * value through the `activeServerId` StateFlow to Compose observers.
     *
     * Mirrors [clearAll]'s null-serverId fallback: a null [serverId] wipes
     * the whole prefs file (factory-reset semantics).
     */
    fun clearAllFor(serverId: String?) {
        val p = prefs ?: return
        runCatching {
            if (serverId == null) {
                p.edit().clear().apply()
                return@runCatching
            }
            val draftPrefix = "draft:$serverId:"
            val bouncedPrefix = "bounced:$serverId:"
            val editor = p.edit()
            for (key in p.all.keys) {
                if (key.startsWith(draftPrefix) || key.startsWith(bouncedPrefix)) {
                    editor.remove(key)
                }
            }
            editor.apply()
        }.onFailure { Log.w(TAG, "clearAllFor() failed", it) }
    }

    /** Wipe every draft + bounce regardless of server. Used by tests / hard resets. */
    fun clearAllServers() {
        val p = prefs ?: return
        runCatching { p.edit().clear().apply() }
            .onFailure { Log.w(TAG, "clearAllServers() failed", it) }
    }

    private fun draftKey(sessionId: String): String? {
        val serverId = activeServerProvider() ?: return null
        return "draft:$serverId:$sessionId"
    }

    private fun bouncedKey(sessionId: String): String? {
        val serverId = activeServerProvider() ?: return null
        return "bounced:$serverId:$sessionId"
    }

    companion object {
        private const val TAG = "DraftRepository"
        private const val PREFS_NAME = "spk_drafts"

        @Volatile
        private var instance: DraftRepository? = null

        fun get(context: Context, activeServerProvider: () -> String?): DraftRepository =
            synchronized(this) {
                val existing = instance
                val store = existing ?: DraftRepository(context.applicationContext).also { instance = it }
                // Rebind the provider every call — see kdoc on
                // [activeServerProvider]. Without this, the first caller's
                // lambda would silently capture for the life of the JVM.
                store.activeServerProvider = activeServerProvider
                store
            }
    }
}
