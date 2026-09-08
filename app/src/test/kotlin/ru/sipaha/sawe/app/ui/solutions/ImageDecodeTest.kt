package ru.sipaha.sawe.app.ui.solutions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [sampleSizeFor] — the sizing rule behind the N-30 / N-49 fix. Full-resolution
 * decodes used to run in composition on the main thread for every bubble and
 * every attachment thumbnail; sampling is what keeps the tapped-image decode
 * bounded and the thumbnail decode cheap.
 *
 * Robolectric only because the enclosing file touches Android/Compose types;
 * the function itself is pure arithmetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImageDecodeTest {

    @Test
    fun an_image_already_within_budget_is_decoded_as_is() {
        assertEquals(1, sampleSizeFor(width = 800, height = 600, maxDim = 2048))
        assertEquals(1, sampleSizeFor(width = 2048, height = 2048, maxDim = 2048))
    }

    @Test
    fun sampling_brings_both_edges_down_to_the_target() {
        // 4096 / 2 = 2048 = the cap, so one halving is enough.
        assertEquals(2, sampleSizeFor(width = 4096, height = 4096, maxDim = 2048))
        assertEquals(2, sampleSizeFor(width = 4000, height = 3000, maxDim = 2048))
        // A 12 MP phone photo into a 96x72 dp thumbnail: 4032 / 16 = 252.
        assertEquals(16, sampleSizeFor(width = 4032, height = 3024, maxDim = 256))
        // The whole point of the cap: never allocate the full 12 MP bitmap.
        val sample = sampleSizeFor(width = 4032, height = 3024, maxDim = 2048)
        assertTrue(4032 / sample <= 2048 && 3024 / sample <= 2048)
    }

    @Test
    fun the_longest_edge_drives_the_decision() {
        assertEquals(8, sampleSizeFor(width = 8000, height = 100, maxDim = 1024))
        assertEquals(8, sampleSizeFor(width = 100, height = 8000, maxDim = 1024))
    }

    @Test
    fun degenerate_bounds_never_produce_an_invalid_sample_size() {
        // BitmapFactory reports -1 for a header it couldn't parse.
        assertEquals(1, sampleSizeFor(width = -1, height = -1, maxDim = 2048))
        assertEquals(1, sampleSizeFor(width = 0, height = 0, maxDim = 2048))
        assertEquals(1, sampleSizeFor(width = 4096, height = 4096, maxDim = 0))
    }
}
