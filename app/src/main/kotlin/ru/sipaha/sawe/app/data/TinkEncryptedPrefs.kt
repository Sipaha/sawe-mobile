package ru.sipaha.sawe.app.data

import android.content.SharedPreferences
import com.google.crypto.tink.Aead
import com.google.crypto.tink.DeterministicAead
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * The two Tink primitives one [TinkEncryptedPrefs] instance needs.
 *
 * Passing them in (rather than deriving them inside the façade) is the
 * whole testability seam: production wiring is
 * [AppTinkKeysets.primitives], which unwraps an Android-Keystore-backed
 * keyset, while a unit test builds the same pair from a cleartext
 * in-memory keyset and gets identical semantics on a plain JVM.
 *
 * @property keyAead   deterministic (AES256_SIV) — deterministic so a key
 *                     can be *looked up* by re-encrypting it, reversible
 *                     so `getAll()` can recover plaintext key names.
 * @property valueAead probabilistic (AES256_GCM) — values are encrypted
 *                     with the *encrypted key bytes* as associated data,
 *                     which binds a ciphertext to the key it was written
 *                     under.
 */
internal class TinkPrefsPrimitives(
    val keyAead: DeterministicAead,
    val valueAead: Aead,
)

/**
 * Compact typed encoding for a [SharedPreferences] value.
 *
 * `SharedPreferences` is a heterogeneous map, so the plaintext has to
 * carry its own type; the ciphertext alone cannot say whether five bytes
 * are a string or an int. This is that plaintext framing — Tink encrypts
 * whatever [encode] produces and [decode] runs on what Tink returns.
 *
 * **Format (all integers big-endian):**
 *
 * ```
 *   byte 0   format version, currently 0x01
 *   byte 1   type tag
 *   byte 2.. payload
 *
 *   tag 0x01 STRING      payload = UTF-8 bytes (possibly empty)
 *   tag 0x02 STRING_SET  payload = repeat { int32 len ; UTF-8 bytes }
 *                        len == -1 encodes a null element, which the
 *                        platform does allow inside a Set<String>
 *   tag 0x03 INT         payload = int32
 *   tag 0x04 LONG        payload = int64
 *   tag 0x05 FLOAT       payload = int32, Float.toRawBits
 *   tag 0x06 BOOLEAN     payload = 1 byte, 0x00 false / 0x01 true
 * ```
 *
 * **Fail closed.** [decode] returns `null` — "this entry does not exist" —
 * for an unknown version byte, an unknown tag, a truncated payload or a
 * trailing-garbage payload. A value written by a *different* encoding
 * version is therefore invisible rather than reinterpreted: mis-typing a
 * persisted blob would hand a repository a plausible-looking wrong answer,
 * which is worse than an empty store it knows how to rebuild.
 */
internal object PrefValueCodec {

    const val VERSION: Byte = 0x01

    private const val TAG_STRING: Byte = 0x01
    private const val TAG_STRING_SET: Byte = 0x02
    private const val TAG_INT: Byte = 0x03
    private const val TAG_LONG: Byte = 0x04
    private const val TAG_FLOAT: Byte = 0x05
    private const val TAG_BOOLEAN: Byte = 0x06

    private val UTF8 = StandardCharsets.UTF_8

    fun encode(value: String): ByteArray = frame(TAG_STRING, value.toByteArray(UTF8))

    fun encode(value: Set<String?>): ByteArray {
        val parts = value.map { it?.toByteArray(UTF8) }
        val size = parts.sumOf { 4 + (it?.size ?: 0) }
        val buf = ByteBuffer.allocate(size)
        for (part in parts) {
            if (part == null) {
                buf.putInt(-1)
            } else {
                buf.putInt(part.size)
                buf.put(part)
            }
        }
        return frame(TAG_STRING_SET, buf.array())
    }

    fun encode(value: Int): ByteArray = frame(TAG_INT, ByteBuffer.allocate(4).putInt(value).array())

    fun encode(value: Long): ByteArray = frame(TAG_LONG, ByteBuffer.allocate(8).putLong(value).array())

    fun encode(value: Float): ByteArray =
        frame(TAG_FLOAT, ByteBuffer.allocate(4).putInt(value.toRawBits()).array())

