package com.ruskcoder.messages.facetime

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Identifies one remote video stream: a participant's camera or their screen. */
data class StreamKey(val participant: Long, val source: VideoSource)

/** What the in-call UI draws for one remote video stream. */
data class RemoteVideo(val key: StreamKey, val width: Int, val height: Int, val rotation: Int)

data class CallUiState(
    val id: Long,
    val phase: CallPhase,
    val handles: List<String>,
    val displayName: String,
    /** Whether the call was offered or placed as a video call; decides the ringing UI. */
    val videoCall: Boolean,
    val muted: Boolean = false,
    val cameraOn: Boolean = false,
    val frontCamera: Boolean = true,
    val speaker: Boolean = false,
    val screenSharing: Boolean = false,
    val connectedAt: Long? = null,
    val participants: List<CallParticipant> = emptyList(),
    val remoteVideo: List<RemoteVideo> = emptyList(),
    val endedReason: String? = null,
)

/**
 * The one FaceTime call the app can be in, and everything wired to it.
 *
 * Owns the media pipeline for the call's lifetime: the camera and screen encoders feeding the
 * transport, the transport feeding the decoders and the speaker. The UI only reads [state] and
 * calls the actions here; the system call surfaces ([FaceTimeConnectionService], [CallService])
 * do the same.
 */
object CallManager {

    private const val TAG = "CallManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var appContext: Context

    /** Set once the Rust FaceTime surface is wired in; until then calls cannot be placed. */
    @Volatile var backend: FaceTimeBackend? = null

    private val _state = MutableStateFlow<CallUiState?>(null)
    val state: StateFlow<CallUiState?> = _state.asStateFlow()

    private var call: ActiveCall? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ---- entry points -------------------------------------------------------------------------

    /** The protocol side announces a ringing incoming call. */
    fun onIncomingCall(transport: CallTransport, displayName: String, video: Boolean) {
        if (call != null) {
            // Busy: one call at a time, the way a phone line behaves.
            transport.decline()
            return
        }
        val active = ActiveCall(transport, wantVideo = false)
        call = active
        _state.value = CallUiState(
            id = SystemClock.elapsedRealtime(),
            phase = CallPhase.RINGING_IN,
            handles = transport.remoteHandles,
            displayName = displayName,
            videoCall = video,
        )
        transport.setSink(active)
        CallTelecom.reportIncoming(appContext, displayName, transport.remoteHandles.firstOrNull(), video)
        CallService.start(appContext)
    }

    /** Start an outgoing call. Fails into an ended state when FaceTime isn't available. */
    fun placeCall(handles: List<String>, displayName: String, video: Boolean) {
        if (call != null) return
        _state.value = CallUiState(
            id = SystemClock.elapsedRealtime(),
            phase = CallPhase.RINGING_OUT,
            handles = handles,
            displayName = displayName,
            videoCall = video,
            cameraOn = video,
        )
        CallService.start(appContext)
        openCallScreen()
        val backend = backend ?: run {
            finish("FaceTime isn't available on this device yet")
            return
        }
        scope.launch {
            val transport = runCatching { backend.placeCall(handles, video) }.getOrElse {
                Log.e(TAG, "placeCall failed", it)
                finish(it.message ?: "Call failed")
                return@launch
            }
            val active = ActiveCall(transport, wantVideo = video)
            call = active
            transport.setSink(active)
            CallTelecom.reportOutgoing(appContext, displayName, handles.firstOrNull(), video)
            if (video) active.startCamera()
        }
    }

    // ---- actions ------------------------------------------------------------------------------

    fun accept(video: Boolean) {
        val active = call ?: return
        active.wantVideo = video
        active.transport.accept(video)
        _state.update { it?.copy(phase = CallPhase.CONNECTING, cameraOn = video) }
        CallTelecom.markActive()
        if (video) active.startCamera()
    }

    fun decline() {
        call?.transport?.decline()
        finish(null)
    }

    fun hangUp() {
        call?.transport?.hangUp()
        finish(null)
    }

    fun setMuted(muted: Boolean) {
        val active = call ?: return
        active.audio.muted = muted
        active.transport.setMuted(muted)
        _state.update { it?.copy(muted = muted) }
    }

    fun setCameraOn(on: Boolean) {
        val active = call ?: return
        active.wantVideo = on
        if (on) active.startCamera() else active.stopCamera()
        active.transport.setVideoEnabled(on)
        _state.update { it?.copy(cameraOn = on) }
    }

    fun flipCamera() {
        val active = call ?: return
        val front = !(state.value?.frontCamera ?: true)
        _state.update { it?.copy(frontCamera = front) }
        if (state.value?.cameraOn == true) active.startCamera()
    }

