package com.ruskcoder.messages.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButtonColors
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** A softer corner than Material's 28dp, matching the app's sheets and menus. */
private val DialogShape = RoundedCornerShape(24.dp)

/** How wide a dialog is allowed to get on a tablet, so it stays a dialog rather than a page. */
private val DialogMaxWidth = 380.dp

/** And how much of the window's height it may take before its middle starts scrolling instead. */
private const val DialogMaxHeightFraction = 0.8f

/**
 * The app's own alert dialog: frosted glass over a copy of the screen, rather than Material's
 * opaque surface.
 *
 * Built on the same photograph as [AppBottomSheet], and for the same reason — a `Dialog` is a
 * window, so nothing behind it is in this composition to blur. The copy is taken from the *host*
 * composition, before the dialog's own window exists, so what it catches is the screen the dialog
 * is about to cover.
 *
 * Everything the caller draws sits on that glass untouched: the blur lives in a layer of its own,
 * behind the content, not around it.
 */
@Composable
fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** A line under the title, for the sentence a title cannot carry. */
    message: String? = null,
    /** The row along the bottom — usually a Cancel and, where there is one, a confirm. */
    buttons: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val hostView = LocalView.current
    var behind by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(hostView) { behind = ScreenSnapshot.copyOf(hostView) }

    // Bounded, so a dialog with a lot in it — a form with two fields and three paragraphs — cannot
    // grow past the screen and put its own buttons out of reach. The content slot below takes
    // whatever is left after the title and the buttons have had theirs, and scrolls inside it.
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = modifier
                .widthIn(max = DialogMaxWidth)
                .heightIn(max = windowHeight * DialogMaxHeightFraction)
                .clip(DialogShape)
                .dialogGlass(behind)
                .padding(top = 22.dp, bottom = 10.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                if (message != null) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
                    )
                }
                Column(
                    // `fill = false` so a short dialog stays short: the slot takes what it needs up
                    // to the room available, rather than always claiming all of it.
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 14.dp),
                    content = content,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp),
                    content = buttons,
                )
            }
        }
    }
}

/**
 * A text field's colours on a dialog's glass.
 *
 * Material's own are drawn from the theme, and the theme has no idea it is being painted onto dark
 * frosted glass: in a light theme a field inside one of these arrived as near-black text on a
 * near-black panel with a border nobody could see. Everything here is the same white the rest of
 * the dialog's content is, at the weights Material would have used for the same parts.
 */
@Composable
fun dialogFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    disabledTextColor = Color.White.copy(alpha = 0.5f),
    cursorColor = Color.White,
    focusedBorderColor = Color.White.copy(alpha = 0.85f),
    unfocusedBorderColor = Color.White.copy(alpha = 0.4f),
    focusedLabelColor = Color.White.copy(alpha = 0.85f),
    unfocusedLabelColor = Color.White.copy(alpha = 0.65f),
    focusedPlaceholderColor = Color.White.copy(alpha = 0.5f),
    unfocusedPlaceholderColor = Color.White.copy(alpha = 0.5f),
    focusedSupportingTextColor = Color.White.copy(alpha = 0.7f),
    unfocusedSupportingTextColor = Color.White.copy(alpha = 0.7f),
)

/** The same correction for a radio in a picker dialog, which is otherwise dark on dark. */
@Composable
fun dialogRadioColors(): RadioButtonColors = RadioButtonDefaults.colors(
    selectedColor = Color.White,
    unselectedColor = Color.White.copy(alpha = 0.6f),
)

/** Secondary prose on the glass — the shade [AppDialog] draws its own message line in. */
val DialogMutedInk = Color.White.copy(alpha = 0.72f)

/**
 * The film over a dialog's blur — a light-leaning grey rather than the near-black a menu uses.
 *
 * A menu is a stack of rows read at a glance and wants to sink into the screen behind it. A dialog
 * is a page of prose with buttons under it that has to be read, and the same black film made it a
 * hole in the screen: the blurred conversation behind it stopped showing through at all, so the
 * glass read as a flat black card. Lifted toward grey, the shapes behind it come back and the
 * dialog reads as the frosted panel it is meant to be.
 */
private val DialogFilm = Color(0x99343A40)

/** Where there is nothing to blur, the same grey as an opaque colour — see [dialogGlass]. */
private val DialogSolid = Color(0xFF2B3036)

/**
 * The dialog's surface: the captured screen behind it, blurred, under a grey film.
 *
 * Falls back to a near-opaque fill where there is no copy to draw — before Android 12, or when the
 * capture failed. A translucent panel over an unblurred screen is not a softer version of this; it
 * is an unreadable one.
 */
@Composable
private fun Modifier.dialogGlass(behind: ImageBitmap?): Modifier =
    if (behind == null) {
        this.background(DialogSolid)
    } else {
        this.snapshotBlur(behind).background(DialogFilm)
    }
