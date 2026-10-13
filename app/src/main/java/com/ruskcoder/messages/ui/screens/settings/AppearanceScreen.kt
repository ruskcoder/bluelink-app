package com.ruskcoder.messages.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.outlined.Palette
import com.ruskcoder.messages.ui.components.ChatColorPicker
import com.ruskcoder.messages.ui.theme.chatThemeLabel
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruskcoder.messages.data.datastore.SettingsRepository
import com.ruskcoder.messages.data.model.BubbleStyle
import com.ruskcoder.messages.data.model.GalleryMode
import com.ruskcoder.messages.data.model.MemberView
import com.ruskcoder.messages.data.model.MultiMediaStyle
import com.ruskcoder.messages.data.model.ReplyMode
import com.ruskcoder.messages.data.model.PageTransition
import com.ruskcoder.messages.data.model.ThemeMode
import com.ruskcoder.messages.ui.components.WALLPAPER_TINTS
import com.ruskcoder.messages.ui.components.settings.AppearancePreview
import com.ruskcoder.messages.ui.components.settings.MemberLayoutPreview
import com.ruskcoder.messages.ui.components.settings.ReplyStylePreview
import com.ruskcoder.messages.ui.components.settings.SettingsGroup
import com.ruskcoder.messages.ui.components.settings.SettingsPage
import com.ruskcoder.messages.ui.components.settings.SettingsRow
import com.ruskcoder.messages.ui.components.settings.SettingsSegmented
import com.ruskcoder.messages.ui.components.settings.SettingsToggle

