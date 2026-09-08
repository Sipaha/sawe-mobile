package ru.sipaha.sawe.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Exercises the PRODUCTION keyset wiring — `AndroidKeysetManager` over an
 * `android-keystore://` master key — which nothing had ever executed.
 *
 * The JVM suite injects cleartext in-memory keysets, because the Android
 * Keystore does not exist under Robolectric. That covers the
 * [TinkEncryptedPrefs] semantics and none of [AppTinkKeysets]: if
 * `masterKeyAvailable` or `primitives` throws on a real device, every
 * store degrades to "no persistence" silently (the app logs a warning and
 * keeps running), and the user loses their pairing, their offline queue and
 * their drafts with no crash to point at.
 *
 * The load-bearing case here is [keysetSurvivesASimulatedProcessRestart]. A
 * keyset that is *regenerated* rather than unwrapped would make every store
 * read back **empty** — persistence would look healthy and the data would
 * be gone. Reading the same values back through a re-derived keyset is what
 * distinguishes the two.
 */
@RunWith(JUnit4::class)
class ProductionKeysetTest {

    private lateinit var app: Context

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        EncryptedPrefsDevice.wipeAll(app)
    }

    @After
    fun tearDown() {
        app.deleteSharedPreferences(DISK_COPY)
        EncryptedPrefsDevice.wipeAll(app)
    }

    // ------------------------------------------------------------------ open

    @Test
    fun freshInstallOpensEveryStoreAndRoundTrips() {
        for (storeId in EncryptedPrefsDevice.STORE_IDS) {
            assertFalse(
                "$storeId: fixture left a legacy file behind",
                EncryptedPrefsDevice.legacyFile(app, storeId).exists(),
            )
            assertFalse(
                "$storeId: fixture left a Tink data file behind",
                EncryptedPrefsDevice.dataFile(app, storeId).exists(),
            )

            val prefs = EncryptedPrefsDevice.open(app, storeId)
            assertNotNull(
                "$storeId: EncryptedPrefs.open returned null on a FRESH install — the " +
                    "Android Keystore path is broken and this store has no persistence",
                prefs,
            )
            assertTrue(requireNotNull(prefs).edit().putString(KEY, valueFor(storeId)).commit())
            assertEquals(valueFor(storeId), requireNotNull(prefs).getString(KEY, null))

            assertTrue(
                "$storeId: no keyset file was created",
                EncryptedPrefsDevice.keysetFile(app, storeId).exists(),
            )
        }
    }

    // --------------------------------------------------------------- restart

    @Test
    fun keysetSurvivesASimulatedProcessRestart() {
        for (storeId in EncryptedPrefsDevice.STORE_IDS) {
            val prefs = requireNotNull(EncryptedPrefsDevice.open(app, storeId))
            assertTrue(prefs.edit().putString(KEY, valueFor(storeId)).putLong(LONG_KEY, 42L).commit())
        }

        // Everything the process memoised is gone; the keyset files and the
        // Keystore alias are all that is left.
        EncryptedPrefsDevice.forgetMemoisedKeys()

        for (storeId in EncryptedPrefsDevice.STORE_IDS) {
            val reopened = EncryptedPrefsDevice.open(app, storeId)
            assertNotNull("$storeId: could not re-open after dropping the memoised keyset", reopened)
            assertEquals(
                "$storeId: value read back EMPTY after a simulated restart — the keyset was " +
                    "regenerated rather than unwrapped, which is silent data loss",
                valueFor(storeId),
                requireNotNull(reopened).getString(KEY, null),
            )
            assertEquals(42L, requireNotNull(reopened).getLong(LONG_KEY, -1L))
        }

        assertTrue(
            "a plain restart reported dropped stores: ${PersistenceHealth.droppedStores.value}",
            PersistenceHealth.droppedStores.value.isEmpty(),
        )
    }

    /**
     * The stronger form of the case above: the delegate the platform hands
     * back is process-cached, so a re-read could in principle be served from
     * memory. Here the ciphertext is taken from the **file on disk** (copied
     * to a name this process has never touched, so the platform has to parse
     * it) and decrypted with a keyset re-derived from the keyset file after
     * every memoisation was dropped. Nothing in the path is cached.
     */
    @Test
    fun ciphertextOnDiskDecryptsWithAKeysetReDerivedFromDisk() {
        val storeId = PairingRepository.PREFS_NAME
        val prefs = requireNotNull(EncryptedPrefsDevice.open(app, storeId))
        assertTrue(prefs.edit().putString(KEY, valueFor(storeId)).commit())

        val onDisk = EncryptedPrefsDevice.dataFile(app, storeId)
        assertTrue("no data file at ${onDisk.absolutePath}", onDisk.exists())
        onDisk.copyTo(EncryptedPrefsDevice.prefsFile(app, DISK_COPY), overwrite = true)

        EncryptedPrefsDevice.forgetMemoisedKeys()

        val fromDisk = TinkEncryptedPrefs(
            app.getSharedPreferences(DISK_COPY, Context.MODE_PRIVATE),
            AppTinkKeysets.primitives(app, storeId),
        )
        assertEquals(
            "ciphertext read straight off disk did not decrypt with the re-derived keyset",
            valueFor(storeId),
            fromDisk.getString(KEY, null),
        )
    }

    // ------------------------------------------------------------ separation

    @Test
    fun eachStoreGetsItsOwnKeysetAndCannotReadAnother() {
        val a = PairingRepository.PREFS_NAME
        val b = EncryptedQueueStore.PREFS_NAME

        val prefsA = requireNotNull(EncryptedPrefsDevice.open(app, a))
        val prefsB = requireNotNull(EncryptedPrefsDevice.open(app, b))
        // Same key, same value in both, so a match below could only come
        // from a shared keyset and never from the payload differing.
        assertTrue(prefsA.edit().putString(KEY, SHARED_VALUE).commit())
        assertTrue(prefsB.edit().putString(KEY, SHARED_VALUE).commit())

        val keysetA = app.getSharedPreferences(AppTinkKeysets.keysetPrefsFile(a), Context.MODE_PRIVATE).all
        val keysetB = app.getSharedPreferences(AppTinkKeysets.keysetPrefsFile(b), Context.MODE_PRIVATE).all
        assertTrue("$a has no keyset entries", keysetA.isNotEmpty())
        assertNotEquals("$a and $b share a keyset", keysetA, keysetB)

        // Deterministic key encryption means identical keysets would produce
        // identical stored key names. They must not.
        val rawA = app.getSharedPreferences(EncryptedPrefs.dataFileName(a), Context.MODE_PRIVATE).all.keys
        val rawB = app.getSharedPreferences(EncryptedPrefs.dataFileName(b), Context.MODE_PRIVATE).all.keys
        assertTrue("stored key names collide across stores", (rawA intersect rawB).isEmpty())

        // Cross-wiring: B's keyset over A's file reads as an empty store,
        // not as B's data and not as an exception out of a getter.
        val crossed = TinkEncryptedPrefs(
            app.getSharedPreferences(EncryptedPrefs.dataFileName(a), Context.MODE_PRIVATE),
            AppTinkKeysets.primitives(app, b),
        )
        assertNull("$b's keyset decrypted $a's file", crossed.getString(KEY, null))
        assertTrue("$b's keyset enumerated $a's file", crossed.all.isEmpty())

        // Control: A's own keyset still reads A's file.
        assertEquals(SHARED_VALUE, prefsA.getString(KEY, null))
    }

    // ---------------------------------------------------------------- health

    @Test
    fun persistenceHealthReportsEveryStoreAvailableAndNothingDropped() {
        for (storeId in EncryptedPrefsDevice.STORE_IDS) {
            assertNotNull(storeId, EncryptedPrefsDevice.open(app, storeId))
        }
        assertTrue(
            "stores reported unavailable on a healthy device: " +
                "${PersistenceHealth.unavailableStores.value}",
            PersistenceHealth.unavailableStores.value.isEmpty(),
        )
        assertTrue(
            "stores reported dropped on a happy path: ${PersistenceHealth.droppedStores.value}",
            PersistenceHealth.droppedStores.value.isEmpty(),
        )
    }

    // ------------------------------------------------------------ repository

    /**
     * The same proof one level up, so the claim is about the app's behaviour
     * and not only about the prefs layer: a pairing saved through
     * [PairingRepository] is still there for a repository built after every
     * memoised keyset was dropped.
     */
    @Test
    fun pairingSurvivesASimulatedProcessRestartThroughTheRepository() {
        val server = PairedServer(
            id = "6c6c1b18-0a0a-4f4f-9e9e-0123456789ab",
            pairingUrl = "sawe-remote://10.0.2.2:8765?secret=cGFpcmluZy1zZWNyZXQ&" +
                "client=emulator&server_fp=ZmluZ2VycHJpbnQ",
            label = "10.0.2.2:8765",
            fingerprintHex = "0123456789abcdef0123456789abcdef",
            firstPairedAtMs = 1_757_000_000_000L,
            lastConnectedAtMs = 1_757_100_000_000L,
        )

        PairingRepository(app).apply {
            upsert(server)
            setActive(server.id)
            assertEquals(listOf(server), loadAll())
        }

        EncryptedPrefsDevice.forgetMemoisedKeys()

        val restarted = PairingRepository(app)
        assertEquals(
            "the paired server did not survive a simulated process restart",
            listOf(server),
            restarted.loadAll(),
        )
        assertEquals(server.id, restarted.activeServerId())
    }

    private fun valueFor(storeId: String) = "value-for-$storeId"

    private companion object {
        const val KEY = "spk_it_keyset_probe"
        const val LONG_KEY = "spk_it_keyset_probe_long"
        const val SHARED_VALUE = "identical-in-both-stores"

        /**
         * A prefs name this process has never opened, so reading it forces
         * the platform to parse the XML instead of serving a cached map.
         */
        val DISK_COPY = "${EncryptedPrefs.dataFileName(PairingRepository.PREFS_NAME)}_diskcopy"
    }
}
