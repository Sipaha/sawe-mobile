package ru.sipaha.sawe.app.vm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import ru.sipaha.sawe.core.EntryImage
import ru.sipaha.sawe.core.EntryRoleDto
import ru.sipaha.sawe.core.EntrySummary

/**
 * Pure-JVM coverage for the lazy-image path that replaces `include_images=true`
 * on every transcript request ([entriesNeedingImageFetch], [carryOverImages],
 * [withEntryImages]).
 *
 * The bug this pins (N-30): a page of 50 entries inlined every attached photo
 * as base64 (a 5 MB JPEG is ~6.7 MB on the wire), and the delta re-sent the
 * whole entry on every `mod_seq` bump — so the same photo was downloaded again
 * and again for the life of the session, and the initial `get_session` could
 * exceed the 30 s call timeout and never open at all.
 *
 * `EntrySummary.images` is nullable on purpose: `null` means "not requested",
 * `[]` means "requested, none present". The distinction is what makes a
 * once-per-entry probe possible.
 */
class EntryImageBackfillTest {

    private fun user(
        index: Int,
        images: List<EntryImage>? = null,
        imageCount: Int? = null,
    ) = EntrySummary(
        role = EntryRoleDto.User,
        preview = "u$index",
        index = index,
        images = images,
        imageCount = imageCount,
    )

    private fun assistant(index: Int) = EntrySummary(
        role = EntryRoleDto.Assistant,
        preview = "a$index",
        index = index,
        images = null,
    )

    private val photo = EntryImage(index = 0, mimeType = "image/jpeg", dataBase64 = "AAAA")

    // ---- image_count: the three states are all load-bearing ----

    @Test
    fun `a positive count is probed`() {
        val entries = listOf(user(0, imageCount = 2), user(1, imageCount = 1))
        assertEquals(listOf(0, 1), entriesNeedingImageFetch(entries, resolved = emptySet()))
    }

    @Test
    fun `a count of zero is skipped`() {
        // The overwhelming majority of user messages. Before the server sent a
        // count, each of these cost a round trip on every session open — a few
        // hundred of them on a long transcript, on the path N-30 exists to
        // make cheap.
        val entries = listOf(user(0, imageCount = 0), user(1, imageCount = 0))
        assertEquals(emptyList<Int>(), entriesNeedingImageFetch(entries, resolved = emptySet()))
    }

    @Test
    fun `a null count is unknown, not none, and must still be probed`() {
        // Every currently-shipped desktop omits `image_count`. Reading the
        // absent field as zero would switch the whole backfill off against
        // them and make already-sent photos unreachable — which is why the DTO
        // is nullable rather than defaulted to 0.
        val entries = listOf(user(0, imageCount = null), user(1, imageCount = null))
        assertEquals(listOf(0, 1), entriesNeedingImageFetch(entries, resolved = emptySet()))
    }

    @Test
    fun `a mixed window probes only the entries that have something to fetch`() {
        val entries = listOf(
            user(0, imageCount = 0),      // text-only — skip
            user(1, imageCount = 3),      // three photos — probe
            assistant(2),                 // never probed regardless
            user(3, imageCount = null),   // older server — probe
            user(4, imageCount = 0),      // text-only — skip
        )
        assertEquals(listOf(1, 3), entriesNeedingImageFetch(entries, resolved = emptySet()))
    }

    @Test
    fun `a zero count wins over an unresolved image section`() {
        // `images == null` alone used to be the whole test. The count is the
        // authority on whether there is anything behind it.
        assertEquals(
            emptyList<Int>(),
            entriesNeedingImageFetch(listOf(user(0, images = null, imageCount = 0)), emptySet()),
        )
    }

    @Test
    fun `a positive count is still not re-probed once claimed`() {
        val entries = listOf(user(0, imageCount = 1), user(1, imageCount = 1))
        assertEquals(listOf(1), entriesNeedingImageFetch(entries, resolved = setOf(0)))
    }

    @Test
    fun `the count survives the disk cache, so a cache hit probes only what matters`() {
        // `persistCache` strips `images` but keeps `imageCount`, so a restored
        // window still knows which entries are worth a fetch without having
        // stored a byte of image data.
        val cached = listOf(
            user(0, imageCount = 0).copy(images = null),
            user(1, imageCount = 2).copy(images = null),
        )
        assertEquals(listOf(1), entriesNeedingImageFetch(cached, resolved = emptySet()))
    }

    @Test
    fun `only user entries with an unresolved image section are fetched`() {
        val entries = listOf(user(0), assistant(1), user(2), assistant(3))
        assertEquals(listOf(0, 2), entriesNeedingImageFetch(entries, resolved = emptySet()))
    }

    @Test
    fun `assistant and tool entries are never probed`() {
        // The server flattens assistant / tool image chunks to `spk-image://N`
        // markdown at conversion time and cannot re-inline them, so a probe
        // would be a guaranteed-empty round trip.
        assertEquals(
            emptyList<Int>(),
            entriesNeedingImageFetch(listOf(assistant(0), assistant(1)), resolved = emptySet()),
        )
    }

