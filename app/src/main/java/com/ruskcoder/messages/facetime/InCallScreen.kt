package com.ruskcoder.messages.facetime

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.automirrored.rounded.ScreenShare
import androidx.compose.material.icons.automirrored.rounded.StopScreenShare
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ruskcoder.messages.ui.components.Avatar
import kotlinx.coroutines.delay

private val CallBackground = Color(0xFF111114)
private val ControlFill = Color(0x33FFFFFF)
private val ControlFillOn = Color(0xFFF2F2F7)
private val EndRed = Color(0xFFFF3B30)
private val AcceptGreen = Color(0xFF34C759)
private val NeutralAvatar = Color(0xFF8E8E93)

@Composable
fun InCallScreen(
    state: CallUiState,
    onAccept: (video: Boolean) -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onMute: (Boolean) -> Unit,
    onCamera: (Boolean) -> Unit,
    onFlip: () -> Unit,
    onSpeaker: (Boolean) -> Unit,
    onScreenShare: (Boolean) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(CallBackground)) {
        val main = state.remoteVideo.firstOrNull { it.key.source == VideoSource.SCREEN }
            ?: state.remoteVideo.firstOrNull()

        if (main != null && state.phase == CallPhase.CONNECTED) {
            RemoteVideoView(main, crop = main.key.source == VideoSource.CAMERA, modifier = Modifier.fillMaxSize())
        } else {
            CallerHeader(state, Modifier.align(Alignment.Center))
        }

        if (state.cameraOn && state.phase != CallPhase.RINGING_IN) {
            LocalPreview(
                front = state.frontCamera,
                modifier = Modifier
                    .safeDrawingPadding()
                    .padding(16.dp)
                    .size(width = 108.dp, height = 168.dp)
                    .align(Alignment.TopEnd),
            )
        }

        // Other people's streams beside the main one, for group calls and camera-plus-screen.
        val others = state.remoteVideo.filter { it != main }
        if (others.isNotEmpty() && state.phase == CallPhase.CONNECTED) {
            Row(
                Modifier.safeDrawingPadding().padding(16.dp).align(Alignment.TopStart),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                others.take(3).forEach {
                    RemoteVideoView(it, crop = true, modifier = Modifier.size(width = 90.dp, height = 140.dp).clip(RoundedCornerShape(14.dp)))
                }
            }
        }

        if (main != null && state.phase == CallPhase.CONNECTED) {
            Text(
                text = state.displayName,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.safeDrawingPadding().padding(top = 20.dp).align(Alignment.TopCenter),
            )
        }

        Box(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 32.dp)) {
            if (state.phase == CallPhase.RINGING_IN) {
                IncomingControls(state.videoCall, onAccept, onDecline)
            } else if (state.phase != CallPhase.ENDED) {
                InCallControls(state, onHangUp, onMute, onCamera, onFlip, onSpeaker, onScreenShare)
            }
        }
    }
}

