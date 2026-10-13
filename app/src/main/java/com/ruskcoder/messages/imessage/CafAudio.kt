package com.ruskcoder.messages.imessage

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.roundToInt
import com.ruskcoder.messages.util.AudioPcm
import uniffi.imessage_core.AttachmentInfo

/** Whether a received attachment is a voice message rather than a file to hand over as-is. */
val AttachmentInfo.isVoiceMessage: Boolean
    get() = CafAudio.isCaf(mime, utiType, name)

/**
 * Reads a received Core Audio Format file into something this phone can play.
 *
 * A voice message from an iPhone arrives as `Audio Message.caf`, and Android's media stack does not
 * know the container at all — no extractor claims it, so the clip lands in the thread as a file
 * with an extension nobody recognises rather than as something you can press play on. This is the
 * mirror of [AudioMessages], which exists because Apple will only play a voice message out of a CAF:
 * both ends of the same disagreement about containers.
 *
 * Only the contents are rewrapped; the samples are handed over untouched. Linear PCM becomes a WAV
 * and AAC becomes an ADTS stream, which are the two containers Android reads without being asked
 * nicely. Anything else — ALAC, IMA4, a CAF whose packet table is missing — is left alone and goes
 * on showing as a file, which is worse than playing it and much better than a bubble that silently
 * plays nothing.
 */
object CafAudio {

    /** A clip, ready to store on a voice-message row. */
    data class Clip(val file: File, val duration: Float, val waveform: List<Float>)

    /**
     * Whether an attachment is a voice message rather than an ordinary file.
     *
     * The UTI is the sender's own answer and the MIME type is what the transfer was labelled with;
     * the name is the fallback for a sender that filled in neither. Any of the three is enough —
     * a `.caf` sent as a plain file is unplayable here for exactly the same reason, so it takes the
     * same path.
     */
    fun isCaf(mime: String, uti: String, name: String): Boolean =
        mime.equals(AudioMessages.MIME, ignoreCase = true) ||
            mime.equals("audio/caf", ignoreCase = true) ||
            uti.equals(AudioMessages.UTI, ignoreCase = true) ||
            // Both spellings turn up in the wild; the reference client checks for both too.
            uti.equals("com.apple.coreaudio_format", ignoreCase = true) ||
            name.endsWith(".caf", ignoreCase = true)

    /**
     * Rewrap [source] as a playable file beside it, with the length and shape of what it holds.
     *
     * Null when the clip cannot be turned into anything this device plays, which is the caller's
     * cue to keep showing it as a file.
     */
    suspend fun playable(source: File): Clip? = withContext(Dispatchers.IO) {
        runCatching { convert(source) }
            .onFailure { Log.w(TAG, "could not read ${source.name} as CAF", it) }
            .getOrNull()
    }

    // ---------------------------------------------------------------- parsing

    /** The `desc` chunk: everything about the samples except the samples. */
    private data class Description(
        val sampleRate: Double,
        val format: String,
        val flags: Int,
        val bytesPerPacket: Int,
        val framesPerPacket: Int,
        val channels: Int,
        val bitsPerChannel: Int,
    )

    /** The `pakt` chunk, which is the only thing that says where one compressed packet ends. */
    private data class PacketTable(val sizes: IntArray, val validFrames: Long, val primingFrames: Int)

