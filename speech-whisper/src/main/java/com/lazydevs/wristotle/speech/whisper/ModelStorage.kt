package com.lazydevs.wristotle.speech.whisper

import android.content.Context
import android.util.Log
import com.lazydevs.wristotle.speech.model.ModelFileStorage
import java.io.InputStream

private const val TAG = "WhisperModelStorage"

/**
 * Whisper model storage — files under `filesDir/whisper-models/` named
 * `ggml-{id}.bin` (matching ggerganov/whisper.cpp's conventions so the
 * file can be passed straight to the native loader).
 *
 * Thin wrapper over [ModelFileStorage] for the catalog-downloaded
 * models. Adds the **user-imported model** surface — a separate id
 * namespace (`imported-<epochMs>-<sanitized>`) that shares the same
 * on-disk layout so the native loader path doesn't need to care
 * whether a model came from a catalog download or a Storage Access
 * Framework file pick.
 */
class ModelStorage(context: Context) : ModelFileStorage(
    context = context,
    dirName = "whisper-models",
    prefsName = "whisper_models",
    filenamePrefix = "ggml-",
    filenameExtension = ".bin",
) {

    private val prefs = context.getSharedPreferences("whisper_models", Context.MODE_PRIVATE)

    /**
     * Ids of every user-imported model currently on disk.
     *
     * Imported ids start with [IMPORTED_ID_PREFIX]; everything else in
     * the dir is a catalog download. The base's [downloadedIds] returns
     * all ids — filter here for just the imported subset.
     */
    fun importedIds(): List<String> = downloadedIds().filter { it.startsWith(IMPORTED_ID_PREFIX) }

    /**
     * User-visible name for [importedId]. Persisted as a per-id prefs
     * key so a single-row schema migration isn't needed if/when the
     * display-name shape changes. Falls back to the raw sanitized tail
     * if the prefs entry has been lost (e.g. the user cleared app data
     * but the model file lives on a sideload location they keep), so
     * the UI still has something readable.
     */
    fun importedDisplayName(importedId: String): String =
        prefs.getString(displayNameKey(importedId), null) ?: importedId
            .removePrefix(IMPORTED_ID_PREFIX)
            .substringAfter('-', missingDelimiterValue = "")
            .ifEmpty { importedId }

    /**
     * Copies [input] into a new imported-model slot, returning the
     * resulting id on success.
     *
     * First 4 bytes of the stream are checked against the known ggml
     * magic numbers — accepts `"ggml"` (whisper.cpp ggml-format
     * header) and `"GGUF"` (the unified-ggml container ggml maintainers
     * are migrating to). Any other magic is rejected with
     * [ImportResult.InvalidFormat] *before* the file lands on disk, so
     * garbage picks (PDFs, JPGs, half-downloaded HTML) don't pollute
     * the models dir.
     *
     * Closes [input] on every exit path. The output file is deleted if
     * a failure happens mid-copy.
     *
     * @param displayName what the user typed as the friendly name.
     *        Used verbatim for the prefs entry; only the derived
     *        **filename tail** is sanitized for filesystem safety.
     */
    fun importFromStream(input: InputStream, displayName: String): ImportResult {
        val id = "$IMPORTED_ID_PREFIX${System.currentTimeMillis()}-${sanitizeForFilename(displayName)}"
        val target = modelFile(id)
        try {
            input.use { src ->
                val magic = ByteArray(MAGIC_BYTES)
                val magicRead = readFully(src, magic)
                if (magicRead < MAGIC_BYTES) {
                    return ImportResult.InvalidFormat(
                        "stream ended at $magicRead bytes (need ≥ $MAGIC_BYTES for magic check)",
                    )
                }
                val magicAscii = magic.toString(Charsets.US_ASCII)
                if (magicAscii !in ACCEPTED_MAGICS) {
                    val hex = magic.joinToString("") { "%02x".format(it) }
                    return ImportResult.InvalidFormat(
                        "first 4 bytes 0x$hex (\"$magicAscii\") — expected " +
                            ACCEPTED_MAGICS.joinToString(", ") { "\"$it\"" },
                    )
                }
                target.outputStream().use { out ->
                    out.write(magic)  // already consumed during the magic check
                    val buf = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
            prefs.edit().putString(displayNameKey(id), displayName).apply()
            Log.d(TAG, "imported $id (${target.length()} bytes)")
            return ImportResult.Success(id)
        } catch (t: Throwable) {
            Log.w(TAG, "import failed for $id", t)
            runCatching { target.delete() }
            return ImportResult.IoError(t)
        }
    }

    /**
     * Deletes the imported model and its display-name prefs entry.
     * Returns the same boolean contract as [ModelFileStorage.delete].
     */
    fun deleteImported(importedId: String): Boolean {
        val removed = delete(importedId)
        prefs.edit().remove(displayNameKey(importedId)).apply()
        return removed
    }

    private fun displayNameKey(importedId: String): String = "imported_name_$importedId"

    private fun sanitizeForFilename(raw: String): String {
        val cleaned = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.', '_', '-')
            .ifEmpty { "model" }
        return cleaned.take(MAX_FILENAME_TAIL_CHARS)
    }

    /**
     * Reads up to [out].size bytes from [input], blocking until enough
     * are available or EOF. Returns the actual number read. Needed
     * because [InputStream.read] can return short reads even when more
     * bytes are coming — we want to know if we genuinely saw EOF before
     * the magic-check size.
     */
    private fun readFully(input: InputStream, out: ByteArray): Int {
        var total = 0
        while (total < out.size) {
            val n = input.read(out, total, out.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    companion object {
        private const val IMPORTED_ID_PREFIX = "imported-"
        private const val MAGIC_BYTES = 4
        private const val BUFFER_BYTES = 64 * 1024
        private const val MAX_FILENAME_TAIL_CHARS = 32

        /** Magic byte sequences we trust as valid whisper.cpp model
         *  files. `lmgg` is the on-disk encoding of whisper.cpp's
         *  `GGML_FILE_MAGIC` (`0x67676d6c`) — the constant reads
         *  "ggml" when printed as a hex number, but it's written to
         *  disk as a little-endian uint32, so the actual first four
         *  bytes are 0x6c 0x6d 0x67 0x67 → "lmgg". `GGUF` covers the
         *  newer unified-ggml container, which by spec stores its
         *  magic as the literal ASCII bytes "GGUF" in order. */
        private val ACCEPTED_MAGICS = setOf("lmgg", "GGUF")
    }
}

/** Outcome of [ModelStorage.importFromStream]. */
sealed class ImportResult {
    data class Success(val id: String) : ImportResult()
    data class InvalidFormat(val detail: String) : ImportResult()
    data class IoError(val cause: Throwable) : ImportResult()
}