@Composable
private fun CallerHeader(state: CallUiState, modifier: Modifier) {
    Column(modifier.padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(initials = initials(state.displayName), color = NeutralAvatar, size = 112.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            state.displayName,
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(statusLine(state), color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun statusLine(state: CallUiState): String {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.connectedAt) {
        while (state.connectedAt != null) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val kind = if (state.videoCall) "FaceTime Video" else "FaceTime Audio"
    return when (state.phase) {
        CallPhase.RINGING_IN -> kind
        CallPhase.RINGING_OUT -> "Calling…"
        CallPhase.CONNECTING -> "Connecting…"
        CallPhase.CONNECTED -> state.connectedAt?.let { elapsed((now - it) / 1000) } ?: kind
        CallPhase.ENDED -> state.endedReason ?: "Call ended"
    }
}

@Composable
private fun IncomingControls(video: Boolean, onAccept: (Boolean) -> Unit, onDecline: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        LabeledButton("Decline", Icons.Rounded.CallEnd, EndRed, Color.White, onClick = onDecline)
        if (video) {
            LabeledButton("Audio", Icons.Rounded.Call, ControlFill, Color.White) { onAccept(false) }
        }
        LabeledButton(
            "Accept",
            if (video) Icons.Rounded.Videocam else Icons.Rounded.Call,
            AcceptGreen,
            Color.White,
        ) { onAccept(video) }
    }
}

@Composable
private fun InCallControls(
    state: CallUiState,
    onHangUp: () -> Unit,
    onMute: (Boolean) -> Unit,
    onCamera: (Boolean) -> Unit,
    onFlip: () -> Unit,
    onSpeaker: (Boolean) -> Unit,
    onScreenShare: (Boolean) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ToggleButton(if (state.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, state.muted, "Mute") { onMute(!state.muted) }
            ToggleButton(if (state.cameraOn) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff, state.cameraOn, "Camera") { onCamera(!state.cameraOn) }
            if (state.cameraOn) ToggleButton(Icons.Rounded.Cameraswitch, false, "Flip camera", onFlip)
            ToggleButton(Icons.AutoMirrored.Rounded.VolumeUp, state.speaker, "Speaker") { onSpeaker(!state.speaker) }
            ToggleButton(
                if (state.screenSharing) Icons.AutoMirrored.Rounded.StopScreenShare else Icons.AutoMirrored.Rounded.ScreenShare,
                state.screenSharing,
                "Share screen",
            ) { onScreenShare(!state.screenSharing) }
        }
        Spacer(Modifier.height(20.dp))
        RoundButton(Icons.Rounded.CallEnd, EndRed, Color.White, "End", size = 68, onClick = onHangUp)
    }
}

@Composable
private fun ToggleButton(icon: ImageVector, on: Boolean, label: String, onClick: () -> Unit) {
    RoundButton(icon, if (on) ControlFillOn else ControlFill, if (on) Color.Black else Color.White, label, size = 56, onClick = onClick)
}

@Composable
private fun LabeledButton(label: String, icon: ImageVector, fill: Color, tint: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RoundButton(icon, fill, tint, label, size = 72, onClick = onClick)
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun RoundButton(icon: ImageVector, fill: Color, tint: Color, label: String, size: Int, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(fill).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size((size * 0.45f).dp))
    }
}

/** One remote stream, decoded straight onto a TextureView and turned upright by a transform. */
@Composable
private fun RemoteVideoView(video: RemoteVideo, crop: Boolean, modifier: Modifier) {
    VideoTexture(
        modifier = modifier,
        key = video.key,
        onSurface = { CallManager.attachRemoteSurface(video.key, it) },
        transform = { view -> fitTransform(view, video.width, video.height, video.rotation, mirror = false, crop = crop) },
    )
}

@Composable
private fun LocalPreview(front: Boolean, modifier: Modifier) {
    val size = remember(front) { CallManager.localCaptureSize() }
    VideoTexture(
        modifier = modifier.clip(RoundedCornerShape(16.dp)),
        key = front,
        bufferSize = size?.let { it.width to it.height },
        onSurface = CallManager::attachLocalPreview,
        // Camera2 already turns the preview upright (and mirrors the front camera) for the device's
        // natural orientation, so only the aspect is left: a landscape sensor shown portrait.
        transform = { view -> size?.let { fitTransform(view, it.height, it.width, 0, mirror = false, crop = true) } },
    )
}

@Composable
private fun VideoTexture(
    modifier: Modifier,
    key: Any,
    onSurface: (Surface?) -> Unit,
    transform: (TextureView) -> Matrix?,
    bufferSize: Pair<Int, Int>? = null,
) {
    val holder = remember(key) { arrayOfNulls<Surface>(1) }
    DisposableEffect(key) {
        onDispose {
            onSurface(null)
            holder[0]?.release()
            holder[0] = null
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        bufferSize?.let { (w, h) -> texture.setDefaultBufferSize(w, h) }
                        val surface = Surface(texture)
                        holder[0] = surface
                        transform(this@apply)?.let(::setTransform)
                        onSurface(surface)
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                        transform(this@apply)?.let(::setTransform)
                    }

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        onSurface(null)
                        holder[0]?.release()
                        holder[0] = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        },
        update = { view -> if (view.isAvailable) transform(view)?.let(view::setTransform) },
    )
}

/**
 * Maps a [width]×[height] picture, which TextureView stretches over its bounds, to one shown
 * upright after [rotation] clockwise degrees at its own aspect: filling the view when [crop],
 * letterboxed otherwise.
 */
private fun fitTransform(view: TextureView, width: Int, height: Int, rotation: Int, mirror: Boolean, crop: Boolean): Matrix? {
    val viewW = view.width.toFloat()
    val viewH = view.height.toFloat()
    if (viewW == 0f || viewH == 0f || width == 0 || height == 0) return null
    val turned = rotation % 180 != 0
    val shownW = if (turned) height.toFloat() else width.toFloat()
    val shownH = if (turned) width.toFloat() else height.toFloat()
    val scale = if (crop) maxOf(viewW / shownW, viewH / shownH) else minOf(viewW / shownW, viewH / shownH)
    val cx = viewW / 2
    val cy = viewH / 2
    return Matrix().apply {
        setScale(width * scale / viewW, height * scale / viewH, cx, cy)
        postRotate(rotation.toFloat(), cx, cy)
        if (mirror) postScale(-1f, 1f, cx, cy)
    }
}

private fun initials(name: String): String =
    name.split(' ').filter { it.firstOrNull()?.isLetter() == true }.take(2).joinToString("") { it.first().uppercase() }
        .ifEmpty { name.take(1) }

private fun elapsed(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
