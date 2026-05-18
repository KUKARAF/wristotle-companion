package com.lazydevs.wristotle.speech.whisper

import android.content.Context
import com.lazydevs.wristotle.speech.model.ModelFileStorage

/**
 * Whisper model storage — files under `filesDir/whisper-models/` named
 * `ggml-{id}.bin` (matching ggerganov/whisper.cpp's conventions so the
 * file can be passed straight to the native loader).
 *
 * Thin wrapper over [ModelFileStorage]; the shared base owns the I/O
 * and prefs plumbing.
 */
class ModelStorage(context: Context) : ModelFileStorage(
    context = context,
    dirName = "whisper-models",
    prefsName = "whisper_models",
    filenamePrefix = "ggml-",
    filenameExtension = ".bin",
)
