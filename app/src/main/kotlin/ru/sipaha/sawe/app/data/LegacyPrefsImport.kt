package ru.sipaha.sawe.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * The legacy side of the one-way "previous file → Tink" migration, behind
 * an interface so [LegacyPrefsImport] can be unit-tested against a source
 * that fails on demand.
 *
 * The production implementations are picked by [legacyPrefsSource] from the
 * store's own [LegacyPrefsFormat]. Injecting the source rather than reaching
 * for one directly is what kept the (now deleted) `androidx.security.crypto`
 * reader confined to a single file, and it is still what lets the failure
 * modes below be provoked in a test.
 */
internal interface LegacyPrefsSource {

    /** True when this install still has a legacy file for [prefsName]. */
    fun exists(prefsName: String): Boolean

    /**
     * Open the legacy store.
     *
     * Returns `null` when the file is merely *not openable yet* (nothing is
     * known about its contents, so the import is deferred rather than
     * declared complete), and throws when it cannot be read at all — see
     * [EncryptedPrefs.isLegacyUnreadable] for which throwables mean
     * "unrecoverable" rather than "try again next launch".
     */
    fun open(prefsName: String): SharedPreferences?

    /** Delete the legacy file for [prefsName]. */
    fun delete(prefsName: String)
}

/**
 * Copies a store out of the file its previous incarnation lived in and into
 * the [TinkEncryptedPrefs] one, exactly once per install.
 *
 * **The invariant.** The plain prefs file `spk_prefs_health` holds
 * `imported:<store>` = `true` **iff** the legacy → Tink import for that
 * store has fully committed. The ordering that makes it true:
 *
 *   1. every legacy entry is written into the new store through **one**
 *      [SharedPreferences.Editor] and one **`commit()`** — synchronous, and
 *      atomic at the file level, so the new file never holds a partial copy;
 *   2. only after that commit returns `true` is the marker set;
 *   3. only after the marker is set is the legacy file deleted.
 *
 * Every crash point therefore lands on a safe state:
 *
 *   - before (1) or between (1) and (2) → no marker, legacy file still
 *     present → the next open re-runs the whole import. It is idempotent by
 *     construction: it writes the same keys with the same values, and the
 *     legacy file it reads is untouched;
 *   - between (2) and (3) → marker present, legacy file still present → the
 *     next open skips the import and finishes the delete;
 *   - after (3) → marker present, no legacy file → nothing to do, and the
 *     legacy file is never looked at again on this install.
 *
 * "Complete" is thus an explicit recorded fact, never inferred from the new
 * file merely existing.
 *
 * **Why the marker is not stored inside the encrypted store itself.**
 * `EncryptedQueueStore.clearAllServers()` and
 * `SessionHistoryRepository.clearAll()` both call `edit().clear()`, which
 * would erase an in-store marker, and `SessionHistoryRepository` /
 * `PendingSendsRepository` / `InFlightUploadsRepository` all enumerate
 * `prefs.all`, which the marker would pollute. `spk_prefs_health` already
 * exists for exactly this kind of out-of-band bookkeeping (it holds the
 * recovery latch) and no repository ever clears it.
 */
internal object LegacyPrefsImport {

    /** Same tag as [EncryptedPrefs]: this is one story in the log. */
    private const val TAG = "EncryptedPrefs"

    internal fun markerKey(prefsName: String) = "imported:$prefsName"

    /**
     * Bring [target] up to date with whatever the legacy store held, if
     * this install has not already done so. Never throws.
     */
    fun run(
        context: Context,
        prefsName: String,
        target: SharedPreferences,
        source: LegacyPrefsSource,
    ) {
        if (isComplete(context, prefsName)) {
            // Marker present but the legacy file survived: the process died
            // between step (2) and step (3). Finish the delete.
            if (legacyExists(source, prefsName)) {
                runCatching { source.delete(prefsName) }
                Log.i(TAG, "$prefsName: removed the legacy file left over from a completed import")
            }
            return
        }

        if (!legacyExists(source, prefsName)) {
            // Fresh install (or an import whose delete already landed while
            // the marker write was lost). Record completion so no later
            // launch pays for the probe.
            markComplete(context, prefsName)
            return
        }

        val legacy = runCatching { source.open(prefsName) }
            .getOrElse { cause -> onLegacyFailure(context, prefsName, source, cause); return }
            ?: run {
                Log.w(TAG, "$prefsName: legacy file present but not openable yet; import deferred")
                return
            }

        val entries = runCatching { legacy.all }
            .getOrElse { cause -> onLegacyFailure(context, prefsName, source, cause); return }

        val editor = runCatching { target.edit() }.getOrNull() ?: run {
            Log.w(TAG, "$prefsName: could not open an editor on the new store; import deferred")
            return
        }
        var copied = 0
        var skipped = 0
        for ((key, value) in entries) {
            val put = runCatching {
                when (value) {
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toMutableSet())
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    // Not representable in SharedPreferences.Editor — cannot
                    // have been written by any of our repositories.
                    else -> null
                }
            }.getOrNull()
            if (put == null) skipped++ else copied++
        }

        // commit(), not apply(): the marker below must only be written once
        // the bytes are actually on disk, or a crash in the window would
        // leave a marker with no data and the legacy file already gone.
        val committed = runCatching { editor.commit() }.getOrDefault(false)
        if (!committed) {
            Log.w(TAG, "$prefsName: legacy import did not commit; will retry on the next launch")
            return
        }

