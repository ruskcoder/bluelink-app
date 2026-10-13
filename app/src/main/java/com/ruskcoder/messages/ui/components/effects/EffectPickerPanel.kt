package com.ruskcoder.messages.ui.components.effects

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ruskcoder.messages.data.model.MessageEffect
import com.ruskcoder.messages.ui.components.ControlBlur
import com.ruskcoder.messages.ui.components.ControlTint
import com.ruskcoder.messages.ui.components.frostedFill

/**
 * The send effects, in the slot the attachment sheet uses.
 *
 * Two pages rather than one list, because there are two kinds and they behave differently — see
 * [com.ruskcoder.messages.data.model.EffectKind]. A bubble effect changes how one message lands and
 * is previewed on the message itself; a screen effect takes over the conversation and is previewed
 * by playing it. Mixing twelve buttons into one grid would have said they were all the same thing.
 *
 * Every button is a toggle: pressing one arms it *and* plays it, pressing it again disarms. Playing
 * on selection is the whole reason the picker is worth having — nobody knows what "gentle" or
 * "celebration" looks like from the word, and the send button is a poor place to find out.
 */
@Composable
fun EffectPickerPanel(
    /** The armed effect, or null. */
    selected: MessageEffect?,
    /** Arms one and plays it, or disarms when the armed one is pressed again. */
    onSelect: (MessageEffect?) -> Unit,
    surface: Color,
    height: Dp = 232.dp,
    modifier: Modifier = Modifier,
) {
    val pager = rememberPagerState(pageCount = { 2 })
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .padding(PanelInset)
            .frostedFill(PanelShape, tint = surface.copy(alpha = PanelSurfaceTint))
            .padding(PanelPadding),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pager,
                // Daylight between the pages. Without it the last button of one page and the first
                // of the next slide past each other touching, which reads as one long grid being
                // dragged rather than as two pages changing over.
                pageSpacing = PanelGap * 2,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) { page ->
                // Two across on both pages, which makes the first two rows deep and the second
                // four. Every button is the same width and the second page's are half the height —
                // eight names need less room each than four do, and a button twice as tall as it
                // needs to be is a button with a hole in it.
                //
                // Both pages still fill the same card, so sliding between them moves nothing but
                // the buttons.
                val effects = if (page == 0) MessageEffect.bubble else MessageEffect.screen
                val columns = 2
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(PanelGap),
                ) {
                    effects.chunked(columns).forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(PanelGap),
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        ) {
                            row.forEach { effect ->
                                EffectPill(
                                    effect = effect,
                                    on = selected == effect,
                                    onClick = { onSelect(if (selected == effect) null else effect) },
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                            // A short last row keeps its buttons the size of every other one.
                            repeat(columns - row.size) { Box(modifier = Modifier.weight(1f)) }
                        }
                    }
                }
            }
            // Which of the two pages you are on. Two dots is the smallest thing that says "there is
            // more this way" — without it the second page is a gesture nobody knows to make.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = PanelGap),
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(2) { page ->
                    val here = pager.currentPage == page
                    val alpha by animateFloatAsState(if (here) 0.9f else 0.3f, tween(180), label = "pageDot")
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(DotSize)
                            .clip(CircleShape)
                            .frostedFill(CircleShape, blur = ControlBlur, tint = Color.White.copy(alpha = alpha * 0.5f)),
                    )
                }
            }
        }
    }
}

/** The same card the attachment sheet and the formatting controls are built on. */
private val PanelInset = 8.dp
private val PanelShape = RoundedCornerShape(26.dp)
private const val PanelSurfaceTint = 0.55f
private val PanelPadding = 14.dp
private val PanelGap = 10.dp
private val PillShape = RoundedCornerShape(percent = 35)
private val DotSize = 6.dp

/** How much the film under a button brightens once its effect is armed. */
private val PillOnTint = Color.White.copy(alpha = 0.24f)

@Composable
private fun EffectPill(
    effect: MessageEffect,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .frostedFill(PillShape, blur = ControlBlur, tint = if (on) PillOnTint else ControlTint)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            effect.label,
            color = Color.White,
            fontSize = 13.sp,
            // The armed one is the only thing on the card that is doing anything, and the film
            // behind it is a subtle difference at a glance — the weight is what carries it.
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
