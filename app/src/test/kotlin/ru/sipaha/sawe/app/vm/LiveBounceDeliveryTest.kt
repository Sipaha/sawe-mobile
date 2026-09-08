package ru.sipaha.sawe.app.vm

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
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
import ru.sipaha.sawe.app.data.AttachmentDraftRepository
import ru.sipaha.sawe.app.data.DraftRepository
import ru.sipaha.sawe.app.data.InFlightUploadsRepository
import ru.sipaha.sawe.app.data.ListCacheRepository
import ru.sipaha.sawe.app.data.PendingSendsRepository
import ru.sipaha.sawe.app.data.SessionHistoryRepository
import ru.sipaha.sawe.app.data.TinkTestKeysets
import ru.sipaha.sawe.core.QueuedMessage
import ru.sipaha.sawe.core.RemoteClient

/**
 * Live delivery of a bounced message to a chat that is already open (N-07).
 *
 * The gap: `onMessageExpired` writes the abandoned text into `DraftRepository`'s
 * bounce slot, but that slot is only ever read by `loadDraftSeed`, which runs
 * on open. A message the queue gave up on while the user was sitting in the
 * chat therefore stayed invisible until they navigated away and came back —
 * and in the meantime the bubble had already disappeared, so it looked like
 * the text was simply gone.
 *
 * These exercise the store end of the fix: which bounces are pushed at the open
 * composer, that a bounce for any OTHER session is still durably recorded
 * rather than dropped, and that taking the live copy retires the slot so the
 * next open does not seed the same text again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LiveBounceDeliveryTest {

    private lateinit var scope: CoroutineScope
    private lateinit var drafts: DraftRepository
    private lateinit var store: SessionDetailStore

    /** No wire, no snackbar host — the store only needs somewhere to talk to. */
    private class SilentContext : ConnectionContext {
        val errors = mutableListOf<String>()
        override fun activeClient(): RemoteClient? = null
        override fun notConnectedMessage(): String = "not connected"
        override fun emitError(message: String) {
            errors += message
        }
        override suspend fun probeLivenessNow(): Boolean = false
    }

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        // `DraftRepository` is encrypted now, and its open needs a keyset.
        // Without this the store degrades to a no-op disk layer and every
        // "…is also written to disk" case below silently reads back "".
        TinkTestKeysets.install()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val connection = SilentContext()
        drafts = DraftRepository(app).also { it.activeServerProvider = { "srv-1" } }
        val history = SessionHistoryRepository(app, scope)
        store = SessionDetailStore(
            scope = scope,
            context = connection,
            draftRepository = drafts,
            attachmentDraftRepository = AttachmentDraftRepository(app),
            uploadManager = UploadManager(
                scope = scope,
                context = connection,
                persistence = InFlightUploadsRepository(app),
                contentResolver = app.contentResolver,
            ),
            sessionList = SessionListStore(
                scope = scope,
                context = connection,
                listCacheRepository = ListCacheRepository(app),
                sessionHistoryRepository = history,
            ),
            sessionHistoryRepository = history,
            pendingSendsRepository = PendingSendsRepository(app),
        )
    }

    @After
    fun tearDown() {
        drafts.clearAllServers()
        scope.cancel()
        TinkTestKeysets.uninstall()
    }

    /** A `send_message_blocks` exactly as the offline queue persisted it. */
    private fun queued(
        sessionId: String,
        text: String,
        csid: Long = 7L,
    ) = QueuedMessage(
        id = queueIdForClientSendId(csid),
        method = "remote.solution_agent.send_message_blocks",
        params = buildJsonObject {
            put("session_id", sessionId)
            put(
                "blocks",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", text)
                            putJsonObject("_meta") {
                                put("spk_client_send_id", JsonPrimitive(csid.toString()))
                            }
                        },
                    )
                },
            )
        },
        enqueuedAtMs = 1_000L,
    )

    @Test
    fun a_bounce_for_the_open_chat_reaches_the_composer_without_a_reopen() {
        store.openSessionId = "sess-1"

        store.handleExpiredMessage(queued("sess-1", "run the tests"))

        val delivered = runBlocking {
            withTimeout(2_000) { store.bouncedDrafts.first() }
        }
        assertEquals(BouncedDraft(sessionId = "sess-1", text = "run the tests"), delivered)
    }

    @Test
    fun the_same_bounce_is_also_written_to_disk_so_the_live_copy_can_never_be_the_only_one() {
        store.openSessionId = "sess-1"

        store.handleExpiredMessage(queued("sess-1", "run the tests"))

        // Deliberately NOT consumed: if the process dies between the push and
        // the composer taking it, the next open must still find the text.
        val (seed, wasBounce) = runBlocking { store.loadDraftSeed("sess-1") }
        assertEquals("run the tests", seed)
        assertTrue(wasBounce)
    }

    @Test
    fun a_bounce_for_another_session_is_recorded_but_not_pushed_at_the_open_chat() {
        store.openSessionId = "sess-1"

        store.handleExpiredMessage(queued("sess-2", "the other message"))

        val leaked = runBlocking {
            withTimeoutOrNull(300) { store.bouncedDrafts.first() }
        }
        assertNull("a bounce must not land in whichever chat happens to be open", leaked)
        val (seed, wasBounce) = runBlocking { store.loadDraftSeed("sess-2") }
        assertEquals("the other message", seed)
        assertTrue(wasBounce)
    }

    @Test
    fun taking_the_live_copy_retires_the_slot_so_the_next_open_does_not_repeat_it() {
        store.openSessionId = "sess-1"
        store.handleExpiredMessage(queued("sess-1", "run the tests"))
        runBlocking { withTimeout(2_000) { store.bouncedDrafts.first() } }

        runBlocking { store.consumeBounce("sess-1") }

        val (seed, wasBounce) = runBlocking { store.loadDraftSeed("sess-1") }
        assertEquals("", seed)
        assertFalse(wasBounce)
    }

    @Test
    fun a_live_bounce_appends_to_what_the_user_has_typed_since() {
        // The whole reason the delivery is a merge and not an assignment: the
        // user is IN the chat, so there is almost always something in the field.
        store.openSessionId = "sess-1"
        runBlocking { store.saveDraft("sess-1", "half-written thought") }

        store.handleExpiredMessage(queued("sess-1", "run the tests"))
        val delivered = runBlocking { withTimeout(2_000) { store.bouncedDrafts.first() } }

        assertEquals(
            "half-written thought\n\nrun the tests",
            mergeDraftSeed(draft = "half-written thought", bounced = delivered.text),
        )
    }
}