    fun encode(value: Boolean): ByteArray = frame(TAG_BOOLEAN, byteArrayOf(if (value) 1 else 0))

    private fun frame(tag: Byte, payload: ByteArray): ByteArray {
        val out = ByteArray(2 + payload.size)
        out[0] = VERSION
        out[1] = tag
        payload.copyInto(out, 2)
        return out
    }

    /**
     * Decode [bytes] back into the `SharedPreferences` value it framed, or
     * `null` when the framing is not something this version understands.
     * Never throws.
     */
    fun decode(bytes: ByteArray): Any? {
        if (bytes.size < 2 || bytes[0] != VERSION) return null
        val payload = ByteBuffer.wrap(bytes, 2, bytes.size - 2)
        return runCatching {
            when (bytes[1]) {
                TAG_STRING -> String(bytes, 2, bytes.size - 2, UTF8)

                TAG_STRING_SET -> {
                    val out = LinkedHashSet<String?>()
                    while (payload.hasRemaining()) {
                        if (payload.remaining() < 4) return null
                        val len = payload.int
                        if (len == -1) {
                            out += null
                        } else {
                            if (len < 0 || len > payload.remaining()) return null
                            val chunk = ByteArray(len)
                            payload.get(chunk)
                            out += String(chunk, UTF8)
                        }
                    }
                    out
                }

                TAG_INT -> if (payload.remaining() != 4) return null else payload.int
                TAG_LONG -> if (payload.remaining() != 8) return null else payload.long
                TAG_FLOAT -> if (payload.remaining() != 4) return null else Float.fromBits(payload.int)
                TAG_BOOLEAN -> when {
                    payload.remaining() != 1 -> return null
                    else -> when (payload.get()) {
                        0.toByte() -> false
                        1.toByte() -> true
                        else -> return null
                    }
                }

                else -> null
            }
        }.getOrNull()
    }
}

/**
 * A [SharedPreferences] whose keys and values are encrypted with Tink,
 * stored inside an ordinary (plain) `SharedPreferences` [delegate].
 *
 * This is the in-house replacement for the deprecated
 * `androidx.security:security-crypto` `EncryptedSharedPreferences`, and it
 * deliberately reproduces that library's shape rather than inventing a new
 * one — the threat model this app documents (adversary with file-system
 * access on an unlocked device must not recover pairing secrets, queued
 * message text or cached transcripts) is the one androidx's design was
 * reasoned against, and everything upstream of `EncryptedPrefs.open`
 * already assumes those properties.
 *
 * **Why not DataStore.** Preferences DataStore is asynchronous by design.
 * Two call sites here are synchronous *on purpose* and say so in their own
 * KDoc — `SessionDetailStore.handleExpiredMessage` and the
 * `pendingSendsRepository.saveOrUpdate` inside `sendMessageBlocks`, both of
 * which accept a zero-length durability window (a process death *inside*
 * the write loses the message) precisely because the write completes before
 * the calling thread moves on. Moving them to DataStore would widen that
 * window to an unbounded scheduler delay. So the container stays
 * `SharedPreferences`; only the encryption layer changed.
 *
 * **Key encryption.** `AES256_SIV` via [TinkPrefsPrimitives.keyAead], with
 * a constant associated data. Deterministic encryption is required, not a
 * shortcut: a lookup has to be able to re-derive the stored key from the
 * plaintext one, and `getAll()` has to be able to walk back the other way
 * (`SessionHistoryRepository` rebuilds its index from `prefs.all`). The
 * cost is the standard SIV trade-off — an observer of the file learns
 * which entries share a key name, never what the name is.
 *
 * **Value encryption.** `AES256_GCM` via [TinkPrefsPrimitives.valueAead],
 * with **associated data = the encrypted key bytes**. A value ciphertext
 * lifted out of one entry and pasted into another therefore fails to
 * authenticate and reads as absent; without that binding, an attacker with
 * write access could swap two servers' pairing blobs around.
 *
 * **On-disk shape.** Delegate key = URL-safe Base64 of the key ciphertext;
 * delegate value = URL-safe Base64 of the value ciphertext, whose plaintext
 * is a [PrefValueCodec] frame. Nothing else is written to the delegate
 * file — in particular the Tink keyset lives in its *own* file (see
 * [AppTinkKeysets]), unlike `EncryptedSharedPreferences`, which kept the
 * keyset inside the data file.
 *
 * **Read failures are absences.** Any entry whose key cannot be decrypted,
 * whose value cannot be decrypted, or whose plaintext is not a frame this
 * build understands is skipped by [getAll] and reads as the caller's
 * default from the typed getters. Nothing in this class throws out of a
 * getter: every repository behind it already treats "missing" as a
 * recoverable cold-cache state, and none of them handle a
 * `GeneralSecurityException` mid-read.
 *
 * **Type mismatches also read as the default**, which is the one deliberate
 * divergence from the platform (`android.app.SharedPreferencesImpl` throws
 * `ClassCastException`). Nothing in this app relies on that throw, and a
 * throw here would be indistinguishable from "the file is corrupt", which
 * this class is required not to propagate.
 *
 * **Thread safety** is the delegate's: reads go straight through, and an
 * [Editor] is single-threaded like the platform's.
 */
