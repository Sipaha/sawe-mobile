package ru.sipaha.sawe.app.vm

import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.sipaha.sawe.app.data.EncryptedQueueStore
import ru.sipaha.sawe.app.data.PairedServer
import ru.sipaha.sawe.app.data.PairingRepository
import ru.sipaha.sawe.app.data.TinkTestKeysets
import ru.sipaha.sawe.core.ConnectionState
import ru.sipaha.sawe.core.PairingUrl
import ru.sipaha.sawe.core.QueuedMessage
import ru.sipaha.sawe.core.RemoteClient

/**
 * The duplicate-connection storm, 2026-09-08 — production regression, not an
 * audit finding.
 *
 * **The defect.** [ConnectionManager] is constructed per [MainViewModel],
 * which is constructed per `MainActivity` (`by viewModels()`), so it is
 * Activity-scoped. `.MainActivity` had no `launchMode` and therefore
 * defaulted to `standard`, which lets a second instance of it join the task.
 * Two Activity instances ⇒ two ViewModels ⇒ two `ConnectionManager`s ⇒ two
 * `RemoteClient`s against one desktop. The server allows a single connection
 * per client id and evicts the older socket on every fresh authentication
 * (`1001 "evicted by new connection"`), so each client read the eviction as a
 * network drop and re-dialled, evicting the other. On the real device: 576
 * authentications in 3.5 h, every socket living exactly as long as the OTHER
 * ladder's current backoff (1 s, 2 s, 4 s, 8 s, 16 s), messages stuck on
 * "Waiting for connection", uploads failing on the phone while the desktop
 * logged `upload_finish OK`.
 *
 * **The fix is two layers, and both are covered here.**
 *  1. `android:launchMode="singleTask"` on `.MainActivity` removes the
 *     trigger — pinned by the manifest scan at the bottom of this file.
 *  2. `ConnectionGuard` in `ConnectionManager.kt` removes the failure mode:
 *     at most one live client per process, last bind wins. Everything else
 *     here.
 *
 * **Why this needs a live [ConnectionManager] rather than a decision
 * function.** Everything else in `ConnectionLifecycleDecisionsTest` is a
 * pure helper, but the bug is not a decision — it is two objects that did
 * not know about each other. Nothing short of two real managers reproduces
 * it, which is also why the 67-finding network audit missed it entirely:
 * every one of its tests constructs exactly one client.
 *
 * The server address is a dead loopback port on purpose. The socket never
 * comes up, which is all these cases need: the bind path — where the guard
 * lives — runs to completion before the first handshake attempt is made, and
 * `RemoteClient` publishes states from its own reconnect ladder either way.
 * The `:core` half of the fix (that a closed client's ladder really stops,
 * and that the handover keeps the durable queue) is [ru.sipaha.sawe.core.ClientHandoffTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DuplicateConnectionGuardTest {

    private companion object {
        /**
         * Nothing listens here. `RemoteClient` fails the connect and enters
         * its reconnect ladder, which is the state the guard has to be able
         * to stop.
         */
        const val DEAD_PORT_A = 9
        const val DEAD_PORT_B = 10

        /**
         * How long the loser is watched for signs of life after the
         * handover. Comfortably past the first rung of the real backoff
         * ladder (1 s), so an unstopped ladder cannot hide inside it.
         */
        const val LADDER_WATCH_MS = 2_500L

        /** Bound on "the manager got as far as binding a client". */
        const val BIND_TIMEOUT_MS = 10_000L

        /** Body of the message parked in the durable queue across a handover. */
        const val PARKED_TEXT = "typed before the second Activity appeared"

        /** Queue-record id of that message. */
        const val PARKED_ID = "queued-across-the-handover"
    }

    /** Records what the coordinator would have been told. */
    private class RecordingLifecycle : ConnectionLifecycle {
        val bound = mutableListOf<RemoteClient>()
        var tearDowns: Int = 0
        val errors = mutableListOf<String>()
        val expired = mutableListOf<QueuedMessage>()

        override fun onClientBound(url: PairingUrl, client: RemoteClient) {
            bound += client
        }

        override fun onTearDown() {
            tearDowns++
        }

        override fun onReconnected() = Unit
        override fun onMessageExpired(message: QueuedMessage) {
            expired += message
        }

        override fun onBeforeSwitch() = Unit
        override fun onError(message: String) {
            errors += message
        }
    }

    /** One manager plus everything needed to drive and dispose of it. */
    private class Managed(
        val manager: ConnectionManager,
        val lifecycle: RecordingLifecycle,
        val scope: CoroutineScope,
    )

    private val managed = mutableListOf<Managed>()

    @Before
    fun setUp() {
        // Both the pairing list and the outbound queue live in Tink-encrypted
        // prefs; without a stand-in keyset every store degrades to a no-op and
        // the manager would find no server to connect to at all.
        TinkTestKeysets.install()
        val repo = PairingRepository.get(app())
        // The repositories are process-wide singletons and Robolectric keeps
        // static state across cases in a sandbox, so start from a known list
        // rather than an assumed-empty one.
        repo.loadAll().forEach { repo.remove(it.id) }
        repo.upsert(server("srv-a", DEAD_PORT_A))
        repo.upsert(server("srv-b", DEAD_PORT_B))
        repo.setActive("srv-a")
        // Same reason as the pairing list: the queue store is process-wide.
        EncryptedQueueStore.get(app()) { "srv-a" }.clearAllServers()
    }

    @After
    fun tearDown() {
        // Release the process-wide guard slot before the next case runs, and
        // stop every ladder this one started.
        managed.asReversed().forEach { runCatching { it.manager.tearDownConnection() } }
        managed.forEach { runCatching { it.scope.cancel() } }
        managed.clear()
        TinkTestKeysets.uninstall()
    }

    // -----------------------------------------------------------------
    // 1. Last bind wins
    // -----------------------------------------------------------------

    @Test
    fun `a second manager evicts the first instead of racing it`() {
        val first = newManager()
        val firstClient = awaitBoundClient(first)

        val second = newManager()
        val secondClient = awaitBoundClient(second)

        assertNotSame("the two managers must build their own clients", firstClient, secondClient)
        // The whole defect in one assertion: before the guard, BOTH of these
        // were live and both were dialling the same desktop.
        assertNull(
            "the evicted manager still owns a client — two clients per process",
            first.manager.activeClient(),
        )
        assertEquals(
            "the newest manager must keep the connection",
            secondClient,
            second.manager.activeClient(),
        )
        assertTrue(
            "the loser's RemoteClient was dropped without being closed",
            firstClient.isClosed,
        )
        assertFalse("the winner must not be closed", secondClient.isClosed)
        assertTrue(
            "the evicted manager should have been told to reset its stores",
            first.lifecycle.tearDowns >= 1,
        )
    }

    // -----------------------------------------------------------------
    // 2. The loser's ladder is actually stopped
    // -----------------------------------------------------------------

    /**
     * A "handover" that merely detached the loser would be the original bug
     * with extra steps: its reconnect ladder would keep authenticating and
     * keep evicting the winner. The loser's `connectionState` is the ladder's
     * only outward sign — a live ladder walks Reconnecting → Connecting →
     * Reconnecting on every rung — so a window with no transition at all is
     * the evidence that it stopped.
     */
    @Test
    fun `the evicted manager's reconnect ladder stops dead`() {
        val first = newManager()
        val firstClient = awaitBoundClient(first)
        // Let the loser's ladder actually get going, so "no states" below
        // means "stopped", not "never started".
        awaitLadderRunning(firstClient)

        val second = newManager()
        awaitBoundClient(second)

        // The ladder assertion comes FIRST on purpose: it is the one that
        // reports what actually went wrong ("still dialling"), and putting
        // the cheap `isClosed` check ahead of it would mask that with a
        // one-line boolean failure.
        val after = collectStates(firstClient, LADDER_WATCH_MS)
        assertTrue(
            "the evicted client kept driving its reconnect ladder: $after",
            after.isEmpty(),
        )
        assertTrue("the guard must close the loser", firstClient.isClosed)
        assertTrue(
            "final state of the evicted client: ${firstClient.connectionState.value}",
            firstClient.connectionState.value is ConnectionState.Disconnected,
        )
    }

    // -----------------------------------------------------------------
    // 3. The handover costs the user nothing
    // -----------------------------------------------------------------

    /**
     * "Last bind wins" is only a safe policy because the loser is retired
     * through `RemoteClient.close()`, whose contract is a HANDOFF: a queue
     * record that never reached a socket stays on disk for the next client
     * and is explicitly NOT bounced back to the composer (bouncing it would
     * let the user send it twice). The durable queue is keyed per server and
     * both managers share the one process-wide [EncryptedQueueStore], so the
     * winner inherits it.
     *
     * This is the property that keeps the guard from trading a reconnect
     * storm for silent data loss, which is why it is pinned separately from
     * the eviction itself. That the winner then actually puts the inherited
     * message on the wire needs a live socket and is covered in
     * [ru.sipaha.sawe.core.ClientHandoffTest].
     */
    @Test
    fun `a message parked in the loser's queue survives the handover`() {
        val queue = EncryptedQueueStore.get(app()) { "srv-a" }
        queue.add(
            QueuedMessage(
                id = PARKED_ID,
                method = "remote.solution_agent.send_message",
                params = buildJsonObject { put("content", PARKED_TEXT) },
                // Fresh: `switchToServerLocked` drains TTL-expired entries
                // before every bind, and an aged fixture would be swept by
                // that rather than by anything under test.
                enqueuedAtMs = System.currentTimeMillis(),
            ),
        )
        assertEquals(listOf(PARKED_ID), queue.loadAll().map { it.id })

        val first = newManager()
        val firstClient = awaitBoundClient(first)
        val second = newManager()
        awaitBoundClient(second)
        assertTrue("precondition: the guard evicted the first manager", firstClient.isClosed)

        assertEquals(
            "the handover ate a message the user had typed",
            listOf(PARKED_ID),
            queue.loadAll().map { it.id },
        )
        assertTrue(
            "nothing was lost, so nothing may be bounced to the composer: " +
                "${first.lifecycle.expired + second.lifecycle.expired}",
            first.lifecycle.expired.isEmpty() && second.lifecycle.expired.isEmpty(),
        )
    }

    // -----------------------------------------------------------------
    // 4. The ordinary single-manager lifecycle is untouched
    // -----------------------------------------------------------------

    @Test
    fun `one manager switching servers rebinds without tripping the guard`() {
        val only = newManager()
        val onServerA = awaitBoundClient(only)

        only.manager.switchToServer("srv-b")
        val onServerB = awaitBoundClient(only, after = onServerA)

        // A rebind is the SAME manager claiming the slot again. The guard
        // must not see itself as its own duplicate — if it did, it would tear
        // the manager down immediately after every server switch.
        assertNotSame(onServerA, onServerB)
        assertTrue("the previous server's client must be closed on a switch", onServerA.isClosed)
        assertFalse("the new server's client must be live", onServerB.isClosed)
        assertEquals(onServerB, only.manager.activeClient())
        assertEquals("srv-b", only.manager.activeServerId.value)
        assertTrue("no errors expected on a plain switch: ${only.lifecycle.errors}", only.lifecycle.errors.isEmpty())
    }

    @Test
    fun `onCleared teardown leaves no live client and no ladder`() {
        val only = newManager()
        val client = awaitBoundClient(only)

        // What MainViewModel.onCleared() calls.
        only.manager.tearDownConnection()

        assertNull(only.manager.activeClient())
        assertTrue(client.isClosed)
        assertTrue("teardown must be reported once", only.lifecycle.tearDowns >= 1)
        assertTrue(
            "a torn-down client kept its ladder: ${collectStates(client, LADDER_WATCH_MS)}",
            collectStates(client, 500L).isEmpty(),
        )

        // Idempotent fast-exit: a second call changes nothing and re-fires
        // nothing (the stores would otherwise be reset back to Loading).
        val tearDownsAfterFirst = only.lifecycle.tearDowns
        only.manager.tearDownConnection()
        assertEquals(tearDownsAfterFirst, only.lifecycle.tearDowns)
    }

    // -----------------------------------------------------------------
    // 5. The manifest half of the fix
    // -----------------------------------------------------------------

    /**
     * Layer 1 of the fix, and the one that is a single deletable attribute.
     *
     * Scanned out of the source manifest rather than asserted against a
     * merged one so the failure names the file a human has to edit. Same
     * technique as the encrypted-store-id scan in
     * [ConnectionLifecycleDecisionsTest].
     */
    @Test
    fun `MainActivity declares a single-instance launch mode`() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue(
            "no manifest at ${manifest.absolutePath}; cwd=${File(".").absolutePath}",
            manifest.isFile,
        )
        val text = manifest.readText()
        val activity = Regex("""<activity\b[^>]*android:name="\.MainActivity"[^>]*>""")
            .find(text)?.value
            ?: Regex("""<activity\b(?:[^>]|\n)*?android:name="\.MainActivity"(?:[^>]|\n)*?>""")
                .find(text)?.value
        assertNotNull(
            "no <activity android:name=\".MainActivity\"> in ${manifest.absolutePath}",
            activity,
        )
        val launchMode = Regex("""android:launchMode="([^"]+)"""").find(activity!!)?.groupValues?.get(1)
        assertTrue(
            """
            |.MainActivity must declare android:launchMode="singleTask" (or "singleInstance");
            |found ${launchMode ?: "no launchMode at all"} in ${manifest.absolutePath}.
            |
            |This attribute is LOAD-BEARING. MainViewModel is Activity-scoped and owns the
            |process's only RemoteClient, so a second MainActivity instance (the `standard`
            |default allows one — `am start -n`, a launcher shortcut, a return from the system
            |photo picker) gives the process two clients against one desktop. The server evicts
            |the older socket on every fresh auth with 1001 "evicted by new connection", so the
            |two clients evict each other forever: 576 authentications in 3.5 h in production on
            |2026-09-08, messages stuck on "Waiting for connection", uploads failing on the phone
            |while the desktop logged upload_finish OK.
            |
            |"singleTop" does NOT satisfy this: it only de-duplicates when the activity is
            |already on top of the task. Do not delete this attribute because ConnectionGuard
            |exists — the guard is the second line of defence, not the first.
            |See docs/findings/2026-09-08-duplicate-connection-storm.md.
            """.trimMargin(),
            launchMode == "singleTask" || launchMode == "singleInstance",
        )
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private fun app(): android.app.Application =
        ApplicationProvider.getApplicationContext()

    /**
     * A paired server pointing at a port nothing listens on. Secret and
     * fingerprint are fixed 32-byte patterns — [PairingUrl.parse] validates
     * both lengths, and a manager whose URL fails to parse never reaches the
     * bind path at all.
     */
    private fun server(id: String, port: Int): PairedServer {
        val b64 = Base64.getUrlEncoder().withoutPadding()
        val secret = b64.encodeToString(ByteArray(PairingUrl.SECRET_LEN) { it.toByte() })
        val fp = b64.encodeToString(ByteArray(PairingUrl.FP_LEN) { (255 - it).toByte() })
        return PairedServer(
            id = id,
            pairingUrl = "sawe-remote://127.0.0.1:$port?secret=$secret&client=$id&server_fp=$fp",
            label = id,
            fingerprintHex = "ab",
            firstPairedAtMs = 1L,
            lastConnectedAtMs = null,
        )
    }

    /**
     * A manager on its own scope, exactly as `MainViewModel` builds one on
     * `viewModelScope`. It auto-connects to the active server from its `init`
     * block, which is the production cold-start path and the path the second
     * Activity instance took.
     */
    private fun newManager(): Managed {
        val lifecycle = RecordingLifecycle()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = ConnectionManager(
            application = app(),
            scope = scope,
            lifecycle = lifecycle,
        )
        return Managed(manager, lifecycle, scope).also { managed += it }
    }

    /**
     * Block until [managed] has bound a client — optionally a DIFFERENT one
     * than [after], for the rebind case. The bind path runs off the caller's
     * thread through the pairing repository's IO read, so there is nothing to
     * await on; polling the observable is the honest option.
     */
    private fun awaitBoundClient(
        managed: Managed,
        after: RemoteClient? = null,
    ): RemoteClient = runBlocking {
        val client = withTimeoutOrNull(BIND_TIMEOUT_MS) {
            var seen: RemoteClient? = null
            while (seen == null) {
                seen = managed.manager.activeClient()?.takeIf { it !== after }
                if (seen == null) delay(5L)
            }
            seen
        }
        assertNotNull(
            "manager ${managed.manager.activeServerId.value} never bound a client " +
                "(errors: ${managed.lifecycle.errors})",
            client,
        )
        client!!
    }

    /**
     * Block until [client] has left its initial `Disconnected` at least once,
     * i.e. its lifecycle loop is genuinely running. Without this, "no state
     * transitions after the handover" could be satisfied by a client that had
     * not started yet.
     */
    private fun awaitLadderRunning(client: RemoteClient) = runBlocking {
        val reached = withTimeoutOrNull(BIND_TIMEOUT_MS) {
            while (client.connectionState.value is ConnectionState.Disconnected) delay(5L)
            true
        }
        assertTrue(
            "the loser's ladder never started; state=${client.connectionState.value}",
            reached == true,
        )
    }

    /**
     * Every state [client] publishes over [windowMs], excluding the value it
     * already held when the window opened (a `StateFlow` always replays that).
     */
    private fun collectStates(client: RemoteClient, windowMs: Long): List<ConnectionState> =
        runBlocking {
            val seen = mutableListOf<ConnectionState>()
            val initial = client.connectionState.value
            withTimeoutOrNull(windowMs) {
                client.connectionState.collect { state ->
                    if (seen.isEmpty() && state == initial) return@collect
                    seen += state
                }
            }
            seen
        }
}