    private fun convert(source: File): Clip? {
        if (source.length() <= 0 || source.length() > MAX_BYTES) return null
        val bytes = source.readBytes()
        if (bytes.size < HEADER || String(bytes, 0, 4, Charsets.US_ASCII) != "caff") return null

        var description: Description? = null
        var audio: ByteArray? = null
        var packets: PacketTable? = null
        var cookie: ByteArray? = null

        var cursor = HEADER
        // Every chunk is a four-character type and a length, so an unknown one is skipped rather
        // than ending the walk — the chunks that matter are rarely the only ones present.
        while (cursor + CHUNK_HEADER <= bytes.size) {
            val type = String(bytes, cursor, 4, Charsets.US_ASCII)
            val declared = ByteBuffer.wrap(bytes, cursor + 4, 8).order(ByteOrder.BIG_ENDIAN).long
            val start = cursor + CHUNK_HEADER
            // A length of -1 means "to the end of the file", which the last chunk of a recording
            // still being written is allowed to say.
            val end = if (declared < 0) bytes.size else min(start + declared, bytes.size.toLong()).toInt()
            if (end < start) return null

            when (type) {
                "desc" -> description = describe(bytes, start, end)
                // The edit count sits between the header and the first sample.
                "data" -> if (end - start > 4) audio = bytes.copyOfRange(start + 4, end)
                "pakt" -> packets = packetTable(bytes, start, end)
                // The codec's own configuration, in whatever form its encoder stored it. For AAC
                // this is the only authoritative statement of the profile and the core sample
                // rate — the description chunk reports the *output* rate, which for anything with
                // SBR in it is twice what the packets are actually coded at.
                "kuki" -> if (end > start) cookie = bytes.copyOfRange(start, end)
            }

            cursor = end
            if (declared < 0) break
        }

        val desc = description
        if (desc == null) {
            Log.w(TAG, "${source.name} has no description chunk")
            return null
        }
        val data = audio
        if (data == null) {
            Log.w(TAG, "${source.name} has no audio data")
            return null
        }
        // Logged at info on every received clip, because the four-character codec is the single
        // fact that explains any failure below and it cannot be recovered after the fact.
        Log.i(
            TAG,
            "${source.name}: '${desc.format}' ${desc.sampleRate.roundToInt()}Hz x${desc.channels} " +
                "${desc.bitsPerChannel}-bit, ${desc.framesPerPacket} frames/packet, " +
                "${data.size} bytes, cookie=${cookie?.size ?: 0}, " +
                    "packets=${packets?.sizes?.size ?: 0}, priming=${packets?.primingFrames ?: 0}",
        )
        if (desc.channels !in 1..2 || desc.sampleRate < 1000 || desc.sampleRate > 192_000) {
            Log.w(TAG, "${source.name} has a shape this cannot use")
            return null
        }

        return when (desc.format) {
            "lpcm" -> fromPcm(source, pcm16(data, desc) ?: return null, desc)
            "aac " -> fromAac(source, data, desc, packets, cookie)
            // What an iMessage voice message actually is — see [OpusCodec].
            "opus" -> fromOpus(source, data, desc, packets)
            "ima4" -> fromPcm(source, expandIma4(data, desc), desc)
            "ulaw" -> fromPcm(source, expandLog(data, ::decodeMuLaw), desc)
            "alaw" -> fromPcm(source, expandLog(data, ::decodeALaw), desc)
            else -> {
                Log.w(TAG, "no support for '${desc.format}' in ${source.name}; leaving it as a file")
                null
            }
        }
    }

    private fun describe(bytes: ByteArray, start: Int, end: Int): Description? {
        if (end - start < 32) return null
        val buffer = ByteBuffer.wrap(bytes, start, 32).slice().order(ByteOrder.BIG_ENDIAN)
        val sampleRate = buffer.double
        val format = ByteArray(4).also { buffer.get(it) }.toString(Charsets.US_ASCII)
        return Description(
            sampleRate = sampleRate,
            format = format,
            flags = buffer.int,
            bytesPerPacket = buffer.int,
            framesPerPacket = buffer.int,
            channels = buffer.int,
            bitsPerChannel = buffer.int,
        )
    }

    /**
     * The size of each compressed packet, read out of the variable-length table.
     *
     * Sizes are written seven bits at a time with the top bit meaning "another byte follows", so a
     * packet's length cannot be found by arithmetic — the table has to be walked from the start.
     */
    private fun packetTable(bytes: ByteArray, start: Int, end: Int): PacketTable? {
        if (end - start < 24) return null
        val header = ByteBuffer.wrap(bytes, start, 24).order(ByteOrder.BIG_ENDIAN)
        val count = header.long
        val validFrames = header.long
        // Samples the decoder is meant to discard at the start. Read rather than skipped because
        // Opus is unusable without it — a wrong pre-skip clips or repeats the first syllable.
        val priming = header.int
        if (count <= 0 || count > MAX_PACKETS) return null

        val sizes = IntArray(count.toInt())
        var cursor = start + 24
        for (index in sizes.indices) {
            var value = 0
            while (true) {
                if (cursor >= end) return null
                val byte = bytes[cursor++].toInt() and 0xFF
                value = (value shl 7) or (byte and 0x7F)
                if (byte and 0x80 == 0) break
                if (value > MAX_PACKET_BYTES) return null
            }
            sizes[index] = value
        }
        return PacketTable(sizes, validFrames, priming.coerceAtLeast(0))
    }

