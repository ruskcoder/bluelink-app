package com.ruskcoder.messages.ui.components.effects

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.ruskcoder.messages.data.model.EffectKind
import com.ruskcoder.messages.data.model.MessageEffect
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * A screen effect in flight.
 *
 * One at a time, deliberately: these take over the whole screen, and two of them running at once is
 * not twice the celebration, it is a mess with no way to tell what either was. Playing a second
 * replaces the first.
 */
@Stable
class ScreenEffectState {
    internal var effect by mutableStateOf<MessageEffect?>(null)
        private set

    /** Where the message that set it off is, for the three effects that point at something. */
    internal var origin by mutableStateOf(Rect.Zero)
        private set

    /** Seconds since this run began; the whole of every effect below is a function of it. */
    internal var time by mutableFloatStateOf(0f)
        private set

    internal var run by mutableIntStateOf(0)
        private set

    /**
     * Starts [effect] over [from] — the bubble it came out of, in screen coordinates.
     *
     * Spotlight, lasers and love all aim at that box: an effect that says "this message" and then
     * happens somewhere else is worse than one that never fires.
     */
    fun play(effect: MessageEffect, from: Rect = Rect.Zero) {
        if (effect.kind != EffectKind.SCREEN) return
        this.effect = effect
        origin = from
        time = 0f
        run++
    }

    internal fun advance(seconds: Float) {
        time = seconds
    }

    internal fun stop() {
        effect = null
        time = 0f
    }
}

@Composable
fun rememberScreenEffect(): ScreenEffectState = remember { ScreenEffectState() }

/** How long each screen effect runs before it clears itself, in seconds. */
private val MessageEffect.screenSeconds: Float
    get() = when (this) {
        MessageEffect.ECHO -> 2.4f
        MessageEffect.SPOTLIGHT -> 2.4f
        MessageEffect.BALLOONS -> 4.5f
        MessageEffect.CONFETTI -> 4f
        MessageEffect.LOVE -> 2.6f
        MessageEffect.LASERS -> 3f
        MessageEffect.FIREWORKS -> 3.5f
        MessageEffect.CELEBRATION -> 3.2f
        else -> 0f
    }

/**
 * The eight full-screen effects, drawn over everything.
 *
 * Put at the very top of the screen's stack. Four of them — spotlight, lasers, fireworks,
 * celebration — darken the whole screen first, which is what iMessage does and what makes them read
 * as *the room* changing rather than as something being sprinkled on top of a conversation.
 *
 * All of them are one `Canvas` and one clock. Nothing here allocates a particle: each is a pure
 * function of the elapsed time and a fixed seed, so a hundred sparks cost a hundred draw calls and
 * no garbage, and there is no simulation to fall behind if a frame is dropped.
 */
@Composable
fun ScreenEffectLayer(state: ScreenEffectState, modifier: Modifier = Modifier) {
    val effect = state.effect ?: return
    val seed = remember(state.run) { Random(state.run * 7919 + 13) }
    LaunchedEffect(state.run) {
        val duration = effect.screenSeconds
        val started = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val elapsed = (now - started) / 1_000_000_000f
            if (elapsed >= duration) break
            state.advance(elapsed)
        }
        state.stop()
    }
    val time = state.time
    Canvas(modifier = modifier.fillMaxSize()) {
        val t = (time / effect.screenSeconds).coerceIn(0f, 1f)
        when (effect) {
            MessageEffect.ECHO -> drawEcho(state.origin, t, seed)
            MessageEffect.SPOTLIGHT -> drawSpotlight(state.origin, t)
            MessageEffect.BALLOONS -> drawBalloons(t, seed)
            MessageEffect.CONFETTI -> drawConfetti(t, seed)
            MessageEffect.LOVE -> drawLove(state.origin, t)
            MessageEffect.LASERS -> drawLasers(state.origin, time, t, seed)
            MessageEffect.FIREWORKS -> drawFireworks(t, seed)
            MessageEffect.CELEBRATION -> drawCelebration(t, seed)
            else -> Unit
        }
    }
}

/** How far into an effect the darkened ground has faded up, and out again at the end. */
private fun curtain(t: Float): Float = when {
    t < 0.12f -> t / 0.12f
    t > 0.82f -> ((1f - t) / 0.18f).coerceIn(0f, 1f)
    else -> 1f
}

// ---------------------------------------------------------------------------------------------
// Echo — the message, over and over, tumbling out across the screen and away.
// ---------------------------------------------------------------------------------------------

