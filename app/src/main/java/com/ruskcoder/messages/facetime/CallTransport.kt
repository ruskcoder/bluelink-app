package com.ruskcoder.messages.facetime

/**
 * The seam between the Android call stack and the FaceTime protocol.
 *
 * Everything on this side of it is platform work: capturing the camera and the screen, encoding and
 * decoding, the microphone and the speaker, the system call UI. Everything on the other side is
 * rustpush's `facetime` and `avconference` modules, which own signaling, the QuickRelay link, SRTP,
 * FEC and rate control. Media crosses as *encoded* frames in both directions — the Rust side
 * packetizes and depacketizes, it never touches pixels or PCM.
 *
 * Video is Annex-B throughout (start-code delimited NAL units), which is what `MediaCodec` emits
 * and accepts and what `avconference`'s `VideoSender` consumes. Parameter sets travel as their own
 * frame so a decoder can be (re)configured before the first picture arrives.
 */
interface CallTransport {

    /** Who the call is with, as the protocol names them (a handle such as `tel:` or `mailto:`). */
    val remoteHandles: List<String>

    fun setSink(sink: CallMediaSink?)

    /** Answer a ringing incoming call. */
    fun accept(video: Boolean)

    /** Turn down a ringing incoming call without answering it. */
    fun decline()

    /** Leave the call, outgoing ringing included. */
    fun hangUp()

    fun setMuted(muted: Boolean)

    /** Start or stop publishing the camera stream. */
    fun setVideoEnabled(enabled: Boolean)

    /** Start or stop publishing a screen-share stream, which is separate from the camera. */
    fun setScreenEnabled(enabled: Boolean)

    /** One encoded video access unit, or a parameter-set frame when [EncodedVideoFrame.config]. */
    fun sendVideo(source: VideoSource, frame: EncodedVideoFrame)

    /**
     * One encoded audio frame. [samples] counts PCM samples at the payload's [AudioPayload.sampleRate]
     * since capture started; converting that to the stream's RTP clock is the protocol side's job.
     */
    fun sendAudio(payload: AudioPayload, frame: ByteArray, samples: Long)

    /** Ask one remote stream for an IDR, after a decoder was (re)created mid-stream or lost data. */
    fun requestRemoteKeyFrame(participant: Long, source: VideoSource)

    /** The audio payload this transport sends with, chosen by the protocol side. */
    val sendAudioPayload: AudioPayload
}

/** What the protocol side reports back. Called from protocol threads; implementations must not block. */
interface CallMediaSink {
    fun onStateChanged(state: CallPhase)
    fun onParticipantsChanged(participants: List<CallParticipant>)

    /** New decoder configuration for one remote stream: VPS/SPS/PPS (or SPS/PPS) as Annex-B. */
    fun onVideoConfig(participant: Long, source: VideoSource, codec: VideoCodec, parameterSets: ByteArray, width: Int, height: Int)

    /** One complete Annex-B access unit for a remote stream. */
    fun onVideoSample(participant: Long, source: VideoSource, data: ByteArray, ptsUs: Long)

    /** The sender's camera orientation, clockwise degrees (0, 90, 180, 270). */
    fun onVideoRotation(participant: Long, degrees: Int)

    fun onAudioFrame(participant: Long, payload: AudioPayload, frame: ByteArray)

    /** The remote side lost a picture and needs an IDR to recover. */
    fun onKeyFrameRequested(source: VideoSource)

    /** Rate control moved the target for one of our streams. */
    fun onTargetBitrate(source: VideoSource, bitsPerSecond: Int)

    fun onEnded(reason: String?)
}

/** Places outgoing calls and announces incoming ones; implemented over the Rust session. */
interface FaceTimeBackend {
    suspend fun placeCall(handles: List<String>, video: Boolean): CallTransport
}

enum class CallPhase { RINGING_IN, RINGING_OUT, CONNECTING, CONNECTED, ENDED }

enum class VideoSource { CAMERA, SCREEN }

enum class VideoCodec(val mime: String) {
    HEVC("video/hevc"),
    H264("video/avc"),
}

/**
 * Audio payloads FaceTime negotiates, by RTP payload type. EVS is what Apple sends by default; the
 * AAC variants are AAC-ELD at 24kHz in 20ms packets and 48kHz in 10ms packets.
 *
 * [sampleRate] is the PCM rate the codec runs at, not the RTP clock: the microphone stream's RTP
 * clock is 24kHz whatever the codec (`StreamId::clock_rate` in avconference). The EVS bandwidth
 * Apple uses is not confirmed; wideband is the assumption until a capture says otherwise.
 */
enum class AudioPayload(val rtpType: Int, val sampleRate: Int, val frameMs: Int) {
    EVS(108, 16_000, 20),
    AAC_ELD(104, 24_000, 20),
    AAC_ELD_48(101, 48_000, 10);

    val samplesPerFrame: Int get() = sampleRate * frameMs / 1000

    companion object {
        fun fromRtp(type: Int): AudioPayload? = entries.firstOrNull { it.rtpType == type }
    }
}

class CallParticipant(
    val id: Long,
    val handle: String,
    val videoOn: Boolean,
    val screenSharing: Boolean,
    val muted: Boolean,
)

class EncodedVideoFrame(
    val data: ByteArray,
    val ptsUs: Long,
    val keyFrame: Boolean,
    val config: Boolean,
    val width: Int,
    val height: Int,
    /** Clockwise degrees the receiver should rotate the picture by to display it upright. */
    val rotation: Int,
)
