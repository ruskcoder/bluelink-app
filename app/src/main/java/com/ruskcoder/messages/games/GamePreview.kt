package com.ruskcoder.messages.games

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream

/**
 * The picture on a game invite bubble.
 *
 * GamePigeon messages always carry a preview image — the board render or the game's poster — and
 * iMessage needs *some* attachment on a balloon message for the receiving client to lay it out.
 *
 * Each game ships a poster under `assets/game_posters/`, and that is what a bubble shows. The drawn
 * card underneath it — the game's colour with its name across the middle — is the fallback for a
 * game with no poster, which is all this used to be: a bubble that read "Mini Golf" on a green
 * rectangle rather than showing mini golf.
 */
object GamePreview {

    private const val SIZE = 512

    /** Poster file names are the game id; the extension varies, so both are tried. */
    private val POSTER_EXTENSIONS = listOf("png", "jpg")

    /**
     * Where a game's poster lives, as a uri an image loader can take, or null for a game with none.
     *
     * The same artwork the bubble carries, so the picker and the message it sends show the same
     * picture rather than a coloured square and then a board.
     */
    fun posterUri(context: Context, gameId: String): String? {
        for (extension in POSTER_EXTENSIONS) {
            val path = "game_posters/$gameId.$extension"
            val exists = runCatching { context.assets.open(path).close(); true }.getOrDefault(false)
            if (exists) return "file:///android_asset/$path"
        }
        return null
    }

    fun jpeg(context: Context, gameId: String, gameName: String): ByteArray =
        poster(context, gameId) ?: card(gameId, gameName)

    /**
     * The game's own artwork, re-encoded as the JPEG a balloon carries.
     *
     * Decoded rather than passed through even when the file is already a JPEG: the posters are
     * full-size art and a balloon preview is a thumbnail, so this is also where they stop being
     * hundreds of kilobytes each on the wire.
     */
    private fun poster(context: Context, gameId: String): ByteArray? {
        for (extension in POSTER_EXTENSIONS) {
            val bytes = runCatching {
                context.assets.open("game_posters/$gameId.$extension").use { it.readBytes() }
            }.getOrNull() ?: continue
            val decoded = runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull() ?: continue
            return runCatching {
                val scale = minOf(
                    1f,
                    SIZE.toFloat() / decoded.width,
                    SIZE.toFloat() / decoded.height,
                )
                val sized = if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        decoded,
                        (decoded.width * scale).toInt().coerceAtLeast(1),
                        (decoded.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                } else {
                    decoded
                }
                ByteArrayOutputStream().use { out ->
                    sized.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    out.toByteArray()
                }
            }.getOrNull()
        }
        return null
    }

    private fun card(gameId: String, gameName: String): ByteArray {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bg = gameThumbnailColorInt(gameId)
        canvas.drawColor(bg)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = SIZE * 0.13f
        }
        // Wrap the name onto at most two lines so a longer title still fits.
        val words = gameName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val lines = when {
            words.isEmpty() -> listOf("Game")
            words.size == 1 -> words
            else -> {
                val mid = (words.size + 1) / 2
                listOf(words.subList(0, mid).joinToString(" "), words.subList(mid, words.size).joinToString(" "))
            }
        }
        val lineHeight = text.fontMetrics.let { it.descent - it.ascent }
        var y = SIZE / 2f - (lines.size - 1) * lineHeight / 2f - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2f
        for (line in lines) {
            canvas.drawText(line, SIZE / 2f, y, text)
            y += lineHeight
        }

        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    /** Same stable per-id hue as the in-app tile ([com.ruskcoder.messages.data.model.gameThumbnailColor]). */
    private fun gameThumbnailColorInt(gameId: String): Int {
        val hue = (gameId.hashCode().toUInt() % 360u).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.55f))
    }
}
