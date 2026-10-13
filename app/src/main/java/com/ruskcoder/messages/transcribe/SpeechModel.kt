package com.ruskcoder.messages.transcribe

import kotlinx.serialization.Serializable

/**
 * The speech models on offer, and what choosing one costs.
 *
 * Whisper, quantized to five bits — the quantization is most of why these are small enough to carry
 * on a phone at all, and costs very little accuracy against the full-precision weights. English
 * only: the multilingual builds are the same size but read English slightly worse, and this is a
 * messaging app whose transcription is there to skim a voice note rather than to translate one.
 *
 * The digests are the files' own, taken from the model host, and every download is checked against
 * them before it is installed — see [SpeechModelStore]. They are what makes the download safe to do
 * over any connection: a model that arrives altered never becomes the model that runs.
 */
@Serializable
enum class SpeechModel(
    val fileName: String,
    val label: String,
    /** What picking this one gets you, in the words somebody choosing would want. */
    val summary: String,
    val bytes: Long,
    val sha256: String,
) {
    TINY(
        fileName = "ggml-tiny.en-q5_1.bin",
        label = "Tiny",
        summary = "Fastest, and the least accurate. Fine for clear speech; drops names and numbers.",
        bytes = 32_166_155L,
        sha256 = "c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b",
    ),
    BASE(
        fileName = "ggml-base.en-q5_1.bin",
        label = "Base",
        summary = "A good balance. Handles ordinary voice notes well and takes a few seconds.",
        bytes = 59_721_011L,
        sha256 = "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f",
    ),
    SMALL(
        fileName = "ggml-small.en-q5_1.bin",
        label = "Small",
        summary = "The most accurate, and several times slower. Worth it for noisy or accented audio.",
        bytes = 190_098_681L,
        sha256 = "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30",
    );

    /** Where the file is fetched from. The whisper.cpp project's own published weights. */
    val url: String get() = "$HOST/$fileName"

    /** Rounded for display — "57 MB" rather than 59,721,011 bytes. */
    val megabytes: Int get() = ((bytes + 524_288L) / 1_048_576L).toInt()

    companion object {
        private const val HOST = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

        /** What a fresh install offers, and what an unreadable stored preference falls back to. */
        val DEFAULT = BASE
    }
}
