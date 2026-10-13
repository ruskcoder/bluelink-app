package com.ruskcoder.messages.util

import java.net.URI

/**
 * Reads what a web page says about itself out of its HTML — the Open Graph and Twitter card tags
 * link previews are built from, with `<title>` and `description` as the fallbacks.
 *
 * Deliberately not an HTML parser. The head of a page is all that is needed, the tags involved are
 * flat `<meta>` elements, and a regex over the first few hundred kilobytes answers the question in a
 * fraction of the time and none of the dependency weight a DOM would cost. Pure Kotlin, so it is
 * tested on the JVM without a device.
 */
object LinkMetaParser {

    data class PageMeta(
        val title: String?,
        val description: String?,
        val siteName: String?,
        /** An absolute http(s) address for the page's picture, resolved against the page. */
        val imageUrl: String?,
    ) {
        val isEmpty: Boolean get() = title.isNullOrBlank() && imageUrl.isNullOrBlank()
    }

    /** Null when the page describes itself with nothing a card could show. */
    fun parse(html: String, pageUrl: String): PageMeta? {
        // Only the head matters, and a page that never closes its head is cut off at the body.
        val head = html.substringBefore("</head>", html.substringBefore("<body", html))
        val metas = HashMap<String, String>()
        MetaTag.findAll(head).forEach { tag ->
            val attributes = attributesOf(tag.value)
            val key = (attributes["property"] ?: attributes["name"] ?: attributes["itemprop"])?.lowercase() ?: return@forEach
            val content = attributes["content"]?.let(::decodeEntities)?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            metas.putIfAbsent(key, content)
        }
        val title = metas["og:title"] ?: metas["twitter:title"]
            ?: TitleTag.find(head)?.groupValues?.get(1)?.let(::decodeEntities)?.let(::collapseWhitespace)
        val description = metas["og:description"] ?: metas["twitter:description"] ?: metas["description"]
        val image = (metas["og:image:secure_url"] ?: metas["og:image"] ?: metas["og:image:url"]
            ?: metas["twitter:image"] ?: metas["twitter:image:src"])
            ?.let { resolve(pageUrl, it) }
        val site = metas["og:site_name"] ?: hostOf(pageUrl)
        return PageMeta(
            title = title?.take(MaxTitle)?.takeIf { it.isNotBlank() },
            description = description?.take(MaxDescription),
            siteName = site,
            imageUrl = image,
        ).takeIf { !it.isEmpty }
    }

    /** The host a link points at, without a leading `www.`, for the line under a card's title. */
    fun hostOf(url: String): String? =
        runCatching { URI(url).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() }

    private fun resolve(pageUrl: String, candidate: String): String? = runCatching {
        val resolved = URI(pageUrl).resolve(candidate.trim()).toString()
        resolved.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
    }.getOrNull()

    private fun attributesOf(tag: String): Map<String, String> =
        Attribute.findAll(tag).associate { match ->
            val value = match.groups[3]?.value ?: match.groups[4]?.value ?: match.groups[5]?.value.orEmpty()
            match.groupValues[1].lowercase() to value
        }

    private fun collapseWhitespace(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /** The handful of entities that actually turn up in titles; anything else is left as written. */
    internal fun decodeEntities(text: String): String =
        Entity.replace(text) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x", ignoreCase = true) ->
                    body.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: match.value
                body.startsWith("#") -> body.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: match.value
                else -> NamedEntities[body.lowercase()] ?: match.value
            }
        }

    private val MetaTag = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val TitleTag = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    /** Name, then the value in whichever of the three quotings it was written with: 3, 4 or 5. */
    private val Attribute = Regex("""([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*(?:(")([^"]*)"|'([^']*)'|([^\s"'>]+))""")
    private val Entity = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
    private val NamedEntities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘",
        "rdquo" to "”", "ldquo" to "“",
    )

    private const val MaxTitle = 200
    private const val MaxDescription = 300
}
