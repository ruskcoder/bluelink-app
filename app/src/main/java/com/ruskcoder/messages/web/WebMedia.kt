package com.ruskcoder.messages.web

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import com.ruskcoder.messages.data.model.Attachment
import com.ruskcoder.messages.data.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Files crossing the web link, in both directions.
 *
 * Everything moves in pieces well under the relay's 1 MiB frame cap: a browser reads a photo as a
 * series of `attachment.read` calls and uploads one as a series of `upload.chunk` calls. Previews
 * are re-encoded as JPEG here rather than shipped as the original, both to keep them small and
 * because a browser can't draw the HEIC an iPhone sends.
 */
internal class WebMedia(private val context: Context) {

    data class Source(val uri: Uri, val name: String, val mime: String)

    /** The file behind a message, or behind one picture of a grouped send. */
    fun sourceOf(message: Message, item: Int): Source? = when (message) {
        is Message.Audio -> message.uri.takeIf { it.isNotBlank() }?.let { source(it, "Audio Message", null) }
        is Message.AttachmentMessage -> sourceOf(message.attachment, item)
        is Message.Deleted -> message.attachment?.let { sourceOf(it, item) }
        else -> null
    }

    private fun sourceOf(attachment: Attachment, item: Int): Source? = when (attachment) {
        is Attachment.Image -> attachment.uri.takeIf { it.isNotBlank() }?.let { source(it, null, null) }
        is Attachment.Video -> attachment.uri.takeIf { it.isNotBlank() }?.let { source(it, null, null) }
        is Attachment.File -> attachment.uri.takeIf { it.isNotBlank() }?.let { source(it, attachment.name, attachment.mimeType) }
        is Attachment.MediaGroup -> attachment.items.getOrNull(item)?.let { sourceOf(it, 0) }
        else -> null
    }

    private fun source(uriString: String, name: String?, mime: String?): Source {
        // Voice messages are stored as a bare filesystem path rather than a uri, and a path handed to
        // the content resolver fails with "No content provider".
        val uri = if (uriString.startsWith("/")) Uri.fromFile(File(uriString)) else Uri.parse(uriString)
        val fileName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val type = mime
            ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        return Source(uri, fileName, type)
    }

    /** A JPEG no larger than [maxSide] on its longer edge, or null when there is nothing drawable. */
    suspend fun thumbnail(uri: Uri, mime: String, maxSide: Int): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = if (mime.startsWith("video/")) videoFrame(uri) else decodeImage(uri, maxSide)
            bitmap ?: return@runCatching null
            val scale = minOf(1f, maxSide.toFloat() / max(bitmap.width, bitmap.height))
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true)
            } else {
                bitmap
            }
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        }.getOrNull()
    }

    private fun decodeImage(uri: Uri, maxSide: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies EXIF orientation, which BitmapFactory silently ignores — a
            // portrait photo off a phone camera would otherwise arrive lying on its side.
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > maxSide) {
                    val ratio = maxSide.toFloat() / longest
                    decoder.setTargetSize((info.size.width * ratio).roundToInt().coerceAtLeast(1), (info.size.height * ratio).roundToInt().coerceAtLeast(1))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun videoFrame(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(0)
        } finally {
            retriever.release()
        }
    }

    /** Up to [length] bytes from [offset], and the file's total size. */
    suspend fun read(uri: Uri, offset: Long, length: Int): Pair<ByteArray, Long> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val size = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()?.takeIf { it >= 0 }
            ?: runCatching { resolver.openInputStream(uri)?.use { it.available().toLong() } }.getOrNull()
            ?: 0L
        val input = resolver.openInputStream(uri) ?: throw WebRpcException("That file is no longer on your phone")
        input.use { stream ->
            var skipped = 0L
            while (skipped < offset) {
                val n = stream.skip(offset - skipped)
                if (n <= 0) break
                skipped += n
            }
            val buffer = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = stream.read(buffer, read, length - read)
                if (n < 0) break
                read += n
            }
            buffer.copyOf(read) to size
        }
    }

    // ---- uploads ----

    class Upload(
        val id: String,
        val chatId: String,
        val file: File,
        val name: String,
        val mime: String,
        val size: Long,
        val width: Int?,
        val height: Int?,
        val durationMs: Long?,
        val replyToId: String?,
        /** `message` to send it, `wallpaper` to set it behind the conversation. */
        val purpose: String,
    ) {
        @Volatile var written = 0L
    }

    private val uploads = ConcurrentHashMap<String, Upload>()

    private val uploadRoot: File get() = File(context.cacheDir, "web-uploads").apply { mkdirs() }

    fun begin(
        chatId: String,
        name: String,
        mime: String,
        size: Long,
        width: Int?,
        height: Int?,
        durationMs: Long?,
        replyToId: String?,
        purpose: String,
    ): String {
        if (size <= 0 || size > MAX_UPLOAD_BYTES) throw WebRpcException("Files can be up to 100 MB")
        pruneOldUploads()
        val id = UUID.randomUUID().toString()
        // A directory per upload so the file keeps the name it was picked with; staging names the
        // attachment after the file, and that name is what the other person sees.
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120).ifBlank { "file" }
        val dir = File(uploadRoot, id).apply { mkdirs() }
        uploads[id] = Upload(id, chatId, File(dir, safeName), safeName, mime, size, width, height, durationMs, replyToId, purpose)
        return id
    }

    suspend fun chunk(id: String, offset: Long, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val upload = uploads[id] ?: throw WebRpcException("Upload expired")
        if (offset != upload.written) throw WebRpcException("Upload out of order")
        if (offset + bytes.size > upload.size) throw WebRpcException("Upload larger than declared")
        RandomAccessFile(upload.file, "rw").use { raf ->
            raf.seek(offset)
            raf.write(bytes)
        }
        upload.written = offset + bytes.size
    }

    fun finish(id: String): Upload {
        val upload = uploads.remove(id) ?: throw WebRpcException("Upload expired")
        if (upload.written != upload.size) throw WebRpcException("Upload incomplete")
        return upload
    }

    private fun pruneOldUploads() {
        val cutoff = System.currentTimeMillis() - UPLOAD_TTL_MS
        val live = uploads.keys
        uploadRoot.listFiles()?.forEach { dir ->
            if (dir.name !in live && dir.lastModified() < cutoff) dir.deleteRecursively()
        }
    }

    companion object {
        const val MAX_READ_BYTES = 512_000
        private const val MAX_UPLOAD_BYTES = 100L * 1024 * 1024
        private const val UPLOAD_TTL_MS = 24L * 60 * 60 * 1000
        private const val JPEG_QUALITY = 82
    }
}
