package com.ruskcoder.messages.data.model

/**
 * Per-conversation color scheme key. A chat stores only the key; the bubbles and wallpaper
 * resolve it against the current light/dark mode (see `ui.theme.ChatTheme`), so a chat with no
 * key set keeps whatever its bubble style would have used on its own — the schemes are an
 * override, not a replacement.
 */
enum class ChatThemeKey(val storageKey: String) {
    DEFAULT("default"),
    OCEAN("ocean"),
    INDIGO("indigo"),
    TEAL("teal"),
    VIOLET("violet"),
    OLIVE("olive"),
    RUST("rust"),

    /**
     * One hue, picked on a slider, with the whole scheme derived from it.
     *
     * The named schemes above are seven points on the same circle, hand-tuned; this is the circle.
     * The hue rides along in the stored key as `custom:214` rather than in a column of its own —
     * the theme is already a free-form string mapped tolerantly on the way in, so this needs no
     * migration, and a build that predates this reads it back as [DEFAULT] rather than as garbage.
     */
    CUSTOM("custom"),
    ;

    companion object {
        /** The hue a [CUSTOM] chat falls back to — the blue the default scheme is already built on. */
        const val DEFAULT_HUE = 205f

        fun fromStorageKey(key: String?): ChatThemeKey = when {
            key == null -> DEFAULT
            key.startsWith(CUSTOM.storageKey) -> CUSTOM
            else -> entries.firstOrNull { it.storageKey == key } ?: DEFAULT
        }

        /** The hue carried by a stored key, or [DEFAULT_HUE] for one that carries none. */
        fun hueFromStorageKey(key: String?): Float =
            key?.substringAfter(':', "")?.toFloatOrNull()?.takeIf { it.isFinite() }?.mod(360f)
                ?: DEFAULT_HUE

        /** What goes in the column: a named scheme is its own name, a custom one is name and hue. */
        fun storageKeyFor(theme: ChatThemeKey, hue: Float): String =
            if (theme == CUSTOM) "${CUSTOM.storageKey}:${hue.mod(360f).toInt()}" else theme.storageKey
    }
}
