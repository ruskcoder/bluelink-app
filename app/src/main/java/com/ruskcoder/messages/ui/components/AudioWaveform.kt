package com.ruskcoder.messages.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

private const val BAR_COUNT = 40
private const val BAR_WIDTH_DP = 2.5f
private const val BAR_GAP_DP = 2f
private const val MAX_BAR_HEIGHT_DP = 24f
private const val MIN_BAR_HEIGHT_DP = 3f

/**
 * A static waveform bar row; `progress` (0..1) decides which bars render `color` vs `mutedColor`.
 * Ported from `audio-waveform.tsx` — purely presentational, recomputed on each recomposition.
 *
 * The bars are spread across whatever width the caller gives it, up to the natural size a 2.5pt bar
 * every 4.5pt would take. A hard-coded natural width was wider than a bubble on a small screen,
 * which pushed the clip's duration off the end of it.
 *
 * When [onScrub] is given, a tap or horizontal drag anywhere across the bars reports where along the
 * clip the finger landed as a 0..1 fraction, so the caller can seek playback to that point.
 */
@Composable
fun AudioWaveform(
    waveform: List<Float>,
    progress: Float,
    color: Color,
    mutedColor: Color,
    modifier: Modifier = Modifier,
    onScrub: ((Float) -> Unit)? = null,
) {
    val bars = if (waveform.isEmpty()) List(BAR_COUNT) { 0.3f } else waveform
    val naturalWidth = (bars.size * (BAR_WIDTH_DP + BAR_GAP_DP)).dp
    val scrubModifier = if (onScrub == null) {
        Modifier
    } else {
        Modifier
            .pointerInput(onScrub) {
                detectTapGestures { offset ->
                    val width = size.width.toFloat()
                    if (width > 0f) onScrub((offset.x / width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(onScrub) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    val width = size.width.toFloat()
                    if (width > 0f) onScrub((change.position.x / width).coerceIn(0f, 1f))
                }
            }
    }
    Canvas(modifier = modifier.then(scrubModifier).widthIn(max = naturalWidth).height(MAX_BAR_HEIGHT_DP.dp)) {
        val maxHeightPx = MAX_BAR_HEIGHT_DP.dp.toPx()
        val minHeightPx = MIN_BAR_HEIGHT_DP.dp.toPx()
        val step = size.width / bars.size
        // Never wider than the gap between two bars, so a squeezed waveform stays a row of bars
        // rather than a solid block.
        val barWidthPx = BAR_WIDTH_DP.dp.toPx().coerceAtMost(step * 0.6f)
        bars.forEachIndexed { index, amplitude ->
            val played = index.toFloat() / bars.size < progress
            val barHeight = minHeightPx + (maxHeightPx - minHeightPx) * amplitude.coerceIn(0f, 1f)
            val x = index * step + step / 2f
            drawLine(
                color = if (played) color else mutedColor,
                start = Offset(x, (maxHeightPx - barHeight) / 2f),
                end = Offset(x, (maxHeightPx + barHeight) / 2f),
                strokeWidth = barWidthPx,
                cap = StrokeCap.Round,
            )
        }
    }
}
