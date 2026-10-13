package com.ruskcoder.messages.imessage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import android.webkit.MimeTypeMap
import com.ruskcoder.messages.data.model.Attachment
import com.ruskcoder.messages.data.model.asShareableText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.imessage_core.OutgoingAttachment

/**
 * Turns a picked file into something the Rust core can upload.
 *
 * Two conversions are needed. The picker hands back a `content://` URI, which only the app's own
 * `ContentResolver` can open — the Rust side needs a real path, so the bytes are copied out first.
 * And Apple keys attachment rendering off a Uniform Type Identifier rather than a MIME type, so one
 * has to be supplied alongside it.
 */
object AttachmentStaging {

    /**
     * Copy [uri] into app storage and describe it for sending.
     *
     * Returns null if the file cannot be read, which is the normal outcome for a URI whose
     * permission grant has already lapsed.
     */
    suspend fun stage(context: Context, uri: Uri): OutgoingAttachment? =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val name = displayName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/')
            // `getType` answers null for anything not served by a content provider that declares
            // one — a `file://` URI, and some camera outputs — and the old fallback of
            // application/octet-stream made every such capture arrive as a generic file rather than
            // a photo. The extension is the only other thing we have to go on.
            val mime = resolver.getType(uri)
                ?: mimeFromExtension(name)
                ?: mimeFromExtension(uri.toString())
                ?: DEFAULT_MIME

            // Already ours. The paste and share paths copy into the outbox at the moment the
            // picture or file arrives, because the grant on somebody else's uri does not outlive
            // the screen that received it — so by the time a send reaches here the file is often
            // one of our own already, and copying it again only doubles what a long video costs in
            // disk and in the wait before the upload starts.
            val ours = stagedFile(context, uri)
            // The staging prefix comes back off: the copy is stored under a nanosecond stamp so two
            // shares of the same file cannot collide, and that stamp is bookkeeping rather than
            // something the recipient should see written on the bubble.
            val fileName = ours?.name?.substringAfter('-') ?: name ?: fallbackName(mime)
            val staged = ours ?: File(outboxDir(context), "${System.nanoTime()}-$fileName")

            if (ours == null) {
                val copied = runCatching {
                    resolver.openInputStream(uri)?.use { input ->
                        staged.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@runCatching false
                    true
                }.getOrDefault(false)

                if (!copied) return@withContext null
            }

            if (mime.lowercase() in JPEG_MIMES) shrinkLargePhoto(staged)

            OutgoingAttachment(
                path = staged.absolutePath,
                name = fileName,
                mime = mime,
                uti = utiFor(mime),
                // Named by whoever sends it, not by whoever stages it — staging does not know
                // which row the file belongs to. See `IMessageSender.sendAttachment`.
                transferId = null,
            )
        }

