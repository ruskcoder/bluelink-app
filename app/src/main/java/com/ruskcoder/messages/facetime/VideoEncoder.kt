package com.ruskcoder.messages.facetime

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * A hardware encoder fed through a [Surface], emitting Annex-B access units.
 *
 * Whatever draws into [inputSurface] — the camera, or a virtual display mirroring the screen — gets
 * encoded at the size it was created with. Parameter sets come out once as a config frame and are
 * then replayed ahead of every IDR, so a receiver that joined late or recreated its decoder can
 * always start from the next key frame without asking for anything else.
 */
class VideoEncoder(
    val codec: VideoCodec,
    val width: Int,
    val height: Int,
    private val frameRate: Int,
    initialBitrate: Int,
    /** Clockwise degrees the receiver should rotate by, read per frame since the device can turn. */
    private val rotation: () -> Int,
    private val onFrame: (EncodedVideoFrame) -> Unit,
) {
    private val thread = HandlerThread("VideoEncoder-${codec.name}").apply { start() }
    private val handler = Handler(thread.looper)
    private val encoder: MediaCodec = MediaCodec.createEncoderByType(codec.mime)
    private var parameterSets: ByteArray? = null
    @Volatile private var released = false

    val inputSurface: Surface

    init {
        val format = MediaFormat.createVideoFormat(codec.mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, initialBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            // Key frames on demand only; a periodic one wastes the budget a call cannot spare, and
            // the far end asks when it needs one.
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_INTERVAL_S)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            }
            // Real-time priority and no B-frames: a call has no use for reordering latency.
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LATENCY, 1)
            }
        }
        encoder.setCallback(Callback(), handler)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()
    }

    fun requestKeyFrame() {
        if (released) return
        runCatching {
            encoder.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }.onFailure { Log.w(TAG, "Key frame request failed", it) }
    }

    fun setBitrate(bitsPerSecond: Int) {
        if (released) return
        runCatching {
            encoder.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitsPerSecond) })
        }.onFailure { Log.w(TAG, "Bitrate change failed", it) }
    }

    fun release() {
        if (released) return
        released = true
        handler.post {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            inputSurface.release()
            thread.quitSafely()
        }
    }

    private inner class Callback : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit // surface input

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (released) return
            val buffer = codec.getOutputBuffer(index)
            if (buffer == null || info.size == 0) {
                codec.releaseOutputBuffer(index, false)
                return
            }
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            val data = ByteArray(info.size).also { buffer.get(it) }
            codec.releaseOutputBuffer(index, false)

            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            if (config) parameterSets = data

            val payload = if (key && !config && !startsWithParameterSets(data)) {
                parameterSets?.let { it + data } ?: data
            } else {
                data
            }
            onFrame(EncodedVideoFrame(payload, info.presentationTimeUs, key, config, width, height, rotation()))
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Encoder error", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
    }

    /** Whether the encoder already put VPS/SPS in front of this IDR (KEY_PREPEND_HEADER_TO_SYNC_FRAMES). */
    private fun startsWithParameterSets(data: ByteArray): Boolean {
        val nal = AnnexB.firstNalHeader(data) ?: return false
        return when (codec) {
            VideoCodec.HEVC -> ((nal.toInt() shr 1) and 0x3F) in 32..34 // VPS, SPS, PPS
            VideoCodec.H264 -> (nal.toInt() and 0x1F) in 7..8           // SPS, PPS
        }
    }

    companion object {
        private const val TAG = "VideoEncoder"
        private const val KEY_FRAME_INTERVAL_S = 10f

        /** HEVC when the device has a hardware encoder for it, which is what FaceTime sends. */
        fun preferredCodec(): VideoCodec =
            if (hasHardwareEncoder(VideoCodec.HEVC.mime)) VideoCodec.HEVC else VideoCodec.H264

        private fun hasHardwareEncoder(mime: String): Boolean =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder &&
                    info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || info.isHardwareAccelerated)
            }
    }
}

/** Helpers for start-code delimited streams. */
internal object AnnexB {
    /** The first byte after the first start code, which is the NAL header's leading byte. */
    fun firstNalHeader(data: ByteArray): Byte? {
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) return data[i + 3]
                if (data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) return data.getOrNull(i + 4)
            }
            i++
        }
        return null
    }
}