    // ------------------------------------------------------------- rewrapping

    /** Linear samples, already the shape a WAV wants — so the waveform is read straight off them. */
    private fun fromPcm(source: File, samples: ByteArray, desc: Description): Clip? {
        if (samples.isEmpty()) return null
        val target = beside(source, "wav")
        writeWav(target, samples, desc.sampleRate.roundToInt(), desc.channels)
        val frames = samples.size / 2 / desc.channels
        return Clip(
            file = target,
            duration = (frames / desc.sampleRate).toFloat(),
            waveform = waveform(asShorts(samples), desc.channels),
        )
    }

    /**
     * Compressed packets, repackaged into a container Android reads.
     *
     * Preferred route is an MPEG-4 file muxed by the platform, configured from the codec cookie the
     * CAF carried. That cookie is an `AudioSpecificConfig` — the exact bytes a decoder wants — so
     * handing it over as `csd-0` lets Android's own AAC decoder settle its own profile, including
     * the SBR variants whose real sample rate is not the one the description chunk states. Writing
     * ADTS headers by hand, which is the fallback where there is no cookie, cannot express any of
     * that: it can only claim plain AAC-LC at the stated rate, and claiming that of an HE-AAC clip
     * plays it at half speed, silently.
     *
     * Either way the result is decoded before it is handed back, which is both how the waveform is
     * measured and the only honest test that the repackaging worked.
     */
    private fun fromAac(
        source: File,
        data: ByteArray,
        desc: Description,
        packets: PacketTable?,
        cookie: ByteArray?,
    ): Clip? {
        if (packets == null) {
            // Without the packet table there is no way to know where one packet ends and the next
            // begins; AAC packets vary in length and nothing in the stream delimits them.
            Log.w(TAG, "${source.name} is AAC with no packet table")
            return null
        }

        val config = cookie?.let { audioSpecificConfig(it) }
        val target = config?.let { muxToMp4(source, data, packets, desc, it) }
            ?: writeAdts(source, data, packets, desc)
            ?: return null

        val decoded = AudioPcm.read(target)
        if (decoded == null) {
            Log.w(TAG, "repackaged ${target.name} but the platform would not decode it")
            target.delete()
            return null
        }
        val frames = packets.validFrames.takeIf { it > 0 }
            ?: (packets.sizes.size.toLong() * desc.framesPerPacket.coerceAtLeast(1))
        return Clip(
            file = target,
            duration = (frames / desc.sampleRate).toFloat(),
            waveform = waveform(decoded.samples, decoded.channels),
        )
    }

