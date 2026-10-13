package com.ruskcoder.messages.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape

/** How dark the bar's film is with something behind it to frost, and without. */
private const val FILM_OVER_BACKDROP = 0.28f
private const val FILM_ALONE = 0.5f

/**
 * The app's bar: frosted glass over whatever it is sitting on.
 *
 * There used to be a setting choosing between this and a Material surface, which meant every bar
 * had to be able to draw either, and half the app's chrome was written twice. There is one bar now.
 *
 * The blur sits on its own empty layer *behind* the bar rather than in the chain above it, which is
 * what keeps the title and the icons sharp while the conversation behind them is frosted. Where
 * there is no recording to sample, the film alone carries the look and is darkened to compensate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyledTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    /** What the bar frosts. Null falls back to the flat film. */
    backdrop: ChatBackdrop? = LocalChatBackdrop.current,
) {
    Box(modifier = modifier) {
        if (backdrop != null) {
            Box(modifier = Modifier.matchParentSize().chatBackdropBlur(backdrop, RectangleShape))
        }
        TopAppBar(
            title = title,
            navigationIcon = navigationIcon,
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = barFilm(backdrop),
                titleContentColor = Color.White,
                navigationIconContentColor = Color.White,
                actionIconContentColor = Color.White,
            ),
        )
    }
}

/** A scrim the bar's own film sits over, so a bar with nothing behind it still reads as glass. */
@Composable
fun barFilm(backdrop: ChatBackdrop?): Color =
    Color.Black.copy(alpha = if (backdrop != null) FILM_OVER_BACKDROP else FILM_ALONE)
