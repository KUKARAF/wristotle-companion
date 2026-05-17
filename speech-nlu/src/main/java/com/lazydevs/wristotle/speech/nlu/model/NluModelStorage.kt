package com.lazydevs.wristotle.speech.nlu.model

import android.content.Context
import java.io.File

/**
 * Local storage for downloaded NLU sentence-encoder models and the user's
 * active selection. Sibling of `ModelStorage` in :speech-whisper with a
 * different on-disk directory and prefs name so the two model families
 * coexist cleanly.
 *
 * All operations are synchronous and cheap (file metadata + prefs).
 */
class NluModelStorage(context: Context) {

    private val modelsDir: File = File(context.filesDir, MODELS_DIR).apply { mkdirs() }
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Resolves a model id to its target file (may or may not exist). */
    fun modelFile(modelId: String): File = File(modelsDir, "minilm-$modelId.onnx")

    fun isDownloaded(modelId: String): Boolean =
        modelFile(modelId).let { it.exists() && it.length() > 0 }

    fun downloadedIds(): List<String> = modelsDir
        .listFiles { _, name -> name.startsWith("minilm-") && name.endsWith(".onnx") }
        ?.map { it.nameWithoutExtension.removePrefix("minilm-") }
        ?: emptyList()

    fun delete(modelId: String): Boolean {
        val file = modelFile(modelId)
        val absent = !file.exists() || file.delete()
        if (absent && activeModelId == modelId) activeModelId = null
        return absent
    }

    var activeModelId: String?
        get() = prefs.getString(KEY_ACTIVE, null)
        set(value) {
            prefs.edit().also {
                if (value == null) it.remove(KEY_ACTIVE) else it.putString(KEY_ACTIVE, value)
            }.apply()
        }

    fun activeModelPath(): String? = activeModelId
        ?.let(::modelFile)
        ?.takeIf { it.exists() && it.length() > 0 }
        ?.absolutePath

    private companion object {
        const val MODELS_DIR = "nlu-models"
        const val PREFS_NAME = "nlu_models"
        const val KEY_ACTIVE = "active_model_id"
    }
}
