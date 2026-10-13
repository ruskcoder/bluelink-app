package com.ruskcoder.messages.data.model

import kotlinx.serialization.Serializable

/**
 * The card a link in a message is drawn with: what the page calls itself, a line about it, and a
 * picture where it has one.
 *
 * Fetched by the sender, at the moment of sending — the way Apple's own clients do it — and carried
 * to the other end inside the message. So a received card costs the receiving phone nothing and,
 * more to the point, tells the linked site nothing: it never hears from the person the link was sent
 * to until they choose to open it.
 */
@Serializable
data class LinkPreview(
    /** The link as it was written in the message, which is what a tap opens. */
    val url: String,
    val title: String? = null,
    /** The page's own one-line description, where it gave one. */
    val summary: String? = null,
    /** The site's name, falling back to the host, for the line under the title. */
    val siteName: String? = null,
    /** A local `file:` uri for the picture, once its bytes are on this device. */
    val imageUri: String? = null,
) {
    /** A card with nothing on it but the address is not worth drawing over the address itself. */
    val isUseful: Boolean get() = !title.isNullOrBlank() || !imageUri.isNullOrBlank()
}

/** The first web address in [text], or null. What a message's card, if any, describes. */
fun firstLinkIn(text: String): String? =
    LinkPattern.find(text)?.value?.trimEnd('.', ',', ')', '!', '?', ';', ':', '\'', '"')

private val LinkPattern = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
