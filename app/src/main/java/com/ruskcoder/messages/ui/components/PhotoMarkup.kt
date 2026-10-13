package com.ruskcoder.messages.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ruskcoder.messages.imessage.AttachmentStaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The pen colours the markup offers.
 *
 * A fixed set rather than a picker: this is a pen you scribble a circle around something with
 * before you send it, and eight colours you can hit with a thumb answer that better than a wheel
 * you have to aim at. White and near-black lead because most of what gets marked up is a
 * screenshot, where the only question is which of the two shows up against the page.
 */
val MarkupColors: List<Color> = listOf(
    Color.White,
    Color(0xFF1C1C1E),
    Color(0xFFFF3B30),
    Color(0xFFFF9500),
    Color(0xFFFFCC00),
    Color(0xFF34C759),
    Color(0xFF007AFF),
    Color(0xFFAF52DE),
)

/** How wide the pen draws on the glass. Held constant on screen — see [MarkupState.begin]. */
val MarkupPenWidth = 5.dp

/**
 * The whole picture, uncropped.
 *
 * A crop is stored as the fraction of the (rotated) picture that survives — (0,0)..(1,1) is all of
 * it. Compared by value against this to answer "has the crop been touched", so it must stay a plain
 * [Rect] and never be mutated in place.
 */
val FullCrop: Rect = Rect(0f, 0f, 1f, 1f)

/**
 * One drawn line.
 *
 * Everything here is in the *picture's* own coordinates, normalised so that (0,0) is its top-left
 * corner and (1,1) its bottom-right — [width] as a fraction of the picture's width. Nothing about a
 * stroke is expressed in screen pixels, which is what lets the same stroke be drawn over a photo
 * that has been zoomed into and then burned into the full-resolution file without being re-fitted.
 */
data class MarkupStroke(
    val color: Color,
    val width: Float,
    val points: List<Offset>,
    /** A blur stroke lays no ink: it smears the photograph under it, and [color] goes unused. */
    val blur: Boolean = false,
)

/** The drawing in progress: what has been laid down, the line under the finger, and the pen. */
@Stable
class MarkupState {
    val strokes: MutableList<MarkupStroke> = mutableStateListOf()

    /**
     * The line under the finger, kept apart from the rest so it can be abandoned without a trace.
     *
     * Its points are a snapshot list appended to in place rather than a new stroke built on every
     * sample. A finger crossing the screen delivers a point every frame or two, and rebuilding an
     * immutable list each time makes a long stroke quadratic in its own length — which shows up as
     * the line falling behind the finger drawing it, exactly when the stroke is at its longest.
     */
    private val livePoints = mutableStateListOf<Offset>()
    private var liveWidth by mutableStateOf(0f)
    private var liveColor by mutableStateOf(MarkupColors.first())
    private var liveBlur by mutableStateOf(false)

    val live: MarkupStroke?
        get() = if (livePoints.isEmpty()) null else MarkupStroke(liveColor, liveWidth, livePoints, liveBlur)

    var color by mutableStateOf(MarkupColors.first())

    /** Whether the brush in hand blurs the picture instead of drawing on it. */
    var blur by mutableStateOf(false)

    /** The pen's thickness as a multiple of [MarkupPenWidth], within [MarkupPenMin]..[MarkupPenMax]. */
    var penScale by mutableStateOf(1f)

    /**
     * Quarter-turns clockwise the picture has been rotated by, 0..3.
     *
     * Kept as a count rather than an angle so it stays exact: four taps come back to where they
     * started with no drift, and [flattenMarkup] turns the file by the same whole number of turns
     * the screen showed.
     */
    var rotationQuarters by mutableStateOf(0)
        private set

    /**
     * The fraction of the picture the crop keeps, in the picture's *rotated* coordinates —
     * (0,0) its top-left, (1,1) its bottom-right. [FullCrop] until a handle is dragged.
     */
    var crop by mutableStateOf(FullCrop)

    val isEmpty: Boolean get() = strokes.isEmpty()

    /** Whether anything at all has been done to the picture — ink, a turn, or a crop. */
    val hasEdits: Boolean get() = strokes.isNotEmpty() || rotationQuarters != 0 || crop != FullCrop

