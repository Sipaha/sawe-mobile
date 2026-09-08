package ru.sipaha.sawe.app.data

import com.google.crypto.tink.Aead
import com.google.crypto.tink.DeterministicAead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.daead.DeterministicAeadConfig

/**
 * Stands in for the Android Keystore so a JVM/Robolectric test can drive the
 * REAL [EncryptedPrefs.open] — recovery latch, legacy import, repositories
 * and all — instead of stopping at the one call neither environment can
 * serve.
 *
 * The keysets are generated in memory rather than unwrapped through the
 * keystore; [AppTinkKeysets] memoises them per store exactly as it memoises
 * the real ones, so two opens of the same store inside one case agree on a
 * key. The production keyset wiring this replaces is covered on a device by
 * `EncryptedPrefsDevice`.
 *
 * Any test that constructs a repository backed by [EncryptedPrefs.open] needs
 * this. Without it the open returns `null`, every such store degrades to a
 * no-op disk layer, and the resulting failures read as "the feature lost my
 * data" rather than as a missing fixture.
 */
internal object TinkTestKeysets {

    /** Install the stand-in. Pair with [uninstall] in `@After`. */
    fun install() {
        AeadConfig.register()
        DeterministicAeadConfig.register()
        AppTinkKeysets.resetForTest()
        AppTinkKeysets.primitivesForTest = { newPrimitives() }
    }

    /** Drop the stand-in and every keyset it memoised. */
    fun uninstall() {
        AppTinkKeysets.resetForTest()
    }

    fun newPrimitives(): TinkPrefsPrimitives = TinkPrefsPrimitives(
        KeysetHandle.generateNew(KeyTemplates.get("AES256_SIV"))
            .getPrimitive(RegistryConfiguration.get(), DeterministicAead::class.java),
        KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java),
    )
}
