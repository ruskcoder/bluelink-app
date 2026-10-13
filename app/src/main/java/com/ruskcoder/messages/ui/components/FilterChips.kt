package com.ruskcoder.messages.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class ChatFilter { ALL, UNREAD, GROUPS }

private val FILTERS = listOf(
    ChatFilter.ALL to "All",
    ChatFilter.UNREAD to "Unread",
    ChatFilter.GROUPS to "Groups",
)

/** Trailing counts, WhatsApp-style ("Unread 13"). Zero or missing hides the number. */
private fun chipLabel(label: String, count: Int?): String =
    if (count != null && count > 0) "$label  $count" else label

/** Material 3 filter chips — a direct Compose port of the RN app's Android-native chip fork. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterChips(
    value: ChatFilter,
    onChange: (ChatFilter) -> Unit,
    unreadCount: Int,
    groupsCount: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(PaddingValues(horizontal = 16.dp, vertical = 8.dp)),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FILTERS.forEach { (filter, label) ->
            val selected = value == filter
            val count = when (filter) {
                ChatFilter.UNREAD -> unreadCount
                ChatFilter.GROUPS -> groupsCount
                ChatFilter.ALL -> null
            }
            FilterChip(
                selected = selected,
                // Tapping the active chip falls back to All, the way WhatsApp behaves.
                onClick = { onChange(if (selected) ChatFilter.ALL else filter) },
                label = { Text(chipLabel(label, count)) },
                leadingIcon = if (selected) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else {
                    null
                },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}
