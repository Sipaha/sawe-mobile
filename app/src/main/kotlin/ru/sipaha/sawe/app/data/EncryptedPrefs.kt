package ru.sipaha.sawe.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.crypto.tink.Aead
import com.google.crypto.tink.DeterministicAead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.daead.DeterministicAeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException

/**
 * Process-wide provider of the Tink keysets behind [TinkEncryptedPrefs],
 * and the successor to the old `AppMasterKey` object that memoised an
 * `androidx.security.crypto.MasterKey`.
 *
 * (That object is gone: it was deleted on 2026-09-08 together with the
 * `androidx.security:security-crypto` dependency and the one-way importer
 * that was its last caller.)
 *
 * **Layout.** Each logical store gets its own keyset file — a PLAIN
 * `SharedPreferences` file [keysetPrefsFile] holding two Tink keysets,
 * `AES256_SIV` for keys and `AES256_GCM` for values, each wrapped by the
 * same Android Keystore key [MASTER_KEY_URI]. One keyset per store (rather
 * than one shared pair) preserves the failure model the recovery machinery
 * was designed around: an unreadable keyset takes down exactly one store,
 * so exactly one store's data is discarded and reported. A shared keyset
 * would let a recovery on the *first* store silently orphan every other
 * store's ciphertext with nobody reporting the loss.
 *
 * **A NEW keystore alias.** [MASTER_KEY_URI] deliberately does not reuse
 * androidx's `_androidx_security_master_key_`. While the legacy importer
 * existed that alias still wrapped the files it read, and the two
 * lifecycles had to stay unentangled — deleting ours must never have made a
 * not-yet-imported legacy file unreadable. The importer is gone, but the
 * alias stays ours: every install's keysets are wrapped by it now, so
 * changing it would be the silent data loss the separation was there to
 * prevent.
 *
 * **Null on failure / off the main thread:** same contract the old
 * `AppMasterKey` had. [masterKeyAvailable] round-trips through the keystore
 * (tens to hundreds of ms, seconds on a slow TEE), so callers reach it via
 * `warmUpEncryptedPrefs` on [Dispatchers.IO] before the first synchronous
 * read on Main.
 */
internal object AppTinkKeysets {
    private const val TAG = "AppTinkKeysets"

    /**
     * Android Keystore alias wrapping every prefs keyset. Ours, not
     * androidx's — see the object KDoc.
     */
    const val MASTER_KEY_URI = "android-keystore://spk_sawe_prefs_master_key_v1"

    private const val KEY_KEYSET_NAME = "spk_prefs_key_daead_v1"
    private const val VALUE_KEYSET_NAME = "spk_prefs_value_aead_v1"

    /** Memoised primitives, keyed by the LOGICAL store id. */
    private val cache = HashMap<String, TinkPrefsPrimitives>()

    @Volatile
    private var configRegistered = false

    /**
     * Memoised only on SUCCESS. A failure is left un-memoised on purpose:
     * the old `AppMasterKey` re-derived on every call too, so a keystore
     * that is momentarily busy while the first store opens does not disable
     * persistence for the ones that open after it.
     */
    @Volatile
    private var masterKeyOk = false

    /**
     * Test seam: when non-null, [primitives] calls this instead of
     * unwrapping a keyset through the Android Keystore, and
     * [masterKeyAvailable] answers true without probing it.
     *
     * The keystore is the one part of this file that neither the JVM nor
     * Robolectric can provide, and without a seam everything ABOVE it —
     * the recovery policy wired to real files, and the whole legacy-import
     * ordering — is reachable only on a device. Overriding the two
     * keystore-touching lines keeps the rest of [EncryptedPrefs.open]
     * production code under test. Only ever set from `src/test`; the real
     * keyset wiring it replaces is covered on-device by
     * `EncryptedPrefsDevice`.
     */
    @Volatile
    internal var primitivesForTest: ((String) -> TinkPrefsPrimitives)? = null

    /** Plain prefs file holding the two keysets for [prefsName]. */
    fun keysetPrefsFile(prefsName: String): String = "${prefsName}_tink_keyset"

