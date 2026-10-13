package com.ruskcoder.messages.imessage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.io.File
import java.security.MessageDigest

/**
 * Turns an emoji into the picture a sticker is sent as: a square PNG, transparent around the glyph.
 *
 * Apple's stickers are cut-outs, and an emoji dragged off the keyboard on an iPhone arrives the
 * same way — as an image, not as text — so this is what goes up to MMCS.
 */
object StickerImages {
    /** Pixels a side of the picture that is sent. */
    const val SIDE = 320

    /** A rendered sticker: where the PNG is, and a hash of its bytes to name it by — 16 hex digits, as an iPhone's are. */
    class Rendered(val file: File, val hash: String)

    /** [emoji] drawn centred on a transparent square [side] pixels across. */
    fun drawEmoji(emoji: String, side: Int = SIDE): Bitmap {
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = side * 0.8f
            textAlign = Paint.Align.CENTER
        }
        // Shrunk until the glyph fits: some emoji (flags, families) draw wider than they are tall.
        val bounds = Rect()
        paint.getTextBounds(emoji, 0, emoji.length, bounds)
        val widest = maxOf(bounds.width(), bounds.height()).coerceAtLeast(1)
        if (widest > side * 0.92f) paint.textSize *= side * 0.92f / widest
        paint.getTextBounds(emoji, 0, emoji.length, bounds)
        Canvas(bitmap).drawText(emoji, side / 2f, side / 2f - bounds.exactCenterY(), paint)
        return bitmap
    }

    fun renderEmoji(context: Context, emoji: String): Rendered {
        val bitmap = drawEmoji(emoji)
        val dir = File(context.cacheDir, "stickers").apply { mkdirs() }
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val hash = MessageDigest.getInstance("SHA-1").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
        val file = File(dir, "$hash-sticker.png")
        if (!file.exists()) file.writeBytes(bytes)
        return Rendered(file, hash)
    }
}