        markComplete(context, prefsName)
        runCatching { source.delete(prefsName) }
        Log.i(
            TAG,
            "$prefsName: imported $copied entries from the legacy prefs file" +
                if (skipped > 0) " ($skipped unsupported entries skipped)" else "",
        )
    }

    private fun legacyExists(source: LegacyPrefsSource, prefsName: String): Boolean =
        runCatching { source.exists(prefsName) }.getOrDefault(false)

    /**
     * The legacy file could not be read.
     *
     * A permanently unreadable legacy file means those bytes are gone by
     * definition — which is the pre-existing
     * [PersistenceHealth.reportDropped] case, kept here with its original
     * wording so the log and the user-facing notice are unchanged. A
     * transient failure (a busy `keystore2`, a momentary IO error) leaves
     * everything alone and is retried on the next launch.
     */
    private fun onLegacyFailure(
        context: Context,
        prefsName: String,
        source: LegacyPrefsSource,
        cause: Throwable,
    ) {
        if (!EncryptedPrefs.isLegacyUnreadable(cause)) {
            Log.w(TAG, "$prefsName: legacy import failed transiently; will retry on the next launch", cause)
            return
        }
        Log.e(
            TAG,
            "$prefsName: keyset unreadable (lost Keystore key); file discarded and " +
                "recreated — its contents are unrecoverable",
        )
        PersistenceHealth.reportDropped(prefsName)
        runCatching { source.delete(prefsName) }
        markComplete(context, prefsName)
    }

    internal fun isComplete(context: Context, prefsName: String): Boolean = runCatching {
        EncryptedPrefs.latchPrefs(context).getBoolean(markerKey(prefsName), false)
    }.getOrDefault(false)

    private fun markComplete(context: Context, prefsName: String) {
        runCatching {
            // commit(), not apply(): the legacy file is deleted immediately
            // after, so the marker has to be durable first.
            EncryptedPrefs.latchPrefs(context).edit().putBoolean(markerKey(prefsName), true).commit()
        }
    }
}

/**
 * Which kind of file a store's PREVIOUS incarnation lived in, and therefore
 * which reader [LegacyPrefsImport] must use to drain it — or [NONE] when
 * there is nothing left for this build to drain.
 *
 * **Why this is a required argument of [EncryptedPrefs.open] rather than a
 * lookup table.** Reading a legacy file with the wrong reader is silent: a
 * plain open of a file written in some other shape hands back that shape's
 * internal key and value strings, and the import "succeeds" anyway — the
 * marker is written and the real file is deleted, so the user's drafts are
 * gone with nothing logged. A store that forgets to say what it came from
 * must therefore fail to COMPILE, not pick a default. That is also why
 * [NONE] is spelled out at the call site instead of being the value an
 * omitted argument would take.
 */
internal enum class LegacyPrefsFormat {
    /**
     * The three stores that used to be UNENCRYPTED (`spk_drafts`,
     * `spk_attachment_drafts`, `spk_list_cache`): their previous file is an
     * ordinary `SharedPreferences` XML under the same name, and installs
     * that predate the switch still have it on disk.
     */
    PLAIN,

    /**
     * Nothing to import: the store either never had a previous file, or had
     * one this build can no longer read.
     *
     * That is the case for the five original encrypted stores
     * (`spk_pairing`, `spk_queue`, `spk_pending_sends`, `spk_history_cache`,
     * `spk_inflight_uploads`). Their pre-Tink file was an
     * `EncryptedSharedPreferences` one, and the only library that could
     * decrypt it — `androidx.security:security-crypto` — was deleted on
     * 2026-09-08, once the sole install in the field had already run the
     * importer to completion.
     *
     * The reader for this arm reports "no legacy file" whatever is on disk.
     * It deliberately does not fall back to [PLAIN]: an
     * `EncryptedSharedPreferences` file opened plain yields base64 key names
     * and base64 values, which would be copied in verbatim, the marker set
     * and the real file deleted. Leaving a file untouched is strictly better
     * than that.
     */
    NONE,
}

/** The reader for [format]'s on-disk shape. */
internal fun legacyPrefsSource(context: Context, format: LegacyPrefsFormat): LegacyPrefsSource =
    when (format) {
        LegacyPrefsFormat.PLAIN -> PlainLegacyPrefsSource(context.applicationContext)
        LegacyPrefsFormat.NONE -> NoLegacyPrefsSource
    }

/**
 * The [LegacyPrefsFormat.NONE] reader: there is no importable legacy file,
 * so [exists] is always false and [LegacyPrefsImport] goes no further than
 * recording the store as imported.
 *
 * [open] and [delete] are unreachable through [LegacyPrefsImport] (it never
 * calls either without [exists] having said yes) and are written so that
 * reaching them anyway is inert rather than destructive.
 */
private object NoLegacyPrefsSource : LegacyPrefsSource {

    override fun exists(prefsName: String): Boolean = false

    override fun open(prefsName: String): SharedPreferences? = null

    override fun delete(prefsName: String) = Unit
}

/**
 * Reads (and finally deletes) the ordinary `shared_prefs/<store>.xml` file a
 * store used before it was moved behind [TinkEncryptedPrefs].
 *
 * It never collides with the store's new file: the encrypted data lives in
 * `<store>_tink.xml` and its keysets in `<store>_tink_keyset.xml`, so the
 * name this source opens is only ever the pre-migration one.
 *
 * There is no master key to be unavailable here and no per-entry decrypt to
 * fail, so [open] never returns null and never throws in practice — the
 * import either runs to completion or the file was not there. The null and
 * throw contracts on [LegacyPrefsSource] are what the deleted androidx
 * reader needed, and they are still what [LegacyPrefsImportTest] provokes.
 */
private class PlainLegacyPrefsSource(private val app: Context) : LegacyPrefsSource {

    override fun exists(prefsName: String): Boolean =
        EncryptedPrefs.prefsFileExists(app, prefsName)

    override fun open(prefsName: String): SharedPreferences? =
        app.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun delete(prefsName: String) {
        app.deleteSharedPreferences(prefsName)
    }
}
