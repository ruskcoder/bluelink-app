package com.ruskcoder.messages.ui.components

import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Draw a [RemoteViews] tree another app built.
 *
 * This is how a Madrid extension shows its own UI inside this one: OpenPigeon composes its game
 * picker and its bubble previews with Glance and hands them over as RemoteViews, the same way a
 * widget reaches a launcher. The layout and every resource in it resolve against *its* package,
 * and the click handlers are PendingIntents that fire in *its* process — so what is drawn here is
 * genuinely the extension's screen, not a copy of it. Nothing about any particular game needs to
 * be known here, which is the point.
 *
 * Inflation is allowed to fail: a RemoteViews from a version of the extension this app does not
 * understand should leave a blank panel, not take the conversation down with it.
 */
@Composable
fun RemoteViewsHost(views: RemoteViews?, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        update = { frame ->
            frame.removeAllViews()
            val tree = views ?: return@AndroidView
            runCatching { tree.apply(frame.context.applicationContext, frame) }
                .onSuccess { frame.addView(it) }
                .onFailure { frame.removeAllViews() }
        },
    )
}