    /**
     * Turns the picture a quarter-turn clockwise, carrying the strokes and the crop with it.
     *
     * Every stroke point and both crop corners are in the picture's own normalised coordinates, so
     * a clockwise turn is the same map for all of them regardless of the picture's shape:
     * (x, y) -> (1 - y, x). Doing it here rather than at flatten time keeps what is on screen and
     * what gets written the same drawing at every step.
     */
    fun rotateClockwise() {
        val turned = strokes.map { stroke ->
            stroke.copy(points = stroke.points.map { Offset(1f - it.y, it.x) })
        }
        strokes.clear()
        strokes.addAll(turned)
        crop = Rect(1f - crop.bottom, crop.left, 1f - crop.top, crop.right)
        rotationQuarters = (rotationQuarters + 1) % 4
    }

    /**
     * Starts a line at [at], with [width] in the picture's own units.
     *
     * The caller works that out from the zoom, so the pen stays the same size on the glass however
     * far in you have gone: zoomed to three times, a stroke covers a third as much of the picture.
     * That is the whole point of being able to zoom while drawing — the same gesture buys you three
     * times the precision rather than three times the ink.
     */
    fun begin(at: Offset, width: Float) {
        livePoints.clear()
        livePoints.add(at)
        liveWidth = width
        liveColor = color
        liveBlur = blur
    }

    fun extend(at: Offset) {
        val last = livePoints.lastOrNull() ?: return
        // Samples arrive faster than the pen is wide, and the path is smoothed through them anyway,
        // so anything that lands inside its own line adds nothing. Measured against the pen rather
        // than against a fixed distance: the pen shrinks as you zoom in, and so does the step worth
        // recording.
        val step = (liveWidth * 0.15f).coerceAtLeast(1e-4f)
        if ((last - at).getDistanceSquared() < step * step) return
        livePoints.add(at)
    }

    fun commit() {
        // Copied out: what is kept must not be the list the next stroke is about to clear.
        if (livePoints.isNotEmpty()) strokes.add(MarkupStroke(liveColor, liveWidth, livePoints.toList(), liveBlur))
        livePoints.clear()
    }

    /** Abandons the line under the finger — a second finger landed, and this is a pinch after all. */
    fun cancel() {
        livePoints.clear()
    }

    fun undo() {
        strokes.removeLastOrNull()
    }

    /**
     * Drops every stroke laid down after the [count]th.
     *
     * What "cancel" means for the pen: the count is taken when the pen is picked up, so backing out
     * of it undoes that session and leaves everything drawn before it alone. Anything coarser would
     * make the cross next to the pen a different button depending on how you got there.
     */
    fun truncateTo(count: Int) {
        while (strokes.size > count) strokes.removeAt(strokes.size - 1)
    }

    fun clear() {
        strokes.clear()
        livePoints.clear()
        rotationQuarters = 0
        crop = FullCrop
    }
}

@Composable
fun rememberMarkupState(): MarkupState = remember { MarkupState() }

/**
 * Where a picture actually lands inside the box it is drawn in, under `ContentScale.Fit`.
 *
 * The box is the whole screen and the picture is almost never that shape, so most of the box is
 * letterbox. Every conversion between a finger and a stroke goes through this rectangle.
 */
fun fittedImageRect(box: Size, intrinsic: IntSize): Rect {
    if (intrinsic.width <= 0 || intrinsic.height <= 0 || box.width <= 0f || box.height <= 0f) {
        return Rect(Offset.Zero, box)
    }
    val scale = min(box.width / intrinsic.width, box.height / intrinsic.height)
    val width = intrinsic.width * scale
    val height = intrinsic.height * scale
    return Rect(Offset((box.width - width) / 2f, (box.height - height) / 2f), Size(width, height))
}

/**
 * Where the picture lands once it has been turned [quarters] quarter-turns.
 *
 * A quarter or three-quarter turn swaps which of the picture's edges is the long one, so the
 * letterbox is computed against the swapped size. The strokes and the crop overlay are both mapped
 * into this rect, which is why the rotated image only needs a `rotationZ` and a fit-correction
 * scale to sit exactly on top of them.
 */
fun fittedRotatedImageRect(box: Size, intrinsic: IntSize, quarters: Int): Rect {
    val swapped = if (quarters % 2 != 0) IntSize(intrinsic.height, intrinsic.width) else intrinsic
    return fittedImageRect(box, swapped)
}

/**
 * Draws the markup over a picture occupying [rect].
 *
 * Clipped to the picture rather than to the box: a stroke that ran off the edge would be painted
 * across the letterbox on screen and then be missing from the file, where there is no letterbox to
 * paint it on. Clipping both makes what you see what gets sent.
 */
