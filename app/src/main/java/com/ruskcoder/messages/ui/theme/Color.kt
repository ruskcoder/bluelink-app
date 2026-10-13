package com.ruskcoder.messages.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Hand-tuned blue app palette, used when Material You is off — the app's default look. Ported
 * from the RN app's `context/app-theme.tsx` `lightColors`/`darkColors`.
 */
object AppPalette {
    val LightPrimary = Color(0xFF0B6CD9)
    val LightOnPrimary = Color(0xFFFFFFFF)
    val LightPrimaryContainer = Color(0xFF08508F)
    val LightOnPrimaryContainer = Color(0xFFFFFFFF)
    val LightSecondary = Color(0xFF2E93FF)
    val LightOnSecondary = Color(0xFFFFFFFF)
    val LightSecondaryContainer = Color(0xFFD6E7FF)
    val LightOnSecondaryContainer = Color(0xFF0B1F33)
    val LightBackground = Color(0xFFFFFFFF)
    val LightOnBackground = Color(0xFF111B21)
    val LightSurface = Color(0xFFFFFFFF)
    val LightOnSurface = Color(0xFF111B21)
    val LightSurfaceVariant = Color(0xFFF0F2F5)
    val LightOnSurfaceVariant = Color(0xFF667781)
    val LightOutline = Color(0xFF8696A0)
    val LightOutlineVariant = Color(0xFFE9EDEF)

    /**
     * The elevated-surface ladder. Material 3 draws menus, sheets and search bars on the
     * `surfaceContainer` roles rather than on `surface`, and those roles have their own defaults —
     * a lavender-tinted neutral from the baseline scheme — which is not something a palette that
     * overrides only `surface` and `surfaceVariant` can reach. Left unset, every menu in the app
     * arrived wearing a different grey from everything around it.
     */
    val LightSurfaceDim = Color(0xFFE1E5E8)
    val LightSurfaceBright = Color(0xFFFFFFFF)
    val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
    val LightSurfaceContainerLow = Color(0xFFFAFBFC)
    val LightSurfaceContainer = Color(0xFFF5F7F8)
    val LightSurfaceContainerHigh = Color(0xFFF0F2F5)
    val LightSurfaceContainerHighest = Color(0xFFE9EDEF)
    val LightInverseSurface = Color(0xFF2A3942)
    val LightInverseOnSurface = Color(0xFFF0F2F5)
    val LightInversePrimary = Color(0xFF4FA6FF)

    val DarkPrimary = Color(0xFF4FA6FF)
    val DarkOnPrimary = Color(0xFF052544)
    val DarkPrimaryContainer = Color(0xFF0C3D6E)
    val DarkOnPrimaryContainer = Color(0xFFE9EDEF)
    val DarkSecondary = Color(0xFF4FA6FF)
    val DarkOnSecondary = Color(0xFF052544)
    val DarkSecondaryContainer = Color(0xFF0C3D6E)
    val DarkOnSecondaryContainer = Color(0xFFE9EDEF)
    val DarkBackground = Color(0xFF0B1014)
    val DarkOnBackground = Color(0xFFE9EDEF)
    val DarkSurface = Color(0xFF0B1014)
    val DarkOnSurface = Color(0xFFE9EDEF)
    val DarkSurfaceVariant = Color(0xFF1F272A)
    val DarkOnSurfaceVariant = Color(0xFF8696A0)
    val DarkOutline = Color(0xFF8696A0)
    val DarkOutlineVariant = Color(0xFF2A3942)

    /** The same ladder in the dark, climbing out of the near-black surface toward the outline. */
    val DarkSurfaceDim = Color(0xFF080C0F)
    val DarkSurfaceBright = Color(0xFF2A3942)
    val DarkSurfaceContainerLowest = Color(0xFF05090B)
    val DarkSurfaceContainerLow = Color(0xFF111A1F)
    val DarkSurfaceContainer = Color(0xFF161F24)
    val DarkSurfaceContainerHigh = Color(0xFF1F272A)
    val DarkSurfaceContainerHighest = Color(0xFF29343A)
    val DarkInverseSurface = Color(0xFFE9EDEF)
    val DarkInverseOnSurface = Color(0xFF111B21)
    val DarkInversePrimary = Color(0xFF0B6CD9)

    /** AMOLED-black page background the chat list paints in dark mode. */
    val AmoledBlack = Color(0xFF000000)
}
