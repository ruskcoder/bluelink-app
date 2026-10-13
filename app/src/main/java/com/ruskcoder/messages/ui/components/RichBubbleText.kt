package com.ruskcoder.messages.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import com.ruskcoder.messages.data.model.TextEffectKind
import com.ruskcoder.messages.data.model.TextRun
import kotlin.math.PI
import kotlin.math.sin

/** One stretch of a message carrying an animation, in character offsets into the whole text. */
data class EffectSpan(val start: Int, val end: Int, val kind: TextEffectKind)

/**
 * A message's words with its formatting applied, and the stretches of it that move.
 *
 * The flags go straight on as spans, over whatever the text already carried — a mention inside a
 * bold sentence keeps its own colour and gains the weight. The effects cannot: an animation is not
 * a span, so those come back as ranges for [EffectLayer] to draw separately.
 *
 * Runs that do not spell the start of the text are ignored rather than applied at the wrong
 * offsets, which is the same guard the store keeps and for the same reason — half-right formatting
 * on the wrong words is worse than none. The start of it rather than the whole of it because one
 * style pads its text with an invisible run to hold the timestamp's footprint open: the padding is
 * layout rather than message, it always follows the words, and the offsets in front of it are
 * unaffected by it.
 */
@Composable
fun rememberFormattedText(base: AnnotatedString, runs: List<TextRun>): Pair<AnnotatedString, List<EffectSpan>> =
    remember(base, runs) {
        if (runs.isEmpty() || !base.text.startsWith(runs.joinToString("") { it.text })) {
            return@remember base to emptyList()
        }
        val effects = mutableListOf<EffectSpan>()
        val styled = buildAnnotatedString {
            append(base)
            var at = 0
            for (run in runs) {
                val start = at
                at += run.text.length
                val format = run.format
                if (format.plain) continue
                format.effect?.let { effects.add(EffectSpan(start, at, it)) }
                if (format.bold || format.italic || format.underline || format.strikethrough) {
                    addStyle(
                        SpanStyle(
                            fontWeight = if (format.bold) FontWeight.Bold else null,
                            fontStyle = if (format.italic) FontStyle.Italic else null,
                            textDecoration = when {
                                format.underline && format.strikethrough ->
                                    TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                                format.underline -> TextDecoration.Underline
                                format.strikethrough -> TextDecoration.LineThrough
                                else -> null
                            },
                        ),
                        start,
                        at,
                    )
                }
            }
        }
        styled to effects
    }

/**
 * A message whose text carries animations, drawn.
 *
 * The whole message is laid out once, as one paragraph, so the wrapping, the line breaks and every
 * glyph's position are exactly what an ordinary [androidx.compose.material3.Text] would have
 * produced — an animated word must not move the words around it. That layout is then drawn twice:
 * once with the animated stretches held back, and once per character over the top, each with its
 * own transform.
 *
 * Character by character rather than run by run, because half of Apple's effects are only
 * legible that way. A ripple is a wave *through* a word — every letter at a different point of the
 * same cycle — and a jitter that moved a whole run as a block would read as the message sliding
 * rather than as the letters being restless.
 *
 * Each character is measured once, when the layout changes, and re-drawn under a transform every
 * frame. The alternative — clipping the shared layout to each character's box and transforming
 * that — cuts the glyph off at the edges of a cell it is deliberately growing out of.
 */
@Composable
fun EffectLayer(
    text: AnnotatedString,
    style: TextStyle,
    effects: List<EffectSpan>,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // One clock for the whole bubble. Every effect reads it at its own rate, so a message carrying
    // two of them has both on the same beat rather than each drifting on a timer of its own.
    val transition = rememberInfiniteTransition(label = "bubbleEffects")
    val beat by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(EFFECT_PERIOD_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "bubbleEffectBeat",
    )

    BoxWithConstraints(modifier = modifier) {
        val maxWidth = constraints.maxWidth
        val drawing = remember(text, style, maxWidth, effects) {
            buildEffectDrawing(measurer, text, style, maxWidth, effects)
        }
        Canvas(
            modifier = Modifier.size(
                with(density) { drawing.width.toDp() },
                with(density) { drawing.height.toDp() },
            ),
        ) {
            // The message as laid out, with the animated stretches drawn in nothing — they are
            // about to be drawn again, moving, and a glyph left underneath would show through
            // whenever the one above it travelled.
            drawText(drawing.base)
            drawing.glyphs.forEach { glyph -> drawGlyph(glyph, beat) }
        }
    }
}

