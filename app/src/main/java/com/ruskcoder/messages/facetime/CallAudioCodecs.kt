package com.ruskcoder.messages.facetime

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM in, encoded packets out. One instance per stream; not thread-safe. */
interface CallAudioEncoder {
    /** [pcm] is exactly one frame of 16-bit mono samples. May return zero or several packets. */
    fun encode(pcm: ShortArray): List<ByteArray>
    fun release()
}

/** Encoded packets in, PCM out. One instance per remote stream; not thread-safe. */
interface CallAudioDecoder {
    fun decode(packet: ByteArray): ShortArray?
    fun release()
}

/**
 * Codec instances by payload.
 *
 * EVS has no platform codec on Android, so until a native one is bundled (the 3GPP reference
 * implementation, TS 26.442) it reports none, and a call negotiated onto EVS has no audio on this
 * side. AAC-ELD is in every Android build through the platform's AAC codec.
 */
object CallAudioCodecs {
    /** Set by a native EVS build when one is linked in; null means EVS is unavailable. */
    @Volatile var evs: EvsFactory? = null

    interface EvsFactory {
        fun encoder(sampleRate: Int): CallAudioEncoder
        fun decoder(sampleRate: Int): CallAudioDecoder
    }

    fun encoder(payload: AudioPayload): CallAudioEncoder? = when (payload) {
        AudioPayload.EVS -> evs?.encoder(payload.sampleRate)
        AudioPayload.AAC_ELD, AudioPayload.AAC_ELD_48 -> runCatching { AacEldEncoder(payload) }
            .onFailure { Log.e(TAG, "AAC-ELD encoder unavailable", it) }.getOrNull()
    }

    fun decoder(payload: AudioPayload): CallAudioDecoder? = when (payload) {
        AudioPayload.EVS -> evs?.decoder(payload.sampleRate)
        AudioPayload.AAC_ELD, AudioPayload.AAC_ELD_48 -> runCatching { AacEldDecoder(payload) }
            .onFailure { Log.e(TAG, "AAC-ELD decoder unavailable", it) }.getOrNull()
    }

    private const val TAG = "CallAudioCodecs"
}

private const val AAC_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
private const val CODEC_TIMEOUT_US = 5_000L
private const val ELD_FRAME_SAMPLES = 480

/**
 * The AudioSpecificConfig for mono AAC-ELD with 480-sample frames and no SBR — the decoder will not
 * configure for ELD without it, and the RTP stream never carries one.
 *
 * Bits: audioObjectType 39 (escaped: 31 then 6 bits of 7), samplingFrequencyIndex, channel
 * configuration 1, then ELDSpecificConfig with frameLengthFlag=1 (480), all three resilience flags
 * 0, ldSbrPresentFlag 0, and ELDEXT_TERM. Apple's exact ELD flags are an assumption until a capture
 * confirms them.
 */
internal fun aacEldAudioSpecificConfig(sampleRate: Int): ByteArray {
    val frequencyIndex = when (sampleRate) {
        48_000 -> 3
        44_100 -> 4
        32_000 -> 5
        24_000 -> 6
        22_050 -> 7
        16_000 -> 8
        else -> error("No AAC frequency index for $sampleRate")
    }
    val bits = BitWriter()
    bits.write(31, 5)
    bits.write(39 - 32, 6)
    bits.write(frequencyIndex, 4)
    bits.write(1, 4)        // mono
    bits.write(1, 1)        // frameLengthFlag: 480 samples
    bits.write(0, 3)        // section/scalefactor/spectral data resilience
    bits.write(0, 1)        // ldSbrPresentFlag
    bits.write(0, 4)        // ELDEXT_TERM
    return bits.toByteArray()
}

private class BitWriter {
    private val bytes = ArrayList<Byte>()
    private var current = 0
    private var used = 0

    fun write(value: Int, count: Int) {
        for (i in count - 1 downTo 0) {
            current = (current shl 1) or ((value shr i) and 1)
            used++
            if (used == 8) {
                bytes.add(current.toByte())
                current = 0
                used = 0
            }
        }
    }

    fun toByteArray(): ByteArray {
        if (used > 0) bytes.add((current shl (8 - used)).toByte())
        return bytes.toByteArray()
    }
}

private class AacEldEncoder(payload: AudioPayload) : CallAudioEncoder {
    private val codec = MediaCodec.createEncoderByType(AAC_MIME)
    private val info = MediaCodec.BufferInfo()
    private var ptsUs = 0L
    private val frameUs = ELD_FRAME_SAMPLES * 1_000_000L / payload.sampleRate

    init {
        val format = MediaFormat.createAudioFormat(AAC_MIME, payload.sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
            setInteger(MediaFormat.KEY_BIT_RATE, if (payload.sampleRate >= 48_000) 64_000 else 32_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, ELD_FRAME_SAMPLES * 2)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    override fun encode(pcm: ShortArray): List<ByteArray> {
        val input = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
        if (input >= 0) {
            val buffer = codec.getInputBuffer(input)!!
            buffer.clear()
            buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
            codec.queueInputBuffer(input, 0, pcm.size * 2, ptsUs, 0)
            ptsUs += frameUs
        }
        val out = ArrayList<ByteArray>(1)
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index < 0) break
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                out += ByteArray(info.size).also { buffer.get(it) }
            }
            codec.releaseOutputBuffer(index, false)
        }
        return out
    }

    override fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}

private class AacEldDecoder(payload: AudioPayload) : CallAudioDecoder {
    private val codec = MediaCodec.createDecoderByType(AAC_MIME)
    private val info = MediaCodec.BufferInfo()
    private var ptsUs = 0L

    init {
        val format = MediaFormat.createAudioFormat(AAC_MIME, payload.sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
            setInteger(MediaFormat.KEY_IS_ADTS, 0)
            setByteBuffer("csd-0", ByteBuffer.wrap(aacEldAudioSpecificConfig(payload.sampleRate)))
        }
        codec.configure(format, null, null, 0)
        codec.start()
    }

    override fun decode(packet: ByteArray): ShortArray? {
        val input = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
        if (input < 0) return null
        val buffer = codec.getInputBuffer(input)!!
        buffer.clear()
        buffer.put(packet)
        codec.queueInputBuffer(input, 0, packet.size, ptsUs, 0)
        ptsUs += 10_000

        var pcm: ShortArray? = null
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (pcm == null) CODEC_TIMEOUT_US else 0)
            if (index < 0) break
            val out = codec.getOutputBuffer(index)
            if (out != null && info.size > 0) {
                out.position(info.offset)
                out.limit(info.offset + info.size)
                val samples = ShortArray(info.size / 2)
                out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                pcm = pcm?.plus(samples) ?: samples
            }
            codec.releaseOutputBuffer(index, false)
        }
        return pcm
    }

    override fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}
