package com.ruskcoder.messages.data.model

import androidx.compose.ui.graphics.Color

/**
 * One entry in the games picker.
 *
 * [id] and [version] are **GamePigeon's**, not ours: they go on the wire as the `game` and
 * `version` keys, and GamePigeon on iOS matches them against the games it knows. A name it does
 * not recognise, or a version it cannot read, makes the receiving iPhone offer to update the app
 * instead of opening the game. Knockout is `knock` on the wire, not `knockout`.
 *
 * [id] is also the key the pigeon app's GameRegistry knows the game by, so the two stay in step.
 */
data class GameCatalogEntry(val id: String, val name: String, val version: String)

/** Every game offered from the "+" menu -- see pigeon's GameRegistry for the matching ids. */
val GameCatalog = listOf(
    GameCatalogEntry(id = "knock", name = "Knockout", version = "6"),
    GameCatalogEntry(id = "connect", name = "Four in a Row", version = "5"),
    GameCatalogEntry(id = "anagrams", name = "Anagrams", version = "5"),
    GameCatalogEntry(id = "hunt", name = "Word Hunt", version = "48"),
    GameCatalogEntry(id = "wordbites", name = "Word Bites", version = "1"),
    GameCatalogEntry(id = "sea", name = "Sea Battle", version = "19"),
    GameCatalogEntry(id = "pool", name = "8 Ball", version = "5"),
    GameCatalogEntry(id = "golf", name = "Mini Golf", version = "41"),
    GameCatalogEntry(id = "beer", name = "Cup Pong", version = "5"),
    GameCatalogEntry(id = "darts", name = "Darts", version = "5"),
)

/**
 * A stable color per game id, so its thumbnail looks the same everywhere (picker grid, sent
 * bubble) without needing a hand-drawn icon per game.
 */
fun gameThumbnailColor(gameId: String): Color {
    val hue = (gameId.hashCode().toUInt() % 360u).toFloat()
    return Color.hsl(hue, 0.55f, 0.45f)
}