fun DrawScope.drawMarkup(strokes: List<MarkupStroke>, live: MarkupStroke?, rect: Rect) {
    if (rect.width <= 0f || rect.height <= 0f) return
    clipRect(rect.left, rect.top, rect.right, rect.bottom) {
        for (stroke in strokes) if (!stroke.blur) drawStroke(stroke, rect)
        live?.takeIf { !it.blur }?.let { drawStroke(it, rect) }
    }
}

/** Draws the blur strokes alone, opaque, as the mask a blurred copy of the picture is cut with. */
fun DrawScope.drawBlurMask(strokes: List<MarkupStroke>, live: MarkupStroke?, rect: Rect) {
    if (rect.width <= 0f || rect.height <= 0f) return
    clipRect(rect.left, rect.top, rect.right, rect.bottom) {
        for (stroke in strokes) if (stroke.blur) drawStroke(stroke, rect)
        live?.takeIf { it.blur }?.let { drawStroke(it, rect) }
    }
}

/** How far the blur brush smears, as a fraction of the picture's width. */
const val MarkupBlurRadius = 0.015f

private fun DrawScope.drawStroke(stroke: MarkupStroke, rect: Rect) {
    val width = (stroke.width * rect.width).coerceAtLeast(0.5f)
    val points = stroke.points.map { Offset(rect.left + it.x * rect.width, rect.top + it.y * rect.height) }
    // A tap is a dot. Nothing else in the loop below draws one, and a pen that ignores a tap reads
    // as a pen that has stopped working.
    if (points.size == 1) {
        drawCircle(stroke.color, radius = width / 2f, center = points.first())
        return
    }
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        // Through the midpoints, with each sampled point as the control: the line bends around the
        // finger's path instead of turning a corner at every sample.
        for (i in 1 until points.size) {
            val previous = points[i - 1]
            val mid = (previous + points[i]) / 2f
            quadraticTo(previous.x, previous.y, mid.x, mid.y)
        }
        lineTo(points.last().x, points.last().y)
    }
    drawPath(path, stroke.color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

const val MarkupPenMin = 0.4f
const val MarkupPenMax = 4f

/**
 * A pen-size slider: drag up (or right, when [horizontal]) for a thicker line. The knob is drawn at
 * the pen's real on-screen diameter in the current colour, so it doubles as a preview of the line.
 * Nothing sits behind it but the picture.
 */
@Composable
fun MarkupPenSlider(state: MarkupState, modifier: Modifier = Modifier, horizontal: Boolean = false) {
    val track = 220.dp
    Box(
        modifier = modifier
            .then(if (horizontal) Modifier.width(track).height(44.dp) else Modifier.width(44.dp).height(track))
            .pointerInput(state, horizontal) {
                val pad = 22.dp.toPx()
                fun set(p: Offset) {
                    val length = if (horizontal) size.width else size.height
                    val span = (length - 2 * pad).coerceAtLeast(1f)
                    val along = if (horizontal) p.x else p.y
                    val raw = ((along - pad) / span).coerceIn(0f, 1f)
                    val f = if (horizontal) raw else 1f - raw
                    state.penScale = MarkupPenMin + f * (MarkupPenMax - MarkupPenMin)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    set(down.position)
                    down.consume()
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: break
                        set(change.position)
                        change.consume()
                    }
                }
            }
            .drawBehind {
                val pad = 22.dp.toPx()
                val f = (state.penScale - MarkupPenMin) / (MarkupPenMax - MarkupPenMin)
                val knob = if (state.blur) Color.White.copy(alpha = 0.5f) else state.color
                val radius = (MarkupPenWidth.toPx() * state.penScale / 2f).coerceAtLeast(1.5.dp.toPx())
                val from: Offset
                val to: Offset
                val centre: Offset
                if (horizontal) {
                    val cy = size.height / 2f
                    from = Offset(pad, cy)
                    to = Offset(size.width - pad, cy)
                    centre = Offset(pad + f * (size.width - 2 * pad), cy)
                } else {
                    val cx = size.width / 2f
                    from = Offset(cx, pad)
                    to = Offset(cx, size.height - pad)
                    centre = Offset(cx, pad + (1f - f) * (size.height - 2 * pad))
                }
                drawLine(Color.White.copy(alpha = 0.5f), from, to, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(knob, radius, centre)
                drawCircle(Color.White, radius, centre, style = Stroke(width = 1.5.dp.toPx()))
            },
    )
}

/** The pen tray: the colours, one of which is standing proud as the one in your hand. */
@Composable
fun MarkupPalette(state: MarkupState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The blur brush leads the colours: it is the one thing here that isn't a colour.
        val blurSize by animateDpAsState(if (state.blur) 34.dp else 26.dp, tween(200), label = "blurSize")
        val blurRing by animateColorAsState(
            if (state.blur) Color.White else Color.White.copy(alpha = 0.35f),
            tween(200),
            label = "blurRing",
        )
        Box(
            modifier = Modifier
                .size(blurSize)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.18f))
                .border(2.dp, blurRing, CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Blur brush",
                    onClick = { state.blur = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.BlurOn, contentDescription = "Blur", tint = Color.White, modifier = Modifier.size(16.dp))
        }
        MarkupColors.forEach { color ->
            val selected = !state.blur && color == state.color
            // The chosen colour grows and takes a white collar; the rest stay small. Animated
            // because the tray is a row of identical discs and a swap between two of them, cut,
            // is almost impossible to see.
            val size by animateDpAsState(if (selected) 34.dp else 26.dp, tween(200), label = "penSize")
            val ring by animateColorAsState(
                if (selected) Color.White else Color.White.copy(alpha = 0.35f),
                tween(200),
                label = "penRing",
            )
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(color)
                    .border(2.dp, ring, CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "Pen colour",
                        onClick = { state.color = color; state.blur = false },
                    ),
            )
        }
    }
}