    fun setSpeaker(on: Boolean) {
        call?.audio?.setSpeaker(on) ?: return
        _state.update { it?.copy(speaker = on) }
    }

    /** Called with the MediaProjection consent result once [CallService] holds the projection type. */
    fun startScreenShare(resultCode: Int, data: Intent) {
        val active = call ?: return
        if (active.startScreen(resultCode, data)) {
            active.transport.setScreenEnabled(true)
            _state.update { it?.copy(screenSharing = true) }
        }
    }

    fun stopScreenShare() {
        val active = call ?: return
        active.stopScreen()
        active.transport.setScreenEnabled(false)
        _state.update { it?.copy(screenSharing = false) }
        CallService.demoteFromScreenShare(appContext)
    }

    // ---- surfaces from the UI -----------------------------------------------------------------

    fun attachLocalPreview(surface: Surface?) {
        val active = call ?: return
        active.preview = surface
        active.camera.setPreview(surface)
    }

    fun attachRemoteSurface(key: StreamKey, surface: Surface?) {
        call?.attachRemote(key, surface)
    }

    fun localCaptureSize() = call?.camera?.captureSize(state.value?.frontCamera ?: true)

    // ---- internals ----------------------------------------------------------------------------

    private fun finish(reason: String?) {
        val active = call
        call = null
        active?.release()
        _state.update { it?.copy(phase = CallPhase.ENDED, endedReason = reason) }
        CallTelecom.markEnded(reason != null)
        CallService.stop(appContext)
        // Leave the ended state up long enough to read, then clear it.
        scope.launch {
            kotlinx.coroutines.delay(if (reason != null) 2_500 else 800)
            if (call == null) _state.value = null
        }
    }

