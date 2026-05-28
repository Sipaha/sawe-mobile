package ru.sipaha.spkremote.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persists per-session compose-bar attachment drafts across process death.
 * Pairs with [DraftRepository] (which handles the text draft) but lives in
 * its own prefs file so the two concerns can be wiped independently and
 * a corrupted attachments blob doesn't take down text persistence.
 *
 * Only metadata is persisted: [AttachmentRef.localKey] is the join key
 * back into [ru.sipaha.spkremote.app.vm.UploadManager]'s own persistent
 * [InFlightUploadsRepository], which already survives process death and
 * resumes the upload coroutines on startup. After re-hydration the
 * chat-detail screen calls `uploadManager.stateFlowOf(localKey)` per
 * persisted ref to recover the live progress flow — the bytes never
 * leave the device a second time. Entries whose `localKey` is no longer
 * known to UploadManager are dropped (purged / cancelled in a prior run).
 *
 * Storage is plain [SharedPreferences] — attachment metadata isn't a
 * secret (the user picked the file themselves), and encrypted prefs cost
 * startup time that doesn't earn anything here.
 */
class AttachmentDraftRepository(
    private val context: Context,
) {

    @Volatile
    var activeServerProvider: () -> String? = { null }
        internal set

    private val prefs: SharedPreferences? by lazy { openPrefs() }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun openPrefs(): SharedPreferences? = runCatching {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }.onFailure { Log.w(TAG, "openPrefs() failed; attachment drafts won't persist", it) }
        .getOrNull()

    fun save(sessionId: String, refs: List<AttachmentRef>) {
        val p = prefs ?: return
        val key = attachmentsKey(sessionId) ?: return
        runCatching {
            if (refs.isEmpty()) {
                p.edit {remove(key)}
            } else {
                p.edit {putString(key, json.encodeToString(refs))}
            }
        }.onFailure { Log.w(TAG, "save() failed", it) }
    }

    fun load(sessionId: String): List<AttachmentRef> {
        val p = prefs ?: return emptyList()
        val key = attachmentsKey(sessionId) ?: return emptyList()
        val raw = runCatching { p.getString(key, null) }
            .onFailure { Log.w(TAG, "load() failed", it) }
            .getOrNull()
            ?: return emptyList()
        return runCatching { json.decodeFromString<List<AttachmentRef>>(raw) }
            .onFailure { Log.w(TAG, "decode failed for $key; treating as empty", it) }
            .getOrDefault(emptyList())
    }

    fun clear(sessionId: String) {
        val p = prefs ?: return
        val key = attachmentsKey(sessionId) ?: return
        runCatching { p.edit {remove(key)} }
            .onFailure { Log.w(TAG, "clear() failed", it) }
    }

    fun clearAllFor(serverId: String?) {
        val p = prefs ?: return
        runCatching {
            if (serverId == null) {
                p.edit {clear()}
                return@runCatching
            }
            val prefix = "attachments:$serverId:"
            p.edit {
                for (k in p.all.keys) if (k.startsWith(prefix)) remove(k)
            }
        }.onFailure { Log.w(TAG, "clearAllFor() failed", it) }
    }

    private fun attachmentsKey(sessionId: String): String? {
        val serverId = activeServerProvider() ?: return null
        return "attachments:$serverId:$sessionId"
    }

    companion object {
        private const val TAG = "AttachmentDraftRepository"
        private const val PREFS_NAME = "spk_attachment_drafts"

        @Volatile
        private var instance: AttachmentDraftRepository? = null

        fun get(context: Context, activeServerProvider: () -> String?): AttachmentDraftRepository =
            synchronized(this) {
                val existing = instance
                val store = existing
                    ?: AttachmentDraftRepository(context.applicationContext).also { instance = it }
                store.activeServerProvider = activeServerProvider
                store
            }
    }
}

/**
 * Stable on-disk shape — small enough that we don't bother with field
 * renaming hygiene. If a field needs renaming, add a migrator that
 * decodes the old key and re-saves under the new shape.
 */
@Serializable
data class AttachmentRef(
    val localKey: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
)
