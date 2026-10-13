package com.ruskcoder.messages.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ruskcoder.messages.data.model.GroupMember

private const val MAX_CIRCLES = 5
private const val COLUMNS = 3
private val Circle = 62.dp
private val Overlap = 12.dp
private val CameraButton = 36.dp

/** Center-to-center distance between neighbouring circles — they overlap by [Overlap]. */
private val Step = Circle - Overlap

/** The slot the cluster is drawn at when nobody asks for a different one. */
val ClusterSize = 140.dp

/**
 * Below this the badge is a smudge rather than a control — the cluster is on its way into the app
 * bar, where there is nothing to tap it with anyway.
 */
private val CameraVisibleFrom = 96.dp

/**
 * The overlapping avatar cluster atop a group's details screen. Ported from
 * `group-avatar-cluster.tsx`.
 *
 * Fitted into a square slot of [size] rather than laid out at whatever its grid comes to: it stands
 * where a one-to-one page puts a single round avatar, and travels into the app bar on the same path,
 * so both pages have to be able to ask for it at an arbitrary size. The grid keeps its own
 * proportions and is scaled to fit — five faces shrunk to a bar's title slot are a group's mark
 * rather than five readable faces, which is exactly what the slot is for at that size.
 */
@Composable
fun GroupAvatarCluster(
    members: List<GroupMember>,
    onCameraClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = ClusterSize,
) {
    val shown = members.take(MAX_CIRCLES)
    val overflow = members.size - shown.size
    val tiles = (shown.size + if (overflow > 0) 1 else 0).coerceAtLeast(1)

    // Sized to the grid it actually lays out. A fixed one-circle box left every circle past the
    // first hanging outside its own bounds, over whatever the page drew next to it.
    val columns = tiles.coerceAtMost(COLUMNS)
    val rows = (tiles + COLUMNS - 1) / COLUMNS
    val width = Step * (columns - 1) + Circle
    val height = Step * (rows - 1) + Circle
    val natural = maxOf(width, height)

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                // *Required*, not merely requested. The slot around this is a fixed box — 40dp in
                // an app bar — and a plain `size` is only ever a preference: it is coerced into
                // whatever the parent allows, so the grid was being laid out at the bar's own size
                // and every circle and every gap in it squashed to fit. The scale below then
                // divided by the size the grid was *supposed* to be, which is what made the faces
                // drift apart and the spacing come out wrong on the way into the bar.
                //
                // Laid out at its true size and scaled down as one piece, the cluster keeps every
                // proportion it has at full size — it is the same picture, smaller.
                .requiredSize(width, height)
                // Scaled about its own centre inside the slot rather than re-laid-out at every
                // step of the collapse: the grid's spacing and overlap are what make it read as a
                // cluster, and recomputing them per frame would have it shuffle as it shrank.
                .graphicsLayer {
                    val factor = size.toPx() / natural.toPx()
                    scaleX = factor
                    scaleY = factor
                },
        ) {
            shown.forEachIndexed { index, member ->
                MemberAvatar(
                    member = member,
                    size = Circle,
                    modifier = Modifier.offset(x = Step * (index % COLUMNS), y = Step * (index / COLUMNS)),
                )
            }
            if (overflow > 0) {
                Box(
                    modifier = Modifier
                        .offset(x = Step * (shown.size % COLUMNS), y = Step * (shown.size / COLUMNS))
                        .size(Circle)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+$overflow", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                }
            }
            // Bottom-right of the cluster, the way a profile photo's camera badge sits — not on top
            // of the first face.
            if (size >= CameraVisibleFrom) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(CameraButton)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                        .clickable(onClick = onCameraClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.CameraAlt,
                        contentDescription = "Set group photo",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
