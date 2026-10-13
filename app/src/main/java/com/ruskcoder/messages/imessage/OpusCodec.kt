package com.ruskcoder.messages.imessage

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.ruskcoder.messages.util.PcmAudio
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opus, in and out, as loose packets rather than as a file.
 *
 * This exists because an iMessage voice message *is* Opus. Apple records one at 24kHz mono in 20ms
 * packets and wraps it in a CAF with no codec cookie at all, and it will not render a voice bubble
 * for anything else — a Linear PCM CAF, correctly formed and correctly described, still arrives as
 * 0:00 and refuses to play. So both directions of the conversion go through this: what arrives is
 * decoded from packets, and what leaves is encoded back into them.
 *
 * Packets are handled bare because that is how CAF stores them: the container holds the payloads
 * end to end and a separate table saying where each one ends. Nothing here writes a container.
 */
object OpusCodec {

    const val MIME = "audio/opus"

    /** What one encoded packet holds, and how Apple's own recorder sizes them. */
    const val PACKET_MS = 20

    /** The rate Apple records a voice message at, which is what this encodes to. */
    const val APPLE_RATE = 24_000

    /** Everything Opus is decoded at, whatever the stream says it was encoded from. */
    const val DECODE_RATE = 48_000

    /** What the encoder produced, with the framing a CAF packet table needs to describe it. */
    class Encoded(
        val packets: List<ByteArray>,
        /** Samples the decoder must throw away at the start — CAF calls these the priming frames. */
        val preSkip: Int,
        val sampleRate: Int,
        val channels: Int,
        val framesPerPacket: Int,
    ) {
        val frames: Long get() = packets.size.toLong() * framesPerPacket - preSkip
    }

    /**
     * The `OpusHead` identification header.
     *
     * Android's decoder wants this as `csd-0` and will not configure without it. A CAF carries no
     * cookie for Opus — Apple's own files have none — so it is rebuilt from the description chunk,
     * which is possible precisely because every field in it is either fixed or stated there.
     */
    fun opusHead(channels: Int, preSkip: Int, inputRate: Int): ByteArray {
        val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        head.put("OpusHead".toByteArray(Charsets.US_ASCII))
        head.put(1)                       // version
        head.put(channels.toByte())
        head.putShort(preSkip.toShort())
        head.putInt(inputRate)
        head.putShort(0)                  // output gain
        head.put(0)                       // channel mapping family: mono/stereo, no mapping table
        return head.array()
    }