    /**
     * Opus packets, decoded and written back out as a WAV.
     *
     * Decoded rather than rewrapped because there is no container Android reads that this can be
     * poured into on every version the app supports: Opus in Ogg only became readable in Android 10,
     * and the recorded clip has to play on anything. The platform's Opus *decoder* has been there
     * since long before that, so the packets are turned into samples here and the samples into the
     * one container nothing has ever had trouble with.
     *
     * The identification header the decoder needs is rebuilt rather than read: a CAF carries no
     * codec cookie for Opus — Apple's own files have none — and every field in that header is
     * either fixed or stated in the description chunk.
     */
    private fun fromOpus(source: File, data: ByteArray, desc: Description, packets: PacketTable?): Clip? {
        if (packets == null) {
            Log.w(TAG, "${source.name} is Opus with no packet table")
            return null
        }
        val decoded = OpusCodec.decode(
            data = data,
            sizes = packets.sizes,
            channels = desc.channels,
            preSkip = packets.primingFrames,
            inputRate = desc.sampleRate.roundToInt(),
        )
        if (decoded == null) {
            Log.w(TAG, "${source.name} is Opus this device would not decode")
            return null
        }

        val target = beside(source, "wav")
        val bytes = ByteArray(decoded.samples.size * 2)
        val sink = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        decoded.samples.forEach { sink.putShort(it) }
        writeWav(target, bytes, decoded.sampleRate, decoded.channels)

        // The stated frame count is in the description's own rate, not the decoder's — which for
        // Opus are rarely the same number.
        val frames = packets.validFrames.takeIf { it > 0 }
            ?: (packets.sizes.size.toLong() * desc.framesPerPacket.coerceAtLeast(1))
        Log.i(TAG, "decoded ${source.name} to ${target.name}: ${decoded.frames} frames at ${decoded.sampleRate}Hz")
        return Clip(
            file = target,
            duration = (frames / desc.sampleRate).toFloat(),
            waveform = waveform(decoded.samples, decoded.channels),
        )
    }

    /**
     * Mux the packets into an `.m4a`, letting the platform write the container.
     *
     * Timestamps are derived from the packet index rather than counted in bytes, because that is
     * what they mean: packet *n* starts at *n* x framesPerPacket into the clip.
     */
    private fun muxToMp4(
        source: File,
        data: ByteArray,
        packets: PacketTable,
        desc: Description,
        config: ByteArray,
    ): File? {
        val target = beside(source, "m4a")
        var muxer: MediaMuxer? = null
        return try {
            val format = MediaFormat.createAudioFormat(AAC, desc.sampleRate.roundToInt(), desc.channels).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(config))
            }
            muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val track = muxer.addTrack(format)
            muxer.start()

