package com.ruskcoder.messages.data.repository

/**
 * Turns what somebody typed into a search box into a full-text `MATCH` expression.
 *
 * Typed text is not an FTS query and must never be passed as one: a stray `"`, a `-`, an `OR` or a
 * `*` in the middle of a word is query syntax, and at best changes what is matched and at worst is a
 * syntax error that throws out of the DAO. So the input is split into words, anything that is not a
 * letter or digit is dropped from each, and every word is quoted and made a prefix — "din tom"
 * becomes `"din"* "tom"*`, which finds "dinner tomorrow". Words are ANDed, which is what a search box
 * means: every word you typed, somewhere in the message.
 */
internal object SearchQuery {
    /** Null when nothing searchable is left, which the caller answers with the substring scan. */
    fun ftsMatch(query: String): String? {
        val words = query
            .split(Regex("\\s+"))
            .map { word -> word.filter { it.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
            // A pathological paste should not become a pathological query.
            .take(MaxWords)
        if (words.isEmpty()) return null
        return words.joinToString(" ") { "\"$it\"*" }
    }

    private const val MaxWords = 12
}
