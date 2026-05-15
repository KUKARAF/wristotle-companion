package com.lazydevs.wristotle.speech.whisper

import android.content.Context
import java.io.File

/**
 * Local storage for downloaded Whisper models and the user's active selection.
 *
 * Models live under `context.filesDir/whisper-models/` as `ggml-{id}.bin` files.
 * The currently active model's id is persisted in a private SharedPreferences
 * so a fresh `WhisperRecognizer` can resolve it at startup.
 *
 * All operations are synchronous and cheap (file metadata + prefs); no need
 * to hop dispatchers in callers.
 */
class ModelStorage(context: Context) {

    private val modelsDir: File = File(context.filesDir, MODELS_DIR).apply { mkdirs() }
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Resolves a model id to its target file on disk (may or may not exist). */
    fun modelFile(modelId: String): File = File(modelsDir, "ggml-$modelId.bin")

    /** True iff the model has been downloaded (file exists and is non-empty). */
    fun isDownloaded(modelId: String): Boolean =
        modelFile(modelId).let { it.exists() && it.length() > 0 }

    /** Ids of all currently-downloaded models, in arbitrary order. */
    fun downloadedIds(): List<String> = modelsDir
        .listFiles { _, name -> name.startsWith("ggml-") && name.endsWith(".bin") }
        ?.map { it.nameWithoutExtension.removePrefix("ggml-") }
        ?: emptyList()

    /**
     * Deletes the model file. If it was the active model, [activeModelId] is
     * cleared. Returns true if a file was actually removed.
     */
    fun delete(modelId: String): Boolean {
        val deleted = modelFile(modelId).delete()
        if (deleted && activeModelId == modelId) activeModelId = null
        return deleted
    }

    /** Id of the currently active model, or null if none is set. */
    var activeModelId: String?
        get() = prefs.getString(KEY_ACTIVE, null)
        set(value) {
            prefs.edit().also {
                if (value == null) it.remove(KEY_ACTIVE) else it.putString(KEY_ACTIVE, value)
            }.apply()
        }

    /**
     * Absolute path of the active model file, or null if no model is active or
     * the active model's file has been deleted out from under us.
     */
    fun activeModelPath(): String? = activeModelId
        ?.let(::modelFile)
        ?.takeIf { it.exists() && it.length() > 0 }
        ?.absolutePath

    private companion object {
        const val MODELS_DIR = "whisper-models"
        const val PREFS_NAME = "whisper_models"
        const val KEY_ACTIVE = "active_model_id"
    }
}
