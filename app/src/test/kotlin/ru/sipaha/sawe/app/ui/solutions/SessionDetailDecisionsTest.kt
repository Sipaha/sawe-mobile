package ru.sipaha.sawe.app.ui.solutions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import ru.sipaha.sawe.core.ChatItem
import ru.sipaha.sawe.core.EntryRoleDto
import ru.sipaha.sawe.core.EntrySummary

/**
 * Pure-JVM coverage of the chat surface's extracted decisions
 * ([SessionDetailDecisions.kt]). No Android, no Compose — every one of these
 * used to be inlined in a composable where it could only be exercised by
 * rendering the whole 4k-line screen.
 */
class SessionDetailDecisionsTest {

    /**
     * Independent transcription of the desktop's accept rule —
     * `sawe/crates/solution_agent/src/upload.rs`: `is_text_like` (a
     * case-sensitive `matches!` over the whole string, plus a
     * `starts_with("text/")`) and the image branch of
     * `resolve_upload_handles`, `mime.starts_with("image/")`.
     *
     * Deliberately NOT expressed in terms of the production constants: an
     * oracle derived from the code under test can only ever be a tautology.
     * Update this only when `upload.rs` changes.
     */
    private fun serverWouldAccept(mime: String): Boolean =
        mime.startsWith("image/") ||
            mime.startsWith("text/") ||
            mime in setOf(
                "application/json",
                "application/xml",
                "application/x-yaml",
                "application/yaml",
                "application/javascript",
                "application/typescript",
                "application/sql",
                "application/x-sh",
            )

    /** Provider answers that mean "I don't know", not "this is the type". */
    private val UNKNOWN_PROVIDER_ANSWERS = setOf("application/octet-stream", "content/unknown", "")

    private fun message(
        index: Int = -1,
        csid: Long? = null,
        role: EntryRoleDto = EntryRoleDto.User,
        preview: String = "hi",
    ) = ChatItem.Message(
        EntrySummary(role = role, preview = preview, index = index, clientSendId = csid),
    )

    @Nested
    inner class ChatItemKeys {

        @Test
        fun `prefers csid then server index then position`() {
            val keys = chatItemKeys(
                listOf(
                    message(index = 4, csid = 77L),
                    message(index = 5),
                    message(index = -1, role = EntryRoleDto.Assistant),
                ),
            )
            assertEquals(listOf("csid:77", "idx:5", "pos2:Assistant"), keys)
        }

        @Test
        fun `date separators key on their epoch day`() {
            val keys = chatItemKeys(
                listOf(ChatItem.DateSeparator(20_000L), message(index = 0)),
            )
            assertEquals(listOf("date:20000", "idx:0"), keys)
        }

        /**
         * The N-59 regression: a desktop clock stepping backwards across local
         * midnight makes `withDateSeparators` emit the same epoch day twice.
         * The old inline derivation handed LazyColumn two `date:N` keys and
         * the list died with "Key date:N already used".
         */
        @Test
        fun `a repeated date separator does not produce a duplicate key`() {
            val keys = chatItemKeys(
                listOf(
                    ChatItem.DateSeparator(20_000L),
                    message(index = 0),
                    ChatItem.DateSeparator(20_001L),
                    message(index = 1),
                    ChatItem.DateSeparator(20_000L),
                    message(index = 2),
                ),
            )
            assertEquals(keys.size, keys.toSet().size, "keys must be unique: $keys")
            assertEquals("date:20000", keys[0])
            assertEquals("date:20000#pos4", keys[4])
        }

        @Test
        fun `a repeated csid does not produce a duplicate key`() {
            val keys = chatItemKeys(listOf(message(csid = 9L), message(csid = 9L)))
            assertEquals(listOf("csid:9", "csid:9#pos1"), keys)
        }

        @Test
        fun `server-queued bubbles are namespaced away from the flushed entry`() {
            val queued = EntrySummary(role = EntryRoleDto.User, preview = "q", clientSendId = 3L)
            val keys = chatItemKeys(
                listOf(ChatItem.Message(queued), message(index = 8, csid = 3L)),
            ) { it === queued }
            assertEquals(listOf("queued:3", "csid:3"), keys)
        }

        @Test
        fun `empty timeline yields no keys`() {
            assertTrue(chatItemKeys(emptyList()).isEmpty())
        }
    }

