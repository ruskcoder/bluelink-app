package com.ruskcoder.messages.data.model

/**
 * One glyph on a bubble, and who put it there.
 *
 * Reactions are stored per sender ([Reactions]) but used to reach the UI as a bare list of glyphs,
 * which lost the two things a group needs: how many people picked the same one, and whether one of
 * them was you. Three people loving a message rendered as three identical hearts with no way to
 * find out whose they were.
 *
 * [senders] holds Apple handles in the order they reacted, which is what the reactor list is
 * ordered by.
 */
data class ReactionSummary(
    val emoji: String,
    val senders: List<String>,
) {
    val count: Int get() = senders.size
}

/**
 * Collapse stored tapbacks into what the bubble draws.
 *
 * Grouped by glyph rather than left one-per-sender: a pill reading "❤️ 3" is the useful form, and
 * the individual senders are still there for the list behind it. Order follows first use, so a
 * reaction does not jump position on the bubble when someone else joins it.
 */
fun Reactions.summarize(): List<ReactionSummary> =
    entries
        .groupBy({ it.value.display() }, { it.key })
        .map { (emoji, senders) -> ReactionSummary(emoji, senders) }

/** Whether any of [myHandles] is among the people who reacted with this glyph. */
fun ReactionSummary.isMine(myHandles: Set<String>): Boolean = senders.any { it in myHandles }

/**
 * Just the glyphs we placed ourselves.
 *
 * What the reaction bar lights up as already-picked. It used to be handed the whole set, so in a
 * group someone else's heart showed as though it were yours — and tapping it read as removing a
 * reaction you had never made.
 */
fun List<ReactionSummary>.mine(myHandles: Set<String>): List<String> =
    filter { it.isMine(myHandles) }.map { it.emoji }
