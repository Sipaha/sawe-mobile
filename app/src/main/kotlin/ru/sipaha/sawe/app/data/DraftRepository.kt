package ru.sipaha.sawe.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persists per-session compose-bar drafts across process death (R-6d).
 *
 * **Two channels, one prefs file:**
 *   - `draft:<serverId>:<sessionId>` — the live typing buffer. Written on
 *     the trailing edge of a 500 ms debounce owned by the caller (NOT once
 *     per keystroke), plus one synchronous flush when the compose bar
 *     leaves composition; cleared after a successful send.
 *   - `bounced:<serverId>:<sessionId>` — the read-once recovery slot for
 *     TTL-expired or terminally-failed messages. Written by
 *     [ru.sipaha.sawe.app.vm.MainViewModel.handleExpiredMessage] when
 *     [ru.sipaha.sawe.core.RemoteClient.onMessageExpired] fires, and by
 *     the user-initiated cancel of a message parked in the offline queue.
 *     Read once via [bouncedFor], which also clears the slot — so the
 *     bounce surfaces exactly once per expiry event.
 *
 *     This slot is the *durable* route, taken when the session is not on
 *     screen at the moment the bounce happens. A bounce for the currently
 *     open session is additionally pushed live through
 *     [ru.sipaha.sawe.app.vm.SessionDetailStore.bouncedDrafts] so the
 *     compose bar recovers the text without waiting for a reopen; that
 *     path retires this slot via `consumeBounce` only after the text has
 *     been merged into the draft.
 *
 * **R-6c-multi per-server scoping:** every key now embeds the active
 * server id (provided via [activeServerProvider]). Same session id on
 * two paired servers no longer collide. The provider is read on every
 * call rather than captured at construction, so a `switchToServer` in
 * [MainViewModel] is reflected immediately in subsequent
 * [save] / [load] / [bouncedFor].
 *
 * **Storage:** encrypted, via [EncryptedPrefs.open].
 *
 * It used to be a plain file, justified as "drafts aren't secrets — the
 * server has the same text the moment Send is tapped". That is false for
 * both channels this file holds. A draft is text that has reached NO server
 * — it is unsent by definition, and the half-written message someone
 * abandoned is exactly the kind of thing they would not expect to find
 * lying in clear on the filesystem. The `bounced:` slot is stronger still:
 * it holds a message that was *attempted* and did not go out. Nor is
 * "server-derived" the line this app draws — `spk_history_cache` is
 * server-derived transcript text and it is encrypted too. The line is
 * whether the store holds content.
 *
 * The write cost is one AES-GCM value encrypt plus one AES-SIV key encrypt
 * per entry, which the write pattern can afford: [save] is driven by a
 * 500 ms trailing-edge debounce in the compose bar, not by the keystroke
 * (`SessionDetailScreen`'s `snapshotFlow { draft }.debounce(500)`), and the
 * only unbatched writer is the one-per-back-press `flushDraft`. The keyset
 * unwrap — the part that actually costs milliseconds — is paid once at
 * ViewModel construction by `warmUpEncryptedPrefs`, off Main.
 *
 * A lost Keystore keyset now costs this file's contents; that is reported
 * on [PersistenceHealth.droppedStores] and surfaced to the user by
 * `persistenceDropNotice`, which a plain file had no way to do.
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

    /**
     * `null` means "degrade to a no-op disk layer", which is what every
     * method here already handles — same contract the plain open had, minus
     * the silent-forever failure mode: [EncryptedPrefs.open] recovers a
     * lost keyset and reports the loss.
     */
    private fun openPrefs(): SharedPreferences? =
        EncryptedPrefs.open(context, PREFS_NAME, LegacyPrefsFormat.PLAIN)

    /**
     * Resolve the encrypted prefs file (Tink keyset unwrap through the
     * Android Keystore, plus the one-off import of the old plain file) on
     * [Dispatchers.IO]. Idempotent — the `by lazy` delegate does the work
     * once and every later access, including the synchronous [save] on Main
     * from `flushDraft`, is a field read.
     */
    suspend fun warmUp() {
        withContext(Dispatchers.IO) { prefs }
    }

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
        internal const val PREFS_NAME = "spk_drafts"

        private val holder = SingletonHolder(::DraftRepository)

        /**
         * The process-wide drafts store, with [activeServerProvider]
         * rebound on every call — see the kdoc on [activeServerProvider].
         * Without that rebind, the first caller's lambda would silently
         * capture for the life of the JVM. [SingletonHolder] owns both
         * halves of that rule.
         */
        fun get(context: Context, activeServerProvider: () -> String?): DraftRepository =
            holder.get(context) { it.activeServerProvider = activeServerProvider }
    }
}