    @Test
    fun `an entry is probed once per open even though the delta re-sends it`() {
        val entries = listOf(user(0), user(1))
        assertEquals(listOf(1), entriesNeedingImageFetch(entries, resolved = setOf(0)))
        assertEquals(
            emptyList<Int>(),
            entriesNeedingImageFetch(entries, resolved = setOf(0, 1)),
            "this is the whole point: without it the photo is re-downloaded on " +
                "every mod_seq bump of its entry",
        )
    }

    @Test
    fun `an entry that answered with images is not probed again`() {
        assertEquals(
            emptyList<Int>(),
            entriesNeedingImageFetch(listOf(user(0, listOf(photo))), resolved = emptySet()),
        )
    }

    @Test
    fun `an entry proven image-free is not probed again`() {
        assertEquals(
            emptyList<Int>(),
            entriesNeedingImageFetch(listOf(user(0, emptyList())), resolved = emptySet()),
        )
    }

    @Test
    fun `unindexed entries are skipped`() {
        val optimistic = EntrySummary(role = EntryRoleDto.User, preview = "typing")
        assertEquals(emptyList<Int>(), entriesNeedingImageFetch(listOf(optimistic), emptySet()))
    }

    @Test
    fun `already-resolved images survive a delta that re-sends the entry stripped`() {
        val previous = listOf(user(0, listOf(photo)), assistant(1))
        val merged = listOf(user(0), assistant(1), assistant(2))
        val carried = carryOverImages(previous, merged)
        assertEquals(listOf(photo), carried[0].images)
        assertNull(carried[1].images)
        assertEquals(
            emptyList<Int>(),
            entriesNeedingImageFetch(carried, resolved = emptySet()),
            "carrying them over is what stops the lazy fetch re-downloading them",
        )
    }

    @Test
    fun `a fresh section from the server wins over the carried one`() {
        val newer = EntryImage(index = 0, mimeType = "image/png", dataBase64 = "BBBB")
        val carried = carryOverImages(
            previous = listOf(user(0, listOf(photo))),
            merged = listOf(user(0, listOf(newer))),
        )
        assertEquals(listOf(newer), carried[0].images)
    }

    @Test
    fun `carry-over is a no-op when nothing was resolved yet`() {
        val merged = listOf(user(0), user(1))
        assertSame(merged, carryOverImages(previous = emptyList(), merged = merged))
        assertSame(merged, carryOverImages(previous = listOf(user(0)), merged = merged))
    }

    @Test
    fun `a fetched image lands on its entry and nothing else`() {
        val entries = listOf(user(0), user(1), assistant(2))
        val updated = withEntryImages(entries, index = 1, images = listOf(photo))
        assertNull(updated[0].images)
        assertEquals(listOf(photo), updated[1].images)
        assertNull(updated[2].images)
    }

    // ---- non-Main streams ----
    //
    // `RemoteClient.getSessionEntry` now carries `stream_id`, so the backfill
    // runs on teammate / shell tabs too rather than bailing out on anything
    // that isn't [StreamIdDto.Main]. These pin the two properties the store
    // relies on for that: the selection rule is stream-agnostic, and the index
    // space is per-stream.

    @Test
    fun `user entries in a teammate window are probed exactly like Main`() {
        // The window handed to the backfill is already scoped to the selected
        // stream, so nothing about the decision may depend on which one it is.
        val teammateWindow = listOf(assistant(0), user(1), assistant(2), user(3))
        assertEquals(
            listOf(1, 3),
            entriesNeedingImageFetch(teammateWindow, resolved = emptySet()),
            "before stream_id existed the store returned early here and a photo " +
                "posted into a teammate tab was simply unreachable",
        )
    }

    @Test
    fun `indices are stream-local so a switch re-probes the same numbers`() {
        // Index 0 on Main and index 0 in a teammate tab are different entries.
        // The store clears `resolvedImageIndices` on a tab switch; this is the
        // behaviour that clearing has to produce.
        val mainWindow = listOf(user(0), user(1))
        val afterMain = entriesNeedingImageFetch(mainWindow, resolved = emptySet()).toSet()
        assertEquals(setOf(0, 1), afterMain)

        val teammateWindow = listOf(user(0), user(1))
        assertEquals(
            emptyList<Int>(),
            entriesNeedingImageFetch(teammateWindow, resolved = afterMain),
            "carrying Main's resolved set across the switch would silently skip " +
                "the teammate stream's own entries 0 and 1",
        )
        assertEquals(
            listOf(0, 1),
            entriesNeedingImageFetch(teammateWindow, resolved = emptySet()),
        )
    }

    @Test
    fun `images carry over within a stream's own window`() {
        // Same merge rule on any stream: a delta re-sends the entry stripped,
        // the already-resolved payload has to survive it.
        val previous = listOf(user(1, listOf(photo)), assistant(2))
        val merged = listOf(user(1), assistant(2), assistant(3))
        val carried = carryOverImages(previous, merged)
        assertEquals(listOf(photo), carried[0].images)
        assertEquals(
            emptyList<Int>(),
            entriesNeedingImageFetch(carried, resolved = emptySet()),
        )
    }
}