    private fun openCallScreen() {
        appContext.startActivity(CallActivity.intent(appContext).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private class RemoteStream(
        var codec: VideoCodec? = null,
        var parameterSets: ByteArray? = null,
        var width: Int = 0,
        var height: Int = 0,
        var surface: Surface? = null,
        var decoder: VideoDecoder? = null,
    )

    private class ActiveCall(val transport: CallTransport, @Volatile var wantVideo: Boolean) : CallMediaSink {

        val camera = CameraCapture(appContext)
        val audio = CallAudio(appContext) { payload, packet, samples -> transport.sendAudio(payload, packet, samples) }
        private val screen = ScreenCapture(appContext) { stopScreenShare() }

        var preview: Surface? = null
        private var cameraEncoder: VideoEncoder? = null
        private var screenEncoder: VideoEncoder? = null
        private val remote = HashMap<StreamKey, RemoteStream>()
        private val rotations = java.util.concurrent.ConcurrentHashMap<Long, Int>()
        private var mediaStarted = false

        fun startCamera() {
            val front = _state.value?.frontCamera ?: true
            val size = camera.captureSize(front)
            cameraEncoder?.release()
            val encoder = VideoEncoder(
                codec = VideoEncoder.preferredCodec(),
                width = size.width,
                height = size.height,
                frameRate = CameraCapture.FRAME_RATE,
                initialBitrate = 1_000_000,
                rotation = { camera.rotationDegrees() },
            ) { frame -> transport.sendVideo(VideoSource.CAMERA, frame) }
            cameraEncoder = encoder
            camera.start(front, encoder.inputSurface, preview)
        }

        fun stopCamera() {
            camera.stop()
            cameraEncoder?.release()
            cameraEncoder = null
        }

        fun startScreen(resultCode: Int, data: Intent): Boolean {
            val size = screen.captureSize()
            screenEncoder?.release()
            val encoder = VideoEncoder(
                codec = VideoEncoder.preferredCodec(),
                width = size.width,
                height = size.height,
                frameRate = ScreenCapture.FRAME_RATE,
                initialBitrate = 2_000_000,
                rotation = { 0 },
            ) { frame -> transport.sendVideo(VideoSource.SCREEN, frame) }
            screenEncoder = encoder
            if (!screen.start(resultCode, data, encoder.inputSurface, size)) {
                encoder.release()
                screenEncoder = null
                return false
            }
            return true
        }

        fun stopScreen() {
            screen.stop()
            screenEncoder?.release()
            screenEncoder = null
        }

        @Synchronized
        fun attachRemote(key: StreamKey, surface: Surface?) {
            val stream = remote.getOrPut(key) { RemoteStream() }
            if (stream.surface == surface) return
            stream.decoder?.release()
            stream.decoder = null
            stream.surface = surface
            if (surface != null) configureDecoder(key, stream)
        }

        private fun configureDecoder(key: StreamKey, stream: RemoteStream) {
            val surface = stream.surface ?: return
            val codec = stream.codec ?: return
            val sets = stream.parameterSets ?: return
            val decoder = stream.decoder ?: VideoDecoder(surface) {
                transport.requestRemoteKeyFrame(key.participant, key.source)
            }.also { stream.decoder = it }
            decoder.configure(codec, sets, stream.width, stream.height)
        }

        private fun startMedia() {
            if (mediaStarted) return
            mediaStarted = true
            audio.enterCallMode()
            audio.startCapture(transport.sendAudioPayload)
            if (wantVideo && cameraEncoder == null) startCamera()
        }

        fun release() {
            stopScreen()
            stopCamera()
            camera.release()
            audio.release()
            if (mediaStarted) audio.exitCallMode()
            synchronized(this) {
                remote.values.forEach { it.decoder?.release() }
                remote.clear()
            }
            transport.setSink(null)
        }

        // ---- CallMediaSink ----

        override fun onStateChanged(state: CallPhase) {
            when (state) {
                CallPhase.CONNECTED -> {
                    startMedia()
                    CallTelecom.markActive()
                    _state.update { it?.copy(phase = CallPhase.CONNECTED, connectedAt = it.connectedAt ?: SystemClock.elapsedRealtime()) }
                }
                CallPhase.ENDED -> finish(null)
                else -> _state.update { it?.copy(phase = state) }
            }
        }

        override fun onParticipantsChanged(participants: List<CallParticipant>) {
            val present = participants.map { it.id }.toSet()
            synchronized(this) {
                remote.keys.filter { it.participant !in present }.forEach { key ->
                    remote.remove(key)?.decoder?.release()
                }
            }
            rotations.keys.retainAll(present)
            _state.value?.participants?.map { it.id }?.filter { it !in present }?.forEach(audio::removeParticipant)
            _state.update { s ->
                s?.copy(
                    participants = participants,
                    remoteVideo = s.remoteVideo.filter { it.key.participant in present },
                )
            }
        }

        override fun onVideoConfig(participant: Long, source: VideoSource, codec: VideoCodec, parameterSets: ByteArray, width: Int, height: Int) {
            val key = StreamKey(participant, source)
            synchronized(this) {
                val stream = remote.getOrPut(key) { RemoteStream() }
                stream.codec = codec
                stream.parameterSets = parameterSets
                stream.width = width
                stream.height = height
                configureDecoder(key, stream)
            }
            publishRemote(key, width, height)
        }

        override fun onVideoSample(participant: Long, source: VideoSource, data: ByteArray, ptsUs: Long) {
            val (decoder, codec) = synchronized(this) {
                val stream = remote[StreamKey(participant, source)] ?: return
                (stream.decoder ?: return) to stream.codec
            }
            decoder.decode(data, ptsUs, isKeyFrame(data, codec))
        }

        override fun onVideoRotation(participant: Long, degrees: Int) {
            rotations[participant] = degrees
            _state.update { s ->
                s?.copy(remoteVideo = s.remoteVideo.map {
                    if (it.key.participant == participant && it.key.source == VideoSource.CAMERA) it.copy(rotation = degrees) else it
                })
            }
        }

        override fun onAudioFrame(participant: Long, payload: AudioPayload, frame: ByteArray) {
            audio.play(participant, payload, frame)
        }

        override fun onKeyFrameRequested(source: VideoSource) {
            when (source) {
                VideoSource.CAMERA -> cameraEncoder?.requestKeyFrame()
                VideoSource.SCREEN -> screenEncoder?.requestKeyFrame()
            }
        }

        override fun onTargetBitrate(source: VideoSource, bitsPerSecond: Int) {
            when (source) {
                VideoSource.CAMERA -> cameraEncoder?.setBitrate(bitsPerSecond)
                VideoSource.SCREEN -> screenEncoder?.setBitrate(bitsPerSecond)
            }
        }

        override fun onEnded(reason: String?) = finish(reason)

        private fun publishRemote(key: StreamKey, width: Int, height: Int) {
            val rotation = if (key.source == VideoSource.CAMERA) rotations[key.participant] ?: 0 else 0
            val entry = RemoteVideo(key, width, height, rotation)
            _state.update { s -> s?.copy(remoteVideo = s.remoteVideo.filter { it.key != key } + entry) }
        }

        private fun isKeyFrame(data: ByteArray, codec: VideoCodec?): Boolean {
            // Walk the NAL units; an IDR/IRAP anywhere in the access unit makes it a key frame.
            var i = 0
            while (i + 3 < data.size) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                    val header = data[i + 3].toInt()
                    val hevcType = (header shr 1) and 0x3F
                    val avcType = header and 0x1F
                    if (codec == VideoCodec.H264 && avcType == 5) return true
                    if (codec != VideoCodec.H264 && hevcType in 16..21) return true
                    i += 3
                } else {
                    i++
                }
            }
            return false
        }
    }
}
