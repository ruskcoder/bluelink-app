package com.ruskcoder.messages.imessage

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.ruskcoder.messages.util.AudioPcm
import com.ruskcoder.messages.util.AudioPcm.monoPcmAt
import com.ruskcoder.messages.util.PcmAudio
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts a recording into the one container iMessage will play inline.
 *
 * Marking a message as a voice message is not enough on its own: the receiving client only renders
 * the playable bubble with a waveform for a Core Audio Format file, and hands anything else over as
 * a file to download. Renaming will not do it either — a mislabelled MPEG-4 produces a bubble that
 * shows zero seconds and plays nothing — so the audio is genuinely decoded and rewritten.
 *
 * The codec is Opus, because that is what an iMessage voice message actually is: an iPhone records
 * one at 24kHz mono in 20ms packets and sends it in a CAF with no codec cookie at all. Linear PCM in
 * a correctly formed CAF was tried first and is not enough — Messages shows it as 0:00 and will not
 * play it, so the container was never the disagreement. Linear PCM remains only as the fallback for
 * a device with no Opus encoder, which means one older than Android 10.
 */
object AudioMessages {

    /**
     * Rewrite [source] as a CAF file alongside it, or null if it cannot be decoded.
     *
     * The caller keeps using the original for local playback — Android's own media stack does not
     * read CAF, so the recording has to stay in the format it was made in.
     */
    suspend fun toCaf(source: File): File? = withContext(Dispatchers.IO) {
        runCatching { convert(source) }
            .onFailure { Log.w(TAG, "could not convert ${source.name} to CAF", it) }
            .getOrNull()
    }

    private fun convert(source: File): File? {
        // The shared decoder rather than one of its own. This used to run its own MediaCodec loop,
        // which could not read a WAV — there is no decoder for `audio/raw`, so forwarding a
        // received voice message failed — and mistook a decoder that hands back 32-bit floats for
        // one handing back 16-bit integers, which turns speech into noise.
        val audio = AudioPcm.read(source)
        if (audio == null) {
            Log.w(TAG, "could not decode ${source.name}; it will be sent as a plain file")
            return null
        }
        if (audio.samples.isEmpty()) {
            Log.w(TAG, "${source.name} decoded to no samples")
            return null
        }

        val target = File(source.parentFile, source.nameWithoutExtension + ".caf")
        // Opus first, because that is what an iMessage voice message *is*. Linear PCM in a CAF is a
        // valid file and Messages still renders it as 0:00 and refuses to play it; the container was
        // never the disagreement, the codec was.
        opus(audio)?.let { encoded ->
            writeOpusCaf(target, encoded)
            Log.i(
                TAG,
                "wrote ${target.name}: ${encoded.packets.size} Opus packets at " +
                    "${encoded.sampleRate}Hz, ${target.length()} bytes",
            )
            return target
        }

        // No Opus encoder on this device — it has only been required since Android 10. The clip
        // still goes as a CAF, which at least arrives as audio rather than as a file.
        Log.w(TAG, "no Opus encoder available; falling back to linear PCM")
        writeCaf(target, audio.samples, audio.sampleRate, audio.channels)
        Log.i(
            TAG,
            "wrote ${target.name}: ${audio.frames} frames at ${audio.sampleRate}Hz " +
                "x${audio.channels}, ${target.length()} bytes",
        )
        return target
    }

    /**
     * Encode the clip the way Apple's own recorder does: Opus, mono, 20ms packets.
     *
     * 24kHz first because that is the rate a real voice message is recorded at, and matching it
     * exactly is the cheapest way to be sure of the result. Not every encoder accepts every rate
     * Opus allows, so 48kHz — the codec's own native rate, which nothing refuses — is tried after.
     */
    private fun opus(audio: PcmAudio): OpusCodec.Encoded? {
        for (rate in intArrayOf(OpusCodec.APPLE_RATE, OpusCodec.DECODE_RATE)) {
            val mono = audio.monoPcmAt(rate)
            if (mono.isEmpty()) return null
            OpusCodec.encode(mono, rate)?.let { return it }
        }
        return null
    }