    @Nested
    inner class AttachmentMime {

        @Test
        fun `images and text-like mimes are accepted`() {
            assertTrue(attachmentMimeIsSupported("image/png"))
            assertTrue(attachmentMimeIsSupported("image/heic"))
            assertTrue(attachmentMimeIsSupported("text/plain"))
            assertTrue(attachmentMimeIsSupported("text/x-kotlin"))
            assertTrue(attachmentMimeIsSupported("application/json"))
            assertTrue(attachmentMimeIsSupported("application/x-sh"))
        }

        @Test
        fun `case and charset parameters do not change the verdict`() {
            assertTrue(attachmentMimeIsSupported("IMAGE/JPEG"))
            assertTrue(attachmentMimeIsSupported("text/plain; charset=utf-8"))
            assertTrue(attachmentMimeIsSupported("Application/JSON"))
        }

        /**
         * The invariant that actually matters, checked against an independent
         * transcription of `upload.rs` rather than against our own predicate:
         * whatever we hand to `upload_init` must be something the desktop's
         * exact, case-sensitive match will still accept at
         * `send_message_blocks` time.
         *
         * This is what a tolerant client predicate plus a raw upload got
         * wrong: `IMAGE/JPEG` and `application/json; charset=utf-8` passed the
         * pick, went on the wire verbatim, burned one of the four per-session
         * upload slots for an hour and then failed with `unsupported_mime` —
         * exactly the outcome N-44 exists to prevent.
         */
        @Test
        fun `every mime we would upload is one the server accepts`() {
            val providerAnswers = listOf(
                "image/png" to "shot.png",
                "IMAGE/JPEG" to "photo.JPG",
                "image/jpeg; charset=binary" to "photo.jpg",
                "text/plain" to "notes.txt",
                "text/plain; charset=utf-8" to "notes.txt",
                "TEXT/X-Kotlin" to "Main.kt",
                "application/json" to "pkg.json",
                "Application/JSON" to "pkg.json",
                "application/json; charset=utf-8" to "pkg.json",
                "application/x-yaml" to "ci.yaml",
                "application/octet-stream" to "SessionDetailStore.kt",
                "application/octet-stream" to "Cargo.toml",
                "application/octet-stream" to "compose.yml",
                "" to "build.gradle",
            )
            for ((raw, name) in providerAnswers) {
                assertTrue(attachmentMimeIsSupported(raw) || raw in UNKNOWN_PROVIDER_ANSWERS,
                    "test fixture bug: $raw/$name is not even a candidate")
                val onTheWire = normalizeAttachmentMime(raw, name)
                assertTrue(
                    serverWouldAccept(onTheWire),
                    "would upload `$name` as \"$onTheWire\" (from \"$raw\"), " +
                        "which upload.rs rejects with unsupported_mime",
                )
            }
        }

        @Test
        fun `a rejected pick still quotes what the provider actually said`() {
            // Not canonicalised: the snackbar should show the provider's own
            // answer so a bug report is diagnosable.
            assertEquals(
                "application/PDF",
                normalizeAttachmentMime("application/PDF", "report.pdf"),
            )
        }

        /**
         * The N-44 regression: the file picker launched with a wildcard filter
         * and nothing re-checked, so a PDF reached `upload_init`, held one of the server's
         * four per-session upload slots for an hour, and only failed at send
         * time with `unsupported_mime`.
         */
        @Test
        fun `everything the desktop would reject is rejected here`() {
            assertFalse(attachmentMimeIsSupported("application/pdf"))
            assertFalse(attachmentMimeIsSupported("application/zip"))
            assertFalse(attachmentMimeIsSupported("video/mp4"))
            assertFalse(attachmentMimeIsSupported("application/octet-stream"))
            assertFalse(attachmentMimeIsSupported(""))
        }

        /**
         * The picker filter is enforced by the document provider, so anything
         * missing from it is simply not selectable. Android's `MimeTypeMap`
         * has no entry for `kt` / `kts` / `rs` / `toml` / `go` / `yml`, so
         * `ExternalStorageProvider` reports them as `application/octet-stream`
         * — leaving that out of the array greys out precisely the files
         * [normalizeAttachmentMime]'s extension table exists to rescue, and
         * "attach the failing test" stops working.
         */
        @Test
        fun `picker filter admits the source files the extension table rescues`() {
            assertTrue(
                "application/octet-stream" in ATTACHMENT_FILE_PICKER_MIME_TYPES,
                "source files would be unselectable: " +
                    ATTACHMENT_FILE_PICKER_MIME_TYPES.joinToString(),
            )
            for (name in listOf("Main.kt", "build.gradle.kts", "Cargo.toml", "compose.yml")) {
                assertTrue(serverWouldAccept(normalizeAttachmentMime("application/octet-stream", name)))
            }
        }

        @Test
        fun `picker filter only advertises types the desktop can use`() {
            for (mime in ATTACHMENT_FILE_PICKER_MIME_TYPES) {
                if (mime == "application/octet-stream") continue // deliberate, see above
                val probe = if (mime.endsWith("/*")) mime.removeSuffix("*") + "plain" else mime
                assertTrue(serverWouldAccept(probe), "picker offers unusable $mime")
            }
        }

        @Test
        fun `a placeholder mime is relabelled from a known text extension`() {
            assertEquals(
                "text/plain",
                normalizeAttachmentMime("application/octet-stream", "SessionDetailStore.kt"),
            )
            assertEquals("text/plain", normalizeAttachmentMime("content/unknown", "Cargo.toml"))
            assertEquals("text/plain", normalizeAttachmentMime("", "build.gradle"))
        }

        @Test
        fun `a placeholder mime on an unknown extension is left alone`() {
            assertEquals(
                "application/octet-stream",
                normalizeAttachmentMime("application/octet-stream", "firmware.bin"),
            )
            assertEquals(
                "application/octet-stream",
                normalizeAttachmentMime("application/octet-stream", "noextension"),
            )
        }

        @Test
        fun `a confident answer from the provider is never overridden`() {
            assertEquals("application/pdf", normalizeAttachmentMime("application/pdf", "notes.kt"))
            assertEquals("image/png", normalizeAttachmentMime("image/png", "shot.png"))
        }
    }

