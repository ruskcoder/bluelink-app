package com.ruskcoder.messages.ui.components.effects

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.ruskcoder.messages.data.model.MessageEffect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * A bubble effect in flight: what is playing, and how far through it is.
 *
 * Hoisted out of the bubble so the same three animations can be driven from two places — the
 * conversation, where they fire once as a message lands, and the effect picker, where pressing a
 * button replays them on a preview. One state class, one set of curves, and no second copy of the
 * timings to drift from the first.
 */
@Stable
class BubbleEffectState internal constructor(internal val effect: MessageEffect?) {
    /** 0 before and after; runs 0..1 while the effect plays. */
    internal var progress by mutableFloatStateOf(0f)
        private set

    /** Bumped on every play, so a repeat restarts rather than being ignored as "no change". */
    internal var run by mutableIntStateOf(0)
        private set

    /**
     * Whether invisible ink is still covering the message.
     *
     * The odd one out: it is not an animation that finishes but a state the message sits in until
     * somebody wipes it away, which is what iMessage does and why it has no duration here.
     */
    internal var inked by mutableStateOf(false)
        internal set

    internal var playing by mutableStateOf(false)
        private set

    /** Plays it from the start, whatever it was doing. */
    fun play() {
        if (effect == null) return
        if (effect == MessageEffect.INVISIBLE_INK) {
            inked = true
            return
        }
        run++
    }

    internal fun begin() {
        playing = true
    }

    internal fun advance(value: Float) {
        progress = value
    }

    internal fun finish() {
        playing = false
        progress = 0f
    }
}

/**
 * The state a bubble's own effect is driven from, and the loop that drives it.
 *
 * [autoPlay] fires once on the first composition, which is how a message that has just landed plays
 * its effect without anything having to tell it to: the thread already knows which rows are
 * arriving, and passes that straight through.
 */
@Composable
fun rememberBubbleEffect(effect: MessageEffect?, autoPlay: Boolean = false): BubbleEffectState {
    val state = remember(effect) { BubbleEffectState(effect) }
    LaunchedEffect(state, autoPlay) { if (autoPlay) state.play() }
    LaunchedEffect(state, state.run) {
        if (state.run == 0 || effect == null || effect.kind != com.ruskcoder.messages.data.model.EffectKind.BUBBLE) {
            return@LaunchedEffect
        }
        if (effect == MessageEffect.INVISIBLE_INK) return@LaunchedEffect
        state.begin()
        val duration = effect.bubbleDurationMs
        val started = withFrameNanos { it }
        var elapsed = 0L
        while (elapsed < duration) {
            val now = withFrameNanos { it }
            elapsed = (now - started) / 1_000_000
            state.advance((elapsed.toFloat() / duration).coerceIn(0f, 1f))
        }
        state.finish()
    }
    return state
}

/** How long each of the three timed bubble effects runs, in milliseconds. */
private val MessageEffect.bubbleDurationMs: Long
    get() = when (this) {
        MessageEffect.SLAM -> 500
        MessageEffect.LOUD -> 900
        MessageEffect.GENTLE -> 1800
        else -> 0
    }

/**
 * A bubble, wearing whatever its send effect does to it.
 *
 * The three timed effects are transforms on the bubble itself and nothing more — which is the whole
 * trick, and why they can be applied to a real message in the conversation and to a preview in the
 * picker with the same code. Only invisible ink needs anything drawn: a fog of particles over the
 * message, which stays until it is wiped off.
 *
 * Modelled on BlueBubbles' own curves rather than invented: slam overshoots to five times size with
 * a tilt and lands hard, loud swells and shakes, gentle rises small and settles. What is different
 * is that these scale about the bubble's own corner rather than resizing its box — the conversation
 * must not reflow around a message that is playing.
 */
@Composable
fun BubbleEffectLayer(
    state: BubbleEffectState,
    mine: Boolean,
    /** The ink's colour, so a fog over a blue bubble reads as the same material as one over grey. */
    inkColor: Color = Color.White,
    content: @Composable () -> Unit,
) {
    val effect = state.effect
    if (effect == null || effect.kind != com.ruskcoder.messages.data.model.EffectKind.BUBBLE) {
        content()
        return
    }
    // Drawn by the words themselves, not around the bubble — see [InkVeil]. Nothing to wrap here.
    if (effect == MessageEffect.INVISIBLE_INK) {
        content()
        return
    }
    val t = state.progress
    val playing = state.playing
    Box(
        modifier = Modifier.graphicsLayer {
            if (!playing) return@graphicsLayer
            // Grown about the corner the message came from, so a bubble that swells stays anchored
            // to its own side of the conversation instead of drifting into the middle of the row.
            transformOrigin = TransformOrigin(if (mine) 1f else 0f, 1f)
            when (effect) {
                MessageEffect.SLAM -> {
                    val scale = slamScale(t)
                    scaleX = scale
                    scaleY = scale
                    rotationZ = slamTilt(t) * (if (mine) 1f else -1f)
                }
                MessageEffect.LOUD -> {
                    val scale = loudScale(t)
                    scaleX = scale
                    scaleY = scale
                    // A shudder rather than a spin: several cycles across the middle of the run,
                    // fading out as the bubble comes back down.
                    rotationZ = sin(t * 8f * PI.toFloat()) * 5f * loudShake(t)
                }
                MessageEffect.GENTLE -> {
                    val scale = gentleScale(t)
                    scaleX = scale
                    scaleY = scale
                }
                else -> Unit
            }
        },
    ) {
        content()
    }
}

/**
 * Slam: out to five times size in a fifth of a second, tilted, then driven down past its resting
 * size and allowed to settle.
 */