private fun DrawScope.drawEcho(origin: Rect, t: Float, seed: Random) {
    if (origin == Rect.Zero) return
    val random = Random(seed.nextInt(0, Int.MAX_VALUE).let { 5701 })
    repeat(ECHO_COPIES) { index ->
        // Each copy sets off a little after the one before it, so the screen fills up rather than
        // flashing all at once.
        val delay = index / (ECHO_COPIES * 1.6f)
        val local = ((t - delay) / (1f - delay)).coerceIn(0f, 1f)
        if (local <= 0f) return@repeat
        val angle = random.nextFloat() * 2f * PI.toFloat()
        val reach = (0.35f + random.nextFloat() * 0.9f) * size.height
        val scale = 0.4f + random.nextFloat() * 1.1f
        val x = origin.center.x + cos(angle) * reach * local
        val y = origin.center.y + sin(angle) * reach * local - local * size.height * 0.15f
        val w = origin.width * scale
        val h = origin.height * scale
        // Ghosts of the bubble rather than the bubble itself: this layer has no idea what the
        // message said, and a rounded slab in the conversation's own blue reads as one at a glance.
        drawRoundRect(
            color = EchoInk.copy(alpha = 0.55f * (1f - local)),
            topLeft = Offset(x - w / 2f, y - h / 2f),
            size = Size(w, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2f, h / 2f),
        )
    }
}

private const val ECHO_COPIES = 26
private val EchoInk = Color(0xFF3291EF)

// ---------------------------------------------------------------------------------------------
// Spotlight — the room goes dark and one circle of light follows the message.
// ---------------------------------------------------------------------------------------------

private fun DrawScope.drawSpotlight(origin: Rect, t: Float) {
    if (origin == Rect.Zero) return
    val fade = curtain(t)
    // The beam widens as it arrives and narrows as it leaves, so the light sweeps onto the message
    // rather than snapping on around it.
    val radius = max(origin.width, origin.height) / 2f + 60f * density * (0.6f + 0.4f * sin(t * PI.toFloat()))
    // A hole cut out of the dark rather than a bright disc drawn on it: the message under it keeps
    // its own colours, which is the whole point of a spotlight.
    val shade = Path().apply {
        addRect(Rect(Offset.Zero, size))
        addOval(Rect(origin.center, radius))
        fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
    }
    drawPath(shade, Color.Black.copy(alpha = 0.82f * fade))
    drawCircle(
        color = Color.White.copy(alpha = 0.10f * fade),
        radius = radius,
        center = origin.center,
        blendMode = BlendMode.Plus,
    )
}

// ---------------------------------------------------------------------------------------------
// Balloons — a slow drift upward, in iMessage's own party colours.
// ---------------------------------------------------------------------------------------------

private fun DrawScope.drawBalloons(t: Float, seed: Random) {
    val random = Random(4111)
    repeat(BALLOON_COUNT) {
        val x = random.nextFloat() * size.width
        val speed = 0.55f + random.nextFloat() * 0.75f
        val delay = random.nextFloat() * 0.35f
        val local = ((t - delay) / (1f - delay)).coerceIn(0f, 1f)
        if (local <= 0f) return@repeat
        val scale = 0.6f + random.nextFloat() * 0.8f
        val width = 34f * density * scale
        val height = width * 1.25f
        // Up from below the fold to well past the top, with a slow sway across it.
        val y = size.height + height - local * speed * (size.height + height * 3f)
        val sway = sin(local * 6f + random.nextFloat() * 6f) * 18f * density
        val colour = BalloonColours[random.nextInt(BalloonColours.size)]
        val centre = Offset(x + sway, y)
        // The string first, so the balloon sits over the top of it.
        drawLine(
            color = Color.White.copy(alpha = 0.35f),
            start = centre + Offset(0f, height / 2f),
            end = centre + Offset(sway * 0.15f, height / 2f + height * 0.9f),
            strokeWidth = 1.2f * density,
        )
        drawOval(
            color = colour,
            topLeft = Offset(centre.x - width / 2f, centre.y - height / 2f),
            size = Size(width, height),
        )
        // One highlight, which is the difference between a balloon and an oval.
        drawOval(
            color = Color.White.copy(alpha = 0.25f),
            topLeft = Offset(centre.x - width * 0.28f, centre.y - height * 0.32f),
            size = Size(width * 0.26f, height * 0.3f),
        )
    }
}

