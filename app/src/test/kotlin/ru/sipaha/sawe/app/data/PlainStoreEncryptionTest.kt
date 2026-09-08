package ru.sipaha.sawe.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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
import ru.sipaha.sawe.core.SessionStateDto
import ru.sipaha.sawe.core.SessionSummary
import ru.sipaha.sawe.core.SolutionSummary
import java.io.File

/**
 * The three stores that moved from a PLAIN `SharedPreferences` file onto
 * [EncryptedPrefs.open] — `spk_drafts`, `spk_attachment_drafts`,
 * `spk_list_cache` — and the one that deliberately did not, `spk_nav_state`.
 *
 * Two things are being pinned here.
 *
 * **The data survives the move.** These files hold text nobody has sent yet
 * (`draft:`), the message that failed to send (`bounced:`), the names of the
 * files someone attached, and the cached session titles. Encrypting them is
 * worth nothing if the switch itself drops what was already on disk, so
 * every case below goes through the repository's own API and through the
 * real [LegacyPrefsImport] — the same importer, the same
 * `imported:<store>` marker in `spk_prefs_health`, the same
 * commit-then-mark-then-delete ordering that the androidx→Tink migration
 * uses. The only difference is the reader, which is now the store's own
 * [LegacyPrefsFormat].
 *
 * **`spk_nav_state` stays plain, on purpose.** The last case asserts that,
 * so a later tidy-up that sweeps every store in the package onto the
 * encrypted opener has to change a test that says why it should not.
 *
 * The Android Keystore is the one thing neither the JVM nor Robolectric
 * provides, so [TinkTestKeysets] stands in for the keyset unwrap. Everything
 * above it — the opener, the recovery latch, the import ordering, the
 * repositories — is the production code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlainStoreEncryptionTest {

    private lateinit var app: Context

    private val server = "srv-1"

    /** Encoder matching `ListCacheRepository`'s private one. */
    private val listJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val stores = listOf(
        DraftRepository.PREFS_NAME,
        AttachmentDraftRepository.PREFS_NAME,
        ListCacheRepository.PREFS_NAME,
        NAV_STATE_PREFS_NAME,
    )

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        TinkTestKeysets.install()
        PersistenceHealth.resetForTest()
        for (store in stores) {
            app.deleteSharedPreferences(store)
            app.deleteSharedPreferences(EncryptedPrefs.dataFileName(store))
            app.deleteSharedPreferences(AppTinkKeysets.keysetPrefsFile(store))
        }
        latch().edit().clear().commit()
    }

    @After
    fun tearDown() {
        TinkTestKeysets.uninstall()
        PersistenceHealth.resetForTest()
    }

    // ---- helpers ------------------------------------------------------

    private fun latch() =
        app.getSharedPreferences(EncryptedPrefs.LATCH_PREFS_NAME, Context.MODE_PRIVATE)

    /** The physical XML behind a prefs file name. */
    private fun xmlOf(fileName: String) = File(File(app.dataDir, "shared_prefs"), "$fileName.xml")

    /** The ciphertext file the store now writes into. */
    private fun cipherXml(store: String) = xmlOf(EncryptedPrefs.dataFileName(store))

    /** Write [entries] into the store's OLD plain file, durably. */
    private fun writeLegacyPlain(store: String, entries: Map<String, String>) {
        val editor = app.getSharedPreferences(store, Context.MODE_PRIVATE).edit()
        editor.clear()
        for ((k, v) in entries) editor.putString(k, v)
        assertTrue("legacy fixture must commit", editor.commit())
        assertTrue("legacy fixture must be on disk", xmlOf(store).isFile)
    }

    private fun drafts() = DraftRepository(app).also { it.activeServerProvider = { server } }

    private fun attachments() =
        AttachmentDraftRepository(app).also { it.activeServerProvider = { server } }

    private fun listCache() = ListCacheRepository(app).also { it.activeServerProvider = { server } }

    /** The delegate map the encrypting layer actually writes into. */
    private fun cipherEntries(store: String): Map<String, Any?> =
        app.getSharedPreferences(EncryptedPrefs.dataFileName(store), Context.MODE_PRIVATE).all

    private fun assertNoPlaintext(store: String, vararg fragments: String) {
        val file = cipherXml(store)
        assertTrue("$store has no ciphertext file at ${file.absolutePath}", file.isFile)
        val text = file.readText()
        for (fragment in fragments) {
            assertFalse(
                "the encrypted $store file still contains the plaintext `$fragment`",
                text.contains(fragment),
            )
        }
    }

    private fun solution(name: String) = SolutionSummary(
        id = 7L,
        name = name,
        root = "/home/dev/work",
        memberCount = 2,
        open = true,
    )

    private fun session(title: String) = SessionSummary(
        id = "sess-1",
        solutionId = 7L,
        agentId = "claude",
        title = title,
        state = SessionStateDto.Idle,
        createdAt = 1_700_000_000_000L,
        lastActivityAt = 1_700_000_000_100L,
    )

    // ---- round trips --------------------------------------------------

    @Test
    fun `drafts round-trip through the encrypted store`() {
        val repo = drafts()

        repo.save("s1", "half a thought")
        assertEquals("half a thought", repo.load("s1"))

        repo.setBounced("s1", "this never went out")
        assertEquals("this never went out", repo.bouncedFor("s1"))
        // bouncedFor is read-once.
        assertNull(repo.bouncedFor("s1"))

        // Nothing readable landed on disk: the delegate sees only Base64.
        assertTrue(cipherEntries(DraftRepository.PREFS_NAME).isNotEmpty())
        assertFalse(
            cipherEntries(DraftRepository.PREFS_NAME).keys.any { it.startsWith("draft:") },
        )

        repo.clear("s1")
        assertEquals("", repo.load("s1"))
    }

    @Test
    fun `a draft written by one instance is readable by the next`() {
        drafts().save("s1", "survives a reopen")

        assertEquals("survives a reopen", drafts().load("s1"))
    }

    @Test
    fun `attachment drafts round-trip through the encrypted store`() {
        val repo = attachments()
        val refs = listOf(
            AttachmentRef("u-1", "tax-return-2025.pdf", "application/pdf", 91_231L),
            AttachmentRef("u-2", "IMG_0001.jpg", "image/jpeg", 2_400_000L),
        )

        repo.save("s1", refs)

        assertEquals(refs, attachments().load("s1"))
        assertFalse(
            cipherEntries(AttachmentDraftRepository.PREFS_NAME).keys
                .any { it.startsWith("attachments:") },
        )

        repo.clear("s1")
        assertEquals(emptyList<AttachmentRef>(), repo.load("s1"))
    }

    @Test
    fun `the list cache round-trips solutions and sessions through the encrypted store`() {
        val repo = listCache()
        val solutions = listOf(solution("Payroll rewrite"))
        val sessions = listOf(session("stop leaking the customer list"))

        repo.saveSolutions(solutions)
        repo.saveSessions(7L, sessions)

        val reopened = listCache()
        assertEquals(solutions, reopened.loadSolutions())
        assertEquals(sessions, reopened.loadSessions(7L))
        assertFalse(
            cipherEntries(ListCacheRepository.PREFS_NAME).keys.any { it.startsWith("solutions:") },
        )
    }

    // ---- migration off the plain file ---------------------------------

    @Test
    fun `a populated plain drafts file is imported whole, then deleted`() {
        val store = DraftRepository.PREFS_NAME
        writeLegacyPlain(
            store,
            mapOf(
                "draft:$server:s1" to "unsent paragraph",
                "draft:$server:s2" to "another one",
                "bounced:$server:s1" to "the message that failed",
            ),
        )

        val repo = drafts()

        assertEquals("unsent paragraph", repo.load("s1"))
        assertEquals("another one", repo.load("s2"))
        assertEquals("the message that failed", repo.bouncedFor("s1"))
        assertTrue("marker not set", LegacyPrefsImport.isComplete(app, store))
        assertFalse("plain file survived the import", xmlOf(store).exists())
        assertNoPlaintext(
            store,
            "draft:$server:s1",
            "bounced:$server:s1",
            "unsent paragraph",
            "the message that failed",
        )
    }

    @Test
    fun `a populated plain attachment-drafts file is imported whole, then deleted`() {
        val store = AttachmentDraftRepository.PREFS_NAME
        val blob =
            """[{"localKey":"u-1","displayName":"payslip.pdf","mimeType":"application/pdf","sizeBytes":4096}]"""
        writeLegacyPlain(store, mapOf("attachments:$server:s1" to blob))

        val loaded = attachments().load("s1")

        assertEquals(
            listOf(AttachmentRef("u-1", "payslip.pdf", "application/pdf", 4096L)),
            loaded,
        )
        assertTrue(LegacyPrefsImport.isComplete(app, store))
        assertFalse(xmlOf(store).exists())
        assertNoPlaintext(store, "attachments:$server:s1", "payslip.pdf")
    }

    @Test
    fun `a populated plain list-cache file is imported whole, then deleted`() {
        val store = ListCacheRepository.PREFS_NAME
        val solutions = listOf(solution("Acme migration"))
        val sessions = listOf(session("rotate the leaked API key"))
        writeLegacyPlain(
            store,
            mapOf(
                "solutions:$server" to listJson.encodeToString(
                    ListSerializer(SolutionSummary.serializer()),
                    solutions,
                ),
                "sessions:$server:7" to listJson.encodeToString(
                    ListSerializer(SessionSummary.serializer()),
                    sessions,
                ),
            ),
        )

        val repo = listCache()

        assertEquals(solutions, repo.loadSolutions())
        assertEquals(sessions, repo.loadSessions(7L))
        assertTrue(LegacyPrefsImport.isComplete(app, store))
        assertFalse(xmlOf(store).exists())
        assertNoPlaintext(store, "solutions:$server", "Acme migration", "rotate the leaked API key")
    }

    /**
     * The marker means "already imported", so a plain file that reappears
     * afterwards must be swept, not read. Anything else would let a stale
     * pre-migration file overwrite drafts written since.
     */
    @Test
    fun `a second cold open re-imports nothing and loses nothing`() {
        val store = DraftRepository.PREFS_NAME
        writeLegacyPlain(store, mapOf("draft:$server:s1" to "kept"))
        assertEquals("kept", drafts().load("s1"))

        writeLegacyPlain(
            store,
            mapOf("draft:$server:s1" to "decoy", "draft:$server:s9" to "ghost"),
        )
        val reopened = drafts()

        assertEquals("kept", reopened.load("s1"))
        assertEquals("", reopened.load("s9"))
        assertFalse("the leftover plain file was not swept", xmlOf(store).exists())
    }

    /**
     * Step (1) landed, step (2) did not: the copy is on disk but the marker
     * is missing, which is exactly what a process death mid-import leaves
     * behind. The next open must re-run the whole import — it is idempotent
     * by construction — and finish it.
     */
    @Test
    fun `an import interrupted before the marker is re-run and completes`() {
        val store = DraftRepository.PREFS_NAME
        writeLegacyPlain(store, mapOf("draft:$server:s1" to "first"))
        assertEquals("first", drafts().load("s1"))

        // Roll back to "committed but unmarked", with the legacy file still
        // there — the state the ordering is designed to survive.
        latch().edit().remove(LegacyPrefsImport.markerKey(store)).commit()
        writeLegacyPlain(
            store,
            mapOf("draft:$server:s1" to "first", "draft:$server:s2" to "second"),
        )
        assertFalse(LegacyPrefsImport.isComplete(app, store))

        val reopened = drafts()

        assertEquals("first", reopened.load("s1"))
        assertEquals("second", reopened.load("s2"))
        assertTrue(LegacyPrefsImport.isComplete(app, store))
        assertFalse(xmlOf(store).exists())
    }

    /** A fresh install has no plain file, and must not go looking twice. */
    @Test
    fun `a store with no plain file is marked imported without touching anything`() {
        val store = ListCacheRepository.PREFS_NAME
        assertFalse(xmlOf(store).exists())

        listCache().loadSolutions()

        assertTrue(LegacyPrefsImport.isComplete(app, store))
    }

    // ---- the one that stays plain -------------------------------------

    /**
     * [NavStateRepository] holds `route:<serverId>` → a resolved nav route:
     * server-assigned ids and fixed path segments, no content. It is the one
     * store in the package left unencrypted, and this asserts the resulting
     * on-disk shape so the decision has to be revisited deliberately rather
     * than swept along with the others.
     */
    @Test
    fun `nav state stays a plain file with plaintext keys and values`() {
        val repo = NavStateRepository(app).also { it.activeServerProvider = { server } }

        repo.saveRoute("workspace/sessions/sess-1")

        assertEquals("workspace/sessions/sess-1", repo.loadRoute())
        val plain = app.getSharedPreferences(NAV_STATE_PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals(
            "workspace/sessions/sess-1",
            plain.getString("route:$server", null),
        )
        assertFalse(
            "nav state grew an encrypted file — the KDoc rationale needs revisiting",
            xmlOf(EncryptedPrefs.dataFileName(NAV_STATE_PREFS_NAME)).exists(),
        )
        assertFalse(
            "nav state grew a Tink keyset",
            xmlOf(AppTinkKeysets.keysetPrefsFile(NAV_STATE_PREFS_NAME)).exists(),
        )
    }

    /**
     * The store id is private to [NavStateRepository], so the sibling case
     * above works off a copy of the string. Read the source and fail if the
     * copy has drifted — otherwise a rename would turn that test into an
     * assertion about a file nothing writes.
     */
    @Test
    fun `nav state is opened plainly, and this test knows its real file name`() {
        val source = File("src/main/kotlin/ru/sipaha/sawe/app/data/NavStateRepository.kt")
        assertTrue("no source at ${source.absolutePath}", source.isFile)
        // Comments are stripped: the KDoc on this very file names
        // `EncryptedPrefs.open` while explaining why it is NOT used, and an
        // assertion that a doc paragraph cannot mention the alternative
        // would be a worse test than none.
        val text = source.readLines()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
            .filterNot { it.trimStart().startsWith("/*") }
            .joinToString("\n")

        assertTrue(
            "NavStateRepository's store id is no longer `$NAV_STATE_PREFS_NAME`",
            text.contains("""PREFS_NAME = "$NAV_STATE_PREFS_NAME""""),
        )
        assertTrue(
            "NavStateRepository no longer opens a plain SharedPreferences",
            text.contains("getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)"),
        )
        assertFalse(
            "NavStateRepository now goes through EncryptedPrefs — update its KDoc, add a " +
                "persistenceDropNotice branch, and delete this assertion on purpose",
            text.contains("EncryptedPrefs.open"),
        )
    }

    private companion object {
        /** Mirrors `NavStateRepository.PREFS_NAME`, which is private. */
        const val NAV_STATE_PREFS_NAME = "spk_nav_state"
    }
}