    /**
     * Resolve (creating on first use) the Android Keystore key that wraps
     * every keyset, returning false if the keystore is unreachable.
     *
     * This is guard 1 of [EncryptedPrefs]'s recovery policy, and the reason
     * it is a separate step: `AndroidKeysetManager.build()` reports "the
     * keystore is broken" and "the on-disk keyset is broken" with the same
     * [GeneralSecurityException], and only the second one may delete a
     * file. Probing the master key first splits the two apart — a false
     * here means nothing was ever opened, so the data is intact.
     */
    fun masterKeyAvailable(context: Context): Boolean {
        if (primitivesForTest != null) return true
        if (masterKeyOk) return true
        return synchronized(this) {
            if (masterKeyOk) return@synchronized true
            runCatching {
                ensureConfigRegistered()
                AndroidKeystoreKmsClient.getOrGenerateNewAeadKey(MASTER_KEY_URI)
                true
            }.onFailure {
                Log.w(TAG, "Keystore master key unavailable; encrypted prefs disabled", it)
            }.getOrDefault(false).also { if (it) masterKeyOk = true }
        }
    }

    /**
     * The key/value primitives for [prefsName], unwrapping (or creating)
     * its keyset file on first use.
     *
     * Throws [GeneralSecurityException] when the keyset cannot be
     * unwrapped and [IOException] when it cannot be parsed — the two
     * failures [EncryptedPrefs.isKeysetFailure] treats as recoverable by
     * discarding the file.
     */
    fun primitives(context: Context, prefsName: String): TinkPrefsPrimitives = synchronized(this) {
        cache[prefsName]?.let { return it }
        primitivesForTest?.let { override ->
            return override(prefsName).also { cache[prefsName] = it }
        }
        ensureConfigRegistered()
        val app = context.applicationContext
        val file = keysetPrefsFile(prefsName)
        val keyAead = keysetHandle(app, file, KEY_KEYSET_NAME, "AES256_SIV")
            .getPrimitive(RegistryConfiguration.get(), DeterministicAead::class.java)
        val valueAead = keysetHandle(app, file, VALUE_KEYSET_NAME, "AES256_GCM")
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        TinkPrefsPrimitives(keyAead, valueAead).also { cache[prefsName] = it }
    }

    private fun keysetHandle(
        context: Context,
        prefsFile: String,
        keysetName: String,
        template: String,
    ): KeysetHandle = AndroidKeysetManager.Builder()
        .withSharedPref(context, keysetName, prefsFile)
        .withKeyTemplate(KeyTemplates.get(template))
        .withMasterKeyUri(MASTER_KEY_URI)
        .build()
        .keysetHandle

    /**
     * Drop the memoised primitives (and the memoised master-key probe) for
     * [prefsName] so the next [primitives] call re-derives them. Used by
     * [EncryptedPrefs.open] between the two attempts of the keyset-recovery
     * path: the cached handles reference a keyset file that has just been
     * deleted.
     */
    fun reset(prefsName: String) {
        synchronized(this) {
            cache.remove(prefsName)
            masterKeyOk = false
        }
    }

    /** Test hook — forget every memoised keyset and the master-key probe. */
    internal fun resetForTest() {
        synchronized(this) {
            cache.clear()
            masterKeyOk = false
            configRegistered = false
            primitivesForTest = null
        }
    }

    private fun ensureConfigRegistered() {
        if (configRegistered) return
        AeadConfig.register()
        DeterministicAeadConfig.register()
        configRegistered = true
    }
}

/**
 * Outcome of opening one encrypted prefs file.
 *
 * Split out from the open call itself so the recovery decision is a
 * pure function ([EncryptedPrefs.openWithRecovery]) that can be unit-tested
 * without an Android Keystore.
 */
internal sealed interface PrefsOpenOutcome<out T> {
    /** Opened on the first attempt — nothing was lost. */
    data class Opened<T>(val prefs: T) : PrefsOpenOutcome<T>

    /**
     * The first attempt failed, the unreadable file was discarded, and the
     * second attempt succeeded against a freshly-created keyset.
     * [droppedData] is true when a prefs file actually existed before the
     * discard — i.e. user data was thrown away, not just an empty slot
     * re-initialised.
     */
    data class Recovered<T>(val prefs: T, val droppedData: Boolean) : PrefsOpenOutcome<T>