    /**
     * Decode loose Opus packets to linear PCM.
     *
     * [sizes] is the packet table: Opus packets vary in length and nothing inside the stream marks
     * where one ends, so without it the payload is undivisible.
     */
    fun decode(data: ByteArray, sizes: IntArray, channels: Int, preSkip: Int, inputRate: Int): PcmAudio? {
        var codec: MediaCodec? = null
        val pcm = ByteArrayOutputStream()
        var rate = DECODE_RATE
        var outChannels = channels

        try {
            val format = MediaFormat.createAudioFormat(MIME, DECODE_RATE, channels).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead(channels, preSkip, inputRate)))
                // Both are nanosecond counts as 64-bit little-endian, which is the shape the
                // platform decoder documents for these two and nothing else accepts.
                setByteBuffer("csd-1", ByteBuffer.wrap(nanos(preSkip.toLong() * 1_000_000_000L / DECODE_RATE)))
                setByteBuffer("csd-2", ByteBuffer.wrap(nanos(SEEK_PREROLL_NS)))
            }
            codec = MediaCodec.createDecoderByType(MIME)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var cursor = 0
            var index = 0
            var sawOutputEnd = false

            while (!sawOutputEnd) {
                if (index <= sizes.size) {
                    val input = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (input >= 0) {
                        if (index == sizes.size) {
                            codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            index++
                        } else {
                            val size = sizes[index]
                            if (size <= 0 || cursor + size > data.size) {
                                codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                index = sizes.size + 1
                            } else {
                                val buffer = codec.getInputBuffer(input)!!
                                buffer.clear()
                                buffer.put(data, cursor, size)
                                codec.queueInputBuffer(input, 0, size, 0, 0)
                                cursor += size
                                index++
                            }
                        }
                    }
                }

                when (val output = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outChannels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (output >= 0) {
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(output)!!
                            val chunk = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.get(chunk, 0, info.size)
                            pcm.write(chunk)
                        }
                        codec.releaseOutputBuffer(output, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not decode ${sizes.size} Opus packets", e)
            return null
        } finally {
            codec?.let {
                runCatching { it.stop() }
                it.release()
            }
        }

        val bytes = pcm.toByteArray()
        if (bytes.isEmpty()) return null
        val samples = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        return PcmAudio(samples, rate, outChannels)
    }

    /**
     * Encode mono PCM at [rate] into Opus packets.
     *
     * [rate] must be one Opus itself supports; 24000 is what Apple uses and therefore what this is
     * asked for. Null when the device has no Opus encoder — it has only been required hardware
     * since Android 10 — which the caller answers by sending something else.
     */
    fun encode(samples: ShortArray, rate: Int, bitrate: Int = BITRATE): Encoded? {
        val framesPerPacket = rate / 1000 * PACKET_MS
        var codec: MediaCodec? = null
        val packets = mutableListOf<ByteArray>()
        var preSkip = DEFAULT_PRE_SKIP

        try {
            val format = MediaFormat.createAudioFormat(MIME, rate, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_AAC_PROFILE, 0) // ignored by Opus, harmless
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, framesPerPacket * 2 * 4)
                setInteger(MediaFormat.KEY_PCM_ENCODING, ENCODING_PCM_16BIT)
            }
            codec = MediaCodec.createEncoderByType(MIME)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var offset = 0
            var sawInputEnd = false
            var sawOutputEnd = false
            var presentation = 0L

            while (!sawOutputEnd) {
                if (!sawInputEnd) {
                    val input = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (input >= 0) {
                        val buffer = codec.getInputBuffer(input)!!
                        buffer.clear()
                        val remaining = samples.size - offset
                        val take = minOf(framesPerPacket, remaining, buffer.capacity() / 2)
                        if (take <= 0) {
                            codec.queueInputBuffer(input, 0, 0, presentation, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEnd = true
                        } else {
                            val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            shorts.put(samples, offset, take)
                            codec.queueInputBuffer(input, 0, take * 2, presentation, 0)
                            presentation += take.toLong() * 1_000_000L / rate
                            offset += take
                        }
                    }
                }

                when (val output = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // The encoder states its own pre-skip in the header it hands back, and that
                        // is the number the packet table has to carry — guessing it puts a click or
                        // a clipped syllable at the start of every message.
                        codec.outputFormat.getByteBuffer("csd-0")?.let { head ->
                            if (head.remaining() >= 12) {
                                val copy = ByteArray(head.remaining())
                                head.duplicate().get(copy)
                                preSkip = ((copy[11].toInt() and 0xFF) shl 8) or (copy[10].toInt() and 0xFF)
                            }
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (output >= 0) {
                        // The codec config buffer is the OpusHead, not audio — a CAF carries no
                        // cookie for Opus, so it is read for its pre-skip above and then dropped.
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (info.size > 0 && !isConfig) {
                            val buffer = codec.getOutputBuffer(output)!!
                            val chunk = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.get(chunk, 0, info.size)
                            packets.add(chunk)
                        }
                        codec.releaseOutputBuffer(output, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not encode ${samples.size} samples as Opus at ${rate}Hz", e)
            return null
        } finally {
            codec?.let {
                runCatching { it.stop() }
                it.release()
            }
        }

        if (packets.isEmpty()) return null
        return Encoded(packets, preSkip, rate, 1, framesPerPacket)
    }

    private fun nanos(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    private const val TAG = "OpusCodec"

    /** 80ms, which is the figure the Opus mapping specifies and every decoder expects. */
    private const val SEEK_PREROLL_NS = 80_000_000L

    /** What libopus uses at 48kHz when nothing says otherwise; only a fallback. */
    private const val DEFAULT_PRE_SKIP = 312

    /** Ample for speech at 24kHz mono, and close to what Apple's own recorder produces. */
    private const val BITRATE = 24_000

    private const val ENCODING_PCM_16BIT = 2
    private const val TIMEOUT_US = 10_000L
}
