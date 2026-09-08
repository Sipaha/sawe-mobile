package ru.sipaha.sawe.app.vm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.sipaha.sawe.core.EntryRoleDto
import ru.sipaha.sawe.core.EntrySummary

/**
 * Claim bookkeeping for the per-entry image backfill ([ImageBackfillTracker]).
 *
 * The bug this pins: the backfill used to claim its whole batch up front
 * (`resolvedImageIndices.addAll(wanted)`) and then `return` from the coroutine
 * on the first failed probe — or on a momentarily-null transport — releasing
 * only the index it was on. Every index behind it stayed claimed, and since
 * the claim set is what `entriesNeedingImageFetch` filters on, those photos
 * were unreachable for the rest of the session open: tapping `[image #N]`
 * silently did nothing until the chat was closed and reopened.
 *
 * The claim is now taken per request and handed back on failure, so
 * abandoning a pass cannot strand anything. These assert that directly,
 * against the same `entriesNeedingImageFetch` filter the store uses.
 */
class ImageBackfillTrackerTest {

    private fun user(index: Int) = EntrySummary(
        role = EntryRoleDto.User,
        preview = "u$index",
        index = index,
        images = null,
    )

    private val window = listOf(user(0), user(1), user(2), user(3), user(4), user(5))

    @Test
    fun `a claimed index is not offered again`() {
        val tracker = ImageBackfillTracker()
        assertTrue(tracker.claim(2))
        assertFalse(tracker.claim(2), "a second claim on the same index must be refused")
        assertEquals(
            listOf(0, 1, 3, 4, 5),
            entriesNeedingImageFetch(window, tracker.claimedIndices()),
        )
    }

    @Test
    fun `a settled index is never asked about again`() {
        val tracker = ImageBackfillTracker()
        tracker.claim(2)
        tracker.settle(2)
        assertEquals(
            listOf(0, 1, 3, 4, 5),
            entriesNeedingImageFetch(window, tracker.claimedIndices()),
            "this is the point of the tracker: one round trip per entry per open, " +
                "not one per delta poll",
        )
    }

    @Test
    fun `abandoning a pass mid-way strands nothing`() {
        val tracker = ImageBackfillTracker()
        // A pass over six photos: two answer, the third fails, and the rest are
        // never attempted because the pass gives up.
        tracker.claim(0); tracker.settle(0)
        tracker.claim(1); tracker.settle(1)
        tracker.claim(2); tracker.fail(2)
        // 3, 4, 5 were never claimed — the old code had already claimed them.
        assertEquals(
            listOf(2, 3, 4, 5),
            entriesNeedingImageFetch(window, tracker.claimedIndices()),
            "the failed probe AND every untried entry behind it must still be " +
                "offered to the next pass",
        )
    }

    @Test
    fun `a claim given back untouched costs no attempt`() {
        val tracker = ImageBackfillTracker(maxAttempts = 2)
        // No client / session closed / tab switched: nothing was tried.
        repeat(5) {
            assertTrue(tracker.claim(3))
            tracker.release(3)
        }
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5),
            entriesNeedingImageFetch(window, tracker.claimedIndices()),
        )
    }

    @Test
    fun `a permanently failing entry is retired after the attempt budget`() {
        val tracker = ImageBackfillTracker(maxAttempts = 3)
        assertTrue(tracker.claim(4))
        assertTrue(tracker.fail(4), "first failure is retryable")
        assertTrue(tracker.claim(4))
        assertTrue(tracker.fail(4), "second failure is retryable")
        assertTrue(tracker.claim(4))
        assertFalse(tracker.fail(4), "the third retires it for this open")
        assertEquals(
            listOf(0, 1, 2, 3, 5),
            entriesNeedingImageFetch(window, tracker.claimedIndices()),
            "without the cap, every pass would retry it forever",
        )
    }

    @Test
    fun `a success resets the failure budget for that entry`() {
        val tracker = ImageBackfillTracker(maxAttempts = 2)
        tracker.claim(1)
        tracker.fail(1)
        tracker.claim(1)
        tracker.settle(1)
        // The entry stays claimed (it answered), but its history is clean — a
        // later open that clears the tracker starts it from zero, and a
        // transient blip earlier in the session cannot count against it.
        tracker.clear()
        tracker.claim(1)
        assertTrue(tracker.fail(1), "the earlier failure must not carry over")
    }

    @Test
    fun `clear returns every entry to the pool`() {
        val tracker = ImageBackfillTracker()
        for (i in 0..5) {
            tracker.claim(i)
            tracker.settle(i)
        }
        assertEquals(emptyList<Int>(), entriesNeedingImageFetch(window, tracker.claimedIndices()))
        // Indices are stream-local and reset on every open, so a switch must
        // put all of them back in play.
        tracker.clear()
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5),
            entriesNeedingImageFetch(window, tracker.claimedIndices()),
        )
    }

    @Test
    fun `a batch is bounded so it cannot head-of-line block the delta poll`() {
        // The store takes the newest IMAGE_BACKFILL_BATCH entries per pass:
        // the desktop dispatches inline and in order, so draining ~20 user
        // entries in one go stalls the transcript sync behind all of them.
        val tracker = ImageBackfillTracker()
        val batch = entriesNeedingImageFetch(window, tracker.claimedIndices()).takeLast(4)
        assertEquals(listOf(2, 3, 4, 5), batch, "newest first — those are on screen")
    }
}