internal class TinkEncryptedPrefs(
    private val delegate: SharedPreferences,
    primitives: TinkPrefsPrimitives,
) : SharedPreferences {

    private val keyAead = primitives.keyAead
    private val valueAead = primitives.valueAead

    /**
     * Wrappers handed to [delegate], keyed by the caller's listener so
     * [unregisterOnSharedPreferenceChangeListener] can find them again.
     */
    private val listeners =
        HashMap<SharedPreferences.OnSharedPreferenceChangeListener, SharedPreferences.OnSharedPreferenceChangeListener>()

    // ---------------------------------------------------------------- keys

    private fun encryptKey(key: String): ByteArray =
        keyAead.encryptDeterministically(key.toByteArray(StandardCharsets.UTF_8), KEY_ASSOCIATED_DATA)

    private fun decryptKey(storedKey: String): String? = runCatching {
        val raw = DECODER.decode(storedKey)
        String(keyAead.decryptDeterministically(raw, KEY_ASSOCIATED_DATA), StandardCharsets.UTF_8)
    }.getOrNull()

    private fun storedKeyOf(key: String): String = ENCODER.encodeToString(encryptKey(key))

    // -------------------------------------------------------------- values

    private fun storedValueOf(encryptedKey: ByteArray, plaintext: ByteArray): String =
        ENCODER.encodeToString(valueAead.encrypt(plaintext, encryptedKey))

    private fun decodeValue(encryptedKey: ByteArray, storedValue: String): Any? = runCatching {
        PrefValueCodec.decode(valueAead.decrypt(DECODER.decode(storedValue), encryptedKey))
    }.getOrNull()

    /** The decoded value stored under [key], or `null` if absent/unreadable. */
    private fun read(key: String): Any? {
        val encryptedKey = runCatching { encryptKey(key) }.getOrNull() ?: return null
        val stored = runCatching {
            delegate.getString(ENCODER.encodeToString(encryptedKey), null)
        }.getOrNull() ?: return null
        return decodeValue(encryptedKey, stored)
    }

    // ------------------------------------------------- SharedPreferences

    override fun getAll(): MutableMap<String, Any?> {
        val out = HashMap<String, Any?>()
        val raw = runCatching { delegate.all }.getOrNull() ?: return out
        for ((storedKey, storedValue) in raw) {
            if (storedValue !is String) continue
            val encryptedKey = runCatching { DECODER.decode(storedKey) }.getOrNull() ?: continue
            val plainKey = runCatching {
                String(
                    keyAead.decryptDeterministically(encryptedKey, KEY_ASSOCIATED_DATA),
                    StandardCharsets.UTF_8,
                )
            }.getOrNull() ?: continue
            val value = decodeValue(encryptedKey, storedValue) ?: continue
            out[plainKey] = value
        }
        return out
    }

    override fun getString(key: String, defValue: String?): String? = read(key) as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (read(key) as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String, defValue: Int): Int = read(key) as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = read(key) as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = read(key) as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean = read(key) as? Boolean ?: defValue

    /**
     * Key presence only, exactly like the platform (and like
     * `EncryptedSharedPreferences`): an entry whose ciphertext no longer
     * decrypts is still *present*, it just reads as the default.
     */
    override fun contains(key: String): Boolean =
        runCatching { delegate.contains(storedKeyOf(key)) }.getOrDefault(false)

    override fun edit(): SharedPreferences.Editor = EncryptingEditor(delegate.edit())

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        synchronized(listeners) {
            if (listeners.containsKey(listener)) return
            val wrapper = object : SharedPreferences.OnSharedPreferenceChangeListener {
                override fun onSharedPreferenceChanged(prefs: SharedPreferences?, storedKey: String?) {
                    if (storedKey == null) {
                        // clear() on API 30+ notifies with a null key.
                        listener.onSharedPreferenceChanged(this@TinkEncryptedPrefs, null)
                        return
                    }
                    // A key we cannot decrypt is not ours to report — swallowing
                    // it is better than handing the caller ciphertext.
                    decryptKey(storedKey)?.let {
                        listener.onSharedPreferenceChanged(this@TinkEncryptedPrefs, it)
                    }
                }
            }
            listeners[listener] = wrapper
            delegate.registerOnSharedPreferenceChangeListener(wrapper)
        }
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        synchronized(listeners) {
            val wrapper = listeners.remove(listener) ?: return
            delegate.unregisterOnSharedPreferenceChangeListener(wrapper)
        }
    }

    /**
     * Encrypts on the way in and forwards straight to the delegate's
     * [SharedPreferences.Editor].
     *
     * Forwarding rather than buffering is deliberate: one batch here is one
     * batch on the delegate, so the whole ordering question resolves exactly
     * as it would on a plain `SharedPreferences`. That matters because the
     * platform's *actual* behaviour and its own javadoc disagree — the doc
     * on `Editor.remove` claims "all removals are done first, regardless of
     * whether you called remove before or after put", while
     * `SharedPreferencesImpl` keeps one map and lets the last call for a
     * given key win. Buffering the batch ourselves would have meant picking
     * one of those two readings and diverging from the platform on the
     * other; forwarding means we are the platform, whichever it is.
     * `clear()` is still applied before the batch's puts, also as delegated.
     *
     * [commit] and [apply] are the delegate's, so `commit()` keeps its
     * synchronous-durability meaning. `EncryptedQueueStore.writeBlob`
     * depends on that: it runs on a single writer thread and treats a
     * returned `true` as "the queue is on disk".
     */
    private inner class EncryptingEditor(
        private val delegate: SharedPreferences.Editor,
    ) : SharedPreferences.Editor {

        private fun put(key: String, plaintext: ByteArray): SharedPreferences.Editor {
            val encryptedKey = encryptKey(key)
            delegate.putString(
                ENCODER.encodeToString(encryptedKey),
                storedValueOf(encryptedKey, plaintext),
            )
            return this
        }

        /** Platform contract: a null value is a removal, not a stored null. */
        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            if (value == null) remove(key) else put(key, PrefValueCodec.encode(value))

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
            if (values == null) remove(key) else put(key, PrefValueCodec.encode(values as Set<String?>))

        override fun putInt(key: String, value: Int): SharedPreferences.Editor =
            put(key, PrefValueCodec.encode(value))

        override fun putLong(key: String, value: Long): SharedPreferences.Editor =
            put(key, PrefValueCodec.encode(value))

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
            put(key, PrefValueCodec.encode(value))

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            put(key, PrefValueCodec.encode(value))

        override fun remove(key: String): SharedPreferences.Editor {
            delegate.remove(storedKeyOf(key))
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            delegate.clear()
            return this
        }

        override fun commit(): Boolean = delegate.commit()

        override fun apply() = delegate.apply()
    }

    companion object {
        /**
         * Associated data for the *key* AEAD. Constant on purpose — the key
         * ciphertext must depend only on the key plaintext, or lookups stop
         * working. It is a domain separator, nothing more: it stops a key
         * ciphertext from being replayed as a value ciphertext.
         */
        private val KEY_ASSOCIATED_DATA = "spk_prefs_key_v1".toByteArray(StandardCharsets.UTF_8)

        private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        private val DECODER: Base64.Decoder = Base64.getUrlDecoder()
    }
}
