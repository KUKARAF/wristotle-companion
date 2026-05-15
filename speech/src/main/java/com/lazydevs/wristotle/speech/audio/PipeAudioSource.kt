package com.lazydevs.wristotle.speech.audio

import android.media.AudioFormat
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "PipeAudioSource"

/**
 * Reads PCM audio from a [ParcelFileDescriptor] passed via
 * [android.speech.RecognizerIntent.EXTRA_AUDIO_SOURCE] (API 33+).
 *
 * Terminates the flow on EOF — that's the caller's signal that they're done
 * feeding audio and want a final transcript. Closes the fd in either exit path
 * (EOF or cancellation).
 *
 * Currently constrained to 16-bit little-endian PCM mono — Whisper's input format.
 * Other encodings would need a resampler/converter; out of scope for v1.
 */
class PipeAudioSource(
    private val fd: ParcelFileDescriptor,
    override val sampleRate: Int = 16_000,
    override val channelCount: Int = 1,
    encoding: Int = AudioFormat.ENCODING_PCM_16BIT,
) : AudioSource {

    init {
        require(encoding == AudioFormat.ENCODING_PCM_16BIT) {
            "Only ENCODING_PCM_16BIT supported (got $encoding)"
        }
        require(channelCount == 1) {
            "Only mono supported (got $channelCount channels)"
        }
    }

    @Volatile private var stopRequested = false

    /** Closing the fd unblocks any in-progress read on the worker thread. */
    override fun stop() {
        stopRequested = true
        runCatching { fd.close() }
    }

    override fun samples(): Flow<ShortArray> = flow {
        val bytesPerChunk = sampleRate / 10 * 2  // 100 ms
        val byteBuf = ByteArray(bytesPerChunk)
        var chunkIdx = 0
        var totalBytes = 0L
        Log.d(TAG, "pipe read loop starting")

        ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
            while (!stopRequested) {
                val read = try {
                    input.read(byteBuf)
                } catch (e: IOException) {
                    // Treat "pipe closed by writer" as natural EOF rather than an error —
                    // many callers (e.g. rePebble's TranscriptionProviderImpl) close their
                    // end abruptly when the audio stream ends.
                    Log.d(TAG, "pipe IOException after $chunkIdx chunks (${totalBytes}B): ${e.message} — treating as EOF")
                    break
                }
                if (read < 0) {
                    Log.d(TAG, "pipe EOF after $chunkIdx chunks (${totalBytes}B)")
                    break
                }
                if (read == 0) continue  // Defensive: shouldn't happen on blocking read.

                val shortCount = read / 2
                val shorts = ShortArray(shortCount)
                val bb = ByteBuffer.wrap(byteBuf, 0, shortCount * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until shortCount) shorts[i] = bb.short
                chunkIdx++
                totalBytes += read
                emit(shorts)
            }
        }
        Log.d(TAG, "pipe read loop ended (chunks=$chunkIdx bytes=$totalBytes stop=$stopRequested)")
    }.flowOn(Dispatchers.IO)
}
