package ru.sipaha.sawe.app.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException

/**
 * N-61 — a lost / invalidated Android Keystore keyset used to disable
 * persistence silently and permanently: opening the store threw, the
 * repository memoised `null`, and because the prefs file was never removed
 * the same failure repeated on every subsequent launch.
 *
 * [EncryptedPrefs.openWithRecovery] is the policy that fixes it, factored
 * out of [EncryptedPrefs.open] precisely so it can be tested without a
 * keystore (the real open needs one, which neither the JVM nor Robolectric
 * provides).
 *
 * The counterweight matters as much as the recovery: discarding the file
 * destroys the user's pairing / offline queue / drafts, so it must happen
 * *only* for a genuinely unreadable keyset. The `shouldDiscard` cases below
 * pin that boundary.
 */
class EncryptedPrefsRecoveryTest {

    private class Prefs(val generation: Int)

    /**
     * Fake prefs file: `create` fails with [failure] while [keysetReadable]
     * is false, and `discard` deletes the file — which also makes a fresh
     * keyset readable, exactly like deleting the real XML.
     */
    private class FakeStore(
        var fileExists: Boolean,
        var keysetReadable: Boolean,
        val failure: () -> Throwable = { GeneralSecurityException("keyset unreadable") },
    ) {
        var createAttempts = 0
        var discards = 0

        fun create(): Prefs {
            createAttempts++
            if (!keysetReadable) throw failure()
            return Prefs(createAttempts)
        }

        fun discard() {
            discards++
            fileExists = false
            keysetReadable = true
        }
    }

    private fun open(
        store: FakeStore,
        shouldDiscard: (Throwable) -> Boolean = { EncryptedPrefs.isKeysetFailure(it) },
    ) = EncryptedPrefs.openWithRecovery(
        create = { store.create() },
        shouldDiscard = shouldDiscard,
        hadExistingData = { store.fileExists },
        discard = { store.discard() },
    )

    // Happy path: a readable keyset opens on the first attempt and nothing
    // is deleted.
    @Test
    fun `healthy keyset opens without discarding anything`() {
        val store = FakeStore(fileExists = true, keysetReadable = true)

        val outcome = open(store)

        assertTrue(outcome is PrefsOpenOutcome.Opened, "got $outcome")
        assertEquals(1, store.createAttempts)
        assertEquals(0, store.discards)
    }

    // The regression: keyset lost, prefs file present. Before the fix this
    // returned null forever. Now the file is discarded once, the store
    // reopens, and the caller learns that data was thrown away.
    @Test
    fun `lost keyset discards the file, reopens, and reports dropped data`() {
        val store = FakeStore(fileExists = true, keysetReadable = false)

        val outcome = open(store)

        assertTrue(outcome is PrefsOpenOutcome.Recovered, "got $outcome")
        outcome as PrefsOpenOutcome.Recovered
        assertTrue(outcome.droppedData, "an existing prefs file was deleted; that is data loss")
        assertEquals(2, store.createAttempts)
        assertEquals(1, store.discards)
        assertEquals(2, outcome.prefs.generation, "must carry the freshly created prefs")
    }

    // Same failure with no file on disk (first launch racing a flaky
    // keystore): recovery still happens, but nothing was lost so the vm
    // layer must not be told otherwise.
    @Test
    fun `recovery with no prefs file on disk does not report dropped data`() {
        val store = FakeStore(fileExists = false, keysetReadable = false)

        val outcome = open(store)

        assertTrue(outcome is PrefsOpenOutcome.Recovered, "got $outcome")
        assertFalse((outcome as PrefsOpenOutcome.Recovered).droppedData)
    }

    // --- the counterweight: when NOT to delete -----------------------------

    // A transient keystore fault (busy keystore2 daemon, TEE contention)
    // leaves the on-disk bytes perfectly intact. Deleting them would destroy
    // the pairing, the offline queue and the drafts for nothing.
    @Test
    fun `transient keystore failure never touches the file`() {
        val store = FakeStore(
            fileExists = true,
            keysetReadable = false,
            failure = { ProviderException("Keystore operation failed") },
        )

        val outcome = open(store)

        assertTrue(outcome is PrefsOpenOutcome.Unavailable, "got $outcome")
        assertEquals(0, store.discards, "a transient fault must not delete user data")
        assertEquals(1, store.createAttempts, "no point retrying against the same dead keystore")
    }

    // The `shouldDiscard` veto is honoured even for a keyset-shaped
    // throwable — this is how the once-per-store latch keeps a repeating
    // failure from deleting on every single launch.
    @Test
    fun `a vetoed discard leaves the file alone and reports unavailable`() {
        val store = FakeStore(fileExists = true, keysetReadable = false)

        val outcome = open(store, shouldDiscard = { false })

        assertTrue(outcome is PrefsOpenOutcome.Unavailable, "got $outcome")
        assertEquals(0, store.discards)
    }

