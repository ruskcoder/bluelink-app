package com.ruskcoder.messages.facetime

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Surface

/**
 * Mirrors the screen into an encoder through a virtual display.
 *
 * The consent itself has to come from an activity ([MediaProjectionManager.createScreenCaptureIntent]),
 * and on Android 14+ the call's foreground service must already be running with the
 * `mediaProjection` type before [start] is called with the result — see [CallService.promoteForScreenShare].
 */
class ScreenCapture(private val context: Context, private val onStopped: () -> Unit) {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null

    /** The capture size: the screen's aspect ratio, scaled so its long edge is at most [MAX_EDGE]. */
    fun captureSize(): Size {
        val metrics = context.resources.displayMetrics
        val (w, h) = metrics.widthPixels to metrics.heightPixels
        val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(w, h))
        // Encoders want even dimensions, and many want multiples of 16.
        fun align(v: Float) = (v.toInt() / 16).coerceAtLeast(1) * 16
        return Size(align(w * scale), align(h * scale))
    }

    fun start(resultCode: Int, data: Intent, encoder: Surface, size: Size): Boolean {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val created = manager.getMediaProjection(resultCode, data) ?: return false
        // Required before createVirtualDisplay on Android 14, and the only notice we get when the
        // user stops sharing from the status bar chip.
        created.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                display?.release()
                display = null
                projection = null
                onStopped()
            }
        }, Handler(Looper.getMainLooper()))
        projection = created
        display = created.createVirtualDisplay(
            "FaceTimeScreenShare",
            size.width,
            size.height,
            context.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            encoder,
            null,
            null,
        )
        return display != null
    }

    fun stop() {
        display?.release()
        display = null
        projection?.stop()
        projection = null
    }

    companion object {
        const val MAX_EDGE = 1920
        const val FRAME_RATE = 15
    }
}
