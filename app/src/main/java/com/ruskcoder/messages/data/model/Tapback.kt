package com.ruskcoder.messages.data.model

import kotlinx.serialization.Serializable

/**
 * The tapbacks iMessage supports, in storage form.
 *
 * Kept as an enum plus an optional emoji rather than a bare emoji string because the six classic
 * tapbacks are a closed set with their own wire representation — Apple sends "loved", not a heart
 * character — and only the newer emoji tapbacks carry an arbitrary glyph.
 */
@Serializable
enum class TapbackKind {
    HEART,
    LIKE,
    DISLIKE,
    LAUGH,
    EMPHASIZE,
    QUESTION,
    EMOJI,
}

/**
 * One person's tapback on one message.
 *
 * `part` matters because iMessage addresses tapbacks at part granularity: a message carrying an
 * image and a caption can be reacted to on either half. This app renders a message as one unit, so
 * `part` is carried for correct round-tripping rather than shown.
 */
@Serializable
data class StoredReaction(
    val kind: TapbackKind,
    /** Set only when [kind] is [TapbackKind.EMOJI]. */
    val emoji: String? = null,
    val part: Long = 0,
)

/** The glyph shown on a bubble for a stored tapback. */
fun StoredReaction.display(): String = when (kind) {
    TapbackKind.HEART -> "❤️"
    TapbackKind.LIKE -> "👍"
    TapbackKind.DISLIKE -> "👎"
    TapbackKind.LAUGH -> "😂"
    TapbackKind.EMPHASIZE -> "‼️"
    TapbackKind.QUESTION -> "❓"
    TapbackKind.EMOJI -> emoji ?: "❓"
}

/**
 * Tapbacks on a message, keyed by the handle that sent each one.
 *
 * Keyed by sender because that is iMessage's own rule: a person has at most one tapback on a part,
 * and sending another replaces it. That makes the map the whole story — adding, replacing and
 * removing are all just writes to one key — where a flat list of glyphs could neither be
 * un-reacted nor tell you whether the reaction was yours.
 */
typealias Reactions = Map<String, StoredReaction>

/**
 * Stands in for our own handle on a conversation that has none.
 *
 * A chat that never went through iMessage still needs a key to hang a reaction on, and using a
 * reserved word rather than an empty string keeps "reacted locally" distinguishable from "sender
 * unknown".
 */
const val LOCAL_SENDER = "self"

/**
 * Interpret an emoji the picker produced.
 *
 * The six classic tapbacks have their own wire representation, so a heart the user taps has to be
 * sent as "loved" rather than as the character — otherwise it arrives on an iPhone as an emoji
 * tapback, which renders differently and cannot be matched against the classic set.
 */
fun reactionFromEmoji(emoji: String, part: Long = 0): StoredReaction = when (emoji) {
    "❤️" -> StoredReaction(TapbackKind.HEART, part = part)
    "👍" -> StoredReaction(TapbackKind.LIKE, part = part)
    "👎" -> StoredReaction(TapbackKind.DISLIKE, part = part)
    "😂" -> StoredReaction(TapbackKind.LAUGH, part = part)
    "‼️" -> StoredReaction(TapbackKind.EMPHASIZE, part = part)
    "❓" -> StoredReaction(TapbackKind.QUESTION, part = part)
    else -> StoredReaction(TapbackKind.EMOJI, emoji = emoji, part = part)
}
