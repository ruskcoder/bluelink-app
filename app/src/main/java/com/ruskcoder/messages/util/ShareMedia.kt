package com.ruskcoder.messages.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

/**
 * Hands one attachment to another app.
 *
 * A stored attachment is a `file://` uri into internal storage, which cannot legally be passed to
 * another process — so it is re-published through the app's `FileProvider` first. Uris that are
 * already `content://` (a photo picked out of the gallery and not yet uploaded) are passed through
 * untouched, since they are somebody else's to grant.
 */
fun shareMedia(context: Context, uri: String) {
    val shareable = shareableUri(context, uri) ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeTypeOf(context, shareable)
        putExtra(Intent.EXTRA_STREAM, shareable)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share"))
}

/**
 * Puts one attachment on the clipboard, so it can be pasted into another app — or back into a
 * conversation here, where the composer takes a pasted picture into its draft.
 *
 * The clip carries the uri rather than the bytes. That is what the clipboard is for on Android: a
 * `ClipData` holding a `content://` uri is how every gallery on the platform copies a picture, and
 * whatever pastes it reads through the provider at that moment instead of a megabyte of image
 * travelling through a system service. Republished through the `FileProvider` for the same reason a
 * share is — the reader is another process, and a `file://` path into our private storage means
 * nothing to it.
 *
 * The read grant is set on the clip's own description, not on an intent: there is no intent here to
 * hang flags off, and without it the pasting app is handed a uri it is not allowed to open.
 *
 * Returns false when the uri cannot be republished, which is the one case worth telling the user
 * about — a silent copy that put nothing on the clipboard is indistinguishable from a broken paste
 * in whatever app they try next.
 */
fun copyMediaToClipboard(context: Context, uri: String): Boolean {
    val shareable = shareableUri(context, uri) ?: return false
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    val clip = ClipData.newUri(context.contentResolver, "Image", shareable)
    clip.description.extras = PersistableBundle().apply {
        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)
    }
    return runCatching {
        clipboard.setPrimaryClip(clip)
        context.grantUriPermission("android", shareable, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    }.getOrDefault(false)
}

/**
 * Hands one attachment to whichever app on the phone edits that kind of thing.
 *
 * Same republishing rule as [shareMedia], plus a write grant: an editor that cannot save its result
 * back has nothing to offer. Not every device has an editor at all, which is what the return value
 * is for — there is no dialog to show when nothing answers the intent.
 */
fun editMedia(context: Context, uri: String): Boolean {
    val shareable = shareableUri(context, uri) ?: return false
    val intent = Intent(Intent.ACTION_EDIT).apply {
        setDataAndType(shareable, mimeTypeOf(context, shareable))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    return runCatching { context.startActivity(Intent.createChooser(intent, "Edit")) }.isSuccess
}

/**
 * Copies one attachment out of the app and into the phone's own photo library.
 *
 * An attachment lives in the app's private storage, which the gallery cannot see — "download" here
 * means publishing a copy through `MediaStore`, where every other app on the device can find it.
 *
 * Scoped storage only, which is to say Android 10 and up. Below that the same write needs the
 * legacy storage permission, and asking for the run of a user's whole SD card to save one picture
 * is a worse trade than telling them it isn't supported.
 */
fun saveMediaToGallery(context: Context, uri: String): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val source = shareableUri(context, uri) ?: return false
    val mime = mimeTypeOf(context, source)
    val video = mime.startsWith("video")
    val collection = if (video) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
    val name = source.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "message-${System.currentTimeMillis()}"
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, mime.takeIf { it != "*/*" } ?: if (video) "video/mp4" else "image/jpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/Messages" else "Pictures/Messages")
        // Held back from the gallery until the bytes are actually there, so a half-copied file is
        // never something the user can open.
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val target = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return false
    val copied = runCatching {
        resolver.openInputStream(source).use { input ->
            resolver.openOutputStream(target).use { output ->
                if (input == null || output == null) throw IllegalStateException("no stream")
                input.copyTo(output)
            }
        }
    }.isSuccess
    if (!copied) {
        runCatching { resolver.delete(target, null, null) }
        return false
    }
    values.clear()
    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
    runCatching { resolver.update(target, values, null, null) }
    return true
}

/** Where a saved attachment ended up, which is the only useful thing to tell the user afterwards. */
enum class SavedTo { GALLERY, DOWNLOADS }

/**
 * Copies any attachment out of the app and somewhere the rest of the phone can reach it.
 *
 * Pictures and clips go to the photo library, where you would look for them; everything else — a
 * voice message, a PDF, a file whose type nothing here recognises — goes to Downloads, which is the
 * only folder on Android that means "things I saved". Both are published through `MediaStore`,
 * because an attachment otherwise lives in the app's private storage where nothing else can see it.
 *
 * Null on failure, and on Android 9 and below: the same write needs the legacy storage permission
 * there, and asking for the run of someone's whole SD card to save one file is a worse trade than
 * saying it isn't supported. See [saveMediaToGallery], which this generalises.
 */
fun saveAttachmentToDevice(context: Context, uri: String, name: String? = null): SavedTo? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val source = shareableUri(context, uri) ?: return null
    val mime = mimeTypeOf(context, source)
    val media = mime.startsWith("image") || mime.startsWith("video")
    if (media && saveMediaToGallery(context, uri)) return SavedTo.GALLERY

    val leaf = name?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
        ?: source.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "message-${System.currentTimeMillis()}"
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, leaf)
        put(MediaStore.MediaColumns.MIME_TYPE, mime.takeIf { it != "*/*" } ?: "application/octet-stream")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Messages")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val target = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
    val copied = runCatching {
        resolver.openInputStream(source).use { input ->
            resolver.openOutputStream(target).use { output ->
                if (input == null || output == null) throw IllegalStateException("no stream")
                input.copyTo(output)
            }
        }
    }.isSuccess
    if (!copied) {
        runCatching { resolver.delete(target, null, null) }
        return null
    }
    values.clear()
    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
    runCatching { resolver.update(target, values, null, null) }
    return SavedTo.DOWNLOADS
}

/**
 * The form of an attachment's uri that another process is allowed to open.
 *
 * A stored attachment is a `file://` uri into internal storage, which cannot legally be passed on —
 * so it is republished through the app's `FileProvider`. Uris that are already `content://` are
 * passed through untouched, since they are somebody else's to grant.
 */
private fun shareableUri(context: Context, uri: String): Uri? {
    val parsed = uri.toUri()
    return when (parsed.scheme) {
        // A bare path has no scheme at all, which is how a voice message stores its clip — the same
        // form the composer records into. Treated as anything but a file it reads as an opaque uri
        // that nothing can open.
        null -> republish(context, uri)
        "file" -> parsed.path?.let { republish(context, it) }
        else -> parsed
    }
}

private fun republish(context: Context, path: String): Uri? = runCatching {
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
}.getOrNull()

/**
 * Falls back to the wildcard rather than guessing: an intent typed as an image that turns out to be
 * a video offers the wrong set of apps, where the wildcard offers all of them.
 */
private fun mimeTypeOf(context: Context, uri: Uri): String =
    context.contentResolver.getType(uri)
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(uri.lastPathSegment?.substringAfterLast('.').orEmpty())
        ?: "*/*"

/**
 * Opens an attachment in whichever app on the phone handles its type. False when none does, or when
 * the file cannot be handed to another app at all.
 */
fun openAttachment(context: Context, uri: String): Boolean {
    val source = shareableUri(context, uri) ?: return false
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(source, mimeTypeOf(context, source))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(intent) }.isSuccess
}
