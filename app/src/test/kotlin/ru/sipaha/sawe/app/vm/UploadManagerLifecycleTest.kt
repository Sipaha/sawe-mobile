package ru.sipaha.sawe.app.vm

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.sipaha.sawe.app.data.InFlightUploadStore
import ru.sipaha.sawe.app.data.PersistedUpload
import ru.sipaha.sawe.core.RemoteClient

/**
 * Behavioural regression tests for the `UploadManager` findings that the
 * adversarial review raised (UP-1, UP-2, UP-4, UP-8).
 *
 * These need a real `UploadManager`: the bugs are in how it mutates its
 * own maps and its store, not in a decision function. `ConnectionContext`
 * is faked with **no** bound client, which is enough — every driver
 * coroutine stages the local bytes (step 0) before it looks for a socket
 * (step 1), so the staging, terminal-failure and bookkeeping paths all
 * run, and nothing needs a `RemoteClient` (whose transport seam is
 * `internal` to `:core` and cannot be faked from `:app`).
 *
 * The store is faked in-memory because the production one is backed by
 * `EncryptedSharedPreferences`, which needs an Android Keystore that
 * neither the JVM nor Robolectric provides.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UploadManagerLifecycleTest {

    /** In-memory [InFlightUploadStore], keyed exactly like the real one. */
    private class FakeStore : InFlightUploadStore {
        var active: String? = "server-A"
        val rows = ConcurrentHashMap<String, PersistedUpload>()

        private fun key(serverId: String?, localKey: String) = "$serverId:$localKey"

        override fun activeServerId(): String? = active

        override fun saveOrUpdate(persisted: PersistedUpload, serverId: String?) {
            if (serverId == null) return
            rows[key(serverId, persisted.localKey)] = persisted
        }

        override fun listFor(serverId: String?): List<PersistedUpload> {
            if (serverId == null) return emptyList()
            return rows.entries.filter { it.key.startsWith("$serverId:") }.map { it.value }
        }

        override fun remove(localKey: String, serverId: String?) {
            rows.remove(key(serverId, localKey))
        }

        override fun removeForServer(serverId: String) {
            rows.keys.filter { it.startsWith("$serverId:") }.forEach { rows.remove(it) }
        }

        override fun allLocalKeys(): Set<String> =
            rows.values.map { it.localKey }.toSet()
    }

    private class FakeConnection : ConnectionContext {
        /** Appended to from driver coroutines on Dispatchers.Default. */
        val errors: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        override fun activeClient(): RemoteClient? = null
        override fun notConnectedMessage(): String = "not connected"
        override fun emitError(message: String) {
            errors += message
        }

        override suspend fun probeLivenessNow(): Boolean = false
    }

    private lateinit var driverJob: kotlinx.coroutines.CompletableJob
    private lateinit var scope: CoroutineScope
    private lateinit var store: FakeStore
    private lateinit var connection: FakeConnection
    private lateinit var resolver: ContentResolver
    private lateinit var stagingDir: File

    @Before
    fun setUp() {
        driverJob = SupervisorJob()
        scope = CoroutineScope(driverJob + Dispatchers.Default)
        store = FakeStore()
        connection = FakeConnection()
        resolver = RuntimeEnvironment.getApplication().contentResolver
        stagingDir = File.createTempFile("staging", "").let {
            it.delete()
            it.mkdirs()
            it
        }
    }

    @After
    fun tearDown() {
        // Join, don't just cancel: a driver still inside its staging copy
        // would otherwise race the directory delete and leak into the
        // next test's temp dir.
        kotlinx.coroutines.runBlocking { driverJob.cancelAndJoin() }
        stagingDir.deleteRecursively()
    }

    private fun manager(withStaging: Boolean = true) = UploadManager(
        scope = scope,
        context = connection,
        persistence = store,
        contentResolver = resolver,
        stagingDir = if (withStaging) stagingDir else null,
    )

    /** Register [bytes] as the content of [uri] for exactly one open. */
    private fun publish(uri: Uri, bytes: ByteArray) {
        shadowOf(resolver).registerInputStream(uri, ByteArrayInputStream(bytes))
    }

    /** Register [uri] as a source whose bytes can no longer be read. */
    private fun publishUnreadable(uri: Uri) {
        shadowOf(resolver).registerInputStream(
            uri,
            object : InputStream() {
                override fun read(): Int = throw FileNotFoundException("grant lapsed")
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    throw FileNotFoundException("grant lapsed")
            },
        )
    }

    /**
     * Block until [localKey]'s driver has run to completion.
     *
     * With no bound client every driver ends at `Paused("waiting for
     * connection")`, and it only gets there *after* step 0 has finished
     * staging the bytes. Waiting for the staged file to merely exist is
     * not enough — the copy is still being written at that point — so
     * every assertion about staging hangs off this instead.
     */
    private fun awaitSettled(mgr: UploadManager, localKey: String): UploadManager.State.Paused =
        await("$localKey to settle") {
            mgr.stateOf(localKey) as? UploadManager.State.Paused
        }

    /** Poll until [probe] is non-null; the driver coroutines run on real threads. */
    private fun <T : Any> await(what: String, probe: () -> T?): T {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            probe()?.let { return it }
            Thread.sleep(5L)
        }
        throw AssertionError("timed out waiting for $what")
    }

    // ---- UP-1: the staging fix must not be able to be dead code again ---

    @Test
    fun `UP-1 a configured staging dir actually copies the picked bytes`() {
        val uri = Uri.parse("content://media/external/images/1")
        val bytes = ByteArray(4_096) { (it % 251).toByte() }
        publish(uri, bytes)

        val mgr = manager(withStaging = true)
        assertTrue(mgr.isStagingConfigured)
        val (localKey, _) = mgr.start(uri, "sess", "image/jpeg", "photo.jpg", bytes.size.toLong())
        awaitSettled(mgr, localKey)

        val staged = stagingDir.listFiles()!!.single { it.name.startsWith(localKey) }
        assertEquals("$localKey$STAGED", staged.name)
        assertArrayEqualsMsg(bytes, staged.readBytes())
        assertTrue(connection.errors.isEmpty())
    }

    @Test
    fun `UP-1 a missing staging dir is loud, not silent`() {
        val uri = Uri.parse("content://media/external/images/2")
        publish(uri, ByteArray(64))

        val mgr = manager(withStaging = false)
        assertFalse(mgr.isStagingConfigured)
        val (first, _) = mgr.start(uri, "sess", "image/jpeg", "photo.jpg", 64L)
        awaitSettled(mgr, first)

        // Visible on the channel the UI renders, exactly once...
        val notice = await("the staging-not-configured notice") {
            connection.errors.firstOrNull()
        }
        assertTrue(notice, notice.contains("staging isn't configured"))

        val (second, _) = mgr.start(uri, "sess", "image/jpeg", "photo2.jpg", 64L)
        awaitSettled(mgr, second)
        assertEquals(1, connection.errors.size)

        // ...and in the copy the failed card shows.
        assertTrue(
            UploadDecisions.unreadableSourceReason(stagingConfigured = false)
                .contains("no attachment staging"),
        )
        assertFalse(
            UploadDecisions.unreadableSourceReason(stagingConfigured = true)
                .contains("no attachment staging"),
        )
    }

    // ---- UP-2: removing one server must not wipe another's uploads ------

    @Test
    fun `UP-2 forgetAllForServer only touches the server it was given`() {
        val mgr = manager(withStaging = true)

        val uriA = Uri.parse("content://media/external/images/10")
        publish(uriA, ByteArray(512) { 1 })
        store.active = "server-A"
        val (keyA, flowA) = mgr.start(uriA, "sessA", "image/jpeg", "a.jpg", 512L)
        awaitSettled(mgr, keyA)

        val uriB = Uri.parse("content://media/external/images/11")
        publish(uriB, ByteArray(512) { 2 })
        store.active = "server-B"
        val (keyB, _) = mgr.start(uriB, "sessB", "image/jpeg", "b.jpg", 512L)
        awaitSettled(mgr, keyB)

        // The user removes the *stale* pairing while still bound to B.
        mgr.forgetAllForServer("server-A")

        // B survives untouched — before the fix every map was cleared.
        assertNotNull("B's state flow was destroyed", mgr.stateFlowOf(keyB))
        assertNotNull(mgr.stateOf(keyB))
        assertTrue(
            "B's staged copy was deleted",
            stagingDir.listFiles()!!.any { it.name.startsWith(keyB) },
        )

        // A is gone: state, and its staged copy.
        assertNull(mgr.stateFlowOf(keyA))
        assertFalse(stagingDir.listFiles()!!.any { it.name.startsWith(keyA) })
        // A's flow is orphaned but still readable by whoever captured it.
        assertNotNull(flowA.value)
    }

    @Test
    fun `UP-2 removing the bound server also drops its disk-only records`() {
        val mgr = manager(withStaging = true)
        store.active = "server-A"
        val orphan = File(stagingDir, "ghost.upload").apply { writeBytes(ByteArray(8)) }
        store.saveOrUpdate(
            PersistedUpload(
                localKey = "ghost",
                uploadId = 7L,
                uriString = "content://gone/1",
                sessionId = "s",
                mime = "image/jpeg",
                displayName = "g.jpg",
                totalSize = 8L,
                lastConfirmedOffset = 0L,
                stagedPath = orphan.absolutePath,
            ),
            serverId = "server-A",
        )

        mgr.forgetAllForServer("server-A")

        assertTrue(store.listFor("server-A").isEmpty())
        assertFalse("staged copy of a disk-only record leaked", orphan.exists())
    }

    // ---- UP-4: a terminal failure must release everything it holds ------

    @Test
    fun `UP-4 a terminal failure drops the disk record so it is not revived forever`() {
        val mgr = manager(withStaging = true)
        val uri = Uri.parse("content://media/external/images/20")
        publishUnreadable(uri)
        store.active = "server-A"
        store.saveOrUpdate(
            PersistedUpload(
                localKey = "revived",
                uploadId = 99L,
                uriString = uri.toString(),
                sessionId = "sess",
                mime = "image/jpeg",
                displayName = "p.jpg",
                totalSize = 4_096L,
                lastConfirmedOffset = 1_024L,
            ),
            serverId = "server-A",
        )

        mgr.resumeAllFromDisk()

        val failed = await("the terminal failure") {
            mgr.stateOf("revived") as? UploadManager.State.Failed
        }
        assertTrue(failed.reason, failed.reason.contains("attach it again"))
        // Before the fix the record survived and was revived — and failed —
        // on every single reconnect, one upload_status RPC each time.
        assertTrue(
            "the persisted record survived a terminal failure",
            store.listFor("server-A").none { it.localKey == "revived" },
        )
        // ...but the state and metadata survive, so retry() still works.
        assertNotNull(mgr.stateFlowOf("revived"))
        assertTrue(mgr.retry("revived"))
    }

    @Test
    fun `UP-4 retry only restarts a Failed upload`() {
        val mgr = manager(withStaging = true)
        val uri = Uri.parse("content://media/external/images/21")
        publishUnreadable(uri)
        store.active = "server-A"
        store.saveOrUpdate(
            PersistedUpload(
                localKey = "k",
                uploadId = 1L,
                uriString = uri.toString(),
                sessionId = "s",
                mime = "image/jpeg",
                displayName = "p.jpg",
                totalSize = 16L,
                lastConfirmedOffset = 0L,
            ),
            serverId = "server-A",
        )
        mgr.resumeAllFromDisk()
        await("the terminal failure") { mgr.stateOf("k") as? UploadManager.State.Failed }

        assertTrue(mgr.retry("k"))
        assertFalse("unknown keys are not retryable", mgr.retry("nope"))
    }

    // ---- UP-8 / staged-file GC -----------------------------------------

    @Test
    fun `orphaned staged copies are swept on the next disk resume`() {
        val mgr = manager(withStaging = true)
        val orphan = File(stagingDir, "no-such-key.upload").apply { writeBytes(ByteArray(4)) }
        val notOurs = File(stagingDir, "unrelated.tmp").apply { writeBytes(ByteArray(4)) }
        store.active = "server-A"

        mgr.resumeAllFromDisk()

        assertFalse("orphaned staged copy leaked", orphan.exists())
        assertTrue("the sweep must not touch unrelated files", notOurs.exists())
    }

    @Test
    fun `forget releases the server slot for an upload that never produced a handle`() {
        val mgr = manager(withStaging = true)
        val uri = Uri.parse("content://media/external/images/30")
        publish(uri, ByteArray(256))
        store.active = "server-A"
        val (localKey, _) = mgr.start(uri, "sess", "image/jpeg", "p.jpg", 256L)
        awaitSettled(mgr, localKey)

        mgr.forget(localKey)

        assertNull(mgr.stateFlowOf(localKey))
        assertTrue(store.listFor("server-A").none { it.localKey == localKey })
        assertFalse(stagingDir.listFiles()!!.any { it.name.startsWith(localKey) })
    }

    private companion object {
        /** Mirrors UploadManager.STAGED_SUFFIX, which is private. */
        const val STAGED = ".upload"
    }

    private fun assertArrayEqualsMsg(expected: ByteArray, actual: ByteArray) {
        assertEquals("staged copy length", expected.size, actual.size)
        assertTrue("staged copy content differs", expected.contentEquals(actual))
    }
}