/**
 * Burns the markup into the picture and stages the result as a draft of its own.
 *
 * Drawn onto the file at its own resolution rather than onto a screen-sized capture: the strokes
 * are stored as fractions of the picture, so they scale up to whatever the file happens to be, and
 * what gets sent is the photograph you picked with a line on it rather than a photograph of your
 * phone's screen.
 *
 * [rotationQuarters] quarter-turns clockwise and [crop] (a fraction of the *rotated* picture) are
 * applied the same way they were shown: the pixels are turned first, the ink is drawn onto the
 * turned picture, and the crop is taken last — so a stroke that ran past the crop edge is trimmed
 * by it exactly as a stroke past the picture's edge already was.
 *
 * Returns null if the picture can't be read or written, which is all the caller can act on.
 */
suspend fun flattenMarkup(
    context: Context,
    source: String,
    strokes: List<MarkupStroke>,
    rotationQuarters: Int = 0,
    crop: Rect = FullCrop,
): Uri? =
    withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(source) }.getOrNull() ?: return@withContext null
        val resolver = context.contentResolver
        val upright = runCatching { decodeUpright(context, uri) }.getOrNull() ?: return@withContext null

        val turns = ((rotationQuarters % 4) + 4) % 4
        var bitmap = if (turns == 0) {
            upright
        } else {
            val matrix = Matrix().apply { postRotate(90f * turns) }
            val rotated = runCatching {
                Bitmap.createBitmap(upright, 0, 0, upright.width, upright.height, matrix, true)
            }.getOrNull() ?: return@withContext null
            if (rotated !== upright) upright.recycle()
            // `createBitmap` can hand back an immutable copy, and the canvas below has to write.
            if (rotated.isMutable) rotated else rotated.copy(Bitmap.Config.ARGB_8888, true).also { rotated.recycle() }
        }

        // Blur strokes cut a blurred copy of the picture taken before any ink goes on, so the brush
        // smears the photograph and the ink always sits on top — as it does on screen.
        val blurred = if (strokes.any { it.blur }) blurredCopy(bitmap) else null
        val canvas = android.graphics.Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        if (blurred != null) {
            paint.shader = android.graphics.BitmapShader(
                blurred, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP,
            )
            for (stroke in strokes) if (stroke.blur) paintStroke(canvas, paint, stroke, width, height)
            paint.shader = null
            blurred.recycle()
        }
        for (stroke in strokes) {
            if (stroke.blur) continue
            paint.color = stroke.color.toArgb()
            paintStroke(canvas, paint, stroke, width, height)
        }

        // The crop, taken last and in the rotated picture's own coordinates. Clamped so a handle
        // dragged to the very edge still leaves at least one row and column to keep.
        if (crop != FullCrop) {
            val bw = bitmap.width
            val bh = bitmap.height
            val left = (crop.left * bw).roundToInt().coerceIn(0, bw - 1)
            val top = (crop.top * bh).roundToInt().coerceIn(0, bh - 1)
            val right = (crop.right * bw).roundToInt().coerceIn(left + 1, bw)
            val bottom = (crop.bottom * bh).roundToInt().coerceIn(top + 1, bh)
            val cropped = runCatching {
                Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            }.getOrNull()
            if (cropped != null && cropped !== bitmap) {
                bitmap.recycle()
                bitmap = cropped
            }
        }

        // PNG only where the original was one — a screenshot marked up and re-encoded as JPEG picks
        // up ringing around every letter on it. Photographs go back out as JPEG, where a PNG of the
        // same picture would be several times the size for no visible gain.
        val png = resolver.getType(uri)?.contains("png", ignoreCase = true) == true ||
            source.substringBefore('?').endsWith(".png", ignoreCase = true)
        AttachmentStaging.stageBitmap(context, bitmap, png)
    }

