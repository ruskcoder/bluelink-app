package com.ruskcoder.messages.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Patterns
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLinks
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Something tappable found in a message: a web address or a place. */
private data class DetectedLink(val start: Int, val end: Int, val kind: Kind, val target: String) {
    enum class Kind { URL, ADDRESS }
}

/**
 * [text] with its links and street addresses underlined and made tappable — links open in Chrome,
 * addresses in Google Maps.
 *
 * Web addresses are found straight away with the platform's own pattern, so a link is underlined on
 * the first frame. Addresses need the system's [TextClassifier], which is far too slow for the main
 * thread and only exists from Android 9, so those arrive a moment later and are simply absent below
 * that. Neither needs a library: both detectors ship with the OS.
 *
 * The underline keeps the bubble's own text colour — a blue link inside a blue bubble disappears.
 */
@Composable
fun rememberLinkedText(text: AnnotatedString): AnnotatedString {
    val context = LocalContext.current
    val urls = remember(text) { findUrls(text.text) }
    val links by produceState(urls, text) {
        val classified = withContext(Dispatchers.Default) { classify(context, text.text) } ?: return@produceState
        // The classifier's view wins where the two overlap; any link only the pattern caught is kept.
        value = (classified + urls.filter { u -> classified.none { it.start < u.end && u.start < it.end } })
            .sortedBy { it.start }
    }
    return remember(text, links) {
        if (links.isEmpty()) return@remember text
        buildAnnotatedString {
            append(text)
            links.forEach { link ->
                addLink(
                    LinkAnnotation.Clickable(
                        tag = link.kind.name,
                        styles = LinkStyle,
                    ) { openLink(context, link) },
                    link.start,
                    link.end,
                )
            }
        }
    }
}

private val LinkStyle = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline))

private fun findUrls(text: String): List<DetectedLink> {
    val matcher = Patterns.WEB_URL.matcher(text)
    val found = mutableListOf<DetectedLink>()
    while (matcher.find()) {
        val raw = matcher.group()
        // The pattern happily matches a bare "e.g" or "file.kt"; demand something that looks
        // like it was meant to be visited.
        if (!raw.contains("://") && !raw.startsWith("www.", ignoreCase = true) && !raw.contains('/') &&
            raw.substringAfterLast('.').length !in 2..6
        ) continue
        found.add(DetectedLink(matcher.start(), matcher.end(), DetectedLink.Kind.URL, raw))
    }
    return found
}

/**
 * Both kinds, from the system classifier. Null where it can't run — too old an OS, a message longer
 * than it will look at, or a classifier that throws — and the caller keeps the pattern's links.
 */
private fun classify(context: Context, text: String): List<DetectedLink>? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || text.isBlank()) return null
    return runCatching {
        val classifier = context.getSystemService(TextClassificationManager::class.java)?.textClassifier
            ?: return null
        if (text.length > classifier.maxGenerateLinksTextLength) return null
        val request = TextLinks.Request.Builder(text)
            .setEntityConfig(
                TextClassifier.EntityConfig.Builder()
                    .setIncludedTypes(listOf(TextClassifier.TYPE_URL, TextClassifier.TYPE_ADDRESS))
                    .includeTypesFromTextClassifier(false)
                    .build(),
            )
            .build()
        classifier.generateLinks(request).links.mapNotNull { link ->
            val kind = when {
                link.getConfidenceScore(TextClassifier.TYPE_ADDRESS) > 0f -> DetectedLink.Kind.ADDRESS
                link.getConfidenceScore(TextClassifier.TYPE_URL) > 0f -> DetectedLink.Kind.URL
                else -> return@mapNotNull null
            }
            DetectedLink(link.start, link.end, kind, text.substring(link.start, link.end))
        }
    }.getOrNull()
}

private fun openLink(context: Context, link: DetectedLink) {
    when (link.kind) {
        DetectedLink.Kind.URL -> {
            val uri = Uri.parse(if (link.target.contains("://")) link.target else "https://${link.target}")
            launch(context, Intent(Intent.ACTION_VIEW, uri), preferredPackage = "com.android.chrome")
        }
        DetectedLink.Kind.ADDRESS -> {
            val query = Uri.encode(link.target)
            launch(
                context,
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$query")),
                preferredPackage = "com.google.android.apps.maps",
                fallback = Uri.parse("https://www.google.com/maps/search/?api=1&query=$query"),
            )
        }
    }
}

/** The preferred app if it is installed, whatever handles the intent if not, and [fallback] last. */
private fun launch(context: Context, intent: Intent, preferredPackage: String, fallback: Uri? = null) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val attempts = listOfNotNull(
        Intent(intent).setPackage(preferredPackage),
        intent,
        fallback?.let { Intent(Intent.ACTION_VIEW, it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
    )
    for (attempt in attempts) {
        try {
            context.startActivity(attempt)
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
}
