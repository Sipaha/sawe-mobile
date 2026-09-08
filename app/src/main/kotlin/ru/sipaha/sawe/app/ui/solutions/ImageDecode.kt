package ru.sipaha.sawe.app.ui.solutions

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.sipaha.sawe.core.EntryImage

/**
 * Off-main-thread, size-bounded image decoding for the chat surface.
 *
 * Both entry points here are `produceState` composables: the decode runs on a
 * background dispatcher and the result lands as a state update, so nothing
 * blocks composition. Decoding in the composition body — which is what the
 * chat bubbles and the attachment cards used to do — put a full-resolution
 * `BitmapFactory` pass on the main thread for every bubble that scrolled past
 * (a 12 MP JPEG is ~48 MB of ARGB_8888) and was the single biggest source of
 * jank and OOM on the transcript list (N-30 / N-49).
 */

/**
 * Longest edge, in pixels, we decode a *tapped* image to. Above this there is
 * nothing left to see: the fullscreen dialog is `fillMaxWidth` with a 600 dp
 * height cap, so even on a 4× density panel 2048 px is beyond one-to-one.
 */
internal const val FULLSCREEN_IMAGE_MAX_DIM_PX: Int = 2048

/** Longest edge for a compose-row attachment thumbnail (a 96×72 dp card). */
internal const val THUMBNAIL_MAX_DIM_PX: Int = 256

/**
 * Smallest power-of-two `inSampleSize` that brings BOTH edges of a
 * [width]×[height] source down to [maxDim] or less.
 *
 * Bounding from above (rather than the common "never decode below the
 * requested size" idiom) is deliberate: this is what caps the allocation. A
 * 12 MP photo at `inSampleSize = 1` is ~48 MB of ARGB_8888 and the app ships
 * without `largeHeap`, so the ceiling matters more than the last stop of
 * sharpness on a phone-sized viewport.
 *
 * `BitmapFactory` rounds any non-power-of-two value down anyway, so computing
 * it explicitly is what makes the result predictable (and testable).
 * Degenerate bounds — an undecodable header reports `-1` — yield `1`, i.e.
 * "decode as-is and let the decoder fail honestly".
 */
internal fun sampleSizeFor(width: Int, height: Int, maxDim: Int): Int {
    if (width <= 0 || height <= 0 || maxDim <= 0) return 1
    var sample = 1
    while (width / sample > maxDim || height / sample > maxDim) {
        sample *= 2
    }
    return sample
}

/**
 * Decode [bytes] down to at most [maxDimPx] on its longest edge, using a
 * bounds-only first pass so the full-size bitmap is never allocated. Returns
 * `null` on malformed input — callers render a placeholder.
 */
private fun decodeSampled(bytes: ByteArray, maxDimPx: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimPx)
    }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}.getOrNull()

/**
 * Outcome of an asynchronous image decode.
 *
 * A tri-state rather than a nullable [Painter] because "still decoding" and
 * "this payload is not an image" both used to arrive as `null`, leaving the
 * caller no way to tell them apart — a truncated base64 blob rendered a
 * progress spinner that never stopped.
 */
internal sealed interface ImageDecodeResult {
    /** Decode in flight. */
    data object Loading : ImageDecodeResult

    /** Nothing to decode, or the payload could not be decoded. */
    data object Failed : ImageDecodeResult

    /** Decoded and ready to draw. */
    data class Ready(val painter: Painter) : ImageDecodeResult
}

/**
 * Decode a chat entry's [image] on [Dispatchers.Default], capped at
 * [maxDimPx].
 *
 * Keyed on the [EntryImage] itself: it is a `data class`, so a transcript
 * re-fetch that returns byte-identical image content does NOT restart the
 * decode. Call this only from a branch that is actually showing the image —
 * the whole point is that a bubble scrolling past costs nothing.
 */
@Composable
internal fun rememberEntryImagePainter(
    image: EntryImage?,
    maxDimPx: Int = FULLSCREEN_IMAGE_MAX_DIM_PX,
): ImageDecodeResult {
    val result by produceState<ImageDecodeResult>(
        initialValue = if (image == null) ImageDecodeResult.Failed else ImageDecodeResult.Loading,
        image,
        maxDimPx,
    ) {
        val target = image
        value = if (target == null) {
            ImageDecodeResult.Failed
        } else {
            withContext(Dispatchers.Default) {
                val bytes = runCatching { Base64.decode(target.dataBase64, Base64.DEFAULT) }
                    .getOrNull()
                bytes?.let { decodeSampled(it, maxDimPx) }
                    ?.let { ImageDecodeResult.Ready(BitmapPainter(it.asImageBitmap())) }
                    ?: ImageDecodeResult.Failed
            }
        }
    }
    return result
}

/**
 * Painter for a picked attachment's `content://` [uri], decoded on
 * [Dispatchers.IO] (the read goes through a ContentProvider) and capped at
 * [maxDimPx]. `null` while in flight, for a restored attachment whose URI grant
 * is gone ([Uri.EMPTY]), and on any provider/decode failure.
 */
@Composable
internal fun rememberContentUriPainter(
    uri: Uri,
    maxDimPx: Int = THUMBNAIL_MAX_DIM_PX,
): Painter? {
    val context: Context = LocalContext.current
    val painter by produceState<Painter?>(initialValue = null, uri, maxDimPx, context) {
        value = if (uri == Uri.EMPTY) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    // Two passes over the stream: content providers don't
                    // reliably support mark/reset, so re-open rather than
                    // rewind between the bounds pass and the real decode.
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimPx)
                    }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, options)
                    }?.let { BitmapPainter(it.asImageBitmap()) }
                }.getOrNull()
            }
        }
    }
    return painter
}
