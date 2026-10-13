package com.ruskcoder.messages.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.ui.text.SpanStyle
import com.ruskcoder.messages.data.model.RichDraft
import com.ruskcoder.messages.data.model.RunFormat
import com.ruskcoder.messages.data.model.TextEffectKind
import com.ruskcoder.messages.data.model.TextFlag
import kotlin.math.PI
import kotlin.math.sin

/**
 * The formatting controls, in the slot the attachment sheet uses.
 *
 * Made of the same glass, at the same inset, with the same pills — it is opened from the same "+"
 * and takes the same keyboard-sized slot, so it is that card showing something else rather than a
 * second card that happens to look similar.
 *
 * Two groups, because they are two different things and Apple treats them as such: the four style
 * flags across the top, which combine freely with each other, and the eight animations below,
 * which do not combine with anything at all. Picking an effect clears the flags and picking a flag
 * clears the effect — see [RunFormat], where that rule lives, because it is the wire's rule and not
 * this panel's.
 *
 * Everything here acts on the *selection*. With nothing selected it sets what the next thing typed
 * will wear, which is what a formatting bar in any editor does with a bare caret.
 */
@Composable
fun RichTextPanel(
    /**
     * What the selection is currently wearing, or `null` where it is not all wearing the same
     * thing — a range half of which is bold is not bold, and lighting the button as though it were
     * would make the first press *remove* the formatting the user was trying to add.
     */
    active: RunFormat?,
    onToggleFlag: (TextFlag) -> Unit,
    /** Null clears whatever effect is set; the panel sends null when an active one is pressed. */
    onPickEffect: (TextEffectKind?) -> Unit,
    /** Sends the four flags out to [QuickFormatBar] and gives the keyboard its slot back. */
    onPopOut: () -> Unit,
    surface: Color,
    height: Dp = 232.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .padding(PanelInset)
            .frostedFill(PanelShape, tint = surface.copy(alpha = PanelSurfaceTint))
            .padding(PanelPadding),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(PanelGap),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(PanelGap),
                modifier = Modifier.fillMaxWidth().weight(FLAG_ROW_WEIGHT),
            ) {
                TextFlag.entries.forEach { flag ->
                    FlagPill(
                        flag = flag,
                        // A mixed selection lights nothing, so the first press sets rather than clears.
                        on = active?.has(flag) == true,
                        onClick = { onToggleFlag(flag) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                // The way out of the card and onto the bar. Narrower than the four beside it: it is
                // not a fifth style, and giving it the same width said it was.
                PillSurface(
                    on = false,
                    onClick = onPopOut,
                    modifier = Modifier.weight(POP_OUT_WEIGHT).fillMaxHeight(),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "Keep the styles above the bar",
                        tint = Color.White,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
            // Two rows of four rather than one long scroller: eight is few enough to show at once,
            // and an effect you have to hunt for is an effect nobody uses.
            TextEffectKind.entries.chunked(EFFECT_COLUMNS).forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(PanelGap),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    row.forEach { kind ->
                        EffectPill(
                            kind = kind,
                            on = active?.effect == kind,
                            // Pressing the one already set takes it off — there is no other way
                            // back to plain text, and a panel you can only add to is a trap.
                            onClick = { onPickEffect(if (active?.effect == kind) null else kind) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The four styles, kept above the send bar while you type.
 *
 * The card they normally live on takes the keyboard's slot, which makes it a place you *go*: open
 * it, press bold, close it, carry on. That is the wrong shape for a style you want on for the next
 * three words — by the time the keyboard is back you have lost your place in the sentence.
 *
 * So the four come out of the card and park in the corner above the bar, and the keyboard keeps its
 * slot underneath them. Sized to its contents and pushed to one end rather than stretched across
 * the screen: it belongs to the draft it is sitting on, and a full-width strip would read as a
 * second bar over the first.
 */
@Composable
fun QuickFormatBar(
    active: RunFormat?,
    onToggleFlag: (TextFlag) -> Unit,
    onClose: () -> Unit,
    surface: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .frostedFill(QuickShape, tint = surface.copy(alpha = PanelSurfaceTint))
            .padding(QuickPadding),
        horizontalArrangement = Arrangement.spacedBy(QuickGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextFlag.entries.forEach { flag ->
            FlagPill(
                flag = flag,
                on = active?.has(flag) == true,
                onClick = { onToggleFlag(flag) },
                modifier = Modifier.size(QuickPill),
            )
        }
        PillSurface(on = false, onClick = onClose, modifier = Modifier.size(QuickPill)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Put the styles away",
                tint = Color.White,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

/** Small enough to sit over a conversation, big enough to hit without looking. */
private val QuickPill = 36.dp
private val QuickShape = RoundedCornerShape(22.dp)
private val QuickPadding = 6.dp
private val QuickGap = 6.dp

/** The same numbers the attachment sheet is built on — the two are one card showing two faces. */
private val PanelInset = 8.dp
private val PanelShape = RoundedCornerShape(26.dp)
private const val PanelSurfaceTint = 0.55f
private val PanelPadding = 14.dp
private val PanelGap = 10.dp
private val PillShape = RoundedCornerShape(percent = 40)

/** The flags get a shallower row than the effects: one letter needs less room than a moving word. */
private const val FLAG_ROW_WEIGHT = 0.8f
private const val POP_OUT_WEIGHT = 0.7f
private const val EFFECT_COLUMNS = 4

/** How much the film under a pill brightens once what it sets is switched on. */
private val PillOnTint = Color.White.copy(alpha = 0.22f)

@Composable
private fun FlagPill(flag: TextFlag, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PillSurface(on = on, onClick = onClick, modifier = modifier) {
        // The button says what it does by *being* it, which is the one label that needs no
        // translating and no icon set.
        Text(
            when (flag) {
                TextFlag.BOLD -> "B"
                TextFlag.ITALIC -> "I"
                TextFlag.UNDERLINE -> "U"
                TextFlag.STRIKETHROUGH -> "S"
            },
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = if (flag == TextFlag.BOLD) FontWeight.Bold else FontWeight.Medium,
            fontStyle = if (flag == TextFlag.ITALIC) FontStyle.Italic else FontStyle.Normal,
            textDecoration = when (flag) {
                TextFlag.UNDERLINE -> TextDecoration.Underline
                TextFlag.STRIKETHROUGH -> TextDecoration.LineThrough
                else -> null
            },
        )
    }
}

/**
 * One effect, previewing itself.
 *
 * The name alone says almost nothing — "Bloom" and "Ripple" are Apple's words for motions most
 * people have never read a description of — so each pill runs the motion it sends, on its own name,
 * on a loop.
 *
 * The moving word is given a box of its own with headroom on every side and nothing clipping it,
 * which is the whole reason the previews are laid out this way: the pill is the surface, and the
 * animation happens *over* it. Run inside the pill's own clip, everything that grows or travels —
 * which is six of the eight — got its top and its edges cut off.
 */
@Composable
private fun EffectPill(kind: TextEffectKind, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PillSurface(on = on, onClick = onClick, modifier = modifier) {
        val transition = rememberInfiniteTransition(label = kind.label)
        // One shared clock per pill, read differently by each effect below. Every motion is a
        // function of this, so they all restart together and none of them needs its own timing.
        val beat by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(kind.periodMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "${kind.label}Beat",
        )
        // Well inside the pill, so a word at 1.35x still has room to grow into.
        Box(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (kind == TextEffectKind.RIPPLE) {
                // The only one that is not a single transform: a ripple runs *through* a word, so
                // each letter has to carry its own phase of the same wave.
                Row {
                    kind.label.forEachIndexed { index, char ->
                        val phase = beat * 2f * PI.toFloat() - index * 0.9f
                        Text(
                            char.toString(),
                            color = Color.White,
                            fontSize = EffectLabelSize,
                            modifier = Modifier.graphicsLayer {
                                translationY = -6f * density * sin(phase).toFloat().coerceAtLeast(0f)
                            },
                        )
                    }
                }
            } else {
                Text(
                    kind.label,
                    color = Color.White,
                    fontSize = EffectLabelSize,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer { applyEffect(kind, beat) },
                )
            }
        }
    }
}

private val EffectLabelSize = 13.sp

/** How long one cycle of each preview takes — the slower motions read as nothing when hurried. */
private val TextEffectKind.periodMs: Int
    get() = when (this) {
        TextEffectKind.BIG, TextEffectKind.SMALL -> 1400
        TextEffectKind.SHAKE, TextEffectKind.JITTER -> 420
        TextEffectKind.NOD -> 900
        TextEffectKind.EXPLODE, TextEffectKind.BLOOM -> 1600
        TextEffectKind.RIPPLE -> 1200
    }

/**
 * The motion itself, as a transform on a graphics layer.
 *
 * Impressions of Apple's animations rather than reproductions of them — what is being shown is
 * which of eight this is, and that reads from the character of the movement long before it reads
 * from the exact curve. [beat] runs 0..1 and restarts.
 */
private fun androidx.compose.ui.graphics.GraphicsLayerScope.applyEffect(kind: TextEffectKind, beat: Float) {
    val wave = sin(beat * 2f * PI.toFloat())
    when (kind) {
        // Swells and settles.
        TextEffectKind.BIG -> {
            val scale = 1f + 0.3f * (0.5f + 0.5f * wave)
            scaleX = scale
            scaleY = scale
        }
        // Shrinks away and comes back.
        TextEffectKind.SMALL -> {
            val scale = 1f - 0.3f * (0.5f + 0.5f * wave)
            scaleX = scale
            scaleY = scale
        }
        // Fast, flat, side to side.
        TextEffectKind.SHAKE -> translationX = 5f * density * wave
        // A nod is vertical and unhurried, which is the whole difference from a shake.
        TextEffectKind.NOD -> {
            translationY = 3f * density * wave
            rotationZ = 4f * wave
        }
        // Bursts out and vanishes, then starts over from nothing.
        TextEffectKind.EXPLODE -> {
            val scale = 0.7f + 1.1f * beat
            scaleX = scale
            scaleY = scale
            alpha = (1f - beat).coerceIn(0f, 1f)
        }
        // The opposite arc: arrives from nothing and holds.
        TextEffectKind.BLOOM -> {
            val grow = (beat * 1.6f).coerceAtMost(1f)
            val scale = 0.55f + 0.45f * grow
            scaleX = scale
            scaleY = scale
            alpha = grow
        }
        // Small, fast and in two axes at once — nervous rather than rhythmic.
        TextEffectKind.JITTER -> {
            translationX = 3f * density * wave
            translationY = 3f * density * sin(beat * 4f * PI.toFloat())
            rotationZ = 3f * wave
        }
        // Handled per character by the caller; nothing to apply to the word as a whole.
        TextEffectKind.RIPPLE -> Unit
    }
}

/** The pill every control in this panel sits on — the attachment sheet's, at a tighter radius. */
@Composable
private fun PillSurface(
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .frostedFill(PillShape, blur = ControlBlur, tint = if (on) PillOnTint else ControlTint)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

/**
 * The draft's own formatting, painted onto the text field as it is typed.
 *
 * An [OutputTransformation] rather than anything drawn over the field, which is the only way this
 * works at all: the field owns its own layout, and a styled copy laid on top of it would agree with
 * the caret only until the first bold character changed a glyph's width. Styling the buffer instead
 * means the field lays out the *formatted* text, so the caret, the selection handles and the
 * scrolling are all measured against what is actually on screen.
 *
 * Returns null for a draft with nothing on it, which is the overwhelmingly common case and costs
 * the field nothing.
 *
 * Effects cannot be animated here — a text field's buffer takes spans, not motion — so a stretch
 * carrying one is tinted in the conversation's own colour instead. That says *which* words are
 * animated, which is the question the composer has to answer; *what* the animation looks like is
 * the panel's job, and it answers it by running the thing on the button.
 */
fun richOutputTransformation(draft: RichDraft, effectInk: Color): OutputTransformation? {
    if (draft.plain) return null
    val runs = draft.runs()
    return OutputTransformation {
        var at = 0
        for (run in runs) {
            val start = at
            at += run.text.length
            if (run.format.plain) continue
            // Clamped: the buffer is the field's text, and for a frame after an edit it can be a
            // character out of step with the draft these runs were derived from. Styling past the
            // end of it would throw rather than simply look wrong.
            val from = start.coerceIn(0, length)
            val to = at.coerceIn(from, length)
            if (from < to) addStyle(run.format.toSpanStyle(effectInk), from, to)
        }
    }
}

/** One run's format as a span — everything about it that a static field can show. */
fun RunFormat.toSpanStyle(effectInk: Color): SpanStyle = SpanStyle(
    fontWeight = if (bold) FontWeight.Bold else null,
    fontStyle = if (italic) FontStyle.Italic else null,
    color = if (effect != null) effectInk else Color.Unspecified,
    textDecoration = when {
        underline && strikethrough -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
        underline -> TextDecoration.Underline
        strikethrough -> TextDecoration.LineThrough
        else -> null
    },
)