    /** Both attempts failed; persistence is off for this process. */
    data class Unavailable(val cause: Throwable) : PrefsOpenOutcome<Nothing>
}

/**
 * Shared opener for the eight Android-Keystore-encrypted prefs stores
 * (`spk_pairing`, `spk_queue`, `spk_pending_sends`, `spk_history_cache`,
 * `spk_inflight_uploads`, `spk_drafts`, `spk_attachment_drafts`,
 * `spk_list_cache`).
 *
 * **What gets encrypted is decided by "does it hold content", not by "has
 * the server seen it".** `spk_history_cache` is server-derived transcript
 * text and it is encrypted; `spk_drafts` holds text that has reached no
 * server at all. The only store deliberately left in a plain file is
 * [NavStateRepository], which stores route ids and nothing the user typed.
 *
 * **[prefsName] is the LOGICAL store id, not a file name.** It is what
 * [PersistenceHealth] publishes and what the vm layer maps to user-facing
 * copy, so those strings are frozen. The physical files are derived from
 * it:
 *
 *  - `<name>_tink`        — the [TinkEncryptedPrefs] data file (ciphertext
 *                           keys and values).
 *  - `<name>_tink_keyset` — the plain file holding that store's two Tink
 *                           keysets, wrapped by the Keystore master key.
 *  - `<name>.xml`         — the LEGACY file this store is migrating off,
 *                           read once and then deleted forever. Its shape
 *                           is the store's own [LegacyPrefsFormat]: an
 *                           ordinary prefs file for the three stores that
 *                           used to be unencrypted, and
 *                           [LegacyPrefsFormat.NONE] — nothing to read — for
 *                           the five originals, whose androidx reader was
 *                           deleted once every install had run it.
 *
 * **Keyset recovery (N-61).** A keyset that can no longer be unwrapped (OEM
 * update bug, credential-store reset) makes its file unreadable by
 * definition, so the only useful move is to discard it and start over:
 * [open] deletes the data file *and* the keyset file, drops the memoised
 * primitives and retries once. Persistence therefore works again on the
 * same launch instead of staying off for the lifetime of the install, and
 * the loss is published on [PersistenceHealth.droppedStores] rather than
 * swallowed.
 *
 * **Deleting is the last resort, not the default.** Three guards keep a
 * *recoverable* failure from being mistaken for an unreadable keyset, since
 * a false positive wipes the user's pairing, offline queue and drafts:
 *
 *  1. The Keystore master key is resolved **before** the recovery path is
 *     entered ([AppTinkKeysets.masterKeyAvailable]). A false there means no
 *     file was ever opened — its contents are intact — so [open] reports
 *     unavailability and returns.
 *  2. Only [isKeysetFailure] throwables trigger a discard. A busy or
 *     erroring `keystore2` daemon surfaces as `ProviderException` /
 *     `RuntimeException`, which is transient and leaves the file alone.
 *  3. At most one discard per store between healthy opens
 *     ([recoveryAlreadyAttempted]). A repeating transient failure therefore
 *     cannot delete on every launch; the latch clears as soon as the store
 *     opens cleanly, so a genuine second keyset loss is still recoverable.
 */
internal object EncryptedPrefs {
    private const val TAG = "EncryptedPrefs"

    /**
     * Plain (unencrypted) prefs holding the per-store "already discarded
     * once" latch and the per-store legacy-import marker.
     */
    internal const val LATCH_PREFS_NAME = "spk_prefs_health"

    /** Physical file backing the [TinkEncryptedPrefs] layer for [prefsName]. */
    internal fun dataFileName(prefsName: String): String = "${prefsName}_tink"

