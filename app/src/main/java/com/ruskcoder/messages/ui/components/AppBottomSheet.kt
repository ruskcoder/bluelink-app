package com.ruskcoder.messages.ui.components

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.ruskcoder.messages.data.model.ThemeMode
import com.ruskcoder.messages.ui.theme.LocalSettings

/** A soft rectangle rather than Material's deep 28dp scoop, matching the app's other glass. */
private val SheetCorner = 22.dp
private val SheetShape = RoundedCornerShape(topStart = SheetCorner, topEnd = SheetCorner)

/**
 * How hard the screen behind the sheet is blurred, and how much of it is left visible under the
 * scrim. The scrim is light because the blur is doing the separating: with the conversation already
 * unreadable there is nothing left for a heavy dim to hide, and a heavy one only makes the sheet
 * read as a different screen rather than as a panel over this one.
 */
private val BehindBlur = 28.dp
private const val Scrim = 0.18f

/** Without a blur the scrim is the only thing holding the sheet apart from the page. */
private const val ScrimAlone = 0.42f

/** The glass the sheet is made of. Opaque where nothing behind it could be blurred. */
private val GlassDark = Color(0xFF101013)
private val GlassLight = Color(0xFFF2F2F7)
private const val GlassAlpha = 0.62f

/** How hard the sheet blurs the screen showing through its own surface. */
private val SheetBlur = 34.dp

/** The grabber at the top of a sheet you can pull down. */
private val GrabberWidth = 36.dp
private val GrabberHeight = 5.dp

/**
 * One bottom sheet, dressed the way the rest of the app is: frosted glass over a blurred screen.
 *
 * Two effects, because they answer two different questions. *Behind* the sheet — the strip of screen
 * still showing above it — is the platform's own cross-window blur, which is the only thing that can
 * reach content in another window. *Through* the sheet is a photograph: the screen is copied as the
 * sheet opens and the sheet's surface draws that copy, blurred, under a film. See [ScreenSnapshot],
 * which the details pages use the same way and for the same reason.
 *
 * The photograph is what this used to lack. Cross-window blur alone is off far more often than it is
 * on — a device profile, a battery saver, an OEM's default — and when it is off there is nothing
 * left to see through, so every sheet in the app fell back to an opaque slab. Now the surface is
 * glass whatever the platform says, and the platform's blur, when there is one, is a bonus applied
 * to the margin around it.
 *
 * The copy is taken from the *host* composition, before `ModalBottomSheet` puts a window of its own
 * over it, so what it catches is the screen the sheet is about to cover. Where even that fails — no
 * window to read, a secure surface — the fill goes solid, which is no worse than what was there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val dark = LocalSettings.current.mode == ThemeMode.DARK
    val blurred = rememberBlurBehind(BehindBlur)
    val glass = if (dark) GlassDark else GlassLight

    // LocalView here is the activity's, not the sheet's: this line runs in the composition that
    // called us, and the sheet's window does not exist until the call below.
    val hostView = LocalView.current
    var behind by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(hostView) { behind = ScreenSnapshot.copyOf(hostView) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = SheetShape,
        // The sheet paints its own surface, below, so that the surface can be made of the screen.
        containerColor = Color.Transparent,
        contentColor = if (dark) Color.White else Color.Black,
        scrimColor = Color.Black.copy(alpha = if (blurred) Scrim else ScrimAlone),
        // Handled inside the glass rather than above it, or the grabber would be the one part of
        // the sheet with nothing behind it.
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .sheetGlass(behind, glass),
        ) {
            Grabber(dark)
            content()
        }
    }
}

/**
 * The sheet's surface: the captured screen behind it, blurred, under a film of the sheet's colour.
 *
 * The blur itself is [snapshotBlur], which the app's dialogs are made of too — the two are the same
 * material and there is no reason for them to be two pieces of code.
 */
@Composable
private fun Modifier.sheetGlass(image: ImageBitmap?, glass: Color, shape: Shape = SheetShape): Modifier =
    this.clip(shape)
        .snapshotBlur(image, radius = SheetBlur)
        .background(if (image == null) glass else glass.copy(alpha = GlassAlpha))

/**
 * Blurs whatever is behind this dialog's window, and reports whether it took.
 *
 * The flag goes on the window rather than into the composition, because what is being blurred is
 * not in this window at all.
 */
@Composable
private fun rememberBlurBehind(radius: Dp): Boolean {
    val view = LocalView.current
    val context = LocalContext.current
    val radiusPx = with(LocalDensity.current) { radius.roundToPx() }
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        context.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true

    // Written from the effect rather than read during composition: the dialog's window is not yet
    // attached on the frame the sheet's content is first composed, so asking then always says no.
    var applied by remember { mutableStateOf(false) }
    DisposableEffect(view, supported, radiusPx) {
        val window = view.dialogWindow()
        if (supported && window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            // Reassigned rather than mutated in place: a window only re-reads its parameters when
            // the object is set back on it.
            window.attributes = window.attributes.apply { blurBehindRadius = radiusPx }
            applied = true
        }
        onDispose {}
    }
    return applied
}

/** The window a dialog's composition is hosted in, or null in a plain in-window composition. */
internal fun View.dialogWindow(): Window? {
    var node = parent
    while (node != null) {
        if (node is DialogWindowProvider) return node.window
        node = (node as? View)?.parent
    }
    return null
}

@Composable
private fun Grabber(dark: Boolean) {
    Box(modifier = Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(width = GrabberWidth, height = GrabberHeight)
                .clip(RoundedCornerShape(GrabberHeight / 2))
                .background(if (dark) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.2f)),
        )
    }
}
