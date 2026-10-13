package com.ruskcoder.messages.data.repository

import android.content.Context
import com.ruskcoder.messages.util.appJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class RecentSearch(
    val id: String,
    val label: String,
    /** Chat this recent search resolves to, when it matches an existing conversation. */
    val chatId: String? = null,
    val color: String,
    val initials: String,
    /** Address-book photo, so a recent search looks the same as the row it came from. */
    val photoUri: String? = null,
)

private const val PREFS_NAME = "recent_searches"
private const val KEY = "recentSearches.v1"
private const val MAX_RECENTS = 8

/** Ported from `data/recent-searches.ts`, backed by `SharedPreferences` instead of the RN app's kv store. */
object RecentSearches {
    fun load(context: Context): List<RecentSearch> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching { appJson.decodeFromString<List<RecentSearch>>(raw) }.getOrDefault(emptyList())
    }

    /** Most recent first, de-duplicated by label, capped so the row never grows unbounded. */
    fun push(context: Context, entry: RecentSearch): List<RecentSearch> {
        val next = (listOf(entry) + load(context).filter { it.label != entry.label }).take(MAX_RECENTS)
        prefs(context).edit().putString(KEY, appJson.encodeToString(next)).apply()
        return next
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
