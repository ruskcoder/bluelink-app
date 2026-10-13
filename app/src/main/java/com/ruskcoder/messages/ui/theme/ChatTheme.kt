package com.ruskcoder.messages.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.ruskcoder.messages.data.model.ChatThemeKey

/** A resolved per-conversation color scheme — bubble fills, backdrop, text, and secondary marks. */
data class ChatThemeColors(
    /** Outgoing bubble fill. */
    val mine: Color,
    /** Incoming bubble fill. */
    val theirs: Color,
    /** Chat backdrop behind the bubbles. */
    val backdrop: Color,
    /** Text drawn on either bubble. */
    val text: Color,
    /** Timestamps, ticks, and other secondary marks inside a bubble. */
    val meta: Color,
)

private data class ChatThemePalette(
    val key: ChatThemeKey,
    val label: String,
    val dark: ChatThemeColors,
    val light: ChatThemeColors,
)

private val DarkMeta = Color(0x99FFFFFF)
private val LightMeta = Color(0x80000000)

/** The order the picker shows them in. `DEFAULT` comes first and is what every chat starts on. */
private val CHAT_THEMES: List<ChatThemePalette> = listOf(
    ChatThemePalette(
        ChatThemeKey.DEFAULT, "Default",
        dark = ChatThemeColors(Color(0xFF014C69), Color(0xFF1B2026), Color(0xFF0C1319), Color(0xFFFFFFFF), DarkMeta),
        light = ChatThemeColors(Color(0xFFD6E7FF), Color(0xFFF0F2F5), Color(0xFFFFFFFF), Color(0xFF111B21), LightMeta),
    ),
    ChatThemePalette(
        ChatThemeKey.OCEAN, "Ocean",
        dark = ChatThemeColors(Color(0xFF00566E), Color(0xFF141B21), Color(0xFF0A1116), Color(0xFFFFFFFF), DarkMeta),
        light = ChatThemeColors(Color(0xFFCCE7F0), Color(0xFFEDF2F5), Color(0xFFFBFDFE), Color(0xFF0B1B22), LightMeta),
    ),
    ChatThemePalette(
        ChatThemeKey.INDIGO, "Indigo",
        dark = ChatThemeColors(Color(0xFF33489C), Color(0xFF171A24), Color(0xFF0B0D14), Color(0xFFFFFFFF), DarkMeta),
        light = ChatThemeColors(Color(0xFFDCE1FA), Color(0xFFEFF0F6), Color(0xFFFCFCFF), Color(0xFF151A33), LightMeta),
    ),
    ChatThemePalette(
        ChatThemeKey.TEAL, "Teal",
        dark = ChatThemeColors(Color(0xFF00565C), Color(0xFF141C1D), Color(0xFF091211), Color(0xFFFFFFFF), DarkMeta),
        light = ChatThemeColors(Color(0xFFCDE9E8), Color(0xFFEDF3F2), Color(0xFFFBFEFD), Color(0xFF0C1F1F), LightMeta),
    ),
    ChatThemePalette(
        ChatThemeKey.VIOLET, "Violet",
        dark = ChatThemeColors(Color(0xFF6B3EA8), Color(0xFF1B1622), Color(0xFF100B15), Color(0xFFFFFFFF), DarkMeta),
        light = ChatThemeColors(Color(0xFFE7DCF7), Color(0xFFF2EFF6), Color(0xFFFDFCFF), Color(0xFF221533), LightMeta),
    ),
    ChatThemePalette(
        ChatThemeKey.OLIVE, "Olive",
        dark = ChatThemeColors(Color(0xFF4C5A34), Color(0xFF181A14), Color(0xFF0D100A), Color(0xFFFFFFFF), DarkMeta),
        light = ChatThemeColors(Color(0xFFDFE7CD), Color(0xFFF0F2EB), Color(0xFFFCFDFA), Color(0xFF1B2011), LightMeta),
    ),
    ChatThemePalette(
        ChatThemeKey.RUST, "Rust",
        dark = ChatThemeColors(Color(0xFF9C3F0C), Color(0xFF211611), Color(0xFF150C08), Color(0xFFFFFFFF), DarkMeta),
        light = ChatThemeColors(Color(0xFFFBDDCB), Color(0xFFF6EFEB), Color(0xFFFFFDFC), Color(0xFF2B1408), LightMeta),
    ),
)

