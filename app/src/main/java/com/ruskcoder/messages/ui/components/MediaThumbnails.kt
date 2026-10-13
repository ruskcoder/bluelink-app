package com.ruskcoder.messages.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.delay

/**
 * The memory-cache entry every in-thread copy of a picture shares — the bubble, the stack's card,
 * the grid's tile.
 *
 * Named rather than left to Coil's own key so the viewer can ask for it: the full-size decode takes
 * a moment, and what is already in memory from the conversation is the right thing to show until it
 * lands, instead of black.
 */
fun thumbnailKey(uri: String): String = "thumb:$uri"

/** How many more times a still that failed to decode is asked for, and the wait before the first. */
private const val THUMBNAIL_RETRIES = 4
private const val THUMBNAIL_RETRY_MS = 350L

/**
 * A photo, or a video's first frame, as the conversation draws it: cropped to fill, cached under
 * [thumbnailKey].
 *
 * A failed load is asked for again rather than left as it fell. A batch of pictures arriving at
 * once is a batch of decodes at once, and the ones that lose — a video whose frame grab found no
 * codec free, a photo decoded while memory was short — used to stay the empty tile they failed as
 * for as long as the conversation was open, since nothing about them ever changed to prompt a
 * second try.
 */
@Composable
fun MediaThumbnail(
    uri: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    onSuccess: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var attempt by remember(uri) { mutableIntStateOf(0) }
    var failed by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri, failed) {
        if (failed && attempt < THUMBNAIL_RETRIES) {
            delay(THUMBNAIL_RETRY_MS shl attempt)
            failed = false
            attempt++
        }
    }
    val request = remember(context, uri) {
        ImageRequest.Builder(context).data(uri).memoryCacheKey(thumbnailKey(uri)).build()
    }
    // Remounted per attempt: the request is the same request, and an image that has already
    // answered it — with a failure — does not ask again on its own.
    key(attempt) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            onSuccess = { onSuccess?.invoke() },
            onError = { failed = true },
            modifier = modifier,
        )
    }
}