    /**
     * Open [prefsName], recovering from an unreadable keyset at most once,
     * then importing the store's legacy file — read with the reader
     * [legacyFormat] names — if this install still has one.
     *
     * Returns `null` when the Keystore master key cannot be resolved, when
     * the failure is not keyset-shaped, or when even a freshly-created
     * keyset cannot be opened — in every one of those cases the files are
     * left untouched and the caller degrades to a no-op disk layer as
     * before.
     */
    fun open(
        context: Context,
        prefsName: String,
        legacyFormat: LegacyPrefsFormat,
    ): SharedPreferences? {
        val app = context.applicationContext
        // Guard 1: resolve the Keystore master key up front. Failing here
        // means the keystore is unreachable, NOT that an on-disk keyset is
        // broken — nothing was opened, so there is nothing to recover from
        // and deleting would destroy intact data.
        if (!AppTinkKeysets.masterKeyAvailable(app)) {
            Log.w(TAG, "$prefsName: Keystore master key unavailable; not persisting (files left intact)")
            PersistenceHealth.reportUnavailable(prefsName)
            return null
        }
        val dataFile = dataFileName(prefsName)
        val outcome = openWithRecovery(
            create = {
                // Re-resolved per attempt: [discard] drops the memoised
                // primitives, so the retry unwraps a freshly created keyset
                // rather than reusing a handle to one that has gone away.
                val primitives = AppTinkKeysets.primitives(app, prefsName)
                TinkEncryptedPrefs(
                    app.getSharedPreferences(dataFile, Context.MODE_PRIVATE),
                    primitives,
                )
            },
            // Guards 2 + 3: only a keyset-shaped failure is recoverable, and
            // only if we have not already spent this store's one discard.
            shouldDiscard = { cause ->
                isKeysetFailure(cause) && !recoveryAlreadyAttempted(app, prefsName)
            },
            hadExistingData = { prefsFileExists(app, dataFile) },
            discard = {
                markRecoveryAttempted(app, prefsName, attempted = true)
                AppTinkKeysets.reset(prefsName)
                app.deleteSharedPreferences(AppTinkKeysets.keysetPrefsFile(prefsName))
                app.deleteSharedPreferences(dataFile)
            },
        )
        val prefs = when (outcome) {
            is PrefsOpenOutcome.Opened -> {
                // Healthy open — release the latch so a genuine keyset loss
                // later in this install can still be recovered.
                markRecoveryAttempted(app, prefsName, attempted = false)
                PersistenceHealth.reportAvailable(prefsName)
                outcome.prefs
            }

            is PrefsOpenOutcome.Recovered -> {
                if (outcome.droppedData) {
                    Log.e(
                        TAG,
                        "$prefsName: keyset unreadable (lost Keystore key); file discarded and " +
                            "recreated — its contents are unrecoverable",
                    )
                    PersistenceHealth.reportDropped(prefsName)
                } else {
                    Log.w(TAG, "$prefsName: recreated after a failed first open (no data existed)")
                }
                PersistenceHealth.reportAvailable(prefsName)
                outcome.prefs
            }

            is PrefsOpenOutcome.Unavailable -> {
                Log.w(
                    TAG,
                    "$prefsName: encrypted prefs unavailable; not persisting",
                    outcome.cause,
                )
                PersistenceHealth.reportUnavailable(prefsName)
                null
            }
        } ?: return null

        LegacyPrefsImport.run(app, prefsName, prefs, legacyPrefsSource(app, legacyFormat))
        return prefs
    }

    /**
     * True when [cause] is the "the on-disk keyset cannot be read" failure
     * class, which is the only one where deleting the file is the right
     * move. Tink surfaces it as a [GeneralSecurityException] (unwrap /
     * decrypt failure) or an [IOException] (unparseable keyset proto) —
     * verified against Tink's own `AndroidKeysetManager` /
     * `KeysetHandle.read` paths, which is what both the new layer and the
     * legacy library go through.
     *
     * Deliberately excluded: [java.security.ProviderException] and every
     * other unchecked throwable — that is how a busy or erroring
     * `keystore2` daemon, TEE contention and EIO/ENOSPC on the keystore
     * backing store present themselves. Those are transient and
     * non-destructive, and the data is still readable on the next launch.
     */
    internal fun isKeysetFailure(cause: Throwable): Boolean {
        val chain = causeChain(cause)
        // A keystore fault anywhere in the chain vetoes the whole
        // classification, even under a checked-exception wrapper — Tink
        // rethrows a ProviderException from the daemon as a
        // GeneralSecurityException, and that is still a transient fault.
        if (chain.any { it is ProviderException }) return false
        return chain.any { it is GeneralSecurityException || it is IOException }
    }

