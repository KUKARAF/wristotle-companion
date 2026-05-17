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
#include <chrono>
#include <cmath>
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

// Energy-based silence trim. Watch dictations routinely include a beat of
// silence at the start (between the user pressing SELECT and starting to
// talk) and the end (waiting for the watch's silence detector to fire).
// Whisper's encoder runs over the entire audio buffer regardless of
// content, so trimming that silence drops encoder work when it pushes
// us down an audio_ctx tier (e.g. 5.5 s of audio with 0.8 s of leading
// silence → 4.7 s fits the tier-256 window instead of needing tier-512,
// halving the encoder cost).
//
// Conservative thresholds: padding stays generous so we never clip a
// leading/trailing consonant, and we bail out (no trim) if the result
// would be too short or if barely any silence was found — those are
// the cases where overhead outweighs benefit.
struct TrimRange {
    size_t start;
    size_t end;
    bool   trimmed;
};

constexpr size_t FRAME_SAMPLES     = 160;      // 10 ms at 16 kHz
constexpr float  RMS_THRESHOLD     = 0.015f;   // ≈ -36 dBFS, just above mic noise floor
constexpr size_t PAD_FRAMES        = 32;       // 320 ms of context kept on each side
constexpr size_t MIN_RESULT_FRAMES = 50;       // never trim shorter than 500 ms
// Require this many *consecutive* loud frames before we believe a region
// is speech. Watch dictations start with a sharp 1–2 frame click (SELECT
// button or BLE/Speex transient) that sits well above the noise floor;
// a single-frame check would treat it as the start of speech and we'd
// fail to trim the silence between the click and the first phoneme.
// Real speech sustains far longer than 50 ms.
constexpr size_t SUSTAINED_FRAMES  = 5;        // 50 ms

