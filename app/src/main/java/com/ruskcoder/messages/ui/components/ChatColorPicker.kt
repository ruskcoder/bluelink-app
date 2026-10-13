package com.ruskcoder.messages.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ruskcoder.messages.data.model.ChatThemeKey
import com.ruskcoder.messages.data.model.ThemeMode
import com.ruskcoder.messages.ui.theme.chatThemeColorsFor
import com.ruskcoder.messages.ui.theme.chatThemeLabel
import com.ruskcoder.messages.ui.theme.customChatTheme
import com.ruskcoder.messages.ui.theme.LocalSettings

/** The circle, drawn along the slider's track so the handle's position *is* the colour it picks. */
private val HueTrack = Brush.horizontalGradient(
    (0..12).map { Color.hsl(it * 30f % 360f, 0.7f, 0.5f) },
)

/**
 * Bottom-sheet grid of per-chat color swatches. Ported from `chat-color-picker.tsx`.
 *
 * Tapping a swatch applies it, rather than arming a Confirm button below the grid: the grid is tall
 * enough that the button could sit under the fold on a short screen, which made the whole sheet look
 * like it did nothing. The tick on the active swatch is the confirmation.
 *
 * Custom is the exception, and has to be. A slider you have to *drag* cannot also be a swatch you
 * tap to commit — every intermediate hue you pass through on the way to the one you want would be
 * saved and the sheet would close under your finger. So picking Custom opens the slider in place,
 * the conversation behind the sheet recolours live as it moves, and one Done commits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatColorPicker(
    selected: ChatThemeKey,
    /** The hue the conversation is already on, which is where the slider starts. */
    selectedHue: Float = ChatThemeKey.DEFAULT_HUE,
    title: String = "Chat colors",
    caption: String = "Applies to this conversation only.",
    onDismiss: () -> Unit,
    onConfirm: (ChatThemeKey, Float) -> Unit,
) {
    val dark = LocalSettings.current.mode == ThemeMode.DARK
    // Opens on the slider for a chat already on a custom hue: arriving at the grid with Custom
    // ticked and no slider in sight leaves the one control that matters a tap away for no reason.
    var tuning by remember { mutableStateOf(selected == ChatThemeKey.CUSTOM) }
    var hue by remember { mutableFloatStateOf(selectedHue) }

    AppBottomSheet(onDismiss = onDismiss) {
        Column(modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                // Bounded rather than fixed: six swatches fit without scrolling, and a taller list
                // scrolls inside the sheet instead of pushing anything off it. Shorter while the
                // slider is out, so the grid gives up the room rather than the sheet growing.
                modifier = Modifier.heightIn(max = if (tuning) 180.dp else 320.dp),
            ) {
                items(ChatThemeKey.entries) { key ->
                    val custom = key == ChatThemeKey.CUSTOM
                    // A custom swatch previews the hue in hand rather than whatever is stored, so
                    // the grid and the slider under it never disagree.
                    val colors = if (custom) customChatTheme(hue, dark) else chatThemeColorsFor(key, dark)
                    val mine = colors?.mine ?: MaterialTheme.colorScheme.primaryContainer
                    val theirs = colors?.theirs ?: MaterialTheme.colorScheme.surfaceVariant
                    val active = key == selected || (custom && tuning)
                    Card(
                        onClick = {
                            if (custom) {
                                // Opens the slider instead of committing; see the note on this
                                // composable for why this one cannot be tap-to-apply.
                                tuning = true
                            } else {
                                onConfirm(key, hue)
                                onDismiss()
                            }
                        },
                        border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                        colors = CardDefaults.cardColors(containerColor = colors?.backdrop ?: MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Box {
                                Row {
                                    Box(modifier = Modifier.weight(1f).height(18.dp).padding(2.dp).background(theirs, RoundedCornerShape(6.dp)))
                                    Box(modifier = Modifier.weight(1f).height(18.dp).padding(2.dp).background(mine, RoundedCornerShape(6.dp)))
                                }
                                if (active) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .size(20.dp)
                                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            }
                            Text(
                                chatThemeLabel(key),
                                style = MaterialTheme.typography.labelMedium,
                                color = colors?.text ?: MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }

            if (tuning) {
                val preview = customChatTheme(hue, dark)
                Text(
                    "Hue",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 18.dp),
                )
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                    // The spectrum is painted behind the slider rather than as its track: Material's
                    // track is drawn in two halves either side of the handle, so a gradient set on
                    // it would restart at the thumb. Behind it, the colour under the handle is the
                    // colour the handle picks, all the way across.
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 10.dp)
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(CircleShape)
                            .background(HueTrack),
                    )
                    Slider(
                        value = hue,
                        onValueChange = { hue = it },
                        valueRange = 0f..359f,
                        colors = SliderDefaults.colors(
                            thumbColor = preview.mine,
                            activeTrackColor = Color.Transparent,
                            inactiveTrackColor = Color.Transparent,
                            activeTickColor = Color.Transparent,
                            inactiveTickColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onConfirm(ChatThemeKey.CUSTOM, hue); onDismiss() }) { Text("Done") }
                }
            }
        }
    }
}
