package com.ruskcoder.messages.data.model

import kotlinx.serialization.Serializable

/**
 * The animations iMessage can play on a stretch of text, with the ids Apple puts on the wire.
 *
 * The ids are not sequential and are not ours to choose — they are what `<texteffect type="N">`
 * carries, and the receiving client looks up the animation by number. Kept on the enum so the one
 * mapping lives in one place; the Rust bridge takes the number and nothing else.
 */
@Serializable
enum class TextEffectKind(val appleId: Int, val label: String) {
    BIG(5, "Big"),
    SMALL(11, "Small"),
    SHAKE(9, "Shake"),
    NOD(8, "Nod"),
    EXPLODE(12, "Explode"),
    RIPPLE(4, "Ripple"),
    BLOOM(6, "Bloom"),
    JITTER(10, "Jitter"),
}

/**
 * Everything one stretch of text can be wearing.
 *
 * An effect and the style flags are mutually exclusive, and that is Apple's model rather than a
 * shortcut of ours: a run on the wire is *either* a set of `<b>/<i>/<u>/<s>` tags or a single
 * `<texteffect>`, never both. So setting an effect clears the flags and setting a flag clears the
 * effect — the alternative is an editor that lets you build a state it cannot send, and discovers
 * that only at the moment you press send.
 */
@Serializable
data class RunFormat(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val effect: TextEffectKind? = null,
) {
    val plain: Boolean get() = this == Plain

    fun toggle(flag: TextFlag): RunFormat = when (flag) {
        // Every flag clears an effect on its way in, for the reason above.
        TextFlag.BOLD -> copy(bold = !bold, effect = null)
        TextFlag.ITALIC -> copy(italic = !italic, effect = null)
        TextFlag.UNDERLINE -> copy(underline = !underline, effect = null)
        TextFlag.STRIKETHROUGH -> copy(strikethrough = !strikethrough, effect = null)
    }

    fun has(flag: TextFlag): Boolean = when (flag) {
        TextFlag.BOLD -> bold
        TextFlag.ITALIC -> italic
        TextFlag.UNDERLINE -> underline
        TextFlag.STRIKETHROUGH -> strikethrough
    }

    /** An effect replaces the whole format: the flags cannot travel with it. */
    fun withEffect(kind: TextEffectKind?): RunFormat =
        if (kind == null) copy(effect = null) else RunFormat(effect = kind)

    companion object {
        val Plain = RunFormat()
    }
}

/** The four style flags, in the order they are offered. */
enum class TextFlag { BOLD, ITALIC, UNDERLINE, STRIKETHROUGH }

/** One stretch of text and the single format it carries — what goes on the wire as one part. */
@Serializable
data class TextRun(val text: String, val format: RunFormat = RunFormat.Plain)

/**
 * A draft, and what each of its characters is wearing.
 *
 * Formatting is held per character rather than as a list of ranges, which is the whole reason this
 * survives ordinary typing. Ranges have to be re-derived against every edit — an insertion in the
 * middle pushes every range after it along, a deletion across a boundary merges two of them — and
 * getting that wrong silently mis-styles text the user cannot see is wrong until it is sent. A
 * parallel array has one invariant, `marks.size == text.length`, which is trivially checkable and
 * which [afterEdit] maintains with a prefix/suffix diff.
 *
 * Runs are derived on demand by grouping equal neighbours, which is the form the wire wants and the
 * form the field's styling wants, and never the form that is stored.
 */
@Serializable
data class RichDraft(val text: String = "", val marks: List<RunFormat> = emptyList()) {
    init {
        require(marks.isEmpty() || marks.size == text.length) {
            "rich draft carries ${marks.size} marks for ${text.length} characters"
        }
    }

    /** True when nothing in the draft is styled, which is almost every draft ever written. */
    val plain: Boolean get() = marks.all { it.plain }

    fun formatAt(index: Int): RunFormat = marks.getOrElse(index) { RunFormat.Plain }

