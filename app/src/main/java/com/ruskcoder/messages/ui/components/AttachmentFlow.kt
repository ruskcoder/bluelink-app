package com.ruskcoder.messages.ui.components

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.ruskcoder.messages.data.model.Attachment
import com.ruskcoder.messages.data.model.AttachmentSource
import java.io.File

/**
 * Turns a tap on one of the "+" panel's tiles into a real attachment.
 *
 * Every source here is a platform picker rather than something drawn in-app: the photo picker, the
 * camera app, the storage document picker, the address book. That is both less code and the flow
 * people already know — and, for photos and documents, the only one that works without asking for
 * a storage permission at all.
 */
@Stable
class AttachmentFlow internal constructor(
    private val context: Context,
    private val onPick: (Attachment) -> Unit,
    private val onStage: (List<Uri>) -> Unit,
    private val launchers: Launchers,
) {
    internal class Launchers(
        val photos: () -> Unit,
        val camera: () -> Unit,
        val video: () -> Unit,
        val document: () -> Unit,
    )

    /** Which source is mid-flight, for the panel's own busy state. */
    var busy by mutableStateOf(false)
        internal set

    /** The address book is drawn in-app, so the screen shows this sheet itself. */
    var contactPickerOpen by mutableStateOf(false)
        internal set

    fun start(source: AttachmentSource) {
        busy = true
        when (source) {
            AttachmentSource.PHOTOS -> launchers.photos()
            AttachmentSource.CAMERA -> launchers.camera()
            AttachmentSource.VIDEO -> launchers.video()
            AttachmentSource.DOCUMENT -> launchers.document()
            AttachmentSource.CONTACT -> { busy = false; contactPickerOpen = true }
        }
    }

    fun closeContactPicker() {
        contactPickerOpen = false
    }

    internal fun deliver(attachment: Attachment?) {
        busy = false
        if (attachment != null) onPick(attachment)
    }

    /**
     * Pictures, which go into the draft rather than straight into the conversation.
     *
     * Everything with a camera or a gallery behind it comes through here: what you get back is a
     * picture you have not seen yet at the size it will be sent at, and the draft is where you
     * look at it. A photo you have just taken is the case that needs it most — it is the one you
     * are most likely to want to draw on, write a line under, or take again.
     *
     * A document and a contact card come back through [deliver] instead, as the attachment they
     * are rather than as a uri. What happens next is the screen's: the conversation puts a document
     * in the draft's strip beside the pictures, and sends a contact card on the spot.
     */
    internal fun stage(uris: List<Uri>) {
        busy = false
        if (uris.isNotEmpty()) onStage(uris)
    }

    internal fun context(): Context = context
}

/** Name and size for a content URI, which is all a file bubble shows. */
private fun queryFile(context: Context, uri: Uri): Pair<String, Long?> {
    var name = uri.lastPathSegment ?: "File"
    var size: Long? = null
    val cursor: Cursor? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
    }.getOrNull()
    cursor?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && !it.isNull(nameIndex)) name = it.getString(nameIndex)
            val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0 && !it.isNull(sizeIndex)) size = it.getLong(sizeIndex)
        }
    }
    return name to size
}

/**
 * Whether a picked URI is a video, so the right bubble is built for it.
 *
 * The extension is consulted when the resolver will not say. A picked file is copied into the app's
 * own storage before it is sent, and what comes back is a `file://` uri with no provider behind it
 * to answer for its type — so asking the resolver alone, a clip staged into the draft came back as
 * "not a video" and was sent as a still.
 */
private fun isVideo(context: Context, uri: Uri): Boolean {
    context.contentResolver.getType(uri)?.let { return it.startsWith("video") }
    val extension = uri.toString().substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.startsWith("video") == true
}

/**
 * A picture's real dimensions, read from its header rather than assumed.
 *
 * Every image the panel produced used to be declared 1080x1350 and every video 1080x1920, whatever
 * they actually were — so a landscape photograph went into the conversation in a portrait frame.
 */
