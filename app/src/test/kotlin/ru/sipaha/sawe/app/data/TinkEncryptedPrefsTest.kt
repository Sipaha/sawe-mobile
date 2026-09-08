package ru.sipaha.sawe.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import com.google.crypto.tink.DeterministicAead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.daead.DeterministicAeadConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

/**
 * [TinkEncryptedPrefs] is the in-house replacement for the deprecated
 * `androidx.security:security-crypto` `EncryptedSharedPreferences`, and it
 * is the only thing standing between four repositories and their bytes —
 * so its `SharedPreferences` semantics have to be exactly the platform's,
 * and its cryptographic properties have to be the ones the app's threat
 * model already assumes.
 *
 * The Android Keystore is not available under Robolectric, which is why the
 * class takes its [TinkPrefsPrimitives] as a constructor parameter: these
 * cases drive it with cleartext in-memory Tink keysets, which exercise
 * exactly the same code paths the keystore-wrapped production keysets do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TinkEncryptedPrefsTest {

    private lateinit var app: Context
    private var fileCounter = 0

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        AeadConfig.register()
        DeterministicAeadConfig.register()
    }

    /** A fresh, unshared delegate file per call, so cases cannot bleed. */
    private fun delegate(name: String = "delegate_${fileCounter++}"): SharedPreferences =
        app.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun cleartextKeysets(): Pair<KeysetHandle, KeysetHandle> =
        KeysetHandle.generateNew(KeyTemplates.get("AES256_SIV")) to
            KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))

    private fun primitives(keysets: Pair<KeysetHandle, KeysetHandle>) = TinkPrefsPrimitives(
        keysets.first.getPrimitive(RegistryConfiguration.get(), DeterministicAead::class.java),
        keysets.second.getPrimitive(RegistryConfiguration.get(), Aead::class.java),
    )

    private fun prefs(
        delegate: SharedPreferences = delegate(),
        keysets: Pair<KeysetHandle, KeysetHandle> = cleartextKeysets(),
    ) = TinkEncryptedPrefs(delegate, primitives(keysets))

    // ------------------------------------------------------------ round trip

    @Test
    fun `every supported value type survives a write-read round trip`() {
        val prefs = prefs()

        prefs.edit()
            .putString("s", "hello")
            .putString("empty", "")
            .putStringSet("set", mutableSetOf("a", "b", "c"))
            .putStringSet("emptySet", mutableSetOf())
            .putInt("i", -12345)
            .putLong("l", Long.MIN_VALUE)
            .putFloat("f", -0.5f)
            .putBoolean("bTrue", true)
            .putBoolean("bFalse", false)
            .commit()

        assertEquals("hello", prefs.getString("s", null))
        assertEquals("", prefs.getString("empty", null))
        assertEquals(setOf("a", "b", "c"), prefs.getStringSet("set", null))
        assertEquals(emptySet<String>(), prefs.getStringSet("emptySet", null))
        assertEquals(-12345, prefs.getInt("i", 0))
        assertEquals(Long.MIN_VALUE, prefs.getLong("l", 0L))
        assertEquals(-0.5f, prefs.getFloat("f", 0f), 0f)
        assertTrue(prefs.getBoolean("bTrue", false))
        assertFalse(prefs.getBoolean("bFalse", true))
    }

    // The awkward payloads: the codec is length-prefixed and UTF-8, so a NUL
    // byte and astral-plane text must not be mistaken for a terminator, and a
    // blob far past any buffer heuristic must round-trip byte for byte.
    @Test
    fun `non-ASCII, NUL bytes and very large strings round trip`() {
        val prefs = prefs()
        val gnarly = "\u0000привет 🚀 — ünïcödé\u0000tail"
        val huge = buildString { repeat(200_000) { append(('a' + (it % 26))) } }

        prefs.edit()
            .putString("gnarly", gnarly)
            .putString("huge", huge)
            .putStringSet("gnarlySet", mutableSetOf(gnarly, "", "\u0000"))
            .commit()

        assertEquals(gnarly, prefs.getString("gnarly", null))
        assertEquals(huge, prefs.getString("huge", null))
        assertEquals(setOf(gnarly, "", "\u0000"), prefs.getStringSet("gnarlySet", null))
    }

    // Keys are as adversarial as values: they go through the deterministic
    // AEAD and then Base64, so the same awkward bytes have to survive.
    @Test
    fun `non-ASCII and empty keys round trip`() {
        val prefs = prefs()
        prefs.edit().putString("", "empty key").putString("ключ 🚀", "unicode key").commit()

        assertEquals("empty key", prefs.getString("", null))
        assertEquals("unicode key", prefs.getString("ключ 🚀", null))
        assertEquals(setOf("", "ключ 🚀"), prefs.all.keys)
    }

    // --------------------------------------------------- SharedPreferences API

    @Test
    fun `getAll returns plaintext keys and typed values`() {
        val prefs = prefs()
        prefs.edit()
            .putString("a", "one")
            .putInt("b", 2)
            .putStringSet("c", mutableSetOf("x"))
            .commit()

        val all = prefs.all

        assertEquals(setOf("a", "b", "c"), all.keys)
        assertEquals("one", all["a"])
        assertEquals(2, all["b"])
        assertEquals(setOf("x"), all["c"])
    }

    @Test
    fun `contains, remove and clear behave like SharedPreferences`() {
        val prefs = prefs()
        prefs.edit().putString("a", "one").putString("b", "two").commit()

        assertTrue(prefs.contains("a"))
        assertFalse(prefs.contains("nope"))

        prefs.edit().remove("a").commit()
        assertFalse(prefs.contains("a"))
        assertNull(prefs.getString("a", null))
        assertEquals("two", prefs.getString("b", null))

        prefs.edit().clear().commit()
        assertTrue(prefs.all.isEmpty())
        assertFalse(prefs.contains("b"))
    }

    // Mixing remove() and put() for the same key in one batch is the corner
    // where the platform javadoc ("all removals are done first") and
    // SharedPreferencesImpl (one map, last call wins) disagree. The contract
    // this class promises is "identical to a plain SharedPreferences", so the
    // assertion is an equivalence against one, not a guess at which doc wins.
    @Test
    fun `a mixed remove and put batch behaves exactly like a plain SharedPreferences`() {
        val plain = delegate("plain_reference")
        val prefs = prefs()

        for (target in listOf<SharedPreferences>(plain, prefs)) {
            target.edit().putString("k", "old").putString("j", "old").commit()
            target.edit().remove("k").putString("k", "new").commit()
            target.edit().putString("j", "new").remove("j").commit()
        }

        assertEquals(plain.getString("k", "<absent>"), prefs.getString("k", "<absent>"))
        assertEquals(plain.getString("j", "<absent>"), prefs.getString("j", "<absent>"))
        assertEquals(plain.contains("j"), prefs.contains("j"))
        // Whatever the platform does, remove-then-put on the same key keeps
        // the put — that is the direction a repository actually relies on.
        assertEquals("new", prefs.getString("k", null))
    }

    // Platform contract: a null value is a removal, not a stored null.
    @Test
    fun `putting null removes the entry`() {
        val prefs = prefs()
        prefs.edit().putString("k", "v").putStringSet("s", mutableSetOf("v")).commit()

        prefs.edit().putString("k", null).putStringSet("s", null).commit()

        assertFalse(prefs.contains("k"))
        assertFalse(prefs.contains("s"))
    }

    @Test
    fun `clear then put in one batch keeps the put`() {
        val prefs = prefs()
        prefs.edit().putString("old", "gone").commit()

        prefs.edit().clear().putString("new", "kept").commit()

        assertNull(prefs.getString("old", null))
        assertEquals("kept", prefs.getString("new", null))
    }

    @Test
    fun `change listeners see the DECRYPTED key and stop after unregister`() {
        val prefs = prefs()
        val seen = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> seen += key }

        prefs.registerOnSharedPreferenceChangeListener(listener)
        prefs.edit().putString("visible-name", "v").commit()
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        prefs.edit().putString("after-unregister", "v").commit()

        assertEquals(listOf("visible-name"), seen)
    }

    // ----------------------------------------------------------- cryptography

    // The whole point of the class: the file must not leak what it stores.
    @Test
    fun `the delegate file holds neither the plaintext key nor the plaintext value`() {
        val delegate = delegate("leak_check")
        val prefs = prefs(delegate)

        prefs.edit()
            .putString("pairing_url", "wss://secret.example/abc")
            .putInt("secret_int_key", 42)
            .commit()

        val onDisk = File(File(app.dataDir, "shared_prefs"), "leak_check.xml").readText()
        assertFalse(onDisk.contains("pairing_url"))
        assertFalse(onDisk.contains("wss://secret.example/abc"))
        assertFalse(onDisk.contains("secret_int_key"))
        // And the raw delegate really does see ciphertext, not our plaintext keys.
        assertFalse(delegate.all.keys.contains("pairing_url"))
        assertEquals(2, delegate.all.size)
    }

    // Key encryption MUST be deterministic across process restarts, or every
    // relaunch would look at a store full of unreachable entries. Rebuilding
    // the primitives from a serialised keyset is the closest a JVM test gets
    // to a real second launch.
    @Test
    fun `the same key maps to the same stored key across two instances`() {
        val keysets = cleartextKeysets()
        val delegate = delegate("determinism")

        prefs(delegate, keysets).edit().putString("k", "v").commit()
        val storedKeys = delegate.all.keys.toSet()

        val reparsed = TinkJsonProtoKeysetFormat.parseKeyset(
            TinkJsonProtoKeysetFormat.serializeKeyset(keysets.first, InsecureSecretKeyAccess.get()),
            InsecureSecretKeyAccess.get(),
        ) to TinkJsonProtoKeysetFormat.parseKeyset(
            TinkJsonProtoKeysetFormat.serializeKeyset(keysets.second, InsecureSecretKeyAccess.get()),
            InsecureSecretKeyAccess.get(),
        )
        val second = prefs(delegate, reparsed)

        assertEquals("v", second.getString("k", null))
        second.edit().putString("k", "v2").commit()
        assertEquals("the second write must land on the SAME stored key", storedKeys, delegate.all.keys)
    }

    // A different keyset must not produce the same stored key — otherwise the
    // determinism above would be an artefact of a constant, not of the key.
    @Test
    fun `a different keyset produces a different stored key`() {
        val a = delegate("ks_a")
        val b = delegate("ks_b")
        prefs(a).edit().putString("k", "v").commit()
        prefs(b).edit().putString("k", "v").commit()

        assertNotEquals(a.all.keys.first(), b.all.keys.first())
    }

    // Associated data = the encrypted key bytes. Moving a value ciphertext to
    // another entry must fail to authenticate, so an attacker with write
    // access cannot swap two entries' contents around.
    @Test
    fun `a value ciphertext moved to another key reads as absent`() {
        val delegate = delegate("ad_binding")
        val keysets = cleartextKeysets()
        val prefs = prefs(delegate, keysets)
        prefs.edit().putString("victim", "victim-value").putString("attacker", "attacker-value").commit()

        val stored = delegate.all.mapValues { it.value as String }
        val victimStored = stored.keys.first { storedKeyDecodesTo(keysets, it, "victim") }
        val attackerStored = stored.keys.first { storedKeyDecodesTo(keysets, it, "attacker") }

        // Paste the attacker's value ciphertext under the victim's key.
        delegate.edit().putString(victimStored, stored.getValue(attackerStored)).commit()

        assertNull("must not surrender another key's plaintext", prefs.getString("victim", null))
        assertFalse("and must not surface in getAll() either", prefs.all.containsKey("victim"))
        // The entry is still *present*, exactly like a corrupt platform entry.
        assertTrue(prefs.contains("victim"))
        // The attacker's own entry is untouched.
        assertEquals("attacker-value", prefs.getString("attacker", null))
    }

    private fun storedKeyDecodesTo(
        keysets: Pair<KeysetHandle, KeysetHandle>,
        storedKey: String,
        plaintext: String,
    ): Boolean = runCatching {
        val daead = keysets.first.getPrimitive(RegistryConfiguration.get(), DeterministicAead::class.java)
        val decoded = Base64.getUrlDecoder().decode(storedKey)
        String(daead.decryptDeterministically(decoded, "spk_prefs_key_v1".toByteArray())) == plaintext
    }.getOrDefault(false)

    @Test
    fun `a corrupt or truncated stored value reads as absent instead of throwing`() {
        val delegate = delegate("corrupt")
        val prefs = prefs(delegate)
        prefs.edit().putString("garbage", "a").putString("wrongBytes", "b").putInt("truncated", 7).commit()

        val keysets = cleartextKeysets()
        val keys = delegate.all.keys.toList()
        val intact = delegate.all.mapValues { it.value as String }
        delegate.edit()
            // Not Base64 at all.
            .putString(keys[0], "!!! not base64 !!!")
            // Valid Base64, but not a Tink ciphertext.
            .putString(keys[1], Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(9)))
            // A real ciphertext with its tail cut off.
            .putString(keys[2], intact.getValue(keys[2]).dropLast(8))
            .commit()

        // None of these may throw, and none may return a value.
        assertNull(prefs.getString("garbage", null))
        assertNull(prefs.getString("wrongBytes", null))
        assertEquals(0, prefs.getInt("truncated", 0))
        assertTrue("getAll() must skip unreadable entries", prefs.all.isEmpty())
        // A stored key that is not even decodable must not break enumeration.
        delegate.edit().putString("*** not a stored key ***", "x").commit()
        assertTrue(prefs.all.isEmpty())
        // Unused, but keeps the helper honest about which keyset is which.
        assertFalse(storedKeyDecodesTo(keysets, keys[0], "garbage"))
    }

    // ------------------------------------------------------------ value codec

    @Test
    fun `an unknown encoding version decodes as absent rather than being mis-typed`() {
        val encoded = PrefValueCodec.encode("hello")
        assertEquals("hello", PrefValueCodec.decode(encoded))

        val fromTheFuture = encoded.copyOf().also { it[0] = 0x02 }
        assertNull(PrefValueCodec.decode(fromTheFuture))

        val unknownType = encoded.copyOf().also { it[1] = 0x7F }
        assertNull(PrefValueCodec.decode(unknownType))

        assertNull(PrefValueCodec.decode(ByteArray(0)))
        assertNull(PrefValueCodec.decode(byteArrayOf(PrefValueCodec.VERSION)))
    }

    @Test
    fun `a truncated codec payload decodes as absent`() {
        assertNull(PrefValueCodec.decode(PrefValueCodec.encode(42L).copyOf(6)))
        assertNull(PrefValueCodec.decode(PrefValueCodec.encode(42).copyOf(4)))
        assertNull(PrefValueCodec.decode(PrefValueCodec.encode(setOf("abc")).copyOf(5)))
        // A boolean payload that is neither 0 nor 1 is not a boolean.
        assertNull(PrefValueCodec.decode(PrefValueCodec.encode(true).copyOf().also { it[2] = 9 }))
    }

    // A wrongly-typed read returns the caller's default rather than throwing:
    // the class must never let a corrupt store take down a getter, and every
    // repository behind it already treats "missing" as a cold-cache state.
    @Test
    fun `a typed getter on a differently-typed entry returns the default`() {
        val prefs = prefs()
        prefs.edit().putString("k", "not an int").commit()

        assertEquals(-1, prefs.getInt("k", -1))
        assertEquals(-1L, prefs.getLong("k", -1L))
        assertFalse(prefs.getBoolean("k", false))
        assertNull(prefs.getStringSet("k", null))
    }
}