private const val BALLOON_COUNT = 22
private val BalloonColours = listOf(
    Color(0xFFE0245E), Color(0xFF1DA1F2), Color(0xFFFFAD1F),
    Color(0xFF17BF63), Color(0xFF794BC4), Color(0xFFF45D22),
)

// ---------------------------------------------------------------------------------------------
// Confetti — from the top, tumbling.
// ---------------------------------------------------------------------------------------------

private fun DrawScope.drawConfetti(t: Float, seed: Random) {
    val random = Random(9173)
    repeat(CONFETTI_COUNT) {
        val x = random.nextFloat() * size.width
        val delay = random.nextFloat() * 0.5f
        val local = ((t - delay) / (1f - delay)).coerceIn(0f, 1f)
        if (local <= 0f) return@repeat
        val speed = 0.7f + random.nextFloat() * 0.6f
        val y = -30f * density + local * speed * (size.height + 60f * density)
        val sway = sin(local * 9f + random.nextFloat() * 6f) * 26f * density
        val w = (5f + random.nextFloat() * 5f) * density
        val h = w * 0.5f
        val spin = local * 900f + random.nextFloat() * 360f
        val colour = BalloonColours[random.nextInt(BalloonColours.size)]
        rotate(degrees = spin, pivot = Offset(x + sway, y)) {
            drawRect(
                color = colour.copy(alpha = (1f - local * 0.35f).coerceIn(0f, 1f)),
                topLeft = Offset(x + sway - w / 2f, y - h / 2f),
                size = Size(w, h),
            )
        }
    }
}

private const val CONFETTI_COUNT = 90

// ---------------------------------------------------------------------------------------------
// Love — one heart, growing out of the message and away.
// ---------------------------------------------------------------------------------------------

private fun DrawScope.drawLove(origin: Rect, t: Float) {
    if (origin == Rect.Zero) return
    // Swells fast, holds, then floats up and fades — one gesture rather than a shower of them,
    // which is what makes it read as an answer to that message and not as weather.
    val grow = when {
        t < 0.35f -> ease(t / 0.35f)
        else -> 1f
    }
    val rise = ((t - 0.45f) / 0.55f).coerceIn(0f, 1f)
    val alpha = (1f - rise * rise)
    if (alpha <= 0f) return
    val scale = grow * (1f + rise * 0.7f)
    val heart = 110f * density * scale
    val centre = Offset(origin.center.x, origin.center.y - rise * size.height * 0.55f)
    drawPath(heartPath(centre, heart), LoveInk.copy(alpha = 0.85f * alpha))
}

private val LoveInk = Color(0xFFFF3B5C)

/** A heart, from two lobes and a point — enough of one to be unmistakable at this size. */
private fun heartPath(centre: Offset, size: Float): Path {
    val w = size
    val h = size
    return Path().apply {
        moveTo(centre.x, centre.y + h * 0.38f)
        cubicTo(
            centre.x - w * 0.75f, centre.y - h * 0.05f,
            centre.x - w * 0.42f, centre.y - h * 0.62f,
            centre.x, centre.y - h * 0.22f,
        )
        cubicTo(
            centre.x + w * 0.42f, centre.y - h * 0.62f,
            centre.x + w * 0.75f, centre.y - h * 0.05f,
            centre.x, centre.y + h * 0.38f,
        )
        close()
    }
}

// ---------------------------------------------------------------------------------------------
// Lasers — beams across a dark screen, out of the message.
// ---------------------------------------------------------------------------------------------

private fun DrawScope.drawLasers(origin: Rect, seconds: Float, t: Float, seed: Random) {
    val fade = curtain(t)
    drawRect(Color.Black.copy(alpha = 0.7f * fade))
    if (origin == Rect.Zero) return
    val random = Random(2287)
    repeat(LASER_BEAMS) { index ->
        val delay = random.nextFloat() * 0.6f
        val local = ((t - delay) / 0.25f).coerceIn(0f, 1f)
        if (local <= 0f || local >= 1f) return@repeat
        // Hue walks with the clock, so the beams cycle through the spectrum the way iMessage's do
        // rather than each picking a colour and keeping it.
        val hue = (seconds * 260f + index * 37f) % 360f
        val colour = Color.hsv(hue, 0.85f, 1f)
        val angle = random.nextFloat() * 2f * PI.toFloat()
        val reach = hypot(size.width, size.height)
        val from = origin.center
        val to = from + Offset(cos(angle), sin(angle)) * reach * local
        // Two passes: a wide soft beam and a hot core, which is what makes a line read as light.
        drawLine(colour.copy(alpha = 0.35f * fade), from, to, strokeWidth = 9f * density)
        drawLine(Color.White.copy(alpha = 0.9f * fade * (1f - local)), from, to, strokeWidth = 2.5f * density)
    }
}