private val ByKey = CHAT_THEMES.associateBy { it.key }

fun chatThemeLabel(key: ChatThemeKey): String =
    if (key == ChatThemeKey.CUSTOM) "Custom" else ByKey[key]?.label ?: "Default"

/**
 * A whole scheme from one hue.
 *
 * The seven named palettes above are seven points on this curve, tuned by hand; these numbers were
 * chosen to land back on them. Feed it Ocean's hue and it comes out within a shade of Ocean. That
 * is the whole reason a single slider is enough: the *relationships* between the bubble, the one
 * beside it, the ground they sit on and the ink on top are what make a scheme readable, and those
 * are fixed here. Only where on the circle they sit is yours to pick.
 *
 * Saturation and lightness therefore stay put and never appear on screen as controls. Two more
 * sliders would mostly offer ways to make the conversation unreadable — a bubble as bright as its
 * text, a backdrop that swallows both — and the one thing anybody actually wants from a per-chat
 * colour is *which* colour.
 */
fun customChatTheme(hue: Float, dark: Boolean): ChatThemeColors {
    val h = hue.mod(360f)
    return if (dark) {
        ChatThemeColors(
            // Deep and clearly coloured: the outgoing bubble is the one thing on the screen that is
            // meant to be the hue you picked.
            mine = Color.hsl(h, 0.55f, 0.32f),
            // Barely tinted. An incoming bubble has to read as "not mine" at a glance, so it keeps
            // the hue only as a cast over what is otherwise the dark grey it has always been.
            theirs = Color.hsl(h, 0.12f, 0.11f),
            // Darker than either bubble, always, or the bubbles stop being objects on a ground.
            backdrop = Color.hsl(h, 0.20f, 0.06f),
            text = Color.White,
            meta = DarkMeta,
        )
    } else {
        ChatThemeColors(
            mine = Color.hsl(h, 0.65f, 0.86f),
            theirs = Color.hsl(h, 0.15f, 0.95f),
            backdrop = Color.hsl(h, 0.40f, 0.99f),
            // Not black: the ink takes the hue too, several shades under the bubble, which is what
            // keeps a light scheme from reading as a coloured sheet with an unrelated page on it.
            text = Color.hsl(h, 0.45f, 0.10f),
            meta = LightMeta,
        )
    }
}

/** The scheme for a stored key, or `null` for `DEFAULT` — meaning "use the bubble style's own colors". */
fun chatThemeColorsFor(key: ChatThemeKey, dark: Boolean, hue: Float = ChatThemeKey.DEFAULT_HUE): ChatThemeColors? {
    if (key == ChatThemeKey.DEFAULT) return null
    if (key == ChatThemeKey.CUSTOM) return customChatTheme(hue, dark)
    val palette = ByKey[key] ?: return null
    return if (dark) palette.dark else palette.light
}

/**
 * The accent hue a theme stands for, or `null` for `DEFAULT` — read off the outgoing bubble, which
 * is the one colour in a scheme that is meant to be the hue itself.
 */
fun chatThemeHue(key: ChatThemeKey, hue: Float = ChatThemeKey.DEFAULT_HUE): Float? {
    if (key == ChatThemeKey.DEFAULT) return null
    if (key == ChatThemeKey.CUSTOM) return hue.mod(360f)
    val mine = ByKey[key]?.dark?.mine ?: return null
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(mine.toArgb(), hsv)
    return hsv[0]
}

val LocalChatTheme =compositionLocalOf<ChatThemeColors?> { null }

/**
 * An explicit per-chat scheme always outranks both the bubble style's own palette and Material You.
 * A conversation without one takes the app's — see [Settings.appTheme].
 */
@Composable
fun ChatThemeProvider(
    themeKey: ChatThemeKey,
    darkTheme: Boolean,
    hue: Float = ChatThemeKey.DEFAULT_HUE,
    content: @Composable () -> Unit,
) {
    val settings = LocalSettings.current
    val own = themeKey != ChatThemeKey.DEFAULT
    val key = if (own) themeKey else settings.appTheme
    val keyHue = if (own) hue else settings.appThemeHue
    val colors = remember(key, darkTheme, keyHue) { chatThemeColorsFor(key, darkTheme, keyHue) }
    CompositionLocalProvider(LocalChatTheme provides colors, content = content)
}