TrimRange trim_silence(const float* pcm, size_t n_samples) {
    const size_t n_frames = n_samples / FRAME_SAMPLES;
    TrimRange r{0, n_samples, false};
    if (n_frames < MIN_RESULT_FRAMES) return r;

    // Per-frame RMS first, then scan for sustained runs in both
    // directions. Computing RMS once avoids re-scanning sample memory
    // during the streak passes.
    std::vector<bool> active(n_frames, false);
    for (size_t f = 0; f < n_frames; ++f) {
        double sumsq = 0.0;
        const float* base = pcm + f * FRAME_SAMPLES;
        for (size_t i = 0; i < FRAME_SAMPLES; ++i) sumsq += base[i] * base[i];
        const float rms = std::sqrt(static_cast<float>(sumsq / FRAME_SAMPLES));
        active[f] = (rms >= RMS_THRESHOLD);
    }

    // First sustained-active run → start of speech.
    size_t first_speech = n_frames;
    {
        size_t streak = 0;
        for (size_t f = 0; f < n_frames; ++f) {
            if (active[f]) {
                if (++streak >= SUSTAINED_FRAMES) {
                    first_speech = f + 1 - SUSTAINED_FRAMES;
                    break;
                }
            } else {
                streak = 0;
            }
        }
    }
    if (first_speech >= n_frames) return r;

    // Last sustained-active run → end of speech (scan from the tail).
    size_t last_speech = first_speech;
    {
        size_t streak = 0;
        for (size_t f = n_frames; f-- > 0; ) {
            if (active[f]) {
                if (++streak >= SUSTAINED_FRAMES) {
                    last_speech = f + SUSTAINED_FRAMES - 1;
                    break;
                }
            } else {
                streak = 0;
            }
        }
    }

    const size_t pad_start = first_speech > PAD_FRAMES ? first_speech - PAD_FRAMES : 0;
    const size_t pad_end   = std::min(last_speech + 1 + PAD_FRAMES, n_frames);
    if (pad_end - pad_start < MIN_RESULT_FRAMES) return r;
    // Skip if we'd trim less than ~200 ms total — overhead isn't worth it
    // and any trim that small can't change the audio_ctx tier.
    const size_t trimmed_frames = (pad_start) + (n_frames - pad_end);
    if (trimmed_frames < 20) return r;

    r.start   = pad_start * FRAME_SAMPLES;
    r.end     = pad_end   * FRAME_SAMPLES;
    r.trimmed = true;
    return r;
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

    // Drop leading/trailing silence so the encoder doesn't burn cycles
    // on dead air. Trim is done in-place on the float vector via a
    // sub-range; we feed whisper_full the active slice.
    const TrimRange trim = trim_silence(pcm32.data(), static_cast<size_t>(n));
    const float* pcm_ptr = pcm32.data() + trim.start;
    const int    pcm_n   = static_cast<int>(trim.end - trim.start);
    if (trim.trimmed) {
        LOGI("trimmed silence: %d → %d samples (%.2fs → %.2fs)",
             static_cast<int>(n), pcm_n,
             static_cast<float>(n) / 16000.0f,
             static_cast<float>(pcm_n) / 16000.0f);
    }

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
    // Adaptive encoder attention window. Each mel frame is 20 ms, so 1500
    // frames is whisper's full 30 s context. Picking a value larger than the
    // actual audio length wastes encoder compute on silence padding, which
    // dominates inference cost on short utterances.
    //
    // Tiered: each step picks the smallest frame count that still covers the
    // audio length with a safety margin. The previous single-threshold
    // (8 s → 768, else 0/1500) doubled inference cost for 8-15 s audio for
    // no benefit (768 actually covers 15.36 s). Measured 15 s audio: ~9 s
    // inference → ~1.2 s on Pixel 10a + base.en.
    // Compute against the *trimmed* length so trimming can drop the tier
    // (e.g. 5.5 s with 0.8 s of leading silence becomes 4.7 s → tier 256).
    const float audio_seconds = static_cast<float>(pcm_n) / 16000.0f;
    int audio_ctx;
    if      (audio_seconds <=  5.0f) audio_ctx = 256;   // covers 5.12 s
    else if (audio_seconds <= 10.0f) audio_ctx = 512;   // covers 10.24 s
    else if (audio_seconds <= 15.0f) audio_ctx = 768;   // covers 15.36 s
    else if (audio_seconds <= 20.0f) audio_ctx = 1024;  // covers 20.48 s
    else                              audio_ctx = 0;     // full 1500, ~30 s
    wparams.audio_ctx        = audio_ctx;

    LOGI("transcribe: %d samples, lang=%s, threads=%d, audio_ctx=%d",
         pcm_n, lang.c_str(), wparams.n_threads, wparams.audio_ctx);

    // Reset so the timings struct reflects ONLY this call instead of the
    // cumulative numbers whisper.cpp would otherwise return.
    whisper_reset_timings(ctx);

    const auto t_full_start = std::chrono::steady_clock::now();
    const int rc = whisper_full(ctx, wparams, pcm_ptr, pcm_n);
    const auto t_full_end = std::chrono::steady_clock::now();
    if (rc != 0) {
        throw_runtime(env, "whisper_full returned non-zero");
        return nullptr;
    }

    // Per-phase numbers from whisper.cpp itself + wall-clock total so a
    // future investigator can tell whether a slow run was burned in the
    // encoder (memory-bound, cache/thermal) or the decoder (compute-bound,
    // thread contention) — separated from JNI / PCM-conversion overhead.
    const auto wall_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        t_full_end - t_full_start).count();
    const whisper_timings* timings = whisper_get_timings(ctx);
    if (timings != nullptr) {
        LOGI("timings: wall=%lldms encode=%.0fms decode=%.0fms sample=%.0fms prompt=%.0fms batchd=%.0fms",
             static_cast<long long>(wall_ms),
             timings->encode_ms, timings->decode_ms,
             timings->sample_ms, timings->prompt_ms, timings->batchd_ms);
    } else {
        LOGI("timings: wall=%lldms (whisper_get_timings returned null)",
             static_cast<long long>(wall_ms));
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
