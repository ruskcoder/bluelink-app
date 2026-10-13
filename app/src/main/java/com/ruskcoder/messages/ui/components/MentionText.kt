package com.ruskcoder.messages.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.ruskcoder.messages.data.model.mentionsIn

/**
 * Message text with its `@name` mentions picked out.
 *
 * Mentions have to look different from the words around them or they are not mentions at all —
 * they were rendered as ordinary text, so the only thing marking one was the at-sign the sender
 * happened to type.
 *
 * Returns the plain string unchanged when nothing matches, which is the overwhelmingly common case
 * and keeps a one-to-one conversation off the styled-text path entirely.
 */
@Composable
fun rememberMentionText(text: String, names: List<String>, accent: Color): AnnotatedString =
    remember(text, names, accent) { annotateMentions(text, names, accent) }

fun annotateMentions(text: String, names: List<String>, accent: Color): AnnotatedString {
    val spans = mentionsIn(text, names)
    if (spans.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        spans.forEach { span ->
            if (span.range.first > cursor) append(text.substring(cursor, span.range.first))
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                append(text.substring(span.range.first, span.range.last + 1))
            }
            cursor = span.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}

/**
 * What colour a mention is drawn in.
 *
 * Your own bubbles are already a filled accent colour, so tinting a mention inside one either
 * disappears into the fill or fights it — those get the bubble's own text colour and lean on the
 * weight instead. An incoming bubble is neutral enough to take the accent.
 */
@Composable
fun mentionAccent(mine: Boolean, bubbleText: Color): Color =
    if (mine) bubbleText else MaterialTheme.colorScheme.primary
