package com.ruskcoder.messages.imessage

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.core.net.toUri
import com.ruskcoder.messages.data.model.Attachment
import com.ruskcoder.messages.data.db.MessageEntity
import com.ruskcoder.messages.util.appJson
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.imessage_core.AttachmentInfo
import uniffi.imessage_core.downloadAttachment
import com.ruskcoder.messages.util.saveAttachmentToDevice

/**
 * Fetches the bytes behind a received attachment.
 *
 * A message arrives carrying only an attachment's name, size and where to find it — Apple stores
 * the contents separately, and they have to be pulled down and decrypted before anything can be
 * shown. Files are fetched only after their durable Room row is committed. The serialized MMCS
 * token remains on that row until the download succeeds, so a process restart cannot make a
 * received file permanently unreachable.
 */
class AttachmentDownloads(
    private val context: Context,
    private val service: IMessageService,
) {
    /** Whether a photo or video is saved to the phone once it has downloaded — see [Settings.autoSaveMedia]. */
    var autoSaveMedia: () -> Boolean = { false }

    /**
     * The attachment as it stands before anything is downloaded.
     *
     * Already the right *kind* of bubble: a photo on its way in is a photo, drawn as an empty tile
     * that fills itself in, rather than a file named after whatever the sender's phone called it.
     * That name is the one people were seeing as `____.png`, and a file bubble is also the one shape
     * a notification cannot put a picture in.
     */
    fun describeOnly(info: AttachmentInfo, caption: String?): Attachment = pending(info, caption)

    /**
     * Fetch [info] and describe it for storage, or null when its bytes did not arrive.
     *
     * Null rather than a file bubble, and the difference matters: the caller leaves the MMCS token
     * on the row for [resume] to try again, instead of writing a permanent document bubble pointing
     * at nothing. A failed download used to be indistinguishable from a document, which is how a
     * photo whose fetch lost the network became a file called `____.png` for good.
     */
    suspend fun fetch(messageId: String, info: AttachmentInfo, caption: String?): Attachment? {
        val safeName = safeName(info.name)
        val root = directory().canonicalFile
        val target = File(root, "$messageId-$safeName").canonicalFile
        require(target.parentFile == root) { "Attachment path escaped its storage directory" }
        if (target.exists() && target.length() > 0) return describe(info, target, caption)

        return try {
            downloadAttachment(service.session(), info.token, target.absolutePath)
            describe(info, target, caption).also { saved ->
                // Only on the download itself: a file already here was saved, or not, when it came.
                if (autoSaveMedia() && (saved is Attachment.Image || saved is Attachment.Video)) {
                    withContext(Dispatchers.IO) {
                        runCatching { saveAttachmentToDevice(context, target.toUri().toString()) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not download ${info.name}", e)
            null
        }
    }

    /** Resume a download whose durable MMCS token survived a process restart. */
    suspend fun resume(row: MessageEntity): Attachment? {
        val token = row.attachmentToken ?: return null
        val stored = row.payload?.let {
            runCatching { appJson.decodeFromString(Attachment.serializer(), it) }.getOrNull()
        }
        // Whatever is still waiting for its bytes: a file, a photo or a clip promised by [pending],
        // or a sticker waiting for its picture. Anything that already has a uri is done.
        val name = when (stored) {
            is Attachment.File -> stored.name
            is Attachment.Image ->
                if (stored.uri.isNotBlank()) return null
                else if (stored.sticker) "sticker" else "image"
            is Attachment.Video -> if (stored.uri.isNotBlank()) return null else "video"
            else -> return null
        }
        val root = directory().canonicalFile
        val target = File(root, "${row.id}-${safeName(name)}").canonicalFile
        require(target.parentFile == root) { "Attachment path escaped its storage directory" }
        return try {
            if (!target.exists() || target.length() == 0L) {
                downloadAttachment(service.session(), token, target.absolutePath)
            }
            val uri = target.toUri().toString()
            when (stored) {
                is Attachment.Image -> {
                    val shape = imageShape(target)
                    stored.copy(
                        uri = uri,
                        width = shape?.first ?: stored.width,
                        height = shape?.second ?: stored.height,
                        genmoji = confirmedGenmoji(stored.genmoji, shape),
                    )
                }
                is Attachment.Video -> {
                    val shape = videoShape(target)
                    stored.copy(
                        uri = uri,
                        width = shape?.first ?: stored.width,
                        height = shape?.second ?: stored.height,
                        durationMs = shape?.third ?: stored.durationMs,
                    )
                }
                else -> (stored as Attachment.File).copy(uri = uri)
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not resume $name", e)
            null
        }
    }

    /**
     * What an attachment looks like before — or without — its bytes.
     *
     * The shape is taken from how the sender labelled it, since that is all there is to go on yet;
     * see [mediaKindOf] for why that is read from three places rather than one. An attachment
     * nothing identifies stays a file, which is the only honest thing to call unknown bytes.
     */
    private fun pending(info: AttachmentInfo, caption: String?): Attachment = when (info.mediaKind) {
        MediaKind.IMAGE -> Attachment.Image(
            uri = "",
            width = 0,
            height = 0,
            caption = caption,
            genmoji = info.genmoji,
        )
        MediaKind.VIDEO -> Attachment.Video(uri = "", width = 0, height = 0, caption = caption)
        MediaKind.FILE, MediaKind.UNKNOWN -> Attachment.File(
            uri = "",
            name = info.name,
            size = info.size.toLong(),
            mimeType = info.mime,
        )
    }

    /**
     * Build the stored form of an attachment whose bytes are here on disk.
     *
     * Images and videos become media bubbles: their dimensions can be read off the file, and the
     * thread needs those to lay the bubble out. Where the labels say nothing the bytes are asked
     * instead — a decoder that can read the header has answered the question more reliably than any
     * attribute could. A picture whose header this phone cannot read is still kept as a picture
     * rather than demoted to a document, for the same reason a sticker that fails to decode stays a
     * sticker: the bubble should be what was sent.
     */
    private fun describe(info: AttachmentInfo, localPath: File?, caption: String?): Attachment {
        if (localPath == null) return pending(info, caption)
        val uri = localPath.toUri().toString()
        val kind = info.mediaKind

        if (kind == MediaKind.IMAGE || kind == MediaKind.UNKNOWN) {
            val shape = imageShape(localPath)
            if (shape != null || kind == MediaKind.IMAGE) {
                return Attachment.Image(
                    uri = uri,
                    width = shape?.first ?: 0,
                    height = shape?.second ?: 0,
                    caption = caption,
                    genmoji = confirmedGenmoji(info.genmoji, shape),
                )
            }
        }

        if (kind == MediaKind.VIDEO || kind == MediaKind.UNKNOWN) {
            val shape = videoShape(localPath)
            if (shape != null || kind == MediaKind.VIDEO) {
                return Attachment.Video(
                    uri = uri,
                    width = shape?.first ?: 0,
                    height = shape?.second ?: 0,
                    durationMs = shape?.third,
                    caption = caption,
                )
            }
        }

        return Attachment.File(
            uri = uri,
            name = info.name,
            size = info.size.toLong(),
            mimeType = info.mime,
        )
    }

    /**
     * Keep a Genmoji verdict only if the bytes agree with it.
     *
     * A glyph with no description was recognised from a bare PNG reporting 0x0, which is a guess; a
     * real photo can report that too. Glyphs are square, so a picture clearly wider or taller than
     * that is a picture, not a Genmoji. A described glyph (an explicit wire marker) is trusted.
     */
    private fun confirmedGenmoji(genmoji: String?, shape: Pair<Int, Int>?): String? {
        if (genmoji == null || genmoji.isNotBlank() || shape == null) return genmoji
        val (w, h) = shape
        if (w <= 0 || h <= 0) return genmoji
        val ratio = w.toFloat() / h
        return if (ratio in 0.9f..1.1f) genmoji else null
    }

    /** A picture's pixel size, or null if the file cannot be read as one. */
    private fun imageShape(file: File): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, bounds) }
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            bounds.outWidth to bounds.outHeight
        } else {
            null
        }
    }

    /**
     * A clip's displayed size and length, or null if the file cannot be read as video.
     *
     * The rotation tag matters: a clip shot in portrait is stored as a landscape frame plus a
     * quarter turn, so taking the raw width and height would lay every phone video out on its side.
     */
    private fun videoShape(file: File): Triple<Int, Int, Long>? = runCatching {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(file.absolutePath)
            fun tag(key: Int) = retriever.extractMetadata(key)?.toLongOrNull()
            val rawWidth = tag(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: return null
            val rawHeight = tag(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: return null
            if (rawWidth <= 0 || rawHeight <= 0) return null
            val rotation = tag(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
            val turned = rotation == 90 || rotation == 270
            Triple(
                if (turned) rawHeight else rawWidth,
                if (turned) rawWidth else rawHeight,
                tag(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: 0L,
            )
        }
    }.getOrNull()

    private fun directory(): File =
        File(context.filesDir, "imessage/attachments").apply { mkdirs() }

    /** Remote names are labels, never paths. This also handles Windows separators in payloads. */
    private fun safeName(name: String): String {
        val leaf = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = leaf
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.', ' ')
            .takeLast(MAX_NAME)
        return cleaned.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "attachment"
    }

    private companion object {
        const val TAG = "AttachmentDownloads"

        /** Keeps a pathological filename from producing a path the filesystem rejects. */
        const val MAX_NAME = 80
    }
}
