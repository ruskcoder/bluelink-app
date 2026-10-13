package com.ruskcoder.messages.data.repository

import android.content.Context

private const val PREFS_NAME = "recent_emoji"
private const val KEY = "recentEmoji.v1"

/** Two full rows of the picker's grid, which is what the Recents section is sized to show. */
private const val MAX_RECENTS = 16

/**
 * The emoji you have picked lately, most recent first.
 *
 * Stored as one string rather than as JSON: the whole value is a short list of glyphs, and a
 * separator that cannot occur inside one is all the structure it needs. Mirrors [RecentSearches],
 * which does the same job for the search screen.
 */
object RecentEmoji {
    private const val SEPARATOR = ""

    fun load(context: Context): List<String> =
        prefs(context).getString(KEY, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    /** Moves [glyph] to the front, de-duplicated, capped so the section stays two rows. */
    fun push(context: Context, glyph: String): List<String> {
        val next = (listOf(glyph) + load(context).filterNot { it == glyph }).take(MAX_RECENTS)
        prefs(context).edit().putString(KEY, next.joinToString(SEPARATOR)).apply()
        return next
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
