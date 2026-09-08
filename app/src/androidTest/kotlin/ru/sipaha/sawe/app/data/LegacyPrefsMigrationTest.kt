package ru.sipaha.sawe.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * One store's worth of legacy contents, plus an optional assertion made
 * through the repository that actually owns the store.
 *
 * [toString] is the JUnit parameter label, so a failure names the store.
 *
 * Public, not `internal`: it is the constructor parameter of the public
 * JUnit-parameterised class below, and Kotlin will not let a public
 * signature expose an internal type. Nothing on its surface is internal.
 */
class StoreFixture(
    val storeId: String,
    val entries: Map<String, Any>,
    /** Extra check run against the REAL repository once the import landed. */
    val verifyThroughRepository: (Context) -> Unit = {},
) {
    override fun toString(): String = storeId
}

/**
 * Proves the one-way "legacy prefs file -> [TinkEncryptedPrefs]" migration
 * against a REAL file, on a REAL device, through the production keyset
 * wiring.
 *
 * This is the gap the JVM suite structurally cannot close.
 * `LegacyPrefsImportTest` drives [LegacyPrefsImport] through a simulated
 * [LegacyPrefsSource], and `PlainStoreEncryptionTest` drives the real plain
 * reader — but both do it under Robolectric, where the keysets are handed
 * in as cleartext because the Android Keystore does not exist. Neither has
 * ever run an import whose target was opened by `AndroidKeysetManager` over
 * `android-keystore://`, and that combination is what ships. A failure
 * there is silent: the store degrades to "no persistence" and the user
 * loses whatever the legacy file held, on the one launch that owns it.
 *
 * **Which stores.** The three that still have an importable legacy file —
 * [EncryptedPrefsDevice.PLAIN_LEGACY_STORE_IDS]. The other five migrated off
 * an `EncryptedSharedPreferences` file, and the only library that could
 * write such a fixture (`androidx.security:security-crypto`) was deleted on
 * 2026-09-08 once the sole install in the field had run the importer; they
 * now pass [LegacyPrefsFormat.NONE] and import nothing, which
 * `ProductionKeysetTest` covers as fresh-install opens.
 *
 * The legacy side below is therefore written with the platform's own
 * `getSharedPreferences` — exactly what `PlainLegacyPrefsSource` reads, so
 * these are genuine on-disk bytes in the shipped format.
 *
 * `spk_drafts` additionally carries a realistic payload under the key shapes
 * [DraftRepository] itself uses, and is re-read through that repository
 * after the import, so a key-scheme regression fails here and not only a
 * byte-copy bug.
 */
@RunWith(Parameterized::class)
class LegacyPrefsMigrationTest(private val fixture: StoreFixture) {

    private lateinit var app: Context

