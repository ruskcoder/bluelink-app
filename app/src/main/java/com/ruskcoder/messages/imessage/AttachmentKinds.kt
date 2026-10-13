package com.ruskcoder.messages.imessage

import uniffi.imessage_core.AttachmentInfo

/**
 * What an attachment off the wire actually is.
 *
 * Deciding this from `mime-type` alone is what put photos in the thread as files called
 * `____.png`: Apple does not promise that attribute, and a client that leaves it out — or sends
 * `application/octet-stream`, which says "bytes" and nothing else — took a picture and made it a
 * document. So all three of the things the sender did tell us get a say: the MIME type, the UTI,
 * and the extension on the name. Any one of them naming a picture is enough to treat it as one.
 *
 * [UNKNOWN] is the honest answer when none of them says anything useful. It is not [FILE]: a file
 * bubble is a claim about what arrived, and for those the bytes themselves get asked instead — see
 * `AttachmentDownloads.describe`.
 */
enum class MediaKind { IMAGE, VIDEO, FILE, UNKNOWN }

/** Extensions worth trusting on a name, for the attachments that arrive with nothing else. */
private val ImageExtensions = setOf(
    "png", "jpg", "jpeg", "jpe", "jfif", "heic", "heif", "hif", "avif",
    "gif", "webp", "bmp", "tif", "tiff", "dng", "ico",
)

private val VideoExtensions = setOf(
    "mov", "qt", "mp4", "m4v", "3gp", "3g2", "3gpp", "avi", "mkv", "webm", "mpg", "mpeg", "mpe",
)

/** Apple's own type identifiers for the pictures and clips a phone sends. */
private val ImageUtis = setOf(
    "public.image", "public.png", "public.jpeg", "public.jpeg-2000", "public.tiff",
    "public.heic", "public.heif", "public.heics", "public.avif", "public.camera-raw-image",
    "com.compuserve.gif", "com.microsoft.bmp", "com.microsoft.ico",
    "org.webmproject.webp", "com.google.webp", "com.adobe.raw-image",
    "com.apple.private.auto-loop-gif", "com.apple.macpaint-image", "com.apple.icns",
)

private val VideoUtis = setOf(
    "public.movie", "public.video", "public.mpeg", "public.mpeg-2-video", "public.mpeg-4",
    "public.avi", "public.3gpp", "public.3gpp2", "org.webmproject.webm",
    "com.apple.quicktime-movie", "com.apple.m4v-video", "com.microsoft.windows-media-wmv",
)

/**
 * A MIME type that carries no information. `application/octet-stream` is the default this end fills
 * in when the sender left the attribute off entirely, so it has to mean "unknown", not "document".
 */
private val VagueMimes = setOf(
    "", "application/octet-stream", "application/unknown", "binary/octet-stream", "*/*",
)

/**
 * Which of the three labels, if any, names a picture or a clip.
 *
 * A Genmoji is a picture whatever else it says about itself — it is drawn rather than filed — so it
 * settles the question before the labels are consulted at all.
 */
fun mediaKindOf(mime: String, utiType: String, name: String, genmoji: String? = null): MediaKind {
    if (genmoji != null) return MediaKind.IMAGE

    val cleanMime = mime.trim().lowercase().substringBefore(';')
    val uti = utiType.trim().lowercase()
    val extension = name.substringAfterLast('.', "").trim().lowercase()

    if (cleanMime.startsWith("image/")) return MediaKind.IMAGE
    if (cleanMime.startsWith("video/")) return MediaKind.VIDEO

    if (uti in ImageUtis) return MediaKind.IMAGE
    if (uti in VideoUtis) return MediaKind.VIDEO
    // Apple names new types every release — `public.heics`, the adaptive image glyph, a raw format
    // from a camera nobody here has heard of. Anything that says image or movie in its own name is
    // taken at its word rather than waiting to be added to the list above.
    if (uti.contains("image") || uti.contains("photo")) return MediaKind.IMAGE
    if (uti.contains("movie") || uti.contains("video")) return MediaKind.VIDEO

    if (extension in ImageExtensions) return MediaKind.IMAGE
    if (extension in VideoExtensions) return MediaKind.VIDEO

    // A MIME type that means something, and it is neither: a PDF, a vCard, a zip. Only when every
    // label is vague is this left open for the bytes to answer.
    val told = cleanMime !in VagueMimes || (uti.isNotEmpty() && uti != "public.data")
    return if (told) MediaKind.FILE else MediaKind.UNKNOWN
}

/** What the sender's labels make of this attachment — see [mediaKindOf]. */
val AttachmentInfo.mediaKind: MediaKind
    get() = mediaKindOf(mime, utiType, name, genmoji)