    // isKeysetFailure is the classifier the real opener uses: Tink's
    // unwrap/parse failures qualify, keystore-daemon faults do not, and the
    // check walks the cause chain because Tink wraps.
    @Test
    fun `isKeysetFailure accepts Tink failures and rejects keystore faults`() {
        assertTrue(EncryptedPrefs.isKeysetFailure(GeneralSecurityException("decryption failed")))
        assertTrue(EncryptedPrefs.isKeysetFailure(IOException("can't parse keyset")))
        assertTrue(
            EncryptedPrefs.isKeysetFailure(RuntimeException("wrapped", GeneralSecurityException("x"))),
            "the classifier must walk the cause chain",
        )

        assertFalse(EncryptedPrefs.isKeysetFailure(ProviderException("Keystore operation failed")))
        assertFalse(EncryptedPrefs.isKeysetFailure(IllegalStateException("master key unavailable")))
        assertFalse(
            EncryptedPrefs.isKeysetFailure(GeneralSecurityException("x", ProviderException("busy"))),
            "a keystore fault under a security wrapper is still a keystore fault",
        )
    }

    // A self-referential cause chain must not hang the classifier.
    @Test
    fun `isKeysetFailure terminates on a cyclic cause chain`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)

        assertFalse(EncryptedPrefs.isKeysetFailure(b))
    }

    // No working keystore at all: both attempts fail, we degrade to the
    // pre-existing "persistence off" behaviour, and both failures are kept
    // for the log.
    @Test
    fun `unrecoverable keystore yields Unavailable carrying both failures`() {
        val store = FakeStore(fileExists = true, keysetReadable = false)
        val outcome = EncryptedPrefs.openWithRecovery<Prefs>(
            create = { store.create() },
            shouldDiscard = { true },
            hadExistingData = { store.fileExists },
            // A discard that cannot repair anything (e.g. the keystore is gone).
            discard = { store.discards++ },
        )

        assertTrue(outcome is PrefsOpenOutcome.Unavailable, "got $outcome")
        outcome as PrefsOpenOutcome.Unavailable
        assertEquals(2, store.createAttempts)
        assertEquals(1, outcome.cause.suppressed.size, "the first failure must not be swallowed")
    }

    // If the "does a file exist" probe itself fails we assume it did, so the
    // user is told about the loss rather than it being hidden.
    @Test
    fun `an unresolvable data-dir probe errs toward reporting the loss`() {
        val store = FakeStore(fileExists = true, keysetReadable = false)

        val outcome = EncryptedPrefs.openWithRecovery(
            create = { store.create() },
            shouldDiscard = { true },
            hadExistingData = { error("data dir cannot be resolved") },
            discard = { store.discard() },
        )

        assertTrue((outcome as PrefsOpenOutcome.Recovered).droppedData)
    }

    // Recovery is attempted exactly once per open — a second open of an
    // already-repaired store must not delete anything again.
    @Test
    fun `second open after recovery is a plain Opened`() {
        val store = FakeStore(fileExists = true, keysetReadable = false)
        open(store)

        val outcome = open(store)

        assertTrue(outcome is PrefsOpenOutcome.Opened, "got $outcome")
        assertEquals(1, store.discards)
    }

    // The signal the vm layer reads: a drop is published, survives until
    // acknowledged, and is idempotent.
    @Test
    fun `PersistenceHealth publishes and clears the dropped-store signal`() {
        PersistenceHealth.resetForTest()

        PersistenceHealth.reportDropped(EncryptedQueueStore.PREFS_NAME)
        PersistenceHealth.reportDropped(EncryptedQueueStore.PREFS_NAME)
        assertEquals(setOf(EncryptedQueueStore.PREFS_NAME), PersistenceHealth.droppedStores.value)

        PersistenceHealth.acknowledgeDropped(EncryptedQueueStore.PREFS_NAME)
        assertTrue(PersistenceHealth.droppedStores.value.isEmpty())
        PersistenceHealth.resetForTest()
    }

    // Unavailable is a separate signal from dropped, and a later successful
    // open clears it.
    @Test
    fun `PersistenceHealth tracks unavailable separately from dropped`() {
        PersistenceHealth.resetForTest()

        PersistenceHealth.reportUnavailable(PendingSendsRepository.PREFS_NAME)
        assertEquals(setOf(PendingSendsRepository.PREFS_NAME), PersistenceHealth.unavailableStores.value)
        assertTrue(PersistenceHealth.droppedStores.value.isEmpty())

        PersistenceHealth.reportAvailable(PendingSendsRepository.PREFS_NAME)
        assertTrue(PersistenceHealth.unavailableStores.value.isEmpty())
        PersistenceHealth.resetForTest()
    }
}
