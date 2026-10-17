package com.ruskcoder.messages.facetime

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.ArrayDeque

/**
 * Decodes one remote video stream straight onto a [Surface].
 *
 * Configured from the parameter sets the protocol side reports, which are fed in as a codec-config
 * buffer rather than as `csd-*` keys: that one path works for HEVC and H.264 alike. A new
 * configuration (a resolution change, a codec switch, a camera flip on the far end) tears the codec
 * down and builds a fresh one, after which nothing is decodable until the next key frame.
 */
class VideoDecoder(private val surface: Surface, private val onNeedKeyFrame: () -> Unit) {

    private class Pending(val data: ByteArray, val ptsUs: Long, val flags: Int)

    private val thread = HandlerThread("VideoDecoder").apply { start() }
    private val handler = Handler(thread.looper)

    // Touched only on [thread].
    private var decoder: MediaCodec? = null
    private var configuredFor: Triple<VideoCodec, Int, Int>? = null
    private var awaitingKeyFrame = true
    private val pending = ArrayDeque<Pending>()
    private val freeInputs = ArrayDeque<Int>()

    fun configure(codec: VideoCodec, parameterSets: ByteArray, width: Int, height: Int) = handler.post {
        val wanted = Triple(codec, width, height)
        if (decoder != null && configuredFor == wanted) {
            // Same stream shape; only the parameter sets may have moved, and they can go in-band.
            enqueue(Pending(parameterSets, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG))
            return@post
        }
        teardown()
        try {
            val format = MediaFormat.createVideoFormat(codec.mime, width, height).apply {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            val created = MediaCodec.createDecoderByType(codec.mime)
            created.setCallback(Callback(created), handler)
            created.configure(format, surface, null, 0)
            created.start()
            decoder = created
            configuredFor = wanted
            awaitingKeyFrame = true
            enqueue(Pending(parameterSets, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG))
            onNeedKeyFrame()
        } catch (e: Exception) {
            Log.e(TAG, "Decoder configure failed for $codec ${width}x$height", e)
            teardown()
        }
    }

    fun decode(data: ByteArray, ptsUs: Long, keyFrame: Boolean) = handler.post {
        if (decoder == null) return@post
        if (awaitingKeyFrame) {
            if (!keyFrame) return@post
            awaitingKeyFrame = false
        }
        // A decoder that falls this far behind is showing the past; drop to the next key frame.
        if (pending.size > MAX_PENDING) {
            pending.clear()
            awaitingKeyFrame = !keyFrame
            if (awaitingKeyFrame) {
                onNeedKeyFrame()
                return@post
            }
        }
        enqueue(Pending(data, ptsUs, if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0))
    }

    fun release() = handler.post {
        teardown()
        thread.quitSafely()
    }

    private fun enqueue(item: Pending) {
        pending.addLast(item)
        drain()
    }

    private fun drain() {
        val codec = decoder ?: return
        while (pending.isNotEmpty() && freeInputs.isNotEmpty()) {
            val index = freeInputs.removeFirst()
            val item = pending.removeFirst()
            try {
                val buffer = codec.getInputBuffer(index) ?: continue
                buffer.clear()
                if (item.data.size > buffer.capacity()) {
                    Log.w(TAG, "Dropping ${item.data.size}-byte frame larger than the input buffer")
                    codec.queueInputBuffer(index, 0, 0, item.ptsUs, 0)
                    continue
                }
                buffer.put(item.data)
                codec.queueInputBuffer(index, 0, item.data.size, item.ptsUs, item.flags)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Queue failed", e)
                return
            }
        }
    }

    private fun teardown() {
        decoder?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        decoder = null
        configuredFor = null
        pending.clear()
        freeInputs.clear()
    }

    private inner class Callback(private val owner: MediaCodec) : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (codec !== decoder) return
            freeInputs.addLast(index)
            drain()
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (codec !== decoder) return
            runCatching { codec.releaseOutputBuffer(index, info.size > 0) }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Decoder error (recoverable=${e.isRecoverable}, transient=${e.isTransient})", e)
            if (codec === owner && !e.isTransient) {
                // The next configure rebuilds it; until then, wait for a fresh key frame.
                awaitingKeyFrame = true
                onNeedKeyFrame()
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
    }

    private companion object {
        const val TAG = "VideoDecoder"
        const val MAX_PENDING = 30
    }
}
