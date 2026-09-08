package ru.sipaha.sawe.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import com.google.crypto.tink.DeterministicAead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.daead.DeterministicAeadConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException

/**
 * The one-way `EncryptedSharedPreferences` → [TinkEncryptedPrefs] import.
 *
 * This is the only part of the migration that can lose user data — the
 * pairing, the offline queue, the pending-send markers and the transcript
 * cache all live behind it — so the cases below pin the invariant stated on
 * [LegacyPrefsImport]: the `imported:<store>` marker in `spk_prefs_health`
 * is set **iff** every legacy entry has been committed into the new store,
 * and the legacy file is deleted only after that.
 *
 * The real legacy reader needs an Android Keystore that neither the JVM nor
 * Robolectric provides, which is exactly why [LegacyPrefsSource] is an
 * injected interface: here it is a plain (unencrypted) `SharedPreferences`
 * standing in for the encrypted one. The REAL on-disk legacy format is
 * verified separately on an emulator; this file verifies the policy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LegacyPrefsImportTest {

    private lateinit var app: Context

    private val store = "spk_queue"

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        AeadConfig.register()
        DeterministicAeadConfig.register()
        PersistenceHealth.resetForTest()
        app.getSharedPreferences(EncryptedPrefs.LATCH_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        PersistenceHealth.resetForTest()
    }

    /**
     * Stand-in for `EncryptedSharedPreferences`: a plain prefs file, plus
     * the two failure modes the real one has (an unreadable keyset, and an
     * unavailable master key).
     */
    private class FakeLegacySource(
        private val app: Context,
        private val fileName: String,
        var present: Boolean = true,
        var failure: Throwable? = null,
        var masterKeyAvailable: Boolean = true,
    ) : LegacyPrefsSource {
        var opens = 0
        var deletes = 0

        val backing: SharedPreferences =
            app.getSharedPreferences(fileName, Context.MODE_PRIVATE)

        override fun exists(prefsName: String): Boolean = present

        override fun open(prefsName: String): SharedPreferences? {
            opens++
            failure?.let { throw it }
            if (!masterKeyAvailable) return null
            return backing
        }

        override fun delete(prefsName: String) {
            deletes++
            present = false
            backing.edit().clear().commit()
        }
    }

    private fun newStore(name: String = "target"): SharedPreferences {
        AeadConfig.register()
        DeterministicAeadConfig.register()
        return TinkEncryptedPrefs(
            app.getSharedPreferences(name, Context.MODE_PRIVATE),
            TinkPrefsPrimitives(
                KeysetHandle.generateNew(KeyTemplates.get("AES256_SIV"))
                    .getPrimitive(RegistryConfiguration.get(), DeterministicAead::class.java),
                KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
                    .getPrimitive(RegistryConfiguration.get(), Aead::class.java),
            ),
        )
    }

    private fun populatedLegacy(): FakeLegacySource {
        val source = FakeLegacySource(app, "legacy_$store")
        source.backing.edit()
            .putString("queue:server-1", """[{"csid":1}]""")
            .putStringSet("servers", mutableSetOf("a", "b"))
            .putInt("schema", 3)
            .putLong("last_write", 1_700_000_000_000L)
            .putFloat("ratio", 0.25f)
            .putBoolean("dirty", true)
            .commit()
        return source
    }

    private fun markerSet() = LegacyPrefsImport.isComplete(app, store)

    // ------------------------------------------------------------ happy path

    @Test
    fun `a populated legacy store is imported entirely and then deleted`() {
        val source = populatedLegacy()
        val target = newStore()

        LegacyPrefsImport.run(app, store, target, source)

        assertEquals("""[{"csid":1}]""", target.getString("queue:server-1", null))
        assertEquals(setOf("a", "b"), target.getStringSet("servers", null))
        assertEquals(3, target.getInt("schema", 0))
        assertEquals(1_700_000_000_000L, target.getLong("last_write", 0L))
        assertEquals(0.25f, target.getFloat("ratio", 0f), 0f)
        assertTrue(target.getBoolean("dirty", false))
        assertEquals(6, target.all.size)

        assertTrue("the marker must be set once the commit landed", markerSet())
        assertFalse("the legacy file must be gone", source.present)
        assertEquals(1, source.deletes)
    }

    @Test
    fun `a second open is a no-op that does not re-read the legacy store`() {
        val source = populatedLegacy()
        val target = newStore()

        LegacyPrefsImport.run(app, store, target, source)
        val afterFirst = target.all
        LegacyPrefsImport.run(app, store, target, source)
        LegacyPrefsImport.run(app, store, target, source)

        assertEquals("the legacy store must be opened exactly once, ever", 1, source.opens)
        assertEquals(afterFirst, target.all)
    }

    // A fresh install has no legacy file: record completion immediately so no
    // later launch pays for the probe (and so the legacy library is never
    // reached on a device that never had one).
    @Test
    fun `a fresh install marks the import complete without touching the legacy layer`() {
        val source = FakeLegacySource(app, "legacy_$store", present = false)
        val target = newStore()

        LegacyPrefsImport.run(app, store, target, source)

        assertTrue(markerSet())
        assertEquals(0, source.opens)
        assertEquals(0, source.deletes)
        assertTrue(target.all.isEmpty())
    }

    // ---------------------------------------------------------- interruption

    // The invariant's whole point: a process death between the data commit
    // and the marker leaves the legacy file in place, so the next open re-runs
    // the import from scratch and overwrites whatever partial state it finds.
    @Test
    fun `an import interrupted before the marker is re-run and ends complete`() {
        val source = populatedLegacy()
        val target = newStore()

        // Simulate the crash: half the entries landed, no marker was written,
        // and the legacy file was (correctly) never deleted.
        target.edit()
            .putString("queue:server-1", "STALE PARTIAL WRITE")
            .putInt("schema", 999)
            .commit()
        assertFalse(markerSet())
        assertTrue(source.present)

        LegacyPrefsImport.run(app, store, target, source)

        assertEquals("""[{"csid":1}]""", target.getString("queue:server-1", null))
        assertEquals(3, target.getInt("schema", 0))
        assertEquals(6, target.all.size)
        assertTrue(markerSet())
        assertFalse(source.present)
    }

    // The other crash window: the marker landed but the delete did not. The
    // import must NOT re-run (that would resurrect entries the app has since
    // removed) — it must just finish the delete.
    @Test
    fun `a legacy file left over from a completed import is deleted, not re-imported`() {
        val source = populatedLegacy()
        val target = newStore()
        LegacyPrefsImport.run(app, store, target, source)
        // Put the legacy file back, as if the delete had been lost.
        source.present = true
        target.edit().clear().commit()

        LegacyPrefsImport.run(app, store, target, source)

        assertTrue("no re-import", target.all.isEmpty())
        assertEquals("still only ever opened once", 1, source.opens)
        assertFalse(source.present)
    }

    // ------------------------------------------------------------- failures

    // Pre-existing behaviour, carried over verbatim: an unreadable legacy
    // keyset means those bytes are gone, so the loss is published rather than
    // retried forever.
    @Test
    fun `an unreadable legacy keyset reports a dropped store and stops retrying`() {
        val source = populatedLegacy()
        source.failure = GeneralSecurityException("decryption failed")
        val target = newStore()

        LegacyPrefsImport.run(app, store, target, source)

        assertEquals(setOf(store), PersistenceHealth.droppedStores.value)
        assertTrue(markerSet())
        assertFalse(source.present)

        LegacyPrefsImport.run(app, store, target, source)
        assertEquals("must not keep re-opening a store it declared lost", 1, source.opens)
    }

    // androidx wraps a per-entry Tink decrypt failure in a plain
    // SecurityException rather than a GeneralSecurityException; it belongs in
    // the same unrecoverable class.
    @Test
    fun `a SecurityException from the legacy reader is unrecoverable too`() {
        val source = populatedLegacy()
        source.failure = SecurityException("Could not decrypt value")
        val target = newStore()

        LegacyPrefsImport.run(app, store, target, source)

        assertEquals(setOf(store), PersistenceHealth.droppedStores.value)
        assertTrue(markerSet())
    }

    // A busy keystore2 daemon is transient. Nothing may be deleted, nothing
    // may be marked complete, and nothing may be reported to the user.
    @Test
    fun `a transient keystore fault leaves the legacy file alone and retries`() {
        val source = populatedLegacy()
        source.failure = GeneralSecurityException(ProviderException("keystore2 busy"))
        val target = newStore()

        LegacyPrefsImport.run(app, store, target, source)

        assertTrue("legacy data must survive a transient fault", source.present)
        assertEquals(0, source.deletes)
        assertFalse(markerSet())
        assertTrue(PersistenceHealth.droppedStores.value.isEmpty())

        // Next launch, the daemon is healthy again.
        source.failure = null
        LegacyPrefsImport.run(app, store, target, source)
        assertEquals(6, target.all.size)
        assertTrue(markerSet())
    }

    // The legacy master key being unavailable says nothing about the file's
    // contents, so the import is deferred, not declared complete.
    @Test
    fun `an unavailable legacy master key defers the import`() {
        val source = populatedLegacy()
        source.masterKeyAvailable = false
        val target = newStore()

        LegacyPrefsImport.run(app, store, target, source)

        assertFalse(markerSet())
        assertTrue(source.present)
        assertEquals(0, source.deletes)
        assertTrue(target.all.isEmpty())

        source.masterKeyAvailable = true
        LegacyPrefsImport.run(app, store, target, source)
        assertTrue(markerSet())
        assertEquals(6, target.all.size)
    }

    // ------------------------------------------------------- classification

    @Test
    fun `isLegacyUnreadable matches the unrecoverable throwables only`() {
        assertTrue(EncryptedPrefs.isLegacyUnreadable(GeneralSecurityException("bad keyset")))
        assertTrue(EncryptedPrefs.isLegacyUnreadable(IOException("unparseable")))
        assertTrue(EncryptedPrefs.isLegacyUnreadable(SecurityException("could not decrypt")))
        assertTrue(EncryptedPrefs.isLegacyUnreadable(RuntimeException(GeneralSecurityException("wrapped"))))

        assertFalse(EncryptedPrefs.isLegacyUnreadable(ProviderException("keystore2 busy")))
        assertFalse(EncryptedPrefs.isLegacyUnreadable(GeneralSecurityException(ProviderException("busy"))))
        assertFalse(EncryptedPrefs.isLegacyUnreadable(IllegalStateException("something else")))
    }

    // The four (now five) logical store ids are what PersistenceHealth
    // publishes and what the vm layer maps to user-facing copy. The Tink
    // migration derives its physical file names FROM them and must not
    // change them.
    @Test
    fun `logical store ids are unchanged and the physical files are derived from them`() {
        assertEquals("spk_pairing", PairingRepository.PREFS_NAME)
        assertEquals("spk_queue", EncryptedQueueStore.PREFS_NAME)
        assertEquals("spk_pending_sends", PendingSendsRepository.PREFS_NAME)
        assertEquals("spk_history_cache", SessionHistoryRepository.PREFS_NAME)

        assertEquals("spk_queue_tink", EncryptedPrefs.dataFileName("spk_queue"))
        assertEquals("spk_queue_tink_keyset", AppTinkKeysets.keysetPrefsFile("spk_queue"))
        // The legacy file keeps the bare logical name — that is what the old
        // builds wrote, and the importer has to find it.
        assertNull(
            "the new layer must not collide with the legacy file",
            "spk_queue".takeIf { it == EncryptedPrefs.dataFileName("spk_queue") },
        )
    }
}