    private val storeId: String get() = fixture.storeId

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        EncryptedPrefsDevice.wipe(app, storeId)
    }

    @After
    fun tearDown() {
        EncryptedPrefsDevice.wipe(app, storeId)
    }

    // ------------------------------------------------------------------ case

    @Test
    fun realLegacyFileIsImportedByteForByte() {
        // 1. Genuine on-disk bytes, in the format the shipped builds wrote.
        writeRealLegacyFile(fixture.entries)
        val legacy = EncryptedPrefsDevice.legacyFile(app, storeId)
        assertTrue("legacy file was not created at ${legacy.absolutePath}", legacy.exists())

        // 2. The production open path: Keystore master key, AndroidKeysetManager
        //    keysets, then LegacyPrefsImport against the real legacy reader.
        val prefs = EncryptedPrefsDevice.open(app, storeId)
        assertNotNull("$storeId: EncryptedPrefs.open returned null — persistence is OFF", prefs)
        assertEntries(requireNotNull(prefs), fixture.entries, "after import")

        // 3. The import committed: legacy file gone, marker recorded.
        assertFalse("$storeId: legacy file survived a completed import", legacy.exists())
        assertTrue("$storeId: import marker not set", LegacyPrefsImport.isComplete(app, storeId))

        // 4. Nothing readable landed on disk.
        assertNoPlaintextOnDisk()

        // 5. A genuine cold open — memoised keysets dropped, so the keyset
        //    file is re-read and re-unwrapped exactly as after a process
        //    restart. A sentinel written between the two opens proves the
        //    second open neither re-imports over the top nor wipes.
        requireNotNull(prefs).edit().putString(SENTINEL_KEY, SENTINEL_VALUE).commit()
        EncryptedPrefsDevice.forgetMemoisedKeys()

        val reopened = EncryptedPrefsDevice.open(app, storeId)
        assertNotNull("$storeId: second EncryptedPrefs.open returned null", reopened)
        assertEntries(requireNotNull(reopened), fixture.entries, "after cold reopen")
        assertEquals(
            "$storeId: entry count changed across the cold reopen",
            fixture.entries.size + 1,
            requireNotNull(reopened).all.size,
        )
        assertEquals(SENTINEL_VALUE, requireNotNull(reopened).getString(SENTINEL_KEY, null))
        assertFalse("$storeId: legacy file came back", legacy.exists())

        // 6. The repository that owns the store agrees, decoding with its
        //    own serializer.
        fixture.verifyThroughRepository(app)

        // 7. A clean import is not a data loss and not an outage.
        assertTrue(
            "$storeId: reported as dropped by a clean import",
            storeId !in PersistenceHealth.droppedStores.value,
        )
        assertTrue(
            "$storeId: reported unavailable after a successful open",
            storeId !in PersistenceHealth.unavailableStores.value,
        )
    }

    // ---------------------------------------------------------------- legacy

    /**
     * Writes [entries] into `shared_prefs/<store>.xml` the same way the
     * shipped builds that predate the encryption did — the platform's own
     * prefs file under the store's logical name, which is exactly what
     * `PlainLegacyPrefsSource` opens. `commit()`, not `apply()` — the import
     * runs immediately after.
     */
    private fun writeRealLegacyFile(entries: Map<String, Any>) {
        val legacy: SharedPreferences = app.getSharedPreferences(storeId, Context.MODE_PRIVATE)
        val editor = legacy.edit()
        editor.clear()
        for ((key, value) in entries) {
            when (value) {
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toMutableSet())
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                else -> throw IllegalArgumentException("unsupported fixture value for $key: $value")
            }
        }
        assertTrue("legacy commit() failed for $storeId", editor.commit())
    }

    // ------------------------------------------------------------ assertions

    private fun assertEntries(prefs: SharedPreferences, entries: Map<String, Any>, where: String) {
        for ((key, expected) in entries) {
            val label = "$storeId/$key ($where)"
            when (expected) {
                // Compared as whole strings rather than dumped: the large
                // value is ~70 KB and a JUnit diff of it is unreadable.
                is String -> {
                    val actual = prefs.getString(key, null)
                    assertTrue(
                        "$label: expected ${expected.length} chars, got " +
                            (actual?.length?.toString() ?: "null"),
                        expected == actual,
                    )
                }

                is Set<*> -> assertEquals(label, expected, prefs.getStringSet(key, null))
                is Int -> assertEquals(label, expected, prefs.getInt(key, Int.MIN_VALUE))
                is Long -> assertEquals(label, expected, prefs.getLong(key, Long.MIN_VALUE))
                is Float -> assertEquals(label, expected, prefs.getFloat(key, Float.NaN), 0f)
                is Boolean -> assertEquals(label, expected, prefs.getBoolean(key, !expected))
                else -> throw IllegalArgumentException("unsupported fixture value for $key")
            }
            assertTrue("$label: contains() says the key is absent", prefs.contains(key))
        }
    }

    /**
     * The new data file must be ciphertext through and through — neither a
     * key name nor a value fragment may be legible in it. Numeric fixtures
     * are skipped: a four-byte int is not a meaningful probe.
     */
    private fun assertNoPlaintextOnDisk() {
        val file = EncryptedPrefsDevice.dataFile(app, storeId)
        assertTrue("$storeId: no data file at ${file.absolutePath}", file.exists())
        val raw = file.readBytes()
        assertTrue("$storeId: data file is empty", raw.isNotEmpty())
        for ((key, value) in fixture.entries) {
            assertFalse(
                "$storeId: plaintext KEY '$key' is legible in ${file.name}",
                EncryptedPrefsDevice.containsUtf8(raw, key),
            )
            val probe = when (value) {
                is String -> value.take(64).takeIf { it.length >= 8 }
                is Set<*> -> value.filterIsInstance<String>().firstOrNull()?.takeIf { it.length >= 8 }
                else -> null
            } ?: continue
            assertFalse(
                "$storeId: plaintext VALUE of '$key' is legible in ${file.name}",
                EncryptedPrefsDevice.containsUtf8(raw, probe),
            )
        }
    }

    companion object {
        private const val SENTINEL_KEY = "spk_it_written_after_import"
        private const val SENTINEL_VALUE = "written-between-the-two-opens"

        private const val SERVER_ID = "11111111-2222-3333-4444-555555555555"
        private const val SESSION_ID = "sess-42"

        /**
         * A draft and a bounce under the exact keys [DraftRepository] builds
         * (`draft:<serverId>:<sessionId>` / `bounced:…`), so the fixture is
         * what a real pre-encryption install had on disk rather than a
         * plausible-looking string.
         */
        private const val DRAFT_TEXT = "недописанное сообщение — draft 🎉\nsecond line"
        private const val BOUNCED_TEXT = "это не ушло ни на один сервер"

        /**
         * The awkward values every store is checked against: non-ASCII, an
         * empty string, one >= 64 KB blob, plus every other type
         * `SharedPreferences.Editor` can carry (all of which
         * [LegacyPrefsImport] has a branch for).
         *
         * No embedded NUL. The legacy file here is an ordinary
         * `SharedPreferences` XML, and U+0000 has no XML representation — no
         * shipped build could have written one, so probing for it would test
         * the fixture writer rather than the import. (The androidx fixture
         * this file used to carry could: it base64-encoded every value.)
         */
        private fun commonEntries(): Map<String, Any> = linkedMapOf(
            "spk_it_ascii" to "plain-ascii-value",
            "spk_it_unicode" to "Привет, мир — 日本語 ✅ 🎉",
            "spk_it_empty" to "",
            "spk_it_large" to largeValue(),
            "spk_it_int" to 1_234_567,
            "spk_it_long" to 9_007_199_254_740_993L,
            "spk_it_float" to 3.5f,
            "spk_it_bool" to true,
            "spk_it_set" to linkedSetOf("alpha-set-member", "бета-set-member"),
        )

        /** >= 64 KB, from a distinctive token so the plaintext scan means something. */
        private fun largeValue(): String = buildString {
            var i = 0
            while (length < 70_000) append("spk-large-payload-marker-").append(i++).append('|')
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<Array<Any>> {
            val drafts = StoreFixture(
                storeId = DraftRepository.PREFS_NAME,
                entries = commonEntries() + mapOf(
                    "draft:$SERVER_ID:$SESSION_ID" to DRAFT_TEXT,
                    "bounced:$SERVER_ID:$SESSION_ID" to BOUNCED_TEXT,
                ),
                verifyThroughRepository = { context ->
                    val repo = DraftRepository(context)
                    repo.activeServerProvider = { SERVER_ID }
                    assertEquals("draft did not survive the import", DRAFT_TEXT, repo.load(SESSION_ID))
                    assertEquals(
                        "bounced message did not survive the import",
                        BOUNCED_TEXT,
                        repo.bouncedFor(SESSION_ID),
                    )
                    // bouncedFor() is read-and-clear: the slot is gone now,
                    // which also proves the value came from the store and
                    // not from a default.
                    assertNull("bounce slot was not cleared", repo.bouncedFor(SESSION_ID))
                },
            )
            // Every store that still has an importable legacy file. The five
            // that migrated off `EncryptedSharedPreferences` import nothing
            // now — see the class kdoc.
            val rest = EncryptedPrefsDevice.PLAIN_LEGACY_STORE_IDS
                .filter { it != drafts.storeId }
                .map { StoreFixture(storeId = it, entries = commonEntries()) }
            return (listOf(drafts) + rest).map { arrayOf<Any>(it) }
        }
    }
}
