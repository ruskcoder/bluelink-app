package com.ruskcoder.messages.transcribe

import android.util.Log
import com.ruskcoder.messages.util.AudioPcm
import com.ruskcoder.messages.util.AudioPcm.monoAt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Turns a voice message into text, on the device.
 *
 * Whisper, quantized, running against a model downloaded to this device — so this works with no
 * network, sends no audio anywhere, and costs nothing per use. Those are the reasons it is a local
 * model rather than a call to a hosted recogniser: a voice note is somebody's voice, and it is the
 * one kind of message where "we uploaded it to transcribe it" is a thing the app would be doing
 * behind everyone's back.
 *
 * One model, one at a time. Loading one costs tens of megabytes of native memory, so there is a
 * single handle for the process; and whisper's context is not re-entrant, so [transcribe] holds a
 * mutex for the length of a run. Two clips pressed at once queue up rather than racing, which for a
 * job measured in seconds is the right trade against a second copy of the weights.
 */
class VoiceTranscriber(
    private val models: SpeechModelStore,
    /** Which model to use, read fresh on every run so a change in settings takes effect at once. */
    private val chosen: () -> SpeechModel,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** What came of asking for a transcription — the failures are all things the UI must say. */
    sealed class Result {
        data class Text(val text: String) : Result()

        /** The clip ran, and there were no words in it. */
        data object NoSpeech : Result()

        /** No model is installed, so there is nothing to transcribe with yet. */
        data object NoModel : Result()

        /** A model is installed but would not load, or this device has no build of the library. */
        data object Unavailable : Result()

        /** The audio could not be read, or the run itself failed. */
        data object Failed : Result()
    }

    private val lock = Mutex()

    /** 0 until a model has been loaded. */
    private var handle = 0L

    /** Which model [handle] is, so changing the setting swaps the loaded one rather than being ignored. */
    private var loaded: SpeechModel? = null

    /** The model that failed, so a hopeless load is not retried on every press — but a different one is. */
    private var failed: SpeechModel? = null

    /** Whether transcription can be offered at all on this device. */
    val isSupported: Boolean get() = WhisperNative.isAvailable

    /**
     * Transcribe the clip at [path].
     *
     * Runs on [io] and can take several seconds; the caller is expected to be a coroutine that can
     * be left waiting, and to have said so on screen.
     */
    suspend fun transcribe(path: String): Result = withContext(io) {
        val file = File(path)
        if (!file.isFile) {
            Log.w(TAG, "nothing to transcribe at $path")
            return@withContext Result.Failed
        }

        // Decoded before the lock is taken: it needs no model, and doing it inside would make every
        // queued clip wait through the one in front of it twice over.
        val audio = AudioPcm.read(file)
        if (audio == null) {
            Log.w(TAG, "could not read ${file.name} as audio")
            return@withContext Result.Failed
        }
        val samples = audio.monoAt(SAMPLE_RATE)
        if (samples.size < MIN_SAMPLES) return@withContext Result.NoSpeech

        lock.withLock {
            val wanted = chosen()
            if (!models.isInstalled(wanted)) return@withContext Result.NoModel
            val model = open(wanted)
            if (model == 0L) return@withContext Result.Unavailable

            val text = WhisperNative.transcribe(model, samples, threads(), LANGUAGE)
                ?: return@withContext Result.Failed

            // Whisper answers a clip with no speech in it with its own punctuation — a lone "." or
            // a bracketed noise tag like "(wind blowing)". Neither is a transcription.
            val cleaned = text.trim().removeSurrounding("(", ")").removeSurrounding("[", "]").trim()
            if (cleaned.none { it.isLetterOrDigit() }) Result.NoSpeech else Result.Text(text.trim())
        }
    }

    /**
     * The handle for [wanted], loading it if it is not already the one that is open.
     *
     * Called only under [lock]. A model stays loaded once opened: reloading per clip would add
     * seconds to every transcription, and the memory only goes back when the process does — which
     * for a messaging app in the background is something the system already handles. Choosing a
     * different model in settings closes the old one first, because two of these will not fit.
     */
    private fun open(wanted: SpeechModel): Long {
        if (handle != 0L && loaded == wanted) return handle
        if (failed == wanted) return 0L
        if (!WhisperNative.isAvailable) {
            Log.w(TAG, "no native library for this ABI")
            failed = wanted
            return 0L
        }

        if (handle != 0L) {
            Log.i(TAG, "swapping ${loaded?.fileName} for ${wanted.fileName}")
            WhisperNative.closeModel(handle)
            handle = 0L
            loaded = null
        }

        val started = System.currentTimeMillis()
        handle = WhisperNative.openModelFile(models.fileFor(wanted).absolutePath)
        if (handle == 0L) {
            Log.e(TAG, "could not load ${wanted.fileName}")
            failed = wanted
        } else {
            loaded = wanted
            failed = null
            Log.i(TAG, "loaded ${wanted.fileName} in ${System.currentTimeMillis() - started}ms")
        }
        return handle
    }

    /**
     * How many cores to give the model.
     *
     * Capped rather than taking everything: this runs while the user is reading the conversation
     * it is transcribing, and a phone with every core saturated drops frames in the thread behind
     * it. One core is also left alone for whatever else the app is doing.
     */
    private fun threads(): Int =
        (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, MAX_THREADS)

    private companion object {
        const val TAG = "VoiceTranscriber"

        /** What whisper's front end expects; anything else has to be resampled to it. */
        const val SAMPLE_RATE = 16_000

        /** An English-only model, so the language is not worth detecting. */
        const val LANGUAGE = "en"

        /** Quarter of a second. Below this there is nothing for the model to find. */
        const val MIN_SAMPLES = SAMPLE_RATE / 4

        const val MAX_THREADS = 4
    }
}
