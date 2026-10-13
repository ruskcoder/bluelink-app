package com.ruskcoder.messages.ui.screens.share

import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * What another app handed us through the share sheet, in the two halves a message is made of.
 *
 * [text] is already the finished draft: a subject and a body arrive as separate extras and are
 * joined here, because whatever the sharing app meant by the split, the person sending it is going
 * to send one message. [uris] are still the *sharing app's* uris at this point and are only
 * readable while the receiving activity lives — see `AttachmentStaging.stageShared`.
 */
data class SharedContent(
    val text: String = "",
    val uris: List<Uri> = emptyList(),
) {
    val isEmpty: Boolean get() = text.isBlank() && uris.isEmpty()
}

/**
 * Read a share intent, or come back empty for anything that is not one.
 *
 * Both actions are handled, and both halves of each: `ACTION_SEND` can carry a file *and* a caption
 * — that is how a gallery shares a picture with a note on it — so reading the stream and then
 * stopping would quietly drop the words.
 *
 * A subject is kept only when it says something the body does not. Browsers share a page as its
 * title plus its URL, which is worth having as two lines; mail apps often repeat the subject inside
 * the body, and printing it twice is worse than not printing it at all.
 */
fun Intent.sharedContent(): SharedContent {
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return SharedContent()

    val body = getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty().trim()
    val subject = getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().trim()
    val text = when {
        subject.isEmpty() -> body
        body.isEmpty() -> subject
        body.contains(subject, ignoreCase = true) -> body
        else -> "$subject\n$body"
    }

    val uris = when (action) {
        Intent.ACTION_SEND -> listOfNotNull(streamExtra())
        else -> streamListExtra()
    }

    return SharedContent(text = text, uris = uris)
}

/**
 * `EXTRA_STREAM` as a uri.
 *
 * The typed getters are deprecated from Android 13 because the untyped ones would happily return
 * whatever class the sender put there; the replacement asks for the class explicitly, and the old
 * call is still the only one that exists below it.
 */
@Suppress("DEPRECATION")
private fun Intent.streamExtra(): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    }

@Suppress("DEPRECATION")
private fun Intent.streamListExtra(): List<Uri> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    } else {
        getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
    }