    /**
     * Write the CAF an iPhone will play: a description, a packet table, and the packets.
     *
     * No magic cookie chunk, deliberately — Apple's own voice messages carry none, and everything a
     * decoder needs for Opus is either fixed by the format or stated in the two chunks that are
     * here. The packet table is not optional: Opus packets vary in length and nothing inside the
     * stream says where one ends, so without it the payload cannot be divided at all.
     */
    private fun writeOpusCaf(target: File, encoded: OpusCodec.Encoded) {
        DataOutputStream(FileOutputStream(target).buffered()).use { out ->
            out.writeBytes("caff")
            out.writeShort(1) // version
            out.writeShort(0) // flags

            out.writeBytes("desc")
            out.writeLong(32L)
            out.writeDouble(encoded.sampleRate.toDouble())
            out.writeBytes("opus")
            out.writeInt(0)                        // no format flags apply to Opus
            out.writeInt(0)                        // bytes per packet: variable
            out.writeInt(encoded.framesPerPacket)
            out.writeInt(encoded.channels)
            out.writeInt(0)                        // bits per channel: not a linear format

            // The packet table: how many, how long the whole thing is, what to trim off each end,
            // and then every packet's size as a variable-length integer.
            val sizes = java.io.ByteArrayOutputStream()
            encoded.packets.forEach { packet -> writeVarLength(sizes, packet.size) }
            val table = sizes.toByteArray()
            out.writeBytes("pakt")
            out.writeLong(24L + table.size)
            out.writeLong(encoded.packets.size.toLong())
            out.writeLong(encoded.frames.coerceAtLeast(0L))
            out.writeInt(encoded.preSkip)
            out.writeInt(0)                        // remainder frames
            out.write(table)

            val payload = encoded.packets.sumOf { it.size }
            out.writeBytes("data")
            out.writeLong(payload.toLong() + 4L)
            out.writeInt(0)                        // edit count
            encoded.packets.forEach { out.write(it) }
        }
    }

    /**
     * A packet size, seven bits at a time with the top bit meaning "another byte follows".
     *
     * Most significant group first, which is the order the format reads them back in — reversed,
     * every packet boundary after the first one lands in the middle of a packet.
     */
    private fun writeVarLength(out: java.io.ByteArrayOutputStream, value: Int) {
        var shift = 28
        var started = false
        while (shift > 0) {
            val group = (value shr shift) and 0x7F
            if (group != 0 || started) {
                out.write(group or 0x80)
                started = true
            }
            shift -= 7
        }
        out.write(value and 0x7F)
    }

    /**
     * Write a Core Audio Format file wrapping 16-bit signed PCM.
     *
     * The fallback only — Messages will not play one of these as a voice message, so it is here for
     * a device with no Opus encoder, where arriving as an audio file beats arriving as a file.
     *
     * CAF is big-endian throughout its headers, and its sample data is described rather than
     * assumed, so the samples are written big-endian and declared as such.
     */
    private fun writeCaf(target: File, samples: ShortArray, sampleRate: Int, channels: Int) {
        val bytesPerFrame = 2 * channels
        val dataBytes = samples.size * 2

        DataOutputStream(FileOutputStream(target).buffered()).use { out ->
            out.writeBytes("caff")
            out.writeShort(1) // version
            out.writeShort(0) // flags

            // Audio description. Fixed 32 bytes, and required to be the first chunk.
            out.writeBytes("desc")
            out.writeLong(32L)
            out.writeDouble(sampleRate.toDouble())
            out.writeBytes("lpcm")
            // 0 = signed integer, big-endian. The float and little-endian variants are flag bits.
            out.writeInt(0)
            out.writeInt(bytesPerFrame) // bytes per packet
            out.writeInt(1)             // frames per packet
            out.writeInt(channels)
            out.writeInt(16)            // bits per channel

            // The data chunk's declared size includes the edit count that precedes the samples, and
            // is taken from what is actually about to be written rather than from the input's byte
            // count — an odd trailing byte otherwise declares a file longer than it turns out to be.
            out.writeBytes("data")
            out.writeLong(dataBytes.toLong() + 4L)
            out.writeInt(0) // edit count

            val swapped = ByteArray(dataBytes)
            val sink = ByteBuffer.wrap(swapped).order(ByteOrder.BIG_ENDIAN)
            samples.forEach { sink.putShort(it) }
            out.write(swapped)
        }
    }

    /** Apple's own name for a recorded audio message, which is what the bubble is labelled with. */
    const val FILE_NAME = "Audio Message.caf"
    const val MIME = "audio/x-caf"
    const val UTI = "com.apple.coreaudio-format"

    private const val TAG = "AudioMessages"
}