/** One stroke onto [canvas] with [paint]'s colour or shader — the smoothing the screen drew with. */
private fun paintStroke(canvas: android.graphics.Canvas, paint: Paint, stroke: MarkupStroke, width: Float, height: Float) {
    paint.strokeWidth = (stroke.width * width).coerceAtLeast(1f)
    val points = stroke.points.map { android.graphics.PointF(it.x * width, it.y * height) }
    if (points.size == 1) {
        paint.style = Paint.Style.FILL
        canvas.drawCircle(points.first().x, points.first().y, paint.strokeWidth / 2f, paint)
        paint.style = Paint.Style.STROKE
        return
    }
    val path = android.graphics.Path().apply {
        moveTo(points.first().x, points.first().y)
        for (i in 1 until points.size) {
            val previous = points[i - 1]
            val next = points[i]
            quadTo(previous.x, previous.y, (previous.x + next.x) / 2f, (previous.y + next.y) / 2f)
        }
        lineTo(points.last().x, points.last().y)
    }
    canvas.drawPath(path, paint)
}

/**
 * A blurred copy of [source] at the same size: shrunk by halves to a coarse grid and scaled back
 * up with filtering, which smears about as far as [MarkupBlurRadius] does on screen.
 */
private fun blurredCopy(source: Bitmap): Bitmap {
    var small = source
    val targetWidth = 32
    while (small.width / 2 >= targetWidth && small.height / 2 >= 1) {
        val next = Bitmap.createScaledBitmap(small, small.width / 2, small.height / 2, true)
        if (small !== source) small.recycle()
        small = next
    }
    val out = Bitmap.createScaledBitmap(small, source.width, source.height, true)
    if (small !== source) small.recycle()
    return out
}

/**
 * The picture as it is meant to be looked at: decoded, turned the right way up, and small enough to
 * hold.
 *
 * The rotation matters more than it looks. A phone camera writes the sensor's own orientation into
 * the file and records which way the phone was held in EXIF; everything that displays the picture
 * reads that tag, and anything that decodes the pixels alone gets it sideways. Marking up the
 * sideways one would put every stroke somewhere else entirely.
 */
private fun decodeUpright(context: Context, uri: Uri): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    // The stream is what has to be there, not the return value: a bounds-only decode reports
    // through the options and always hands back null, so testing what `decodeStream` returned here
    // is testing a constant. Read as "no picture", it failed every save.
    val measured = resolver.openInputStream(uri) ?: return null
    measured.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        // Halved until it fits. A modern phone's camera roll runs to fifty megapixels, and a
        // mutable copy of one of those is a couple of hundred megabytes of heap.
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MaxEdge) sample *= 2
        inSampleSize = sample
        inMutable = true
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: return null

    val degrees = resolver.openInputStream(uri)?.use { stream ->
        runCatching {
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
    } ?: 0f
    // `inMutable` is a request, not a guarantee — a decoder that has to go its own way hands back
    // an immutable bitmap, and a canvas over one of those throws rather than failing quietly.
    if (degrees == 0f) return decoded.mutable()

    val matrix = Matrix().apply { postRotate(degrees) }
    val turned = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    if (turned !== decoded) decoded.recycle()
    // `createBitmap` hands back an immutable copy, and the canvas has to be able to write.
    return turned.mutable()
}

/** The same bitmap where it can already be drawn on, and a writable copy of it where it can't. */
private fun Bitmap.mutable(): Bitmap =
    if (isMutable) this else copy(config ?: Bitmap.Config.ARGB_8888, true).also { recycle() }

/** The longest edge a marked-up picture is decoded at. Beyond this the heap is the constraint. */
private const val MaxEdge = 4096
