// Phase 2a JNI hello-world. Validates the NDK + CMake + JNI pipeline before
// Phase 2b drops whisper.cpp in here. The single `addTwo` function gives the
// Kotlin side something to call and assert against.

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "wristotle_speech"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jint JNICALL
Java_com_lazydevs_wristotle_speech_whisper_WhisperNative_addTwo(
    JNIEnv* env, jobject /* this */, jint a, jint b) {
    jint result = a + b;
    LOGI("addTwo(%d, %d) = %d", a, b, result);
    return result;
}
