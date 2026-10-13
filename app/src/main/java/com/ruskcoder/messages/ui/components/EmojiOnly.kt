package com.ruskcoder.messages.ui.components

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import java.text.BreakIterator

/**
 * A message that is nothing but emoji is drawn as emoji rather than as a message.
 *
 * iMessage has done this since it had bubbles: send a thumbs-up on its own and you get a thumbs-up,
 * not a blue lozenge with a thumbs-up in it. The bubble is chrome that says "these are words
 * somebody typed"; a single glyph is not a sentence and does not need saying about it. Past a
 * handful the reading flips back — a row of eight emoji *is* a message, and one drawn loose on the
 * wallpaper at display size would be a wall rather than a remark — so it takes an ordinary bubble
 * again, at the ordinary size.
 *
 * The counting is deliberately conservative. Anything with a letter, a digit, a bare punctuation
 * mark or a word joiner in it comes back 0 and is drawn as text, so the fallback for a string this
 * cannot read confidently is the behaviour every message had before.
 */
fun emojiOnlyCount(text: String): Int {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return 0
    val breaks = BreakIterator.getCharacterInstance()
    breaks.setText(trimmed)
    var start = breaks.first()
    var end = breaks.next()
    var count = 0
    while (end != BreakIterator.DONE) {
        val cluster = trimmed.substring(start, end)
        // Emoji separated by spaces are still only emoji — "🙂 🙂" is two, not a sentence.
        if (cluster.isNotBlank()) {
            if (!cluster.isEmojiCluster()) return 0
            count++
            // Nothing past the cap is drawn any differently, so there is no reason to keep walking
            // a paragraph of them to find out exactly how many there are.
            if (count > EmojiJumboMax) return 0
        }
        start = end
        end = breaks.next()
    }
    return count
}

/** The most emoji that are still drawn loose. Past this a message is a message and takes a bubble. */
const val EmojiJumboMax = 3

/**
 * How big a run of [count] emoji is set, before the reader's own text scaling.
 *
 * One on its own carries the whole message and is set at display size. Two or three are a reaction
 * rather than a statement and step down sharply — set at the same size as one, three of them ran
 * past the width of the screen at anything but the smallest text setting.
 */
fun emojiJumboSize(count: Int): TextUnit = when (count) {
    1 -> 52.sp
    2 -> 38.sp
    else -> 30.sp
}

/** A line box with enough room for the glyph, which is taller than a line of type at the same size. */
fun emojiJumboLineHeight(count: Int): TextUnit = (emojiJumboSize(count).value * 1.22f).sp

/**
 * Whether one grapheme cluster is an emoji.
 *
 * Cluster by cluster rather than code point by code point, because an emoji is routinely several of
 * them: a joined family, a skin tone, a flag built out of tag characters. The joiners and modifiers
 * carry no meaning on their own, so they are allowed through without counting as evidence — a
 * cluster has to contain at least one code point that is an emoji in its own right.
 */
private fun String.isEmojiCluster(): Boolean {
    // A keycap is the one emoji built on an ordinary character: "1️⃣" is the digit one, a variation
    // selector, and the enclosing keycap. The keycap is what makes it an emoji, so it is read first.
    if (indexOf(KEYCAP) >= 0) return true
    // Two kinds of code point are in play, and the difference matters. One kind is drawn as a
    // picture wherever it appears; the other is an ordinary typographic symbol — an arrow, a
    // fraction, a geometric shape — that only becomes an emoji when it is followed by the emoji
    // presentation selector. Counted together, a message that was one arrow came out set at fifty
    // points because "→" happens to live in a block that also holds ➡️.
    val requested = indexOf(VS16.toChar()) >= 0
    var emoji = false
    var index = 0
    while (index < length) {
        val cp = codePointAt(index)
        index += Character.charCount(cp)
        when {
            cp == ZWJ || cp == VS15 || cp == VS16 -> Unit
            cp in 0x1F3FB..0x1F3FF -> Unit // skin tones
            cp in 0xE0020..0xE007F -> Unit // tag characters, for the subdivision flags
            isPictographic(cp) -> emoji = true
            requested && isTextDefault(cp) -> emoji = true
            else -> return false
        }
    }
    return emoji
}

private const val ZWJ = 0x200D
private const val VS15 = 0xFE0E
private const val VS16 = 0xFE0F
private const val KEYCAP = '⃣'

/** Drawn as a picture wherever it turns up, with or without a presentation selector. */
private fun isPictographic(cp: Int): Boolean = when (cp) {
    in 0x1F300..0x1FAFF -> true // the emoji blocks proper, pictographs included
    in 0x1F000..0x1F0FF -> true // mahjong, dominoes, playing cards
    in 0x1F1E6..0x1F1FF -> true // regional indicators, which pair into flags
    in 0x1F200..0x1F2FF -> true // enclosed ideographic supplement
    in 0x2600..0x27BF -> true // misc symbols and dingbats: ☀ ✅ ❌ ➡
    0x2B50, 0x2B55, 0x2B1B, 0x2B1C, 0x2B06, 0x2B07, 0x2B05 -> true
    else -> false
}

/** An ordinary typographic symbol that is only an emoji when it asks to be. */
private fun isTextDefault(cp: Int): Boolean = when (cp) {
    0x00A9, 0x00AE, 0x203C, 0x2049, 0x2122, 0x2139, 0x3030, 0x303D, 0x3297, 0x3299 -> true
    in 0x2190..0x21FF -> true // arrows
    in 0x2300..0x23FF -> true // watches, hourglasses, the media controls
    in 0x2460..0x24FF -> true // enclosed alphanumerics
    in 0x25A0..0x25FF -> true // geometric shapes
    in 0x2900..0x297F -> true
    in 0x2B00..0x2BFF -> true
    else -> false
}
