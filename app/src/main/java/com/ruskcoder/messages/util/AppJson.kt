package com.ruskcoder.messages.util

import kotlinx.serialization.json.Json

/** Shared JSON codec for the columns that store a serialized list/object (members, payload, etc). */
val appJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    // A stored value that no longer exists — a wallpaper tint from when there were six of them —
    // falls back to the property's default instead of failing the parse. Without this, dropping a
    // single enum entry costs the whole blob: settings are decoded with a `runCatching` that
    // returns `Settings()` on failure, so one stale name would silently reset every other choice.
    coerceInputValues = true
}
