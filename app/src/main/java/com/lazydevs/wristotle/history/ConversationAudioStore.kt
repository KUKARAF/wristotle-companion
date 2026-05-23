package com.lazydevs.wristotle.history

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "ConversationAudioStore"

/**
 * Owns the on-disk conversation-audio directory and the per-call WAV
 * writes that back the inline-playback button on the Conversation
 * screen.
 *
 * - Files live at `filesDir/conversation-audio/<epoch_ms>-<samples>.wav`.
 * - On every save, evicts files in FIFO order until at most
 *   [ConversationAudioSettings.MAX_FILES] remain. Conversation entries
 *   whose audio has been evicted simply lose their play button — the UI
 *   checks file existence before rendering it.
 * - Failures swallow + log so the recognition path never breaks because
 *   of audio-storage trouble.
 */
class ConversationAudioStore(context: Context) {

    /** The on-disk directory holding the `.wav` files. Read-only access for
     *  callers that need to enumerate files (e.g. the backup exporter). */
    val dir: File = File(context.filesDir, DIR_NAME)

    /** Persists [samples] as a 16-bit PCM mono WAV. Returns the file on success, null on failure. */
    fun save(samples: ShortArray, sampleRate: Int): File? {
        return runCatching {
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "couldn't create $dir")
                return null
            }
            val file = File(dir, "${System.currentTimeMillis()}-${samples.size}.wav")
            writeWav(file, samples, sampleRate)
            Log.d(TAG, "saved ${samples.size} samples to ${file.absolutePath}")
            prune(ConversationAudioSettings.MAX_FILES)
            file
        }.onFailure { Log.w(TAG, "save failed", it) }.getOrNull()
    }

    /** Removes the file at [path] if it exists. Safe to call with a stale or invalid path. */
    fun delete(path: String) {
        runCatching {
            val f = File(path)
            if (f.parentFile?.absolutePath == dir.absolutePath && f.exists()) f.delete()
        }.onFailure { Log.w(TAG, "delete($path) failed", it) }
    }

    /** Wipes every captured audio file. Called when the user disables the feature or clears history. */
    fun deleteAll() {
        runCatching {
            dir.listFiles()?.forEach { it.delete() }
        }.onFailure { Log.w(TAG, "deleteAll failed", it) }
    }

    private fun writeWav(file: File, samples: ShortArray, sampleRate: Int) {
        val dataSize = samples.size * 2
        val totalSize = HEADER_SIZE - 8 + dataSize

        val bytes = ByteBuffer.allocate(HEADER_SIZE + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray(Charsets.US_ASCII))
        bytes.putInt(totalSize)
        bytes.put("WAVE".toByteArray(Charsets.US_ASCII))
        bytes.put("fmt ".toByteArray(Charsets.US_ASCII))
        bytes.putInt(16)
        bytes.putShort(1)             // PCM
        bytes.putShort(1)             // mono
        bytes.putInt(sampleRate)
        bytes.putInt(sampleRate * 2)  // byte rate
        bytes.putShort(2)             // block align
        bytes.putShort(16)            // bits per sample
        bytes.put("data".toByteArray(Charsets.US_ASCII))
        bytes.putInt(dataSize)
        for (s in samples) bytes.putShort(s)

        FileOutputStream(file).use { it.write(bytes.array()) }
    }

    private fun prune(maxFiles: Int) {
        val files = dir.listFiles { _, name -> name.endsWith(".wav") } ?: return
        if (files.size <= maxFiles) return
        files.sortedBy { it.lastModified() }
            .take(files.size - maxFiles)
            .forEach { runCatching { it.delete() } }
    }

    private companion object {
        const val DIR_NAME = "conversation-audio"
        const val HEADER_SIZE = 44
    }
}
