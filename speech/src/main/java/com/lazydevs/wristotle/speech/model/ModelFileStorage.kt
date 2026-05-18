package com.lazydevs.wristotle.speech.model

import android.content.Context
import java.io.File

/**
 * Shared on-disk model storage + active-id preference. Each model
 * family (Whisper, NLU MiniLM, …) used to ship its own near-identical
 * copy; this base class encapsulates the common shape and per-family
 * subclasses only declare their dir / prefs / filename convention.
 *
 * Open (not final) so subclasses can add family-specific helpers
 * without forcing everything through the base API. All operations are
 * synchronous and cheap (file metadata + prefs); no need to hop
 * dispatchers in callers.
 */
open class ModelFileStorage(
    context: Context,
    dirName: String,
    prefsName: String,
    private val filenamePrefix: String,
    private val filenameExtension: String,
) {

    private val modelsDir: File = File(context.filesDir, dirName).apply { mkdirs() }
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /** Resolves a model id to its target file on disk (may or may not exist). */
    fun modelFile(modelId: String): File =
        File(modelsDir, "$filenamePrefix$modelId$filenameExtension")

    /** True iff the model has been downloaded (file exists and is non-empty). */
    fun isDownloaded(modelId: String): Boolean =
        modelFile(modelId).let { it.exists() && it.length() > 0 }

    /** Ids of all currently-downloaded models, in arbitrary order. */
    fun downloadedIds(): List<String> = modelsDir
        .listFiles { _, name -> name.startsWith(filenamePrefix) && name.endsWith(filenameExtension) }
        ?.map { it.name.removePrefix(filenamePrefix).removeSuffix(filenameExtension) }
        ?: emptyList()

    /**
     * Ensures the model file is absent. If it was the active model,
     * [activeModelId] is cleared. Returns true if the postcondition holds —
     * either the file was just removed or it didn't exist to begin with.
     */
    fun delete(modelId: String): Boolean {
        val file = modelFile(modelId)
        val absent = !file.exists() || file.delete()
        if (absent && activeModelId == modelId) activeModelId = null
        return absent
    }

    /** Id of the currently active model, or null when none is set. */
    var activeModelId: String?
        get() = prefs.getString(KEY_ACTIVE, null)
        set(value) {
            prefs.edit().also {
                if (value == null) it.remove(KEY_ACTIVE) else it.putString(KEY_ACTIVE, value)
            }.apply()
        }

    /**
     * Absolute path of the active model file, or null when no model is
     * active or its file has been deleted out from under us.
     */
    fun activeModelPath(): String? = activeModelId
        ?.let(::modelFile)
        ?.takeIf { it.exists() && it.length() > 0 }
        ?.absolutePath

    private companion object {
        const val KEY_ACTIVE = "active_model_id"
    }
}
