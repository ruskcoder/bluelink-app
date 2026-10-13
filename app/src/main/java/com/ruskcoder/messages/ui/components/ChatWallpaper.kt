package com.ruskcoder.messages.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import coil3.compose.AsyncImage
import com.ruskcoder.messages.R
import com.ruskcoder.messages.data.model.ThemeMode
import com.ruskcoder.messages.data.model.WallpaperTint
import com.ruskcoder.messages.ui.theme.LocalChatTheme
import com.ruskcoder.messages.ui.theme.LocalSettings

/**
 * Resolves the chat backdrop + doodle pattern colors, in priority order: per-chat theme, then
 * Material You, then the wallpaper tint. A per-chat theme carries no doodle, which is expressed by
 * tinting the pattern to the backdrop — making it invisible — rather than by skipping the image, so
 * that turning it off is a pure colour swap with no mounting difference.
 */
data class WallpaperColors(val backdrop: Color, val doodle: Color)

private data class TintPair(val dark: Pair<Color, Color>, val light: Pair<Color, Color>)

/**
 * Backdrop and doodle for each tint, per colour mode.
 *
 * Slate is the default and is deliberately only just not black — a blue-grey cast you can see
 * against Ink's true black, with none of the lift that made the old mid-tone backdrops compete with
 * the bubbles sitting on them.
 */
private val TINTS: Map<WallpaperTint, TintPair> = mapOf(
    WallpaperTint.SLATE to TintPair(
        dark = Color(0xFF05080B) to Color(0xFF141E26),
        light = Color(0xFFE9EEF3) to Color(0xFFCFDAE4),
    ),
    WallpaperTint.INK to TintPair(
        dark = Color(0xFF000000) to Color(0xFF12161A),
        light = Color(0xFFF4F4F5) to Color(0xFFDBDBDE),
    ),
)

/** The swatch list the appearance page renders, in the order it shows them. */
val WALLPAPER_TINTS: List<Triple<WallpaperTint, String, Color>> = listOf(
    Triple(WallpaperTint.SLATE, "Slate", TINTS.getValue(WallpaperTint.SLATE).dark.first),
    Triple(WallpaperTint.INK, "Ink", TINTS.getValue(WallpaperTint.INK).dark.first),
)

/** The colors the chat wallpaper resolves to for the current theme and wallpaper settings. */
@Composable
fun useChatWallpaperColors(): WallpaperColors {
    val chatTheme = LocalChatTheme.current
    val settings = LocalSettings.current
    val colorScheme = MaterialTheme.colorScheme
    val dark = settings.mode == ThemeMode.DARK

    val base = when {
        // A per-conversation scheme sets the backdrop and hides the doodle: the pattern belongs to
        // the app's own look, not to a colour someone picked for one thread.
        chatTheme != null -> WallpaperColors(chatTheme.backdrop, chatTheme.backdrop)
        settings.materialYou -> WallpaperColors(colorScheme.surfaceVariant, colorScheme.outlineVariant)
        else -> {
            val pair = TINTS.getValue(settings.wallpaperTint).let { if (dark) it.dark else it.light }
            WallpaperColors(pair.first, pair.second)
        }
    }

    // Turning the pattern off tints the doodle sheet to the backdrop rather than dropping the
    // image, which keeps this a pure color swap with no layout or mounting difference.
    return if (settings.wallpaperPattern) base else WallpaperColors(base.backdrop, base.backdrop)
}

/**
 * The chat background: a solid backdrop with the WhatsApp doodle pattern tinted on top.
 *
 * The sheet is *tiled* at its own size rather than stretched to fill the screen. Cropped to fit, a
 * doodle tile is blown up several times over and the pattern stops reading as a pattern — a few
 * enormous smudges instead of the repeating scribble it is drawn as.
 */
@Composable
fun ChatWallpaper(
    modifier: Modifier = Modifier,
    /**
     * A picture chosen for this conversation, which replaces the colour and the doodle both.
     *
     * The pattern is the app's own look; a photograph is somebody's choice about one thread, and
     * scribbling over it would be the app overruling that choice.
     */
    photoUri: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = useChatWallpaperColors()
    val patterned = photoUri == null && colors.doodle != colors.backdrop
    val tile = if (patterned) ImageBitmap.imageResource(R.drawable.wallpaper) else null
    val brush = remember(tile) {
        tile?.let { ShaderBrush(ImageShader(it, TileMode.Repeated, TileMode.Repeated)) }
    }

    Box(modifier = modifier.fillMaxSize().background(colors.backdrop)) {
        if (photoUri != null) {
            AsyncImage(
                model = photoUri,
                contentDescription = null,
                // Cropped rather than fitted: a wallpaper with letterboxing at the top and bottom
                // is not a wallpaper.
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (brush != null) {
            Box(
                modifier = Modifier.fillMaxSize().drawBehind {
                    // `tint` keeps the sheet's own alpha and paints every mark in one colour, which
                    // is what makes the doodle sit *on* the backdrop rather than replace it.
                    drawRect(brush = brush, colorFilter = ColorFilter.tint(colors.doodle))
                },
            )
        }
        content()
    }
}
