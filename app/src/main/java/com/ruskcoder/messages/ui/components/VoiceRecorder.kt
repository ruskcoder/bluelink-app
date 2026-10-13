package com.ruskcoder.messages.ui.components

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.log10
import kotlin.math.roundToInt

/** What the composer is doing with the microphone right now. */
enum class RecorderPhase { IDLE, RECORDING, PREVIEW }

/** A finished clip, ready to be sent. */
data class RecordedClip(val uri: String, val duration: Float, val waveform: List<Float>)

/** Bars in the composer's waveform, live while recording and as the clip's shape afterwards. */
private const val WAVEFORM_BARS = 40
private const val MIN_BAR_LEVEL = 0.05f

/**
 * Quiet speech sits around here on `MediaRecorder`'s 0..32767 amplitude scale. Measuring from this
 * floor rather than from silence is what gives the bars a usable range instead of pinning them all
 * near the bottom — the same job `METERING_FLOOR_DB` does in the RN source, in the units Android
 * reports instead of decibels.
 */
private const val AMPLITUDE_FLOOR_DB = -50f

/** How often the amplitude is sampled, which is also one bar's worth of time. */
private const val SAMPLE_MS = 100L

private fun normalize(amplitude: Int): Float {
    if (amplitude <= 0) return MIN_BAR_LEVEL
    // 32767 is full scale, so this is dBFS: 0 at the top, negative below.
    val db = 20f * log10(amplitude / 32767f)
    val clamped = db.coerceIn(AMPLITUDE_FLOOR_DB, 0f)
    return MIN_BAR_LEVEL + (1f - MIN_BAR_LEVEL) * ((clamped - AMPLITUDE_FLOOR_DB) / -AMPLITUDE_FLOOR_DB)
}

/** Compresses the whole recorded trail down to the fixed number of bars the sent clip carries. */
private fun downsample(samples: List<Float>, bars: Int): List<Float> {
    if (samples.isEmpty()) return List(bars) { MIN_BAR_LEVEL }
    return List(bars) { index ->
        val start = (index.toFloat() / bars * samples.size).toInt()
        val end = maxOf(start + 1, ((index + 1).toFloat() / bars * samples.size).toInt())
        samples.subList(start, minOf(end, samples.size)).average().toFloat()
    }
}

/** The most recent [count] values, left-padded so the row is always full width. */
private fun rollingWindow(samples: List<Float>, count: Int): List<Float> {
    val tail = samples.takeLast(count)
    return if (tail.size < count) List(count - tail.size) { MIN_BAR_LEVEL } + tail else tail
}

/**
 * The composer's voice-message flow: record with a live waveform, then preview the clip before
 * sending it. Ported from `use-voice-recorder.ts`, on `MediaRecorder` and `MediaPlayer` in place of
 * expo-audio — which means the waveform is sampled from `maxAmplitude` on a 100ms tick rather than
 * from the recorder's own metering, and playback position is polled rather than published.
 *
 * Asking for the microphone is the caller's job, through the app's own permission flow; [start]
 * only records, so this never puts up a dialog of its own.
 */
@Stable
class VoiceRecorderState(private val context: Context, private val scope: CoroutineScope) {
    var phase by mutableStateOf(RecorderPhase.IDLE)
        private set
    var isPaused by mutableStateOf(false)
        private set
    var recordingSeconds by mutableFloatStateOf(0f)
        private set
    /** The rolling window drawn while recording. */
    var liveWaveform by mutableStateOf(List(WAVEFORM_BARS) { MIN_BAR_LEVEL })
        private set
    /** The finished shape, once the recording has stopped. */
    var waveform by mutableStateOf(emptyList<Float>())
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var playbackSeconds by mutableFloatStateOf(0f)
        private set

    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var file: File? = null
    private var samples = mutableListOf<Float>()
    private var ticker: Job? = null

    /** 0..1 through the recorded clip, for the preview waveform. */
    val playbackProgress: Float
        get() = if (recordingSeconds > 0f) (playbackSeconds / recordingSeconds).coerceIn(0f, 1f) else 0f