    /**
     * Copy a pasted or dropped picture into app storage, and hand back a uri for the copy.
     *
     * Taken at the moment of the paste rather than at the moment of the send, because the grant on a
     * clipboard uri is not ours to keep: it lasts about as long as the clipboard entry does, and a
     * picture pasted into a draft may sit there while you write a paragraph, switch apps, and come
     * back. By then reading the original would fail and the send would drop a message you had
     * plainly composed. A copy of our own cannot be revoked.
     *
     * Written into the same outbox as everything else on its way out, so it is swept up by the same
     * [prune] — a picture pasted and then thought better of is a file nobody deletes otherwise.
     */
    suspend fun stagePasted(context: Context, uri: Uri): Uri? =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: mimeFromExtension(uri.toString()) ?: "image/jpeg"
            // A real extension matters here in a way it does not for a content uri: the copy is
            // addressed as `file://` from now on, and a file uri has no provider to ask for a type,
            // so the name is the only thing left to work it out from.
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
            val staged = File(outboxDir(context), "${System.nanoTime()}-pasted.$extension")
            val copied = runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    staged.outputStream().use { output -> input.copyTo(output) }
                } ?: return@runCatching false
                true
            }.getOrDefault(false)
            if (copied) Uri.fromFile(staged) else null
        }

    /**
     * Copy something another app shared with us into the outbox, and hand back a uri for the copy.
     *
     * The share sheet grants read access to the *receiving activity*, for as long as that activity
     * lives. The share screen finishes the moment Send is pressed, and the uploads carry on behind
     * it on the application scope — so by the time Apple asks for the bytes, the grant that would
     * have let us read them is gone and every attachment in the share fails silently. Copying up
     * front, while the grant is still good, is what makes the send survive the screen that started
     * it. Everything else here stages at the moment of the send; this one has to stage at the
     * moment of the *share*.
     *
     * The original name is kept where the provider will give one, since it is what the recipient
     * sees on a file bubble, and unlike a pasted picture a shared file is usually something the
     * sender named on purpose.
     */
    suspend fun stageShared(context: Context, uri: Uri): Uri? =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val name = displayName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/')
            val mime = resolver.getType(uri) ?: mimeFromExtension(name) ?: mimeFromExtension(uri.toString())
            // A file uri has no provider to answer for its type, so the extension is the only thing
            // the copy carries its type in — a name without one is given the mime's own.
            val extension = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            val base = safeName(name) ?: "shared"
            val fileName = if (base.contains('.')) base else extension?.let { "$base.$it" } ?: base
            val staged = File(outboxDir(context), "${System.nanoTime()}-$fileName")
            val copied = runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    staged.outputStream().use { output -> input.copyTo(output) }
                } ?: return@runCatching false
                true
            }.getOrDefault(false)
            if (copied) Uri.fromFile(staged) else null
        }

    /**
     * Re-encode a staged JPEG that is far bigger than a photo needs to be, in place.
     *
     * A phone camera set to its full sensor writes fifteen or twenty megabytes for one picture, and
     * every one of them has to climb a mobile uplink before the message it is in can go — which is
     * most of why a photo that takes a moment from an iPhone took a minute from here. An iPhone's
     * own photo is twelve megapixels and a few megabytes; this brings ours down to the same.
     *
     * Only the staged copy is touched, never the original, and only when it is worth it: a picture
     * under [LARGE_PHOTO_BYTES] goes exactly as it is, and so does one the re-encode barely shrinks.
     * Any failure — an unreadable file, not enough memory for the decode — leaves the copy as it was,
     * because a big photo that sends slowly is still better than one that does not send.
     */
    private fun shrinkLargePhoto(staged: File) {
        if (staged.length() <= LARGE_PHOTO_BYTES) return
        val smaller = File(staged.parentFile, "${staged.name}.tmp")
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(staged.path, bounds)
            val longEdge = max(bounds.outWidth, bounds.outHeight)
            if (longEdge <= 0) return@runCatching
            // Sampled on the way in, so a fifty-megapixel original is never held whole.
            var sample = 1
            while (longEdge / (sample * 2) >= PHOTO_LONG_EDGE) sample *= 2
            val decoded = BitmapFactory.decodeFile(
                staged.path,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return@runCatching
            val scale = PHOTO_LONG_EDGE.toFloat() / max(decoded.width, decoded.height)
            val shaped = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).roundToInt().coerceAtLeast(1),
                    (decoded.height * scale).roundToInt().coerceAtLeast(1),
                    true,
                )
            } else {
                decoded
            }
            smaller.outputStream().use { shaped.compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, it) }
            if (shaped !== decoded) shaped.recycle()
            decoded.recycle()

            if (smaller.length() == 0L || smaller.length() > staged.length() * WORTHWHILE_SHRINK) return@runCatching

            // The pixels are left the way the camera wrote them and the orientation tag is carried
            // across with the rest, so the picture stands the same way up without being rotated here.
            val from = ExifInterface(staged.path)
            val to = ExifInterface(smaller.path)
            KEPT_EXIF_TAGS.forEach { tag -> from.getAttribute(tag)?.let { to.setAttribute(tag, it) } }
            to.saveAttributes()

            if (!smaller.renameTo(staged)) smaller.copyTo(staged, overwrite = true)
        }.onFailure { Log.w(TAG, "could not shrink ${staged.name}; sending it as it is", it) }
        smaller.delete()
    }

    /**
     * The file behind [uri] when it is one of our own staged copies, and null for anything else.
     *
     * Compared on the canonical path of the parent rather than on the string, so a uri pointing at
     * the outbox by way of `..` from somewhere else cannot pass for a file we wrote.
     */
    private fun stagedFile(context: Context, uri: Uri): File? {
        if (uri.scheme != "file") return null
        val file = uri.path?.let(::File)?.takeIf { it.isFile } ?: return null
        val outbox = runCatching { outboxDir(context).canonicalPath }.getOrNull() ?: return null
        return file.takeIf { runCatching { it.canonicalFile.parentFile?.path }.getOrNull() == outbox }
    }

    /**
     * A display name reduced to something safe to build a path out of.
     *
     * The name comes from another app, so it is not ours to trust: a separator in it would put the
     * copy somewhere other than the outbox, and a leading `..` would put it outside the app's
     * storage altogether.
     *
     * An allowlist rather than a list of characters to strip. Naming the dangerous ones means being
     * right about all of them — on every filesystem the app might be installed on — where naming
     * the safe ones only has to be right about the handful a filename actually needs.
     */
    private fun safeName(name: String?): String? = name
        ?.map { if (it.isLetterOrDigit() || it in ALLOWED_NAME_CHARS) it else '_' }
        ?.joinToString("")
        ?.trim()
        ?.trimStart('.')
        ?.take(120)
        ?.takeIf { it.isNotBlank() }

    /**
     * Write a bitmap into the outbox as a draft picture of our own, and hand back a uri for it.
     *
     * The case is a picture that has been drawn on: the original is somebody else's file — the
     * camera roll, a screenshot, a copy staged by [stagePasted] — and the marked-up version is a
     * new picture that has never existed anywhere. It goes into the same outbox as everything else
     * on its way out, so [prune] sweeps it up if the draft is thought better of.
     */
    suspend fun stageBitmap(context: Context, bitmap: Bitmap, png: Boolean = false): Uri? =
        withContext(Dispatchers.IO) {
            val staged = File(outboxDir(context), "${System.nanoTime()}-markup.${if (png) "png" else "jpg"}")
            val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val written = runCatching {
                staged.outputStream().use { bitmap.compress(format, 92, it) }
            }.getOrDefault(false)
            if (written) Uri.fromFile(staged) else null
        }

    /**
     * Write a contact card out as a vCard and describe it for sending.
     *
     * A shared contact has no file behind it — it is a row from the address book — so one has to be
     * produced. Sending the card's text instead, which is what happened before, arrives as a plain
     * message rather than something the receiving client offers to add to Contacts.
     */
    suspend fun stageContact(context: Context, name: String, phone: String?): OutgoingAttachment? =
        withContext(Dispatchers.IO) {
            val display = name.trim().ifEmpty { return@withContext null }
            // vCard 3.0: the version every platform's Contacts app reads, where 4.0 support is
            // patchier. N is structured last;first, FN is what gets displayed.
            val words = display.split(Regex("\\s+"))
            val last = words.drop(1).joinToString(" ")
            val card = buildString {
                append("BEGIN:VCARD\r\n")
                append("VERSION:3.0\r\n")
                append("N:${escapeVCard(last)};${escapeVCard(words.first())};;;\r\n")
                append("FN:${escapeVCard(display)}\r\n")
                phone?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    append("TEL;type=CELL;type=VOICE;type=pref:${escapeVCard(it)}\r\n")
                }
                append("END:VCARD\r\n")
            }

            val fileName = display.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "Contact" }
            val staged = File(outboxDir(context), "${System.nanoTime()}-$fileName.vcf")
            val written = runCatching { staged.writeText(card); true }.getOrDefault(false)
            if (!written) return@withContext null

            OutgoingAttachment(
                path = staged.absolutePath,
                name = "$fileName.vcf",
                mime = "text/vcard",
                uti = "public.vcard",
                transferId = null,
            )
        }

    /**
     * Writes a game invite's preview image to the outbox so the balloon send can upload it as an
     * ordinary attachment part — the same shape Apple's own clients put an extension message on the
     * wire in (an object part for the summary text, then the preview picture). Returns null when
     * there is no preview, or the bytes are unreadable, in which case the balloon is sent without
     * one.
     */
    suspend fun stageGamePreview(
        context: Context,
        jpegBytes: ByteArray,
    ): OutgoingAttachment? = withContext(Dispatchers.IO) {
        if (jpegBytes.isEmpty()) return@withContext null
        val staged = File(outboxDir(context), "${System.nanoTime()}-game-preview.jpg")
        val written = runCatching { staged.writeBytes(jpegBytes); true }.getOrDefault(false)
        if (!written) return@withContext null

        OutgoingAttachment(
            path = staged.absolutePath,
            name = "preview.jpeg",
            mime = "image/jpeg",
            uti = "public.jpeg",
            transferId = null,
        )
    }

    /** Backslash, comma, semicolon and newline are vCard's structural characters. */
    private fun escapeVCard(value: String): String = value
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\n", "\\n")

    /**
     * Files staged for sending.
     *
     * Kept out of the cache directory: the upload streams from here, and a file the system may
     * reclaim mid-send is not a good foundation for one.
     */
    private fun outboxDir(context: Context): File =
        File(context.filesDir, "imessage/outbox").apply { mkdirs() }

    /** Removes staged files older than a day, which have either been sent or long since failed. */
    fun prune(context: Context) {
        val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
        outboxDir(context).listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }

    private fun displayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
        }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * The MIME type an extension implies, or null when the platform has no mapping for it.
     *
     * `MimeTypeMap` wants a bare, lowercase extension; anything else — including the leading dot it
     * is often written with — simply misses.
     */
    private fun mimeFromExtension(candidate: String?): String? {
        val extension = candidate?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() && it.length <= 5 && it.all(Char::isLetterOrDigit) }
            ?.lowercase()
            ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    private fun fallbackName(mime: String): String {
        val extension = mime.substringAfterLast('/', "bin").takeWhile { it.isLetterOrDigit() }
        return "attachment.${extension.ifEmpty { "bin" }}"
    }

    /**
     * Apple's Uniform Type Identifier for a MIME type.
     *
     * A small table rather than the full UTI map OpenBubbles ships, covering what a phone's picker
     * actually produces. Anything unrecognised falls back to `public.data`, which arrives as a
     * generic file — readable, just without a preview.
     */
    private fun utiFor(mime: String): String = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> "public.jpeg"
        "image/png" -> "public.png"
        "image/gif" -> "com.compuserve.gif"
        "image/heic" -> "public.heic"
        "image/heif" -> "public.heif"
        "image/webp" -> "org.webmproject.webp"
        "image/tiff" -> "public.tiff"
        "image/bmp" -> "com.microsoft.bmp"
        "video/mp4" -> "public.mpeg-4"
        "video/quicktime" -> "com.apple.quicktime-movie"
        "video/3gpp" -> "public.3gpp"
        "video/x-matroska" -> "org.matroska.mkv"
        "audio/mpeg", "audio/mp3" -> "public.mp3"
        "audio/mp4", "audio/m4a" -> "public.mpeg-4-audio"
        "audio/wav", "audio/x-wav" -> "com.microsoft.waveform-audio"
        "audio/amr" -> "org.3gpp.adaptive-multi-rate-audio"
        "audio/ogg" -> "org.xiph.ogg-audio"
        "application/pdf" -> "com.adobe.pdf"
        "text/plain" -> "public.plain-text"
        "text/vcard", "text/x-vcard" -> "public.vcard"
        "application/zip" -> "public.zip-archive"
        else -> "public.data"
    }

    /**
     * The size of whatever is behind a URI, or 0 when the provider will not say.
     *
     * Read before the copy starts so a bubble knows how big the thing it is sending is from its
     * very first frame, rather than growing a denominator halfway through.
     */
    fun sizeOf(context: Context, uri: Uri): Long =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else 0L
            } ?: 0L
        }.getOrDefault(0L)

    /** What a filename may keep, beyond letters and digits — see [safeName]. */
    private const val ALLOWED_NAME_CHARS = " ._-()[]"

    private const val TAG = "AttachmentStaging"

    private val JPEG_MIMES = setOf("image/jpeg", "image/jpg")

    /** A JPEG bigger than this is re-encoded before it is sent — see [shrinkLargePhoto]. */
    private const val LARGE_PHOTO_BYTES = 6L * 1024 * 1024

    /** The long edge of a twelve-megapixel photo, which is what an iPhone sends. */
    private const val PHOTO_LONG_EDGE = 4032
    private const val PHOTO_QUALITY = 90

    /** A re-encode is kept only if it comes out under this fraction of the original. */
    private const val WORTHWHILE_SHRINK = 0.8

    /** What is carried over from the original's EXIF: which way up it is, and when and where it was taken. */
    private val KEPT_EXIF_TAGS = listOf(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
    )

    private const val DEFAULT_MIME = "application/octet-stream"
    private const val STALE_AFTER_MS = 24L * 60 * 60 * 1000
}
