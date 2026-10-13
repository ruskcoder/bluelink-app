package com.ruskcoder.messages.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ruskcoder.messages.data.model.ALL_EMOJI
import com.ruskcoder.messages.data.model.EMOJI_GROUPS
import com.ruskcoder.messages.data.model.Emoji
import com.ruskcoder.messages.data.model.matches
import com.ruskcoder.messages.data.repository.RecentEmoji

/** Eight across, which is what puts the sixteen recents in exactly two rows. */
private const val COLUMNS = 8

/** The field is a single line of text and a glyph either side, and nothing else. */
private val FieldHeight = 40.dp

/** How large a glyph is drawn in the grid, and how much room its cell gets around it. */
private val GlyphSize = 24.sp
private val CellSize = 44.dp

/**
 * The full emoji palette, as a sheet rather than a dialog.
 *
 * It opens part way up — a search field, then Recents in two rows — and pulling it the rest of the
 * way reveals the whole catalogue, grouped and labelled. That shape falls out of the sheet's own
 * partially-expanded state and a list tall enough to have somewhere to expand *into*: there is no
 * second layout here for the two heights, only more of the same list showing.
 *
 * One `LazyVerticalGrid` carries all of it, headers included as full-width spans, so the sheet's
 * drag and the list's scroll are the one gesture the sheet already knows how to arbitrate — a
 * column of separate scrolling sections would fight it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiPickerSheet(
    /** Already on this message, so the palette can show which ones are yours. */
    active: List<String>? = null,
    /**
     * Empties the slot this sheet was opened from, rather than choosing something for it.
     *
     * Null everywhere it makes no sense — reacting to a message is choosing an emoji, and there is
     * nothing there to clear. Non-null only when the sheet is editing one slot of the reaction bar,
     * where "none" is a real answer and the bar simply comes up one emoji shorter.
     */
    onClear: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var recents by remember { mutableStateOf(RecentEmoji.load(context)) }

    fun pick(glyph: String) {
        recents = RecentEmoji.push(context, glyph)
        onPick(glyph)
    }

    AppBottomSheet(onDismiss = onDismiss) {
        // Full height so the sheet has a tall thing inside it: a sheet only offers a partial stop
        // when its content is taller than that stop, and this one's whole shape depends on it.
        Column(
            modifier = Modifier.fillMaxHeight().navigationBarsPadding(),
        ) {
            val searching = query.isNotBlank()
            val results = remember(query) {
                if (query.isBlank()) emptyList() else ALL_EMOJI.filter { it.matches(query) }
            }

            HeaderedPanel(
                modifier = Modifier.fillMaxWidth().weight(1f),
                header = {
                    // The bin sits *beside* the field rather than inside it: it acts on the slot
                    // being edited, not on what has been typed, and a control inside a search
                    // field is read as belonging to the search.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SearchField(
                            query = query,
                            onQueryChange = { query = it },
                            modifier = Modifier.weight(1f),
                        )
                        if (onClear != null) {
                            IconButton(
                                onClick = { onClear(); onDismiss() },
                                modifier = Modifier.padding(end = 10.dp).size(FieldHeight),
                            ) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "Leave this slot empty",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                },
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(COLUMNS),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (searching) {
                        if (results.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                SectionLabel("Nothing matches \"${query.trim()}\"")
                            }
                        }
                        items(results, key = { "search-${it.glyph}" }) { emoji ->
                            Glyph(emoji.glyph, selected = active?.contains(emoji.glyph) == true) { pick(emoji.glyph) }
                        }
                    } else {
                        if (recents.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Recents") }
                            items(recents, key = { "recent-$it" }) { glyph ->
                                Glyph(glyph, selected = active?.contains(glyph) == true) { pick(glyph) }
                            }
                        }
                        EMOJI_GROUPS.forEach { group ->
                            item(span = { GridItemSpan(maxLineSpan) }, key = "head-${group.label}") {
                                SectionLabel(group.label)
                            }
                            items(group.emoji, key = { "${group.label}-${it.glyph}" }) { emoji ->
                                Glyph(emoji.glyph, selected = active?.contains(emoji.glyph) == true) { pick(emoji.glyph) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The thin field at the top.
 *
 * A `BasicTextField` in a pill of the sheet's own control tint, for the same reason the search
 * screen uses one: a Material field brings a container and a minimum height of its own, and one
 * drawn inside a pill is two surfaces where the design calls for one thin line.
 */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(FieldHeight)
            .clip(RoundedCornerShape(FieldHeight / 2))
            .background(ControlTint)
            .padding(start = 12.dp, end = 6.dp),
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Box(
            modifier = Modifier.weight(1f).padding(start = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (query.isEmpty()) {
                Text(
                    "Search emoji",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(
                    MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Clear", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 14.dp, bottom = 6.dp),
    )
}

/** One cell: the glyph, and the grey disc that marks one already on the message. */
@Composable
private fun Glyph(glyph: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(CellSize)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(Color.White.copy(alpha = 0.16f)) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = GlyphSize)
    }
}