    /**
     * Same question for the LEGACY file, which admits one extra failure
     * shape: a plain [SecurityException]. That is how the deleted
     * `EncryptedSharedPreferences` reader surfaced a per-entry Tink decrypt
     * failure, and it is kept because the classification is about the file
     * on disk, not about the reader: a legacy file that will not open today
     * will not open tomorrow, whichever throwable says so. The
     * [ProviderException] veto still applies, so a keystore hiccup mid-read
     * is still transient.
     */
    internal fun isLegacyUnreadable(cause: Throwable): Boolean {
        val chain = causeChain(cause)
        if (chain.any { it is ProviderException }) return false
        return chain.any { it is GeneralSecurityException || it is IOException || it is SecurityException }
    }

    /** [cause] and its causes, terminating on a self-referential chain. */
    private fun causeChain(cause: Throwable): List<Throwable> {
        val chain = mutableListOf<Throwable>()
        val seen = HashSet<Throwable>()
        var t: Throwable? = cause
        while (t != null && seen.add(t)) {
            chain += t
            t = t.cause
        }
        return chain
    }

    /**
     * True when this store has already been discarded once and has not been
     * opened cleanly since — see guard 3 on the object kdoc.
     */
    private fun recoveryAlreadyAttempted(context: Context, prefsName: String): Boolean = runCatching {
        latchPrefs(context).getBoolean(latchKey(prefsName), false)
    }.getOrDefault(false)

    private fun markRecoveryAttempted(context: Context, prefsName: String, attempted: Boolean) {
        runCatching {
            val prefs = latchPrefs(context)
            val key = latchKey(prefsName)
            if (!attempted && !prefs.contains(key)) return
            prefs.edit().putBoolean(key, attempted).apply()
        }
    }

    internal fun latchPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(LATCH_PREFS_NAME, Context.MODE_PRIVATE)

    private fun latchKey(prefsName: String) = "recovered:$prefsName"

    /**
     * True when a `shared_prefs/<file>.xml` file is present on disk.
     *
     * Errs toward `true`: if the path cannot be resolved (device-protected
     * storage, a relocated data dir) we assume data existed, so a discard is
     * reported to the user rather than silently hidden.
     */
    internal fun prefsFileExists(context: Context, fileName: String): Boolean = runCatching {
        File(File(context.dataDir, "shared_prefs"), "$fileName.xml").exists()
    }.getOrDefault(true)

    /**
     * Pure recovery policy, factored out of [open] so it can be tested
     * without a keystore.
     *
     * 1. Try [create]. Success → [PrefsOpenOutcome.Opened].
     * 2. On failure, ask [shouldDiscard] whether the throwable means the
     *    on-disk keyset is unreadable. If not, stop — the data stays and the
     *    result is [PrefsOpenOutcome.Unavailable].
     * 3. Otherwise note whether [hadExistingData], run [discard], and try
     *    [create] once more. Success → [PrefsOpenOutcome.Recovered].
     * 4. Second failure → [PrefsOpenOutcome.Unavailable] carrying the
     *    *second* throwable (the first is attached as a suppressed cause so
     *    no diagnostic is lost).
     */
    internal fun <T : Any> openWithRecovery(
        create: () -> T,
        shouldDiscard: (Throwable) -> Boolean,
        hadExistingData: () -> Boolean,
        discard: () -> Unit,
    ): PrefsOpenOutcome<T> {
        val first = runCatching(create)
        first.getOrNull()?.let { return PrefsOpenOutcome.Opened(it) }
        val firstCause = first.exceptionOrNull() ?: IllegalStateException("prefs open failed")

        if (!runCatching { shouldDiscard(firstCause) }.getOrDefault(false)) {
            return PrefsOpenOutcome.Unavailable(firstCause)
        }

        val existed = runCatching(hadExistingData).getOrDefault(true)
        runCatching(discard)

        val second = runCatching(create)
        second.getOrNull()?.let { return PrefsOpenOutcome.Recovered(it, droppedData = existed) }

        val cause = second.exceptionOrNull() ?: IllegalStateException("prefs open failed")
        if (firstCause !== cause) runCatching { cause.addSuppressed(firstCause) }
        return PrefsOpenOutcome.Unavailable(cause)
    }
}