    fun start(): Boolean {
        if (phase != RecorderPhase.IDLE) return false
        val target = File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        val next = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        val started = runCatching {
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioSamplingRate(44_100)
            next.setAudioEncodingBitRate(128_000)
            next.setOutputFile(target.absolutePath)
            next.prepare()
            next.start()
        }.isSuccess
        if (!started) {
            runCatching { next.release() }
            return false
        }
        recorder = next
        file = target
        samples = mutableListOf()
        recordingSeconds = 0f
        isPaused = false
        phase = RecorderPhase.RECORDING
        ticker = scope.launch {
            while (isActive) {
                delay(SAMPLE_MS)
                val current = recorder ?: break
                if (isPaused) continue
                val amplitude = runCatching { current.maxAmplitude }.getOrDefault(0)
                samples.add(normalize(amplitude))
                liveWaveform = rollingWindow(samples, WAVEFORM_BARS)
                recordingSeconds += SAMPLE_MS / 1000f
            }
        }
        return true
    }

    fun pause() {
        val current = recorder ?: return
        if (phase != RecorderPhase.RECORDING || isPaused) return
        runCatching { current.pause() }.onSuccess { isPaused = true }
    }

    fun resume() {
        val current = recorder ?: return
        if (phase != RecorderPhase.RECORDING || !isPaused) return
        runCatching { current.resume() }.onSuccess { isPaused = false }
    }

    /** Ends the recording and moves to preview. A clip too short to have any content is discarded. */
    fun stop() {
        if (phase != RecorderPhase.RECORDING) return
        ticker?.cancel()
        ticker = null
        val current = recorder
        recorder = null
        val ok = runCatching { current?.stop() }.isSuccess
        runCatching { current?.release() }
        isPaused = false
        if (!ok || recordingSeconds < 0.4f) {
            discardFile()
            reset()
            return
        }
        waveform = downsample(samples, WAVEFORM_BARS)
        phase = RecorderPhase.PREVIEW
        playbackSeconds = 0f
    }

    fun togglePlayback() {
        val path = file?.absolutePath ?: return
        val existing = player
        if (existing != null && existing.isPlaying) {
            existing.pause()
            isPlaying = false
            return
        }
        val active = existing ?: MediaPlayer().also { created ->
            runCatching {
                created.setDataSource(path)
                created.prepare()
            }.onFailure {
                created.release()
                return
            }
            created.setOnCompletionListener {
                isPlaying = false
                playbackSeconds = recordingSeconds
            }
            player = created
        }
        // Replaying a clip that has run to the end starts it over rather than doing nothing.
        if (active.currentPosition >= active.duration - 50) active.seekTo(0)
        active.start()
        isPlaying = true
        scope.launch {
            while (isActive && isPlaying) {
                playbackSeconds = (player?.currentPosition ?: 0) / 1000f
                delay(SAMPLE_MS)
            }
        }
    }

    /** The clip as a message, once [phase] is [RecorderPhase.PREVIEW]. */
    fun build(): RecordedClip? {
        val path = file?.absolutePath ?: return null
        if (phase != RecorderPhase.PREVIEW) return null
        return RecordedClip(uri = path, duration = (recordingSeconds * 10).roundToInt() / 10f, waveform = waveform)
    }

    /** Throws the clip away, whichever phase it is in. */
    fun cancel() {
        if (phase == RecorderPhase.RECORDING) {
            ticker?.cancel()
            ticker = null
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            recorder = null
        }
        discardFile()
        reset()
    }

    /** Back to idle, keeping the file — used after the clip has been handed to the chat. */
    fun reset() {
        ticker?.cancel()
        ticker = null
        runCatching { player?.release() }
        player = null
        isPlaying = false
        playbackSeconds = 0f
        recordingSeconds = 0f
        samples = mutableListOf()
        waveform = emptyList()
        liveWaveform = List(WAVEFORM_BARS) { MIN_BAR_LEVEL }
        isPaused = false
        phase = RecorderPhase.IDLE
    }

    private fun discardFile() {
        runCatching { file?.delete() }
        file = null
    }

    /** Leaving the screen mid-recording must not leave the microphone open. */
    internal fun dispose() {
        if (phase != RecorderPhase.IDLE) cancel()
        runCatching { player?.release() }
        player = null
    }
}

@Composable
fun rememberVoiceRecorder(scope: CoroutineScope): VoiceRecorderState {
    val context = LocalContext.current
    val state = remember(context, scope) { VoiceRecorderState(context.applicationContext, scope) }
    DisposableEffect(state) { onDispose { state.dispose() } }
    return state
}