/** How long one cycle of every bubble animation takes; each effect reads its own rate off this. */
private const val EFFECT_PERIOD_MS = 1600

/** One animated character: where it sits in the paragraph, and its own little layout. */
private class EffectGlyph(
    val kind: TextEffectKind,
    /** Its place in its own run, which is what gives a ripple its per-letter delay. */
    val position: Int,
    val layout: TextLayoutResult,
    val topLeft: Offset,
    val center: Offset,
)

private class EffectDrawing(
    val base: TextLayoutResult,
    val glyphs: List<EffectGlyph>,
    val width: Int,
    val height: Int,
)

private fun buildEffectDrawing(
    measurer: TextMeasurer,
    text: AnnotatedString,
    style: TextStyle,
    maxWidth: Int,
    effects: List<EffectSpan>,
): EffectDrawing {
    val constraints = Constraints(maxWidth = maxWidth)
    // Positions come from the *whole* message, laid out as one paragraph. Nothing about an effect
    // is allowed to change where a word sits.
    val full = measurer.measure(text, style, constraints = constraints)
    val hidden = buildAnnotatedString {
        append(text)
        effects.forEach { addStyle(SpanStyle(color = Color.Transparent), it.start, it.end) }
    }
    val base = measurer.measure(hidden, style, constraints = constraints)

    val glyphs = mutableListOf<EffectGlyph>()
    for (span in effects) {
        for (index in span.start until span.end.coerceAtMost(text.length)) {
            val char = text.text[index]
            // A line break has no glyph to animate, and a space has nothing to see move.
            if (char.isWhitespace()) continue
            val box = runCatching { full.getBoundingBox(index) }.getOrNull() ?: continue
            glyphs.add(
                EffectGlyph(
                    kind = span.kind,
                    position = index - span.start,
                    // Measured on its own so it can be transformed on its own. Once, here — not
                    // per frame.
                    layout = measurer.measure(AnnotatedString(char.toString()), style),
                    topLeft = box.topLeft,
                    center = box.center,
                ),
            )
        }
    }
    return EffectDrawing(base, glyphs, full.size.width, full.size.height)
}

/**
 * One character, moved.
 *
 * The motions are impressions of Apple's rather than reproductions: what has to read is which of
 * the eight this is, and that comes from the character of the movement rather than the exact curve.
 */
private fun DrawScope.drawGlyph(glyph: EffectGlyph, beat: Float) {
    // Every letter of a run moves on the same cycle, offset along it by its place in the word.
    // That offset is what turns a shared wave into a ripple travelling through the text.
    val phase = beat * 2f * PI.toFloat() - glyph.position * RIPPLE_STEP
    val wave = sin(phase)
    // The fast effects run several cycles per period; the slow ones run exactly one.
    val fast = sin(phase * 4f)
    var alpha = 1f
    var scale = 1f
    var dx = 0f
    var dy = 0f
    var spin = 0f
    when (glyph.kind) {
        TextEffectKind.BIG -> scale = 1f + 0.28f * (0.5f + 0.5f * sin(beat * 2f * PI.toFloat()))
        TextEffectKind.SMALL -> scale = 1f - 0.28f * (0.5f + 0.5f * sin(beat * 2f * PI.toFloat()))
        TextEffectKind.SHAKE -> dx = 2.5f * density * fast
        TextEffectKind.NOD -> {
            dy = 2f * density * wave
            spin = 5f * wave
        }
        TextEffectKind.EXPLODE -> {
            scale = 0.8f + 0.9f * beat
            alpha = (1f - beat).coerceIn(0f, 1f)
        }
        TextEffectKind.BLOOM -> {
            val grow = (beat * 1.8f).coerceAtMost(1f)
            scale = 0.6f + 0.4f * grow
            alpha = grow
        }
        TextEffectKind.JITTER -> {
            dx = 1.8f * density * fast
            dy = 1.8f * density * sin(phase * 5f)
            spin = 3f * fast
        }
        // The one the per-letter phase was built for: a wave running along the word, each letter
        // rising as the one before it comes back down.
        TextEffectKind.RIPPLE -> dy = -4f * density * wave.coerceAtLeast(0f)
    }
    withTransform({
        translate(dx, dy)
        if (scale != 1f) scale(scale, scale, glyph.center)
        if (spin != 0f) rotate(spin, glyph.center)
    }) {
        drawText(glyph.layout, topLeft = glyph.topLeft, alpha = alpha)
    }
}

/** How far along the cycle each successive letter of a run sits — the ripple's own wavelength. */
private const val RIPPLE_STEP = 0.9f