private const val LASER_BEAMS = 26

// ---------------------------------------------------------------------------------------------
// Fireworks — shells that rise, burst, and rain down.
// ---------------------------------------------------------------------------------------------

private fun DrawScope.drawFireworks(t: Float, seed: Random) {
    val fade = curtain(t)
    drawRect(Color.Black.copy(alpha = 0.78f * fade))
    val random = Random(6421)
    repeat(FIREWORK_SHELLS) {
        val launch = random.nextFloat() * 0.55f
        val peakX = size.width * (0.15f + random.nextFloat() * 0.7f)
        val peakY = size.height * (0.15f + random.nextFloat() * 0.35f)
        val local = ((t - launch) / 0.45f).coerceIn(0f, 1f)
        if (local <= 0f) return@repeat
        val hue = random.nextFloat() * 360f
        val colour = Color.hsv(hue, 0.7f, 1f)
        if (local < 0.35f) {
            // The climb: a bright head with a short tail behind it.
            val rise = local / 0.35f
            val y = size.height - (size.height - peakY) * ease(rise)
            drawCircle(colour, radius = 2.4f * density, center = Offset(peakX, y))
            drawLine(
                colour.copy(alpha = 0.4f),
                Offset(peakX, y),
                Offset(peakX, y + 22f * density),
                strokeWidth = 1.6f * density,
            )
            return@repeat
        }
        // The burst: sparks out along a circle, slowing and falling as they go.
        val burst = (local - 0.35f) / 0.65f
        val sparks = 34
        repeat(sparks) { spark ->
            val angle = spark * 2f * PI.toFloat() / sparks + random.nextFloat() * 0.1f
            val reach = 150f * density * ease(burst)
            val drop = 90f * density * burst * burst
            val at = Offset(peakX + cos(angle) * reach, peakY + sin(angle) * reach + drop)
            drawCircle(
                color = colour.copy(alpha = (1f - burst) * fade),
                radius = (2.2f - 1.2f * burst) * density,
                center = at,
            )
        }
    }
}

private const val FIREWORK_SHELLS = 7

// ---------------------------------------------------------------------------------------------
// Celebration — sparkles rising from the foot of the screen.
// ---------------------------------------------------------------------------------------------

private fun DrawScope.drawCelebration(t: Float, seed: Random) {
    val fade = curtain(t)
    drawRect(Color.Black.copy(alpha = 0.7f * fade))
    val random = Random(3767)
    repeat(SPARKLE_COUNT) {
        val x = size.width * (0.1f + random.nextFloat() * 0.8f)
        val delay = random.nextFloat() * 0.6f
        val local = ((t - delay) / (1f - delay)).coerceIn(0f, 1f)
        if (local <= 0f) return@repeat
        val reach = size.height * (0.35f + random.nextFloat() * 0.5f)
        val y = size.height - ease(local) * reach
        val sway = sin(local * 5f + random.nextFloat() * 6f) * 30f * density
        // Bright at the top of its climb and gone by the end of it: a spark, not a balloon.
        val alpha = (sin(local * PI.toFloat()) * fade).coerceIn(0f, 1f)
        val arm = (3f + random.nextFloat() * 4f) * density * (0.5f + 0.5f * sin(local * 12f))
        val centre = Offset(x + sway, y)
        val colour = if (random.nextBoolean()) Color(0xFFFFD76E) else Color.White
        // A four-pointed star, drawn as two crossed strokes — cheap, and unmistakably a sparkle.
        drawLine(colour.copy(alpha = alpha), centre - Offset(arm, 0f), centre + Offset(arm, 0f), 1.4f * density)
        drawLine(colour.copy(alpha = alpha), centre - Offset(0f, arm), centre + Offset(0f, arm), 1.4f * density)
        drawCircle(colour.copy(alpha = alpha * 0.8f), radius = 1.2f * density, center = centre)
    }
}

private const val SPARKLE_COUNT = 70

private fun ease(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return 1f - (1f - x) * (1f - x)
}
