package com.ruskcoder.messages.facetime

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * The microphone and the speaker for one call.
 *
 * Capture runs on its own thread at the send codec's rate, one codec frame per read, with the
 * platform's echo canceller and noise suppressor attached to the recording session — a phone held
 * on speaker is unusable without them. Each remote participant gets its own decoder and its own
 * [AudioTrack]; the platform mixes the tracks, which is all a group call needs.
 */
class CallAudio(
    private val context: Context,
    private val send: (payload: AudioPayload, packet: ByteArray, samples: Long) -> Unit,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile private var capturing = false
    @Volatile var muted = false
    private var captureThread: Thread? = null
    private val effects = ArrayList<android.media.audiofx.AudioEffect>()

    private class Playback(val payload: AudioPayload, val decoder: CallAudioDecoder, val track: AudioTrack)
    private val playbacks = ConcurrentHashMap<Long, Playback>()

    private var savedMode = AudioManager.MODE_NORMAL

    fun enterCallMode() {
        savedMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    fun exitCallMode() {
        setSpeaker(false)
        audioManager.mode = savedMode
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested before a call starts
    fun startCapture(payload: AudioPayload) {
        if (capturing) return
        val encoder = CallAudioCodecs.encoder(payload) ?: run {
            Log.w(TAG, "No encoder for $payload; sending no audio")
            return
        }
        val frame = payload.samplesPerFrame
        val minBuffer = AudioRecord.getMinBufferSize(payload.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                payload.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, frame * 2 * 4),
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord failed", e)
            encoder.release()
            return
        }
        attachEffects(record.audioSessionId)

        capturing = true
        captureThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val pcm = ShortArray(frame)
            val silence = ShortArray(frame)
            var samples = 0L
            try {
                record.startRecording()
                while (capturing) {
                    var read = 0
                    while (read < frame && capturing) {
                        val n = record.read(pcm, read, frame - read)
                        if (n <= 0) break
                        read += n
                    }
                    if (read < frame) continue
                    // Muted still sends: silence keeps the far end's jitter buffer and our RTP clock
                    // running, which is what an iPhone does too.
                    for (packet in encoder.encode(if (muted) silence else pcm)) {
                        send(payload, packet, samples)
                    }
                    samples += frame
                }
            } catch (e: Exception) {
                Log.e(TAG, "Capture loop failed", e)
            } finally {
                runCatching { record.stop() }
                record.release()
                encoder.release()
            }
        }, "CallAudioCapture").apply { start() }
    }

    fun stopCapture() {
        capturing = false
        captureThread?.join(500)
        captureThread = null
        effects.forEach { runCatching { it.release() } }
        effects.clear()
    }

    /** One received packet from [participant]. Called on a protocol thread. */
    fun play(participant: Long, payload: AudioPayload, packet: ByteArray) {
        val playback = playbacks[participant]?.takeIf { it.payload == payload }
            ?: createPlayback(participant, payload) ?: return
        val pcm = playback.decoder.decode(packet) ?: return
        playback.track.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
    }

    fun removeParticipant(participant: Long) {
        playbacks.remove(participant)?.let(::releasePlayback)
    }

    fun setSpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (on) {
                audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { audioManager.setCommunicationDevice(it) }
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
        }
    }

    fun release() {
        stopCapture()
        playbacks.values.forEach(::releasePlayback)
        playbacks.clear()
    }

    private fun createPlayback(participant: Long, payload: AudioPayload): Playback? {
        playbacks.remove(participant)?.let(::releasePlayback)
        val decoder = CallAudioCodecs.decoder(payload) ?: run {
            Log.w(TAG, "No decoder for $payload from $participant")
            return null
        }
        val minBuffer = AudioTrack.getMinBufferSize(payload.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(payload.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuffer, payload.samplesPerFrame * 2 * 6))
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) }
            .build()
        track.play()
        return Playback(payload, decoder, track).also { playbacks[participant] = it }
    }

    private fun releasePlayback(playback: Playback) {
        runCatching { playback.track.stop() }
        playback.track.release()
        playback.decoder.release()
    }

    private fun attachEffects(session: Int) {
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session)?.also { it.enabled = true; effects += it }
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session)?.also { it.enabled = true; effects += it }
        if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(session)?.also { it.enabled = true; effects += it }
    }

    private companion object {
        const val TAG = "CallAudio"
    }
}
