package com.lazydevs.wristotle.speech.whisper

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "ModelDownloader"

/** Stream of events the downloader emits during a model download. */
sealed interface DownloadEvent {
    /**
     * Periodic progress update. [totalBytes] is null if the server didn't
     * advertise a length (UI should fall back to indeterminate progress).
     */
    data class Progress(val bytesDownloaded: Long, val totalBytes: Long?) : DownloadEvent

    /** Terminal: the file is fully downloaded and ready to load. */
    data object Complete : DownloadEvent

    /** Terminal: download failed. The partial file remains for a future resume. */
    data class Failed(val message: String, val cause: Throwable? = null) : DownloadEvent
}

/**
 * Downloads Whisper model files into [ModelStorage] with resumable HTTP and
 * throttled progress events.
 *
 * If the local file is present and shorter than [ModelInfo.approxSizeBytes], the
 * downloader sends `Range: bytes=N-` and appends. If the server refuses the
 * range (replies with 200 instead of 206), the file is overwritten from byte 0.
 *
 * Cancelling the collecting coroutine aborts the download cleanly via Kotlin
 * coroutine cancellation; the partial file remains on disk for a future resume.
 */
class ModelDownloader(private val storage: ModelStorage) {

    fun download(model: ModelInfo): Flow<DownloadEvent> = flow {
        val target = storage.modelFile(model.id)
        val existing = if (target.exists()) target.length() else 0L

        if (existing > 0L && model.approxSizeBytes > 0L && existing >= model.approxSizeBytes) {
            Log.d(TAG, "${model.id}: already at $existing bytes — nothing to do")
            emit(DownloadEvent.Complete)
            return@flow
        }

        val connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            val code = connection.responseCode
            val resuming = code == HttpURLConnection.HTTP_PARTIAL
            val fresh = code == HttpURLConnection.HTTP_OK
            if (!resuming && !fresh) {
                emit(DownloadEvent.Failed("HTTP $code from ${model.url}"))
                return@flow
            }

            val contentLength = connection.contentLengthLong.takeIf { it > 0L }
            val totalBytes = when {
                resuming && contentLength != null -> existing + contentLength
                else -> contentLength
            }

            var downloaded = if (resuming) existing else 0L
            var sinceEmit = 0L
            emit(DownloadEvent.Progress(downloaded, totalBytes))

            connection.inputStream.use { input ->
                FileOutputStream(target, /* append = */ resuming).use { output ->
                    val buf = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        output.write(buf, 0, read)
                        downloaded += read
                        sinceEmit += read
                        if (sinceEmit >= EMIT_EVERY_BYTES) {
                            emit(DownloadEvent.Progress(downloaded, totalBytes))
                            sinceEmit = 0L
                        }
                    }
                }
            }
            emit(DownloadEvent.Progress(downloaded, totalBytes))
            emit(DownloadEvent.Complete)
        } catch (t: Throwable) {
            Log.w(TAG, "${model.id}: download failed", t)
            emit(DownloadEvent.Failed(t.message ?: "download failed", t))
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val EMIT_EVERY_BYTES = 256L * 1024L
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
