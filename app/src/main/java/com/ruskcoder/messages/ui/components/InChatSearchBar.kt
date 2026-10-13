package com.ruskcoder.messages.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Find-in-conversation, in the app bar: the browser's control, which is the one people already know
 * — a field, a running "3/20", and a pair of chevrons that walk the hits. It replaces the header
 * rather than opening a screen, so the conversation stays in view behind every step.
 *
 * The wider search — other chats, media, links, dates — is its own page, one tap away on the right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InChatSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    /** 1-based position of the hit being shown, or 0 when there are none. */
    current: Int,
    total: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onAdvanced: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    // The bar exists to be typed into, so it opens with the cursor already in it.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val hasQuery = query.isNotBlank()
    val enabled = total > 0

    // The same glass as the header it replaces and the selection bar it shares a slot with. It
    // was the one bar in the conversation still drawn as a solid Material surface, so opening
    // find-in-chat swapped the top of the screen for a slab.
    StyledTopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Close search") }
        },
        title = {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = Color.White),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // Return walks to the next hit, the way a browser's find bar does.
                keyboardActions = KeyboardActions(onSearch = { if (enabled) onNext() }),
                modifier = Modifier.focusRequester(focusRequester),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text("Search in chat", color = Color.White.copy(alpha = 0.6f))
                    }
                    inner()
                },
            )
        },
        actions = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (hasQuery) {
                    // The count reads as "which one of how many", and says so plainly when the
                    // answer is none.
                    Text(
                        if (total == 0) "0/0" else "$current/$total",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (enabled) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    IconButton(onClick = onPrevious, enabled = enabled) {
                        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Previous match")
                    }
                    IconButton(onClick = onNext, enabled = enabled) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Next match")
                    }
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear search", modifier = Modifier.size(20.dp))
                    }
                }
                IconButton(onClick = onAdvanced) {
                    Icon(Icons.AutoMirrored.Rounded.ManageSearch, contentDescription = "Advanced search")
                }
            }
        },
    )
}
