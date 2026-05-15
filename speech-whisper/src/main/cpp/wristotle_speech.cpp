// JNI bridge into whisper.cpp.
//
// Exposes three functions to Kotlin:
//   - loadModel(path)        → opens a whisper_context, returns it as a jlong
//   - transcribe(h, pcm, lang) → runs whisper_full, returns concatenated text
//   - freeModel(h)           → whisper_free
//
// All errors surface to Kotlin as RuntimeException so the call sites don't need
// to manage native-side error codes. PCM-16 → float conversion happens here so
// callers can keep their ShortArray buffers cheap.

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <string>
#include <vector>

#include "whisper.h"

#define LOG_TAG "wristotle_speech"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

void throw_runtime(JNIEnv* env, const char* msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    env->ThrowNew(cls, msg);
    env->DeleteLocalRef(cls);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_lazydevs_wristotle_speech_whisper_WhisperNative_loadModel(
    JNIEnv* env, jobject /*this*/, jstring jpath) {

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (path == nullptr) {
        throw_runtime(env, "loadModel: null model path");
        return 0;
    }

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;  // Android: CPU only — no NNAPI / OpenCL path enabled.

    whisper_context* ctx = whisper_init_from_file_with_params(path, cparams);
    LOGI("loadModel('%s') → %p", path, ctx);
    env->ReleaseStringUTFChars(jpath, path);

    if (ctx == nullptr) {
        throw_runtime(env, "whisper_init_from_file_with_params returned null");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_lazydevs_wristotle_speech_whisper_WhisperNative_transcribe(
    JNIEnv* env, jobject /*this*/,
    jlong handle, jshortArray jsamples, jstring jlang, jint nThreads) {

    whisper_context* ctx = reinterpret_cast<whisper_context*>(handle);
    if (ctx == nullptr) {
        throw_runtime(env, "transcribe: null whisper context handle");
        return nullptr;
    }

    // PCM-16 mono → float32 in [-1.0, 1.0].
    jsize n = env->GetArrayLength(jsamples);
    jshort* samples = env->GetShortArrayElements(jsamples, nullptr);
    if (samples == nullptr) {
        throw_runtime(env, "transcribe: failed to access ShortArray");
        return nullptr;
    }

    std::vector<float> pcm32(static_cast<size_t>(n));
    constexpr float SCALE = 1.0f / 32768.0f;
    for (jsize i = 0; i < n; ++i) {
        pcm32[i] = static_cast<float>(samples[i]) * SCALE;
    }
    env->ReleaseShortArrayElements(jsamples, samples, JNI_ABORT);

    // Language code; "en" if null/empty.
    std::string lang = "en";
    if (jlang != nullptr) {
        const char* l = env->GetStringUTFChars(jlang, nullptr);
        if (l != nullptr) {
            if (l[0] != '\0') lang = l;
            env->ReleaseStringUTFChars(jlang, l);
        }
    }

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress   = false;
    wparams.print_special    = false;
    wparams.print_realtime   = false;
    wparams.print_timestamps = false;
    wparams.translate        = false;
    wparams.language         = lang.c_str();
    // Thread count is decided by the caller (see WhisperRecognizer.kt); clamp
    // defensively so a bogus value can't crash the inference.
    wparams.n_threads        = nThreads > 0 ? nThreads : 4;
    // Treat each transcribe() as one logical utterance with no carry-over from
    // any previous call. Skip whisper's internal segment-splitting since
    // SpeechRecognizer consumers expect a single transcript per session.
    wparams.single_segment   = true;
    wparams.no_context       = true;
    // Adaptive encoder attention window. Whisper's full 30s context (1500 mel
    // frames) is wasted on short utterances — trim to ~10s (768) when the
    // input fits, fall back to the default (0 → 1500) for anything longer.
    // Drops encoder compute by roughly half on the typical short utterance
    // without affecting accuracy.
    constexpr float SHORT_AUDIO_THRESHOLD_SECS = 8.0f;
    const float audio_seconds = static_cast<float>(n) / 16000.0f;
    wparams.audio_ctx        = (audio_seconds <= SHORT_AUDIO_THRESHOLD_SECS) ? 768 : 0;

    LOGI("transcribe: %d samples, lang=%s, threads=%d, audio_ctx=%d",
         static_cast<int>(n), lang.c_str(), wparams.n_threads, wparams.audio_ctx);
    if (whisper_full(ctx, wparams, pcm32.data(), static_cast<int>(pcm32.size())) != 0) {
        throw_runtime(env, "whisper_full returned non-zero");
        return nullptr;
    }

    std::string text;
    const int n_seg = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_seg; ++i) {
        const char* seg = whisper_full_get_segment_text(ctx, i);
        if (seg != nullptr) text += seg;
    }

    LOGI("transcribe done: %d segments, %zu chars", n_seg, text.size());
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_lazydevs_wristotle_speech_whisper_WhisperNative_freeModel(
    JNIEnv* /*env*/, jobject /*this*/, jlong handle) {

    whisper_context* ctx = reinterpret_cast<whisper_context*>(handle);
    if (ctx != nullptr) {
        LOGI("freeModel(%p)", ctx);
        whisper_free(ctx);
    }
}
