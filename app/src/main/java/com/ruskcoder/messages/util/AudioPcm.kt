package com.ruskcoder.messages.util

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Samples read back off a local audio file, at whatever rate and width they were stored in. */
class PcmAudio(val samples: ShortArray, val sampleRate: Int, val channels: Int) {
    /** How many sampling instants there are, as opposed to how many numbers. */
    val frames: Int get() = if (channels > 0) samples.size / channels else 0
}

/**
 * Reads a local audio file back to linear PCM.
 *
 * The one place in the app that owns "turn a file on disk into samples". Two callers want it for
 * unrelated reasons — drawing a received clip's waveform, and feeding one to the speech model — and
 * both would otherwise be carrying their own copy of the same decoder loop.
 *
 * `audio/raw` is handled without a codec at all: a WAV has nothing to decode, and asking
 * `MediaCodec` for a decoder that does not exist is how a perfectly readable file gets rejected.
 */
object AudioPcm {

    /**
     * Decode [file], or null if the platform cannot read it.
     *
     * Stops early at [maxFrames], which is a guard against an enormous file rather than a real
     * limit — everything this is asked about is a voice message.
     */
    fun read(file: File, maxFrames: Int = MAX_FRAMES): PcmAudio? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val pcm = ByteArrayOutputStream()
        var sampleRate: Int
        var channels: Int
        var floatOutput: Boolean

        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)

            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (sampleRate <= 0 || channels !in 1..MAX_CHANNELS) return null
            floatOutput = false

            val maxBytes = maxFrames.toLong() * channels * 2

            if (mime == RAW) {
                val buffer = ByteBuffer.allocate(READ_BUFFER)
                while (pcm.size() < maxBytes) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val chunk = ByteArray(size)
                    buffer.position(0)
                    buffer.get(chunk, 0, size)
                    pcm.write(chunk)
                    extractor.advance()
                }
            } else {
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()

                val info = MediaCodec.BufferInfo()
                var sawInputEnd = false
                var sawOutputEnd = false
                while (!sawOutputEnd && pcm.size() < maxBytes) {
                    if (!sawInputEnd) {
                        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val buffer = codec.getInputBuffer(inputIndex)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEnd = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            // The decoder is the authority on what it actually produced; the
                            // extractor's format was only what the container claimed.
                            val out = codec.outputFormat
                            sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            // Some decoders hand back 32-bit floats rather than 16-bit integers,
                            // and reading those as shorts produces noise, not quiet audio.
                            floatOutput = out.pcmEncoding() == ENCODING_PCM_FLOAT
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (outputIndex >= 0) {
                            if (info.size > 0) {
                                val buffer = codec.getOutputBuffer(outputIndex)!!
                                val chunk = ByteArray(info.size)
                                buffer.position(info.offset)
                                buffer.get(chunk, 0, info.size)
                                pcm.write(chunk)
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not read ${file.name}", e)
            return null
        } finally {
            codec?.let {
                runCatching { it.stop() }
                it.release()
            }
            extractor.release()
        }

        val bytes = pcm.toByteArray()
        if (bytes.isEmpty()) return null
        val samples = if (floatOutput) narrowFloats(bytes) else asShorts(bytes)
        return if (samples.isEmpty()) null else PcmAudio(samples, sampleRate, channels)
    }

    /**
     * Downmix to mono and resample to [rate], as a normalized float per sample.
     *
     * Each output sample is the mean of the input samples that fall inside it rather than the one
     * nearest its centre. Averaging is a crude low-pass filter, and decimating 44.1kHz to 16kHz
     * without one folds everything above 8kHz back down over the speech as noise — sibilance turns
     * into a whistle, which is exactly the part of the signal a recogniser is reading.
     *
     * Going the other way (an 8kHz phone-quality clip) there is nothing to filter and each output
     * sample simply repeats its input, which is all the extra samples can honestly contain.
     */
    fun PcmAudio.monoAt(rate: Int): FloatArray {
        val frames = frames
        if (frames <= 0 || rate <= 0) return FloatArray(0)
        val count = (frames.toLong() * rate / sampleRate).toInt()
        if (count <= 0) return FloatArray(0)

        return FloatArray(count) { index ->
            val from = (index.toLong() * frames / count).toInt()
            val to = maxOf(from + 1, ((index + 1).toLong() * frames / count).toInt()).coerceAtMost(frames)
            var sum = 0f
            for (frame in from until to) {
                for (channel in 0 until channels) sum += samples[frame * channels + channel]
            }
            sum / ((to - from) * channels) / FULL_SCALE
        }
    }

    /**
     * The same downmix and resample as [monoAt], as 16-bit samples.
     *
     * Encoders take integers, recognisers take floats, and both want mono at a rate of their own
     * choosing — so the arithmetic lives in one place and only the last step differs.
     */
    fun PcmAudio.monoPcmAt(rate: Int): ShortArray {
        val floats = monoAt(rate)
        return ShortArray(floats.size) {
            (floats[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun asShorts(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }

    private fun narrowFloats(bytes: ByteArray): ShortArray {
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return ShortArray(floats.limit()) {
            (floats.get(it).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /** `KEY_PCM_ENCODING` is absent on most formats, and its absence means 16-bit. */
    private fun MediaFormat.pcmEncoding(): Int =
        if (containsKey(MediaFormat.KEY_PCM_ENCODING)) getInteger(MediaFormat.KEY_PCM_ENCODING)
        else ENCODING_PCM_16BIT

    private const val TAG = "AudioPcm"

    /** What an extractor calls a track that is already linear PCM. */
    private const val RAW = "audio/raw"

    /** `AudioFormat.ENCODING_PCM_16BIT` / `ENCODING_PCM_FLOAT`, named here to keep the import out. */
    private const val ENCODING_PCM_16BIT = 2
    private const val ENCODING_PCM_FLOAT = 4

    private const val FULL_SCALE = 32768f
    private const val MAX_CHANNELS = 8
    private const val READ_BUFFER = 64 * 1024

    /** An hour at 48kHz — far past anything that is a voice message, which is the point. */
    private const val MAX_FRAMES = 48_000 * 60 * 60

    private const val TIMEOUT_US = 10_000L
}
