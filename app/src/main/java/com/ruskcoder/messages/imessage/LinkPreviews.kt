package com.ruskcoder.messages.imessage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.net.toUri
import com.ruskcoder.messages.data.model.LinkPreview
import com.ruskcoder.messages.util.LinkMetaParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import uniffi.imessage_core.IncomingLinkPreview
import uniffi.imessage_core.LinkPreviewSpec

/**
 * Link cards: fetching one for a link we are about to send, and keeping the one a message arrived
 * with.
 *
 * Only ever the *sender* fetches, which is how Apple's clients do it and the privacy point of the
 * whole arrangement: the person a link is sent to does not have their phone contact the linked site
 * — revealing that they received it, and their address — just because a message landed. Their card
 * is drawn entirely from what came inside the message.
 */
class LinkPreviews(private val context: Context) {

    /** A card for the store, and the same card in the shape the wire takes. */
    class Fetched(val preview: LinkPreview, val spec: LinkPreviewSpec)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * Fetches the page behind [url] and builds its card, or null when there is nothing to show.
     *
     * Bounded everywhere it can be: the page is read only as far as its head, the picture only up to
     * a few megabytes and then shrunk, and the caller holds the whole thing to a short timeout — a
     * card is a nicety, and a message must never wait long for one.
     */
    suspend fun fetch(messageId: String, url: String): Fetched? = withContext(Dispatchers.IO) {
        runCatching {
            val html = get(url, accept = "text/html,application/xhtml+xml", maxBytes = MaxPageBytes, htmlOnly = true)
                ?.toString(Charsets.UTF_8)
                ?: return@runCatching null
            val meta = LinkMetaParser.parse(html, url) ?: return@runCatching null
            val picture = meta.imageUrl
                ?.let { get(it, accept = "image/*", maxBytes = MaxImageBytes, htmlOnly = false) }
                ?.let(::shrink)
            val stored = picture?.let { store(messageId, it.bytes) }
            Fetched(
                preview = LinkPreview(
                    url = url,
                    title = meta.title,
                    summary = meta.description,
                    siteName = meta.siteName,
                    imageUri = stored,
                ),
                spec = LinkPreviewSpec(
                    url = url,
                    title = meta.title,
                    summary = meta.description,
                    image = picture?.bytes,
                    imageMime = picture?.let { "image/jpeg" },
                    imageWidth = (picture?.width ?: 0).toUInt(),
                    imageHeight = (picture?.height ?: 0).toUInt(),
                ),
            ).takeIf { it.preview.isUseful }
        }.onFailure { Log.i(TAG, "no link card for $url", it) }.getOrNull()
    }

    /** Keeps a card that arrived inside a message: its picture goes to disk, the rest to the row. */
    suspend fun storeIncoming(messageId: String, incoming: IncomingLinkPreview): LinkPreview? =
        withContext(Dispatchers.IO) {
            val image = incoming.image
                ?.takeIf { it.isNotEmpty() && it.size <= MaxImageBytes }
                // Decoded for its bounds before it is kept: a card's picture is from the sender, and
                // bytes that are not an image have no business on disk pretending to be one.
                ?.takeIf { bytes ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    bounds.outWidth > 0 && bounds.outHeight > 0
                }
                ?.let { runCatching { store(messageId, it) }.getOrNull() }
            LinkPreview(
                url = incoming.url,
                title = incoming.title?.take(200),
                summary = incoming.summary?.take(300),
                siteName = LinkMetaParser.hostOf(incoming.url),
                imageUri = image,
            ).takeIf { it.url.isNotBlank() && it.isUseful }
        }

    private class Picture(val bytes: ByteArray, val width: Int, val height: Int)

    private fun get(url: String, accept: String, maxBytes: Int, htmlOnly: Boolean): ByteArray? {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return null
        val request = Request.Builder()
            .url(url)
            .header("Accept", accept)
            // Plenty of sites serve their card tags only to something that looks like a browser.
            .header("User-Agent", UserAgent)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val type = body.contentType()
            if (htmlOnly && type != null && type.subtype != "html" && type.subtype != "xhtml+xml") return null
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            body.byteStream().use { input ->
                while (out.size() < maxBytes) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
            }
            // A picture cut off at the limit is a broken picture; a page cut off at it still has its head.
            if (!htmlOnly && out.size() >= maxBytes) return null
            return out.toByteArray()
        }
    }

    /** Downscaled to a card's size and re-encoded as JPEG, so what is sent is small whatever the site served. */
    private fun shrink(bytes: ByteArray): Picture? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = Integer.highestOneBit((longest / MaxImageEdge).coerceAtLeast(1))
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val scale = minOf(1f, MaxImageEdge.toFloat() / maxOf(decoded.width, decoded.height))
        val sized = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        sized.compress(Bitmap.CompressFormat.JPEG, JpegQuality, out)
        return Picture(out.toByteArray(), sized.width, sized.height)
    }

    private fun store(messageId: String, bytes: ByteArray): String {
        val directory = File(context.filesDir, "link_previews").apply { mkdirs() }
        val safe = messageId.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
        val file = File(directory, "$safe.jpg")
        file.writeBytes(bytes)
        return file.toUri().toString()
    }

    private companion object {
        const val TAG = "LinkPreviews"
        const val MaxPageBytes = 512 * 1024
        const val MaxImageBytes = 4 * 1024 * 1024
        const val MaxImageEdge = 720
        const val JpegQuality = 82
        const val UserAgent =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    }
}