/**
 * Observable health of the encrypted-prefs layer.
 *
 * The vm layer reads [droppedStores] to tell the user that persisted data
 * was lost (offline queue, pending-send markers, transcript cache) after a
 * Keystore key went missing, and [unavailableStores] to know that
 * persistence is off entirely for this process. Both are LOGICAL store ids
 * (`spk_queue`, …) — stable identifiers the vm layer maps to user-facing
 * copy, deliberately unchanged by the Tink migration.
 */
internal object PersistenceHealth {
    private val _droppedStores = MutableStateFlow<Set<String>>(emptySet())

    /** Stores whose unreadable contents were discarded to restore persistence. */
    val droppedStores: StateFlow<Set<String>> = _droppedStores.asStateFlow()

    private val _unavailableStores = MutableStateFlow<Set<String>>(emptySet())

    /** Stores that could not be opened at all — persistence is off for them. */
    val unavailableStores: StateFlow<Set<String>> = _unavailableStores.asStateFlow()

    fun reportDropped(prefsName: String) {
        _droppedStores.update { it + prefsName }
    }

    fun reportUnavailable(prefsName: String) {
        _unavailableStores.update { it + prefsName }
    }

    fun reportAvailable(prefsName: String) {
        _unavailableStores.update { if (prefsName in it) it - prefsName else it }
    }

    /** Clear a drop notice once the vm layer has shown it. */
    fun acknowledgeDropped(prefsName: String) {
        _droppedStores.update { if (prefsName in it) it - prefsName else it }
    }

    /**
     * Clear a whole batch in ONE update.
     *
     * The vm layer coalesces a batch into a single notice, so it has to
     * clear the batch as a single transition too: acknowledging one store at
     * a time re-emits [droppedStores] after each removal, and every one of
     * those emissions is a batch the collector has to recognise as already
     * handled.
     */
    fun acknowledgeDropped(prefsNames: Collection<String>) {
        if (prefsNames.isEmpty()) return
        val batch = prefsNames.toSet()
        _droppedStores.update { it - batch }
    }

    /** Test hook — reset process-wide state between cases. */
    internal fun resetForTest() {
        _droppedStores.value = emptySet()
        _unavailableStores.value = emptySet()
    }
}

/**
 * Single eager entry point for the Android-Keystore-encrypted prefs stores,
 * so cold start pays the Tink keyset unwrap once, on [Dispatchers.IO],
 * instead of once per store on Main (N-56).
 *
 * Call it from the ViewModel's own scope right after the repositories are
 * constructed. Each `warmUp()` only resolves a `by lazy` delegate, so the
 * call is idempotent and racing it with a synchronous read is safe — the
 * reader simply blocks on the same lazy initialiser it would have run
 * itself.
 */
internal suspend fun warmUpEncryptedPrefs(
    pairing: PairingRepository,
    queue: EncryptedQueueStore,
    pendingSends: PendingSendsRepository,
    history: SessionHistoryRepository,
    drafts: DraftRepository,
    attachmentDrafts: AttachmentDraftRepository,
    listCache: ListCacheRepository,
) {
    // Sequential on purpose: the opens contend on the same keystore and the
    // first one dominates (it generates or unwraps the master key for the
    // rest, and pays the one-off legacy import).
    pairing.warmUp()
    queue.warmUp()
    pendingSends.warmUp()
    history.warmUp()
    // The three that used to be plain files. [drafts] matters most: the
    // compose bar's `flushDraft` runs its write on Main from
    // `DisposableEffect.onDispose`, so without this the FIRST back-press out
    // of a chat would pay the keyset unwrap there.
    drafts.warmUp()
    attachmentDrafts.warmUp()
    listCache.warmUp()
}