/**
 * A picture that arrived some way other than the picker — pasted into the draft, or dropped on it.
 *
 * The same reading of the same header; only the way it got here is different, and where it came
 * from is not something a bubble has any business knowing.
 */
fun mediaAttachmentFor(context: Context, uri: Uri): Attachment =
    if (isVideo(context, uri)) videoAttachment(context, uri) else imageAttachment(context, uri)

/** Whether a draft's media uri is a clip, so a thumbnail can wear a play badge. */
fun isVideoUri(context: Context, uri: Uri): Boolean = isVideo(context, uri)

private fun imageAttachment(context: Context, uri: Uri): Attachment.Image {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } }
    val width = options.outWidth.takeIf { it > 0 } ?: 1080
    val height = options.outHeight.takeIf { it > 0 } ?: 1440
    return Attachment.Image(uri = uri.toString(), width = width, height = height)
}

/** Ditto for a clip: its frame size and how long it runs. */
private fun videoAttachment(context: Context, uri: Uri): Attachment.Video {
    var width = 0
    var height = 0
    var duration: Long? = null
    runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            retriever.release()
        }
    }
    return Attachment.Video(
        uri = uri.toString(),
        width = width.takeIf { it > 0 } ?: 1080,
        height = height.takeIf { it > 0 } ?: 1920,
        durationMs = duration,
    )
}

/**
 * A capture's destination: a file in the app's own cache, handed to the camera as a content uri it
 * is granted write access to.
 *
 * The camera used to be asked for a *preview* — the thumbnail bundled back in the result, a couple
 * of hundred pixels across — because that is the one capture that needs no output uri at all. It is
 * also, therefore, the one capture whose result is unusable as a photograph. The app already
 * publishes its cache directory through a `FileProvider` so attachments can be shared out of it, so
 * there was nothing to avoid: the same provider takes a full-resolution frame back, and a video,
 * which has no thumbnail-shaped shortcut at all.
 */
private fun captureTarget(context: Context, extension: String): Uri? = runCatching {
    val file = File(context.cacheDir, "capture-" + System.currentTimeMillis() + "." + extension)
    FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
}.getOrNull()

/**
 * How many pictures the gallery will let you take at once.
 *
 * Not unbounded: each one becomes a message of its own, and a hundred messages is not a thing
 * anybody meant to do. High enough that nobody bumps it choosing a set of holiday photographs.
 */
private const val GALLERY_LIMIT = 20

