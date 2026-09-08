package ru.sipaha.sawe.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persists per-session compose-bar attachment drafts across process death.
 * Pairs with [DraftRepository] (which handles the text draft) but lives in
 * its own prefs file so the two concerns can be wiped independently and
 * a corrupted attachments blob doesn't take down text persistence.
 *
 * Only metadata is persisted: [AttachmentRef.localKey] is the join key
 * back into [ru.sipaha.sawe.app.vm.UploadManager]'s own persistent
 * [InFlightUploadsRepository], which already survives process death and
 * resumes the upload coroutines on startup. After re-hydration the
 * chat-detail screen calls `uploadManager.stateFlowOf(localKey)` per
 * persisted ref to recover the live progress flow — the bytes never
 * leave the device a second time. Entries whose `localKey` is no longer
 * known to UploadManager are dropped (purged / cancelled in a prior run).
 *
 * **Storage:** encrypted, via [EncryptedPrefs.open]. The earlier rationale
 * ("attachment metadata isn't a secret — the user picked the file
 * themselves") confused *who chose it* with *what it reveals*: an
 * [AttachmentRef] is a file name and a MIME type, and a list of the file
 * names someone was about to send is content about them. It also pairs with
 * [DraftRepository] — encrypting the covering message while leaving the
 * names of its attachments in clear next to it would be a threat model with
 * a hole in the middle. Writes are per compose-bar attachment change (pick,
 * remove, send), never in a tight loop, so the per-entry AES cost is not on
 * any hot path; the keyset unwrap is paid once by `warmUpEncryptedPrefs`.
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

    /** `null` degrades to a no-op disk layer — see [DraftRepository.openPrefs]. */
    private fun openPrefs(): SharedPreferences? =
        EncryptedPrefs.open(context, PREFS_NAME, LegacyPrefsFormat.PLAIN)

    /**
     * Resolve the encrypted prefs file (and the one-off import of the old
     * plain file) on [Dispatchers.IO], so no caller pays the Keystore
     * round-trip on Main. Idempotent.
     */
    suspend fun warmUp() {
        withContext(Dispatchers.IO) { prefs }
    }

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
        internal const val PREFS_NAME = "spk_attachment_drafts"

        private val holder = SingletonHolder(::AttachmentDraftRepository)

        /** Process-wide instance; provider rebound per call ([SingletonHolder]). */
        fun get(context: Context, activeServerProvider: () -> String?): AttachmentDraftRepository =
            holder.get(context) { it.activeServerProvider = activeServerProvider }
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