    /**
     * The format the whole of [start], [end] is wearing, or `null` where it is not all the same.
     *
     * Used to light the buttons: a selection that is half bold is not bold, and offering it as
     * bold would have the first press un-bold the half that was.
     */
    fun formatOver(start: Int, end: Int): RunFormat? {
        if (start >= end) return formatAt((start - 1).coerceAtLeast(0)).takeIf { text.isNotEmpty() }
        val first = formatAt(start)
        for (index in start until minOf(end, text.length)) if (formatAt(index) != first) return null
        return first
    }

    /** The same text with [transform] applied to every character in [start], [end]. */
    fun mapRange(start: Int, end: Int, transform: (RunFormat) -> RunFormat): RichDraft {
        if (start >= end || text.isEmpty()) return this
        val next = MutableList(text.length) { formatAt(it) }
        for (index in start until minOf(end, text.length)) next[index] = transform(next[index])
        return copy(marks = next)
    }

    /**
     * This draft carried forward onto [updated] text, keeping the formatting the untouched parts
     * had.
     *
     * The diff is the cheapest one that is correct for how text actually changes in a field: find
     * how much of the head and how much of the tail survived, and everything between them is what
     * the edit replaced. That covers typing, deleting, selecting-and-replacing and pasting alike,
     * without needing to know which of them happened.
     *
     * Inserted characters inherit from the character *before* them — type in the middle of a bold
     * word and the new letters are bold, which is what every editor does — falling back to
     * [pending] at the very start of the text, where there is nothing before them to inherit from.
     */
    fun afterEdit(updated: String, pending: RunFormat = RunFormat.Plain): RichDraft {
        if (updated == text) return this
        if (marks.isEmpty() && pending.plain) return RichDraft(updated, emptyList())

        val old = text
        val shared = minOf(old.length, updated.length)
        var prefix = 0
        while (prefix < shared && old[prefix] == updated[prefix]) prefix++
        var suffix = 0
        while (suffix < shared - prefix && old[old.length - 1 - suffix] == updated[updated.length - 1 - suffix]) suffix++

        val inserted = updated.length - prefix - suffix
        val carried = if (prefix > 0) formatAt(prefix - 1) else pending
        val next = ArrayList<RunFormat>(updated.length)
        for (index in 0 until prefix) next.add(formatAt(index))
        repeat(inserted.coerceAtLeast(0)) { next.add(carried) }
        for (index in old.length - suffix until old.length) next.add(formatAt(index))
        return RichDraft(updated, next)
    }

    /** The draft as the stretches it is actually made of — equal neighbours folded together. */
    fun runs(): List<TextRun> {
        if (text.isEmpty()) return emptyList()
        if (marks.isEmpty()) return listOf(TextRun(text))
        val out = mutableListOf<TextRun>()
        var start = 0
        for (index in 1..text.length) {
            if (index == text.length || formatAt(index) != formatAt(start)) {
                out.add(TextRun(text.substring(start, index), formatAt(start)))
                start = index
            }
        }
        return out
    }

    companion object {
        /** A draft with no formatting on it — the state every message starts in. */
        fun plain(text: String): RichDraft = RichDraft(text, emptyList())
    }
}

/**
 * A draft's runs carried onto the trimmed body that is actually sent and stored.
 *
 * The body loses its leading and trailing whitespace on the way out, so the marks belonging to
 * what went have to go with it: runs that no longer spell the text they describe are not slightly
 * wrong, they are formatting applied to the wrong characters. Returns empty for a draft with
 * nothing on it, which is what "plain text" stores and sends.
 */
fun trimRuns(draft: String, body: String, runs: List<TextRun>): List<TextRun> {
    if (runs.isEmpty() || runs.all { it.format.plain }) return emptyList()
    val start = draft.indexOf(body).coerceAtLeast(0)
    val end = start + body.length
    val out = mutableListOf<TextRun>()
    var at = 0
    for (run in runs) {
        val from = maxOf(at, start)
        val to = minOf(at + run.text.length, end)
        at += run.text.length
        if (from < to) out.add(TextRun(draft.substring(from, to), run.format))
    }
    return out
}