@Composable
fun rememberAttachmentFlow(
    onPick: (Attachment) -> Unit,
    /** Gallery picks, which land in the draft rather than being sent — see [AttachmentFlow.stage]. */
    onStage: (List<Uri>) -> Unit = {},
): AttachmentFlow {
    val context = LocalContext.current
    // Held across the round trip to the camera app, which is a separate process: the contracts
    // report only whether the capture happened, not where it went.
    var pendingCapture by remember { mutableStateOf<Uri?>(null) }
    var flow by remember { mutableStateOf<AttachmentFlow?>(null) }
    // The fallback gallery's question, up while it is being asked.
    var askingKind by remember { mutableStateOf(false) }

    // Many at a time. Sending one picture and then going back into the gallery for the next was
    // the whole cost of the single-item picker, and the platform one has taken a selection since
    // Android 13 — with a system-drawn selection UI on older versions through the backport.
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(GALLERY_LIMIT)) { uris ->
        uris.forEach { context.contentResolver.takePersistableUriPermissionSafely(it) }
        flow?.stage(uris)
    }
    /**
     * The gallery, for devices with no system photo picker on them.
     *
     * `PickVisualMedia` falls back to `ACTION_OPEN_DOCUMENT` there — the files browser, opened on a
     * folder tree, which is not where anyone keeps their photographs. This opens the gallery app
     * instead, through `openGallery`. Rare now: the manifest asks Play services for the backported
     * system picker, which covers most devices.
     */
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // `ACTION_PICK` reports one selection in `data` and several in `clipData`, never both, so
        // both have to be read or a multiple selection comes back empty on exactly the devices this
        // fallback exists for.
        val data = result.data
        val clip = data?.clipData
        val uris = when {
            clip != null -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            else -> listOfNotNull(data?.data)
        }
        flow?.stage(uris)
    }
    // Into the draft, the same as a gallery pick: a capture is a picture you have not looked at
    // yet. Cancelled, it is `deliver(null)` — nothing to stage, and the panel's busy state still
    // has to be put down.
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val target = pendingCapture
        pendingCapture = null
        if (saved && target != null) flow?.stage(listOf(target)) else flow?.deliver(null)
    }
    val video = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { saved ->
        val target = pendingCapture
        pendingCapture = null
        if (saved && target != null) flow?.stage(listOf(target)) else flow?.deliver(null)
    }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        flow?.deliver(
            uri?.let {
                context.contentResolver.takePersistableUriPermissionSafely(it)
                val (name, size) = queryFile(context, it)
                Attachment.File(uri = it.toString(), name = name, size = size, mimeType = context.contentResolver.getType(it))
            },
        )
    }

    /**
     * The gallery app, for one kind at a time.
     *
     * `ACTION_PICK` against a media collection is what the gallery apps answer — not the files
     * browser — but it is a request for that collection by its address, so pictures and clips have
     * to be asked for separately. A device with nothing to answer it gets `GET_CONTENT` instead,
     * without `CATEGORY_OPENABLE` so the gallery apps are offered alongside the files browser.
     */
    fun openGallery(kind: GalleryKind) {
        val collection = when (kind) {
            GalleryKind.PHOTOS -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            GalleryKind.VIDEOS -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val pick = Intent(Intent.ACTION_PICK, collection).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        val launched = runCatching { gallery.launch(pick) }.isSuccess
        if (!launched) {
            gallery.launch(
                Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = if (kind == GalleryKind.PHOTOS) "image/*" else "video/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                },
            )
        }
    }

    if (askingKind) {
        AppDialog(
            title = "Gallery",
            message = "Choose from your photos or your videos.",
            onDismiss = {
                askingKind = false
                flow?.deliver(null)
            },
            buttons = {
                TextButton(onClick = {
                    askingKind = false
                    openGallery(GalleryKind.PHOTOS)
                }) { Text("Photos", color = Color.White) }
                TextButton(onClick = {
                    askingKind = false
                    openGallery(GalleryKind.VIDEOS)
                }) { Text("Videos", color = Color.White) }
            },
        )
    }

    val launchers = remember {
        AttachmentFlow.Launchers(
            photos = {
                if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)) {
                    photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                } else {
                    // No system picker: ask which, and hand that to the gallery app — see [GalleryKind].
                    askingKind = true
                }
            },
            camera = {
                val target = captureTarget(context, "jpg")
                pendingCapture = target
                if (target == null) flow?.deliver(null) else camera.launch(target)
            },
            video = {
                val target = captureTarget(context, "mp4")
                pendingCapture = target
                if (target == null) flow?.deliver(null) else video.launch(target)
            },
            document = { document.launch(arrayOf("*/*")) },
        )
    }

    val created = remember(launchers) { AttachmentFlow(context, onPick, onStage, launchers) }
    flow = created
    return created
}

/** Which of the gallery app's collections to open, where there is no system picker to show both. */
private enum class GalleryKind { PHOTOS, VIDEOS }

/** Keeps a picked document readable after the process restarts, where the provider allows it. */
private fun android.content.ContentResolver.takePersistableUriPermissionSafely(uri: Uri) {
    runCatching { takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
}

/** A file path as the `uri` string the bubbles render from. */
internal fun File.toBubbleUri(): String = toUri().toString()
