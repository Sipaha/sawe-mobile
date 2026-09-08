package ru.sipaha.sawe.app.data

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Shared plumbing for the two on-device suites in this package.
 *
 * These tests run **in the app's own process and UID** (there is no
 * `testApplicationId`), so `context.dataDir` is the real installed app's
 * data dir and the Android Keystore they reach is the real one. That is
 * the whole point — everything below the `SharedPreferences` interface is
 * production wiring, and none of it exists under Robolectric.
 *
 * Because the state is process-wide and file-backed, every case has to
 * start from a clean slate and put the memoised keysets back: see [wipe].
 */
internal object EncryptedPrefsDevice {

    /**
     * Every encrypted store, read off the repositories that own them rather
     * than restated here — if a ninth store appears, or one is renamed,
     * these suites follow it instead of silently testing eight.
     *
     * Each one is paired with the [LegacyPrefsFormat] its own repository
     * passes to [EncryptedPrefs.open], because opening with the wrong reader
     * is silent: it yields an empty import, a set marker and a deleted file.
     */
    val STORES: List<Pair<String, LegacyPrefsFormat>> = listOf(
        // The five originals. Their pre-Tink file was an
        // `EncryptedSharedPreferences` one and nothing can read it any more,
        // so they import nothing — see [LegacyPrefsFormat.NONE].
        PairingRepository.PREFS_NAME to LegacyPrefsFormat.NONE,
        EncryptedQueueStore.PREFS_NAME to LegacyPrefsFormat.NONE,
        PendingSendsRepository.PREFS_NAME to LegacyPrefsFormat.NONE,
        SessionHistoryRepository.PREFS_NAME to LegacyPrefsFormat.NONE,
        InFlightUploadsRepository.PREFS_NAME to LegacyPrefsFormat.NONE,
        // Encrypted later, and migrating off an ORDINARY prefs file.
        DraftRepository.PREFS_NAME to LegacyPrefsFormat.PLAIN,
        AttachmentDraftRepository.PREFS_NAME to LegacyPrefsFormat.PLAIN,
        ListCacheRepository.PREFS_NAME to LegacyPrefsFormat.PLAIN,
    )

    val STORE_IDS: List<String> = STORES.map { it.first }

    /**
     * The stores that still have an importable legacy file — the only ones a
     * fixture can write, now that the androidx reader is gone.
     */
    val PLAIN_LEGACY_STORE_IDS: List<String> =
        STORES.filter { it.second == LegacyPrefsFormat.PLAIN }.map { it.first }

    /** [EncryptedPrefs.open] with the legacy reader [storeId]'s owner uses. */
    fun open(context: Context, storeId: String): android.content.SharedPreferences? =
        EncryptedPrefs.open(context, storeId, formatOf(storeId))

    fun formatOf(storeId: String): LegacyPrefsFormat =
        STORES.first { it.first == storeId }.second

    fun prefsFile(context: Context, fileName: String): File =
        File(File(context.dataDir, "shared_prefs"), "$fileName.xml")

    /** `shared_prefs/<store>.xml` — the store's pre-Tink file. */
    fun legacyFile(context: Context, storeId: String): File = prefsFile(context, storeId)

    /** `shared_prefs/<store>_tink.xml` — the [TinkEncryptedPrefs] ciphertext. */
    fun dataFile(context: Context, storeId: String): File =
        prefsFile(context, EncryptedPrefs.dataFileName(storeId))

    /** `shared_prefs/<store>_tink_keyset.xml` — the wrapped Tink keysets. */
    fun keysetFile(context: Context, storeId: String): File =
        prefsFile(context, AppTinkKeysets.keysetPrefsFile(storeId))

    /**
     * Clean slate for [storeId]: the legacy file, the Tink data + keyset
     * files, and the whole `spk_prefs_health` file (both the import markers
     * and the recovery latches), plus the process-wide memoisation.
     *
     * The Keystore *master key* is deliberately left alone — it is shared by
     * every store and by every other install-lifetime, and deleting an alias
     * out from under a not-yet-imported legacy file is exactly the failure
     * mode [AppTinkKeysets] was designed to avoid.
     */
    fun wipe(context: Context, storeId: String) {
        context.deleteSharedPreferences(storeId)
        context.deleteSharedPreferences(EncryptedPrefs.dataFileName(storeId))
        context.deleteSharedPreferences(AppTinkKeysets.keysetPrefsFile(storeId))
        context.deleteSharedPreferences(EncryptedPrefs.LATCH_PREFS_NAME)
        forgetMemoisedKeys()
        PersistenceHealth.resetForTest()
    }

    fun wipeAll(context: Context) {
        STORE_IDS.forEach { wipe(context, it) }
    }

    /**
     * The closest a single process can get to a restart: drop every memoised
     * keyset handle and the memoised master-key probe, so the next open
     * re-reads the keyset file off disk and unwraps it through the Keystore
     * again. (Both live in [AppTinkKeysets] now that the legacy master key
     * is gone.)
     */
    fun forgetMemoisedKeys() {
        AppTinkKeysets.resetForTest()
    }

    /** True when [needle]'s UTF-8 bytes occur anywhere in [haystack]. */
    fun containsUtf8(haystack: ByteArray, needle: String): Boolean {
        val probe = needle.toByteArray(StandardCharsets.UTF_8)
        if (probe.isEmpty() || probe.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - probe.size) {
            for (j in probe.indices) if (haystack[i + j] != probe[j]) continue@outer
            return true
        }
        return false
    }
}