            val info = MediaCodec.BufferInfo()
            val buffer = ByteBuffer.wrap(data)
            val frames = desc.framesPerPacket.coerceAtLeast(1).toLong()
            var cursor = 0
            var index = 0L
            for (size in packets.sizes) {
                if (size <= 0 || cursor + size > data.size) break
                buffer.limit(cursor + size)
                buffer.position(cursor)
                info.set(cursor, size, index * frames * 1_000_000L / desc.sampleRate.toLong(), 0)
                muxer.writeSampleData(track, buffer, info)
                cursor += size
                index++
            }
            if (index == 0L) throw IllegalStateException("no packets")
            muxer.stop()
            target
        } catch (e: Exception) {
            Log.w(TAG, "could not mux ${source.name} as MPEG-4", e)
            target.delete()
            null
        } finally {
            runCatching { muxer?.release() }
        }
    }

    /** The seven-byte-framed stream, for a CAF that carried no codec cookie. */
    private fun writeAdts(source: File, data: ByteArray, packets: PacketTable, desc: Description): File? {
        // 1024 frames a packet is plain AAC-LC. Anything else is a profile whose real sample rate
        // is not the one the description states, and wrapping it as LC would play it at the wrong
        // speed - silently, which is the worst way to be wrong.
        if (desc.framesPerPacket != FRAMES_PER_PACKET) {
            Log.w(TAG, "${source.name} is ${desc.framesPerPacket} frames/packet with no cookie to explain it")
            return null
        }
        val rateIndex = SAMPLE_RATES.indexOf(desc.sampleRate.roundToInt()).takeIf { it >= 0 } ?: run {
            Log.w(TAG, "${source.name} has no ADTS index for ${desc.sampleRate}Hz")
            return null
        }

        val target = beside(source, "aac")
        FileOutputStream(target).buffered().use { out ->
            var cursor = 0
            for (size in packets.sizes) {
                if (size <= 0 || cursor + size > data.size) break
                out.write(adtsHeader(size, rateIndex, desc.channels))
                out.write(data, cursor, size)
                cursor += size
            }
        }
        return target
    }

    /** The seven-byte frame header an ADTS stream puts in front of every packet. */
    private fun adtsHeader(payload: Int, rateIndex: Int, channels: Int): ByteArray {
        val length = payload + 7
        return byteArrayOf(
            0xFF.toByte(),
            // MPEG-4, layer 0, no CRC — so the header is seven bytes rather than nine.
            0xF1.toByte(),
            (((AAC_LC - 1) shl 6) or (rateIndex shl 2) or ((channels shr 2) and 0x01)).toByte(),
            (((channels and 0x03) shl 6) or ((length shr 11) and 0x03)).toByte(),
            ((length shr 3) and 0xFF).toByte(),
            (((length and 0x07) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    /**
     * The `AudioSpecificConfig` inside a magic cookie, or null if there is none to be found.
     *
     * CAF stores whatever CoreAudio calls that codec's cookie, and for AAC that is either the bare
     * config or an MPEG-4 elementary stream descriptor with the config buried a few tags down. Both
     * are seen in the wild, so both are read: the descriptor is walked for its DecoderSpecificInfo,
     * and anything already the right shape is taken as it stands.
     */
    private fun audioSpecificConfig(cookie: ByteArray): ByteArray? {
        if (cookie.isEmpty()) return null
        // An ES descriptor opens with tag 0x03. Anything else short enough is already a config.
        if (cookie[0].toInt() and 0xFF != ES_DESCRIPTOR) {
            return cookie.takeIf { it.size in 2..MAX_CONFIG && plausibleConfig(it) }
        }
        return findDescriptor(cookie, DECODER_SPECIFIC_INFO)?.takeIf { plausibleConfig(it) }
    }

    /** Walk the nested MPEG-4 descriptors for one tag, skipping the variable-length sizes. */
    private fun findDescriptor(bytes: ByteArray, wanted: Int): ByteArray? {
        var cursor = 0
        while (cursor + 2 <= bytes.size) {
            val tag = bytes[cursor++].toInt() and 0xFF
            var length = 0
            var byte: Int
            var guard = 0
            do {
                if (cursor >= bytes.size || guard++ >= 4) return null
                byte = bytes[cursor++].toInt() and 0xFF
                length = (length shl 7) or (byte and 0x7F)
            } while (byte and 0x80 != 0)
            if (length < 0 || cursor + length > bytes.size) return null

            when (tag) {
                wanted -> return bytes.copyOfRange(cursor, cursor + length)
                // These two carry other descriptors inside them, after a fixed preamble.
                ES_DESCRIPTOR -> cursor += 3
                DECODER_CONFIG -> cursor += 13
                else -> cursor += length
            }
        }
        return null
    }

    /** A config names an object type in 1..31, and a cookie that does not is not one. */
    private fun plausibleConfig(config: ByteArray): Boolean {
        if (config.size < 2) return false
        return ((config[0].toInt() and 0xFF) shr 3) in 1..31
    }

    /**
     * Rewrite CAF samples as the 16-bit little-endian ones every other part of this app works in.
     *
     * CAF is big-endian by default and says so in its flags rather than in its layout, and it is
     * allowed to carry floats or 24- and 32-bit integers. All of them are narrowed here: the
     * difference is inaudible in speech, and it means one sample format downstream.
     */
    private fun pcm16(data: ByteArray, desc: Description): ByteArray? {
        val float = desc.flags and PCM_FLOAT != 0
        val littleEndian = desc.flags and PCM_LITTLE_ENDIAN != 0
        val order = if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val width = desc.bitsPerChannel / 8
        if (width !in 1..4) return null
        if (float && width != 4) return null

        val count = data.size / width
        val out = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        val source = ByteBuffer.wrap(data).order(order)
        repeat(count) {
            val sample = when {
                float -> (source.float.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                // CAF has no flag for unsigned samples, so every integer width here is signed.
                width == 1 -> source.get().toInt() shl 8
                width == 2 -> source.short.toInt()
                width == 3 -> {
                    val first = source.get()
                    val second = source.get().toInt() and 0xFF
                    val third = source.get()
                    // Only the top two bytes survive, whichever end they were written from — and
                    // the high one keeps its sign, or every negative sample comes back inverted.
                    if (littleEndian) (third.toInt() shl 8) or second
                    else (first.toInt() shl 8) or second
                }
                else -> source.int shr 16
            }
            out.putShort(sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
        return out.array()
    }

    /**
     * IMA ADPCM, which is what an older recorder produced: 34 bytes a packet holding 64 samples.
     *
     * Each packet carries its own predictor and step index, so it decodes independently of the ones
     * before it - which is what makes a fixed-size packet table possible in the first place.
     */
    private fun expandIma4(data: ByteArray, desc: Description): ByteArray {
        val channels = desc.channels
        val packetBytes = IMA4_PACKET * channels
        val packets = data.size / packetBytes
        if (packets <= 0) return ByteArray(0)
        val out = ByteBuffer.allocate(packets * IMA4_FRAMES * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        val decoded = Array(channels) { ShortArray(IMA4_FRAMES) }

        for (packet in 0 until packets) {
            for (channel in 0 until channels) {
                var cursor = packet * packetBytes + channel * IMA4_PACKET
                // Nine bits of predictor and seven of step index, packed into the first two bytes.
                val preamble = ((data[cursor].toInt() and 0xFF) shl 8) or (data[cursor + 1].toInt() and 0xFF)
                cursor += 2
                var predictor = (preamble and 0xFF80).toShort().toInt()
                var stepIndex = (preamble and 0x7F).coerceIn(0, IMA_STEPS.size - 1)
                var sample = 0
                while (sample < IMA4_FRAMES) {
                    val byte = data[cursor++].toInt() and 0xFF
                    // Low nibble first, which is the order the format packs them in.
                    for (half in 0 until 2) {
                        val nibble = if (half == 0) byte and 0x0F else (byte shr 4) and 0x0F
                        val step = IMA_STEPS[stepIndex]
                        var difference = step shr 3
                        if (nibble and 0x04 != 0) difference += step
                        if (nibble and 0x02 != 0) difference += step shr 1
                        if (nibble and 0x01 != 0) difference += step shr 2
                        predictor += if (nibble and 0x08 != 0) -difference else difference
                        predictor = predictor.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        stepIndex = (stepIndex + IMA_INDEX[nibble]).coerceIn(0, IMA_STEPS.size - 1)
                        decoded[channel][sample++] = predictor.toShort()
                    }
                }
            }
            // Interleaved on the way out; the packets themselves are one channel at a time.
            for (frame in 0 until IMA4_FRAMES) {
                for (channel in 0 until channels) out.putShort(decoded[channel][frame])
            }
        }
        return out.array()
    }

    /** µ-law and A-law are a byte a sample, so the expansion is a table lookup in a loop. */
    private fun expandLog(data: ByteArray, decode: (Int) -> Int): ByteArray {
        val out = ByteBuffer.allocate(data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.forEach { out.putShort(decode(it.toInt() and 0xFF).toShort()) }
        return out.array()
    }

    private fun decodeMuLaw(byte: Int): Int {
        val inverted = byte.inv() and 0xFF
        val sign = inverted and 0x80
        val exponent = (inverted shr 4) and 0x07
        val mantissa = inverted and 0x0F
        val magnitude = (((mantissa shl 3) + 0x84) shl exponent) - 0x84
        return if (sign != 0) -magnitude else magnitude
    }

    private fun decodeALaw(byte: Int): Int {
        val value = byte xor 0x55
        val sign = value and 0x80
        val exponent = (value shr 4) and 0x07
        val mantissa = value and 0x0F
        val magnitude = when (exponent) {
            0 -> (mantissa shl 4) + 8
            else -> ((mantissa shl 4) + 0x108) shl (exponent - 1)
        }
        return if (sign != 0) -magnitude else magnitude
    }

    private fun writeWav(target: File, samples: ByteArray, sampleRate: Int, channels: Int) {
        val byteRate = sampleRate * channels * 2
        DataOutputStream(FileOutputStream(target).buffered()).use { out ->
            fun int(value: Int) = out.write(
                byteArrayOf(
                    value.toByte(),
                    (value shr 8).toByte(),
                    (value shr 16).toByte(),
                    (value shr 24).toByte(),
                ),
            )
            fun short(value: Int) = out.write(byteArrayOf(value.toByte(), (value shr 8).toByte()))

            out.writeBytes("RIFF")
            int(36 + samples.size)
            out.writeBytes("WAVEfmt ")
            int(16)          // the size of the format chunk that follows
            short(1)         // uncompressed PCM
            short(channels)
            int(sampleRate)
            int(byteRate)
            short(channels * 2) // block align
            short(16)           // bits per sample
            out.writeBytes("data")
            int(samples.size)
            out.write(samples)
        }
    }

    // --------------------------------------------------------------- measuring

    /**
     * The bar heights the bubble draws, on the same scale the recorder samples its own clips on.
     *
     * A bar is the loudest moment in its slice rather than the average of them, because that is
     * what `MediaRecorder.maxAmplitude` reports for a clip recorded here — a received message and a
     * sent one should not be drawn by two different rules.
     */
    /** The little-endian 16-bit samples behind a block of PCM bytes. */
    private fun asShorts(pcm: ByteArray): ShortArray {
        val shorts = ShortArray(pcm.size / 2)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }

    private fun waveform(samples: ShortArray, channels: Int): List<Float> {
        val frames = samples.size / channels
        if (frames <= 0) return List(BARS) { MIN_BAR }
        return List(BARS) { bar ->
            val from = (bar.toLong() * frames / BARS).toInt()
            val to = maxOf(from + 1, ((bar + 1).toLong() * frames / BARS).toInt()).coerceAtMost(frames)
            var peak = 0
            for (frame in from until to) {
                for (channel in 0 until channels) {
                    val value = abs(samples[frame * channels + channel].toInt())
                    if (value > peak) peak = value
                }
            }
            normalize(peak)
        }
    }

    /** Mirrors the composer's own metering: full scale at the top, a floor 50dB below it. */
    private fun normalize(peak: Int): Float {
        if (peak <= 0) return MIN_BAR
        val db = (20f * log10(peak / 32767f)).coerceIn(FLOOR_DB, 0f)
        return MIN_BAR + (1f - MIN_BAR) * ((db - FLOOR_DB) / -FLOOR_DB)
    }

    /** The rewrapped clip lives beside the original, which is kept — it is what Apple sent. */
    private fun beside(source: File, extension: String): File =
        File(source.parentFile, "${source.nameWithoutExtension}.$extension")

    private const val TAG = "CafAudio"

    /** `caff`, a version and a flags word. */
    private const val HEADER = 8

    /** A four-character type and a 64-bit length. */
    private const val CHUNK_HEADER = 12

    /** A voice message is seconds of speech; anything this size is not one, and is not read. */
    private const val MAX_BYTES = 32L * 1024 * 1024
    private const val MAX_PACKETS = 500_000L
    private const val MAX_PACKET_BYTES = 1 shl 20

    private const val PCM_FLOAT = 1
    private const val PCM_LITTLE_ENDIAN = 2

    private const val AAC_LC = 2
    private const val FRAMES_PER_PACKET = 1024
    private const val AAC = "audio/mp4a-latm"

    /** MPEG-4 descriptor tags, for digging a codec config out of an elementary stream descriptor. */
    private const val ES_DESCRIPTOR = 0x03
    private const val DECODER_CONFIG = 0x04
    private const val DECODER_SPECIFIC_INFO = 0x05
    private const val MAX_CONFIG = 64

    /** IMA ADPCM: 34 bytes carry a two-byte header and 64 samples at four bits each. */
    private const val IMA4_PACKET = 34
    private const val IMA4_FRAMES = 64

    private val IMA_INDEX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)
    private val IMA_STEPS = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253,
        279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166,
        1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428,
        4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289,
        16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
    )

    /** The rates an ADTS header can name, in the order its index field numbers them. */
    private val SAMPLE_RATES = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350,
    )

    private const val BARS = 40
    private const val MIN_BAR = 0.05f
    private const val FLOOR_DB = -50f

}