    @Nested
    inner class DeferredSendRestore {

        @Test
        fun `an id leaving the in-flight set means a send settled`() {
            assertTrue(deferredSendSettled(setOf(1L, 2L), setOf(2L)))
            assertTrue(deferredSendSettled(setOf(1L), emptySet()))
        }

        @Test
        fun `a new send starting is not a settle`() {
            assertFalse(deferredSendSettled(setOf(1L), setOf(1L, 2L)))
            assertFalse(deferredSendSettled(emptySet(), setOf(1L)))
            assertFalse(deferredSendSettled(setOf(1L), setOf(1L)))
            assertFalse(deferredSendSettled(emptySet(), emptySet()))
        }
    }

    @Nested
    inner class DraftSavedStateCap {

        @Test
        fun `an ordinary draft is saved verbatim`() {
            assertEquals("hello", draftSaveableValue("hello"))
            assertEquals("", draftSaveableValue(""))
            val atCap = "x".repeat(DRAFT_SAVEABLE_MAX_CHARS)
            assertEquals(atCap, draftSaveableValue(atCap))
        }

        /**
         * The N-58 regression: a pasted build log went into the saved-state
         * Bundle verbatim and blew the ~1 MB Binder transaction budget on
         * backgrounding, killing the app with TransactionTooLargeException.
         */
        @Test
        fun `an oversized draft is kept out of the bundle`() {
            assertNull(draftSaveableValue("x".repeat(DRAFT_SAVEABLE_MAX_CHARS + 1)))
            assertNull(draftSaveableValue("x".repeat(1_000_000)))
        }
    }
}
