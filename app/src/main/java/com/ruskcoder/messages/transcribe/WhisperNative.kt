package com.ruskcoder.messages.transcribe

/**
 * The raw calls into `libwhisper_jni.so`. Nothing here is safe to use directly — see
 * [VoiceTranscriber], which owns the one model handle and the thread it may be touched from.
 *
 * A handle is a native pointer. Using one after [closeModel], or from two threads at once, is a
 * process crash rather than an exception.
 */
internal object WhisperNative {

    private var available = false

    init {
        available = runCatching { System.loadLibrary("whisper_jni") }.isSuccess
    }

    /** False on a device whose ABI the library was not built for; nothing else here works then. */
    val isAvailable: Boolean get() = available

    /** Opens the model file at [path]. 0 if it is missing, truncated, or not a model at all. */
    external fun openModelFile(path: String): Long

    external fun closeModel(handle: Long)

    /**
     * Transcribes [samples] — 16kHz mono, normalized to -1..1 — returning null if the run failed.
     *
     * Blocks for as long as the model takes, which for a minute of speech is seconds, not
     * milliseconds.
     */
    external fun transcribe(handle: Long, samples: FloatArray, threads: Int, language: String?): String?
}