private fun slamScale(t: Float): Float = when {
    t < 0.4f -> lerp(1f, 5f, ease(t / 0.4f))
    t < 0.8f -> lerp(5f, 0.8f, ease((t - 0.4f) / 0.4f))
    else -> lerp(0.8f, 1f, ease((t - 0.8f) / 0.2f))
}

private fun slamTilt(t: Float): Float = when {
    t < 0.4f -> lerp(0f, 11f, ease(t / 0.4f))
    t < 0.8f -> lerp(11f, 0f, ease((t - 0.4f) / 0.4f))
    else -> 0f
}

/** Loud: a fast swell to three times size, held while it shakes, then back down. */
private fun loudScale(t: Float): Float = when {
    t < 0.33f -> lerp(1f, 3f, ease(t / 0.33f))
    t < 0.45f -> 3f
    else -> lerp(3f, 1f, ease((t - 0.45f) / 0.55f))
}

/** How much of the shudder survives, at each point of the run: all of it, then nothing. */
private fun loudShake(t: Float): Float = ((0.9f - t) / 0.5f).coerceIn(0f, 1f)

/** Gentle: comes in from nothing, overshoots a little, and eases back — the quietest of the three. */
private fun gentleScale(t: Float): Float = when {
    t < 0.3f -> lerp(0f, 1.2f, ease(t / 0.3f))
    t < 0.55f -> 1.2f
    else -> lerp(1.2f, 1f, ease((t - 0.55f) / 0.45f))
}

private fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t

/** Ease-in-out, which is the curve every one of these scenes is written on. */
private fun ease(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return if (x < 0.5f) 2f * x * x else 1f - (-2f * x + 2f).let { it * it } / 2f
}

/**
 * Invisible ink: the words under a drift of particles, until they are wiped clear.
 *
 * Not a timed animation. iMessage hides the text behind a shimmer that stays put until you rub it
 * off, and that is a state rather than an event — so this has no duration and no completion, only a
 * horizontal drag that clears it.
 *
 * The bubble is untouched: it keeps its colour, its shape and its tail, and only what is written in
 * it goes. And it goes by being *covered* rather than removed, so the message holds its exact size
 * either way — wiping it reveals the words where they already were instead of growing the bubble
 * around them, and a screen reader can still read a message the eye cannot.
 */
/**
 * The state a bubble's ink is read from, where the words are drawn.
 *
 * Invisible ink is the one effect that is not about the bubble at all: the bubble stays exactly as
 * it is, and what is hidden is the *text inside it*. So it cannot be a wrapper around the message
 * the way the other three are — it has to reach the one composable that draws words, several layers
 * down and behind three different bubble styles. A composition local is what carries it there
 * without every style having to learn about an effect it does not draw.
 */
val LocalBubbleInk = compositionLocalOf<BubbleEffectState?> { null }

/**
 * The words, under ink where there is any.
 *
 * A pass-through on every message that is not inked, which is all but a handful of them.
 */
@Composable
fun InkVeil(content: @Composable () -> Unit) {
    val state = LocalBubbleInk.current
    if (state == null || state.effect != MessageEffect.INVISIBLE_INK) {
        content()
        return
    }
    InvisibleInk(state = state, inkColor = Color.White, content = content)
}

@Composable
private fun InvisibleInk(state: BubbleEffectState, inkColor: Color, content: @Composable () -> Unit) {
    val inked = state.inked
    // One field of particles per showing, so a message wiped and re-inked does not come back with
    // the fog frozen exactly where it was left.
    val seed = remember(inked) { Random.nextInt() }
    var frame by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(inked, seed) {
        if (!inked) return@LaunchedEffect
        val started = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            frame = (now - started) / 1_000_000_000f
        }
    }
    Box(
        modifier = Modifier
            .pointerInput(inked) {
                if (!inked) return@pointerInput
                detectHorizontalDragGestures { change, amount ->
                    // A deliberate sideways rub, not any stray movement: the conversation scrolls
                    // under the same finger, and a message that uncovered itself on a fling would
                    // be no secret at all.
                    if (abs(amount) > 1f) {
                        change.consume()
                        state.inked = false
                    }
                }
            }
            .drawWithContent {
                drawContent()
                if (!inked) return@drawWithContent
                val random = Random(seed)
                val count = ((size.width * size.height) / 90f).toInt().coerceIn(60, 1200)
                repeat(count) {
                    val x0 = random.nextFloat() * size.width
                    val y0 = random.nextFloat() * size.height
                    val speed = 0.25f + random.nextFloat() * 0.75f
                    val angle = random.nextFloat() * 2f * PI.toFloat()
                    // Each mote drifts on its own heading and wraps, so the fog never thins out at
                    // one edge or piles up at another however long it is left.
                    val x = ((x0 + kotlin.math.cos(angle) * speed * frame * 22f) % size.width + size.width) % size.width
                    val y = ((y0 + sin(angle) * speed * frame * 22f) % size.height + size.height) % size.height
                    val twinkle = 0.35f + 0.65f * abs(sin((frame * 2.4f + random.nextFloat() * 6f)))
                    drawCircle(
                        color = inkColor.copy(alpha = 0.55f * twinkle),
                        radius = (0.45f + random.nextFloat() * 0.8f) * density,
                        center = androidx.compose.ui.geometry.Offset(x, y),
                    )
                }
            },
    ) {
        // Hidden rather than removed, so the bubble keeps exactly the size it has when uncovered.
        Box(modifier = Modifier.graphicsLayer { alpha = if (inked) 0f else 1f }) { content() }
    }
}
