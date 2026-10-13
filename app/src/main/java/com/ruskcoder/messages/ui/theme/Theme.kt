package com.ruskcoder.messages.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.ruskcoder.messages.data.model.Settings
import com.ruskcoder.messages.data.model.ThemeMode

/** Exposes the live app `Settings` down the tree, mirroring `useSettings()`'s React context. */
val LocalSettings = compositionLocalOf { Settings() }

/**
 * Builds the Material 3 color scheme from the persisted appearance settings.
 *
 * When Material You is on, this uses *real* Android 12+ dynamic (wallpaper-derived) color,
 * falling back to the stock M3 seed below API 31 — a genuine upgrade over the RN app, which could
 * only ever fall back to Paper's un-customized default palette (there is no dynamic-color API
 * available to a React Native app). When Material You is off, a hand-tuned blue palette is used
 * instead (`AppPalette`), with `surfaceTint` pinned to `surface` so Material 3's tonal elevation
 * doesn't tint elevated surfaces with the seed color — the same "flat elevation" the RN app forced
 * via its own elevation-level overrides. The `surfaceContainer` ladder is spelled out for the same
 * reason: Material draws menus and sheets on those roles rather than on `surface`, and a scheme
 * that leaves them alone gets the baseline scheme's lavender neutrals under every menu in the app.
 */
@Composable
fun MessagesTheme(settings: Settings, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = settings.mode == ThemeMode.DARK
    val colorScheme = when {
        settings.materialYou && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        settings.materialYou -> if (darkTheme) darkColorScheme() else lightColorScheme()
        darkTheme -> seedDarkColorScheme()
        else -> seedLightColorScheme()
    }.accented(chatThemeHue(settings.appTheme, settings.appThemeHue), darkTheme)
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/**
 * The scheme with its accent roles moved to [hue], and everything neutral left alone.
 *
 * The lightness and saturation are the stock blue's own, so a picked colour sits on the dark
 * surfaces exactly as legibly as the blue it replaces; only where on the circle it is changes.
 */
private fun ColorScheme.accented(hue: Float?, dark: Boolean): ColorScheme {
    if (hue == null) return this
    return if (dark) {
        val accent = Color.hsl(hue, 1f, 0.65f)
        val onAccent = Color.hsl(hue, 0.86f, 0.14f)
        val container = Color.hsl(hue, 0.80f, 0.24f)
        copy(
            primary = accent, onPrimary = onAccent, primaryContainer = container,
            secondary = accent, onSecondary = onAccent, secondaryContainer = container,
            inversePrimary = Color.hsl(hue, 0.90f, 0.45f),
        )
    } else {
        val accent = Color.hsl(hue, 0.90f, 0.45f)
        copy(
            primary = accent, primaryContainer = Color.hsl(hue, 0.89f, 0.30f),
            secondary = Color.hsl(hue, 1f, 0.59f), secondaryContainer = Color.hsl(hue, 1f, 0.92f),
            onSecondaryContainer = Color.hsl(hue, 0.65f, 0.12f),
            inversePrimary = Color.hsl(hue, 1f, 0.65f),
        )
    }
}

/** For previews and any composable outside the settings-driven tree — follows the OS scheme. */
@Composable
fun MessagesTheme(content: @Composable () -> Unit) {
    MessagesTheme(settings = Settings(mode = if (isSystemInDarkTheme()) ThemeMode.DARK else ThemeMode.LIGHT), content = content)
}

private fun seedLightColorScheme() = lightColorScheme(
    primary = AppPalette.LightPrimary,
    onPrimary = AppPalette.LightOnPrimary,
    primaryContainer = AppPalette.LightPrimaryContainer,
    onPrimaryContainer = AppPalette.LightOnPrimaryContainer,
    secondary = AppPalette.LightSecondary,
    onSecondary = AppPalette.LightOnSecondary,
    secondaryContainer = AppPalette.LightSecondaryContainer,
    onSecondaryContainer = AppPalette.LightOnSecondaryContainer,
    background = AppPalette.LightBackground,
    onBackground = AppPalette.LightOnBackground,
    surface = AppPalette.LightSurface,
    onSurface = AppPalette.LightOnSurface,
    surfaceVariant = AppPalette.LightSurfaceVariant,
    onSurfaceVariant = AppPalette.LightOnSurfaceVariant,
    outline = AppPalette.LightOutline,
    outlineVariant = AppPalette.LightOutlineVariant,
    surfaceTint = AppPalette.LightSurface,
    // Menus, sheets and search bars are drawn on these, not on `surface`.
    surfaceDim = AppPalette.LightSurfaceDim,
    surfaceBright = AppPalette.LightSurfaceBright,
    surfaceContainerLowest = AppPalette.LightSurfaceContainerLowest,
    surfaceContainerLow = AppPalette.LightSurfaceContainerLow,
    surfaceContainer = AppPalette.LightSurfaceContainer,
    surfaceContainerHigh = AppPalette.LightSurfaceContainerHigh,
    surfaceContainerHighest = AppPalette.LightSurfaceContainerHighest,
    inverseSurface = AppPalette.LightInverseSurface,
    inverseOnSurface = AppPalette.LightInverseOnSurface,
    inversePrimary = AppPalette.LightInversePrimary,
)

private fun seedDarkColorScheme() = darkColorScheme(
    primary = AppPalette.DarkPrimary,
    onPrimary = AppPalette.DarkOnPrimary,
    primaryContainer = AppPalette.DarkPrimaryContainer,
    onPrimaryContainer = AppPalette.DarkOnPrimaryContainer,
    secondary = AppPalette.DarkSecondary,
    onSecondary = AppPalette.DarkOnSecondary,
    secondaryContainer = AppPalette.DarkSecondaryContainer,
    onSecondaryContainer = AppPalette.DarkOnSecondaryContainer,
    background = AppPalette.DarkBackground,
    onBackground = AppPalette.DarkOnBackground,
    surface = AppPalette.DarkSurface,
    onSurface = AppPalette.DarkOnSurface,
    surfaceVariant = AppPalette.DarkSurfaceVariant,
    onSurfaceVariant = AppPalette.DarkOnSurfaceVariant,
    outline = AppPalette.DarkOutline,
    outlineVariant = AppPalette.DarkOutlineVariant,
    surfaceTint = AppPalette.DarkSurface,
    surfaceDim = AppPalette.DarkSurfaceDim,
    surfaceBright = AppPalette.DarkSurfaceBright,
    surfaceContainerLowest = AppPalette.DarkSurfaceContainerLowest,
    surfaceContainerLow = AppPalette.DarkSurfaceContainerLow,
    surfaceContainer = AppPalette.DarkSurfaceContainer,
    surfaceContainerHigh = AppPalette.DarkSurfaceContainerHigh,
    surfaceContainerHighest = AppPalette.DarkSurfaceContainerHighest,
    inverseSurface = AppPalette.DarkInverseSurface,
    inverseOnSurface = AppPalette.DarkInverseOnSurface,
    inversePrimary = AppPalette.DarkInversePrimary,
)
