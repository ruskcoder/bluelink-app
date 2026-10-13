package com.ruskcoder.messages.data.model

/** One `@name` found in a message body, and who it points at. */
data class MentionSpan(val range: IntRange, val name: String)

/**
 * Find the `@name` mentions in a message.
 *
 * Derived from the text against the roster rather than stored as its own column, deliberately: a
 * mention *is* an at-sign followed by somebody's name, and iMessage puts exactly that on the wire.
 * Computing it means an inbound message from a real iMessage client highlights correctly with no
 * agreement about metadata, and a contact rename retroactively fixes older messages instead of
 * leaving them pointing at a name nobody has any more.
 *
 * Longest names are matched first, so "@Alex Kim" is one mention rather than "@Alex" followed by
 * loose text — and a mention only counts where the at-sign starts a word, so an email address in
 * the body is left alone.
 */
fun mentionsIn(text: String, names: List<String>): List<MentionSpan> {
    if (text.isEmpty() || names.isEmpty()) return emptyList()
    val candidates = names.filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }
    val found = mutableListOf<MentionSpan>()
    var index = 0
    while (index < text.length) {
        val at = text.indexOf('@', index)
        if (at < 0) break
        // An at-sign inside a word is part of that word — an address, a handle — not a mention.
        if (at > 0 && !text[at - 1].isWhitespace() && text[at - 1] !in "([\"'") {
            index = at + 1
            continue
        }
        val match = candidates.firstOrNull { name ->
            text.regionMatches(at + 1, name, 0, name.length, ignoreCase = true)
        }
        if (match == null) {
            index = at + 1
            continue
        }
        found += MentionSpan(at until (at + 1 + match.length), match)
        index = at + 1 + match.length
    }
    return found
}

/**
 * Whether a message mentions you.
 *
 * Takes every name you might be addressed by — your profile name and its first word — since a
 * group is as likely to write "@Alex" as your full name.
 */
fun mentionsMe(text: String, myNames: List<String>): Boolean =
    mentionsIn(text, myNames).isNotEmpty()

/** The names your own mentions could be written as, from the name in settings. */
fun Settings.myMentionNames(): List<String> {
    val full = displayName()
    return listOfNotNull(full, full.substringBefore(' ').takeIf { it != full && it.isNotBlank() })
}
