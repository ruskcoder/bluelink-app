package com.ruskcoder.messages.ui.components.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ruskcoder.messages.ui.components.ControlTint
import com.ruskcoder.messages.ui.components.FrostedPage
import com.ruskcoder.messages.ui.components.FrostedSection
import androidx.compose.ui.graphics.Color
import com.ruskcoder.messages.ui.components.AppDialog
import com.ruskcoder.messages.ui.components.DialogMutedInk
import com.ruskcoder.messages.ui.components.dialogRadioColors

/**
 * Shared settings UI kit. Ported from `settings-page.tsx`.
 *
 * Every settings screen is one of these, which is why the frost lives here rather than on any of
 * them: the pages are laid over a blurred copy of whatever they were opened from, and their cards
 * are a thicker piece of the same glass — the treatment the contact and group pages already wore.
 * See [FrostedPage].
 */
@Composable
fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    FrostedPage(
        title = { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 20.sp) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 48.dp),
            content = content,
        )
    }
}

@Composable
fun SettingsSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 28.dp, end = 28.dp, bottom = 8.dp),
    )
}

/**
 * The card a run of related rows sits on: the page's own frost, taken a second time and tinted.
 *
 * Settings are titles only — no explanatory lines under a card or a row — so there is no footnote.
 */
@Composable
fun SettingsGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(bottom = 20.dp)) {
        if (title != null) SettingsSection(title)
        FrostedSection(content = content)
    }
}

/** A free-form block inside a settings card — an optional title followed by custom content. */
@Composable
fun SettingsBlock(title: String? = null, content: @Composable ColumnScope.() -> Unit = {}) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (title != null) Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        content()
    }
}

@Composable
fun SettingsRow(
    title: String,
    description: String? = null,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    chevron: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .let { if (onClick != null && enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        if (icon != null) {
            Box(
                // A pale film rather than the page's background colour: on glass there is no
                // background colour to borrow, and a solid disc would be the one opaque thing on
                // the card.
                modifier = Modifier.size(34.dp).clip(CircleShape).background(ControlTint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(19.dp))
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = color)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
        if (chevron) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
fun SettingsToggle(
    title: String,
    icon: ImageVector? = null,
    checked: Boolean,
    enabled: Boolean = true,
    description: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsRow(
        title = title,
        description = description,
        icon = icon,
        enabled = enabled,
        trailing = { NativeStyleSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled) },
        onClick = if (enabled) { { onCheckedChange(!checked) } } else null,
    )
}

/**
 * The Material 3 [Switch], tinted from the app theme instead of Compose's own default palette —
 * checked: `secondary`/`onSecondary` track/thumb (the WhatsApp green the send button uses);
 * unchecked: `surfaceVariant`/`outline`. Ported from `native-switch.android.tsx`, which renders the
 * same real Compose `Switch` with this same color mapping — so this *is* the native switch, not an
 * approximation of it.
 */
@Composable
private fun NativeStyleSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = MaterialTheme.colorScheme.secondary,
            checkedThumbColor = MaterialTheme.colorScheme.onSecondary,
            checkedBorderColor = MaterialTheme.colorScheme.secondary,
            checkedIconColor = MaterialTheme.colorScheme.secondary,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
            uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            uncheckedIconColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    )
}

/**
 * A row whose value is one of a short list: it reads back the current choice and opens the Material
 * single-choice dialog to change it. The pattern Android settings use everywhere for a setting with
 * more than two states and too few for a screen of its own.
 */
@Composable
fun <T> SettingsChoice(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    SettingsRow(
        title = title,
        description = label(selected),
        icon = icon,
        chevron = true,
        enabled = enabled,
        onClick = { open = true },
    )
    if (open) {
        AppDialog(
            title = title,
            onDismiss = { open = false },
            content = {
                Column(modifier = Modifier.selectableGroup().padding(horizontal = 24.dp)) {
                    options.forEach { option ->
                        val isSelected = option == selected
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                // One target for the whole row, radio included — the button itself
                                // is along for the ride, which is what `selectable` handles.
                                .selectable(selected = isSelected, role = Role.RadioButton) {
                                    onSelect(option)
                                    open = false
                                }
                                .padding(vertical = 4.dp),
                        ) {
                            RadioButton(selected = isSelected, onClick = null, colors = dialogRadioColors())
                            Text(label(option), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            buttons = { TextButton(onClick = { open = false }) { Text("Cancel", color = Color.White) } },
        )
    }
}

@Composable
fun <T> SettingsSegmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    /** An icon to the left of each label; null leaves the segments as plain text. */
    icon: ((T) -> ImageVector)? = null,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                onClick = { onSelect(option) },
                selected = option == selected,
                // Tighter than the default 12dp a side: a label like "Quick: 👍" is only just wider
                // than a third of the row, and the default padding is what turned it into "Qui…".
                contentPadding = PaddingValues(horizontal = 4.dp),
                // No tick on the selected segment: the fill already says which one is on, and the
                // icon eats the label's width — with five options across a phone there isn't any
                // to spare.
                icon = {
                    if (icon != null) Icon(icon(option), contentDescription = null, modifier = Modifier.size(SegmentedButtonDefaults.IconSize))
                },
            ) {
                // Every segment gets an equal slice of the row, so a label too wide for its slice
                // has to end somewhere rather than being clipped mid-glyph.
                Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
