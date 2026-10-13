package com.ruskcoder.messages.util

import androidx.compose.ui.graphics.Color

/** Parses a `"#RRGGBB"`/`"#AARRGGBB"` string as stored on a chat/contact into a Compose [Color]. */
fun String.toComposeColor(): Color = runCatching { Color(android.graphics.Color.parseColor(this)) }.getOrDefault(Color.Gray)
