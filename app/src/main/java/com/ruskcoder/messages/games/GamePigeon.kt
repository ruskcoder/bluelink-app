package com.ruskcoder.messages.games

import android.content.Context
import androidx.core.content.edit
import androidx.core.net.toUri
import java.net.URLEncoder
import java.util.UUID

/**
 * The GamePigeon balloon format, as OpenPigeon / GamePigeon-on-iOS speak it.
 *
 * A game turn is a flat `Map<String,String>` (`game`, `game_name`, `sender`, `player1`, `caption`,
 * plus each game's own state keys). On the wire it becomes a query string, obfuscated by
 * [Cryption], wrapped in a second query string, and prefixed `data:`:
 *
 *     data:?ver=52&data=<obfuscated>
 *
 * This app produces that URL for [uniffi.imessage_core.OutgoingAppMessage.url] and parses it back
 * out of an incoming balloon. The pigeon app ([PigeonClient]) only ever sees the decoded map.
 */
object GamePigeon {

    /** The iMessage balloon-plugin bundle id GamePigeon tags its messages with. */
    const val BUNDLE_ID =
        "com.apple.messages.MSMessageExtensionBalloonPlugin:EWFNLB79LQ:com.gamerdelights.gamepigeon.ext"

    /** GamePigeon's App Store id — the balloon `adam-id`. */
    const val APP_ID = 1124197642L

    /** The extension's display name, as it appears in the balloon's app info. */
    const val APP_NAME = "GamePigeon"

    private const val PREFS = "pigeon"
    private const val KEY_SENDER = "sender_uuid"

    /**
     * This install's player id.
     *
     * Generated once and kept in the `pigeon` shared preferences under `sender_uuid` — the same
     * file and key OpenPigeon uses, so the pigeon app and this host agree on who "you" are without
     * either telling the other.
     */
    fun senderUuid(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_SENDER, null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs.edit { putString(KEY_SENDER, fresh) }
        return fresh
    }

    /**
     * The opening state for a new game — the fields GamePigeon expects on a first turn.
     *
     * `player2` is us because the opener is player 2 in GamePigeon's numbering; the recipient
     * becomes player 1 when they take the first move.
     */
    fun newGame(
        context: Context,
        gameId: String,
        gameName: String,
        version: String = "5",
    ): MutableMap<String, String> {
        val sender = senderUuid(context)
        return linkedMapOf(
            "sender" to sender,
            "tver" to "5",
            "ios" to "18.3.2",
            "start" to "",
            "caption" to "Let's play $gameName!",
            "version" to version,
            "player" to "2",
            "id" to Cryption.newId(),
            "game" to gameId,
            "game_name" to gameName,
            "num" to "1",
            "build" to "HeO3hkh1UZH8IaVCaV",
            "player2" to sender,
        )
    }

    /** `map` → the `data:?ver=52&data=…` URL that rides in the balloon. Opaque once built. */
    fun encode(map: Map<String, String>): String {
        val inner = query(map).replace("+", "%20")
        return "data:" + query(linkedMapOf("ver" to "52", "data" to Cryption.encrypt(inner)))
    }

    /**
     * The inverse of [encode]: pull the game-state map back out of a balloon's URL.
     *
     * Returns an empty map if the URL is not a GamePigeon payload this build understands, so a
     * malformed or foreign balloon degrades to "an invite we can't open" rather than a crash.
     */
    fun decode(url: String): Map<String, String> = try {
        val outer = url.replace("data:", "data://").toUri()
        val data = outer.getQueryParameter("data") ?: return emptyMap()
        val innerUri = ("data://" + Cryption.decrypt(data)).toUri()
        buildMap {
            for (key in innerUri.queryParameterNames) {
                innerUri.getQueryParameter(key)?.let { put(key, it) }
            }
        }
    } catch (_: Exception) {
        emptyMap()
    }

    /** The status line GamePigeon shows on the bubble for a given turn's map. */
    fun caption(map: Map<String, String>, senderUuid: String): String {
        val winner = map["winner"]
        if (winner != null) {
            val parts = winner.split("|")
            var iWon = map["sender"] == parts.getOrNull(0)
            if (parts.getOrNull(1) == "-1") iWon = !iWon
            return when {
                parts.getOrNull(1) == "0" -> "Draw!"
                iWon -> "You won!"
                else -> "You lost!"
            }
        }
        map["caption"]?.takeIf { it.startsWith("Let's") }?.let { return it }
        return if (map["sender"] == senderUuid) "Opponent's move." else "Your move."
    }

    private fun query(params: Map<String, String>): String =
        params.entries.joinToString("&", prefix = "?") { (k, v) ->
            enc(k) + "=" + enc(v)
        }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