/** Ported from `settings/appearance.tsx`. */
@Composable
fun AppearanceScreen(settingsRepository: SettingsRepository, onBack: () -> Unit) {
    val settings by settingsRepository.settings.collectAsStateWithLifecycle()
    // The Material You switch is gone; a build that had it on would otherwise be stuck with
    // wallpaper-derived bubbles and no control to turn them off.
    LaunchedEffect(settings.materialYou) {
        if (settings.materialYou) settingsRepository.update { it.copy(materialYou = false) }
    }
    // And so is the light one. Every surface in the app — the frosted bars, the wallpapers, the
    // bubble palettes, the AMOLED page behind the conversation list — is drawn for a dark room and
    // was only ever half-translated into a light one. An install left sitting on Light is brought
    // back here rather than stranded on a theme with no switch left to leave it by.
    LaunchedEffect(settings.mode) {
        if (settings.mode != ThemeMode.DARK) settingsRepository.update { it.copy(mode = ThemeMode.DARK) }
    }

    // "Off" is gone from the list. An install left on it would be stuck with no segment lit and
    // no way to move, so it is put back on Auto.
    LaunchedEffect(settings.pageTransition) {
        if (settings.pageTransition == PageTransition.NONE) {
            settingsRepository.update { it.copy(pageTransition = PageTransition.DEFAULT) }
        }
    }

    var pickingTheme by remember { mutableStateOf(false) }

    SettingsPage(title = "Appearance", onBack = onBack) {
        // Everything below is easier to choose against a picture of the result than by name.
        AppearancePreview(settings = settings, modifier = Modifier.padding(bottom = 20.dp))

        // One choice, not two. The bar that came with each style used to be its own setting,
        // which offered six combinations of which four are simply mismatched — and nothing about
        // picking Apple bubbles suggests you then want a Material bar under them.
        SettingsGroup(title = "Style") {
            SettingsSegmented(
                options = listOf(BubbleStyle.WHATSAPP, BubbleStyle.GOOGLE, BubbleStyle.APPLE),
                selected = settings.bubbleType,
                label = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                onSelect = { style -> settingsRepository.update { it.copy(bubbleType = style) } },
            )
        }

        SettingsGroup(title = "Color") {
            SettingsRow(
                title = "App theme",
                icon = Icons.Outlined.Palette,
                description = chatThemeLabel(settings.appTheme),
                trailing = {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    )
                },
                onClick = { pickingTheme = true },
            )
        }

        SettingsGroup(title = "Page transition") {
            SettingsSegmented(
                options = listOf(PageTransition.DEFAULT, PageTransition.SLIDE, PageTransition.FADE, PageTransition.SHEET),
                selected = settings.pageTransition,
                // Short labels: five segments across a phone leave about 60dp each, and "Default"
                // doesn't fit in that.
                label = {
                    when (it) {
                        PageTransition.DEFAULT -> "Auto"
                        PageTransition.SLIDE -> "Slide"
                        PageTransition.FADE -> "Fade"
                        PageTransition.SHEET -> "Sheet"
                        PageTransition.NONE -> "Off" // no longer offered; see the effect above
                    }
                },
                onSelect = { value -> settingsRepository.update { it.copy(pageTransition = value) } },
            )
        }

        SettingsGroup(title = "Multiple media") {
            SettingsSegmented(
                options = listOf(MultiMediaStyle.GRID, MultiMediaStyle.STACK),
                selected = settings.multiMediaStyle,
                label = { if (it == MultiMediaStyle.GRID) "Media bubble" else "Card stack" },
                onSelect = { value -> settingsRepository.update { it.copy(multiMediaStyle = value) } },
            )
        }

        // What the "+" card does with the gallery: a widget among the tiles, nothing, or the card
        // given over to it — the tiles in two scrolling rows and the pictures running on below.
        SettingsGroup(title = "Gallery picker") {
            SettingsSegmented(
                options = listOf(GalleryMode.WIDGET, GalleryMode.NONE, GalleryMode.FULL),
                selected = settings.galleryMode,
                label = {
                    when (it) {
                        GalleryMode.WIDGET -> "Widget"
                        GalleryMode.NONE -> "None"
                        GalleryMode.FULL -> "Full"
                    }
                },
                onSelect = { value -> settingsRepository.update { it.copy(galleryMode = value) } },
            )
        }

        // Replying and the member list are looks, not behaviours, so they sit with the other
        // looks and get a picture of the result like the bubbles do.
        SettingsGroup(title = "Reply style") {
            SettingsSegmented(
                options = listOf(ReplyMode.QUOTE, ReplyMode.FOCUS),
                selected = settings.replyMode,
                label = { if (it == ReplyMode.QUOTE) "Quick quote" else "Focus thread" },
                onSelect = { value -> settingsRepository.update { it.copy(replyMode = value) } },
            )
            ReplyStylePreview(settings = settings, modifier = Modifier.padding(bottom = 12.dp))
        }

        SettingsGroup(title = "Member layout") {
            SettingsSegmented(
                options = listOf(MemberView.GRID, MemberView.LIST),
                selected = settings.memberView,
                label = { if (it == MemberView.GRID) "Grid" else "List" },
                icon = { if (it == MemberView.GRID) Icons.Outlined.GridView else Icons.AutoMirrored.Filled.FormatListBulleted },
                onSelect = { value -> settingsRepository.update { it.copy(memberView = value) } },
            )
            MemberLayoutPreview(view = settings.memberView, modifier = Modifier.padding(bottom = 4.dp))
        }

        SettingsGroup(title = "Wallpaper") {
            // Two swatches read faster than they describe: a row with the choice sitting in the
            // trailing slot, the same shape as every toggle under it, rather than the titled and
            // subtitled block a six-colour grid needed.
            SettingsRow(
                title = "Background",
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        WALLPAPER_TINTS.forEach { (tint, label, swatch) ->
                            val selected = settings.wallpaperTint == tint
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(swatch)
                                    // A near-black swatch on a dark card needs an edge of its own
                                    // to be a swatch at all, so the unselected ring is drawn too —
                                    // faintly, and in the same place the selected one goes.
                                    .border(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        shape = CircleShape,
                                    )
                                    .clickable { settingsRepository.update { s -> s.copy(wallpaperTint = tint) } }
                                    .semantics { contentDescription = label },
                            )
                        }
                    }
                },
            )
            SettingsToggle(
                title = "Doodle pattern",
                icon = Icons.Filled.GridView,
                checked = settings.wallpaperPattern,
                onCheckedChange = { value -> settingsRepository.update { it.copy(wallpaperPattern = value) } },
            )
        }
    }

    if (pickingTheme) {
        ChatColorPicker(
            selected = settings.appTheme,
            selectedHue = settings.appThemeHue,
            title = "App theme",
            caption = "The app's accent, and every conversation without a color of its own.",
            onDismiss = { pickingTheme = false },
            onConfirm = { theme, hue -> settingsRepository.update { it.copy(appTheme = theme, appThemeHue = hue) } },
        )
    }
}
