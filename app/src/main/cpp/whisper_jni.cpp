// The bridge between Kotlin and whisper.cpp.
//
// Deliberately small: open a model, transcribe one array of samples, close it. Everything about
// *which* model, where the audio came from and what is done with the text lives in Kotlin, because
// none of it needs to be native and all of it is easier to change there.
//
// The model is opened from a path in the app's own storage, because it is downloaded rather than
// packaged: it is tens of megabytes that most installs never need, and shipping it inside the APK
// charged every user for it on every update. See `SpeechModelStore` for how it gets there.

#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>

#include "whisper.h"

#define TAG "WhisperJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

/// whisper and ggml both write to stderr, which goes nowhere on Android. Routed to logcat so a
/// model that fails to load says why.
void forward_log(ggml_log_level level, const char *text, void *) {
    __android_log_print(
        level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
        : level == GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN
                                       : ANDROID_LOG_DEBUG,
        TAG, "%s", text);
}

/// Trim the leading space whisper puts in front of every segment, and any stray edges.
std::string trimmed(const std::string &text) {
    const auto first = text.find_first_not_of(" \t\n\r");
    if (first == std::string::npos) return {};
    const auto last = text.find_last_not_of(" \t\n\r");
    return text.substr(first, last - first + 1);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_ruskcoder_messages_transcribe_WhisperNative_openModelFile(
    JNIEnv *env, jobject, jstring model_path) {

    whisper_log_set(forward_log, nullptr);
    ggml_log_set(forward_log, nullptr);

    const char *path = env->GetStringUTFChars(model_path, nullptr);

    whisper_context_params params = whisper_context_default_params();
    // No GPU backend is built into this library, and asking for one only costs a failed probe.
    params.use_gpu = false;

    whisper_context *context = whisper_init_from_file_with_params(path, params);
    if (context == nullptr) {
        LOGW("model at %s failed to load", path);
    }
    env->ReleaseStringUTFChars(model_path, path);
    return reinterpret_cast<jlong>(context);
}

JNIEXPORT void JNICALL
Java_com_ruskcoder_messages_transcribe_WhisperNative_closeModel(
    JNIEnv *, jobject, jlong handle) {
    if (handle == 0) return;
    whisper_free(reinterpret_cast<whisper_context *>(handle));
}

JNIEXPORT jstring JNICALL
Java_com_ruskcoder_messages_transcribe_WhisperNative_transcribe(
    JNIEnv *env, jobject, jlong handle, jfloatArray samples, jint threads, jstring language) {

    if (handle == 0) return nullptr;
    auto *context = reinterpret_cast<whisper_context *>(handle);

    jfloat *audio = env->GetFloatArrayElements(samples, nullptr);
    const jsize count = env->GetArrayLength(samples);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.n_threads = threads;
    // Each clip is transcribed on its own. Carrying context between them would let one voice note's
    // words prime the next, and they are not the same utterance.
    params.no_context = true;
    params.single_segment = false;
    // Whisper hallucinates fluent sentences over silence and background noise; these are the two
    // thresholds that suppress most of it on short clips.
    params.suppress_blank = true;
    params.no_speech_thold = 0.6f;

    const char *lang = nullptr;
    if (language != nullptr) {
        lang = env->GetStringUTFChars(language, nullptr);
        params.language = lang;
    }

    std::string text;
    if (whisper_full(context, params, audio, count) == 0) {
        const int segments = whisper_full_n_segments(context);
        for (int i = 0; i < segments; ++i) {
            const char *segment = whisper_full_get_segment_text(context, i);
            if (segment == nullptr) continue;
            if (!text.empty()) text += ' ';
            text += trimmed(segment);
        }
    } else {
        LOGW("whisper_full failed over %d samples", count);
        env->ReleaseFloatArrayElements(samples, audio, JNI_ABORT);
        if (lang != nullptr) env->ReleaseStringUTFChars(language, lang);
        return nullptr;
    }

    env->ReleaseFloatArrayElements(samples, audio, JNI_ABORT);
    if (lang != nullptr) env->ReleaseStringUTFChars(language, lang);

    return env->NewStringUTF(trimmed(text).c_str());
}

} // extern "C"
