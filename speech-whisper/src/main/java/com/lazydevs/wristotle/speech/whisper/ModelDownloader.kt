package com.lazydevs.wristotle.speech.whisper

import android.util.Log
import kotlinx.coroutines.CancellationException
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
 * If the local file is present and shorter than the server's reported length,
 * the downloader sends `Range: bytes=N-` and appends. If the server refuses
 * the range (replies with 200), the file is overwritten from byte 0. If the
 * server says the range is already satisfied (HTTP 416), the existing file
 * is considered complete.
 *
 * Redirects are followed manually (up to [MAX_REDIRECTS]) so the `Range`
 * header is re-issued on each hop — `HttpURLConnection`'s built-in redirect
 * handling can silently drop it cross-host (HuggingFace 302→CDN), which would
 * corrupt a resumed download.
 *
 * Cancelling the collecting coroutine aborts the download cleanly via Kotlin
 * coroutine cancellation; the partial file remains on disk for a future resume.
 */
class ModelDownloader(private val storage: ModelStorage) {

    fun download(model: ModelInfo): Flow<DownloadEvent> = flow {
        val target = storage.modelFile(model.id)
        val existing = if (target.exists()) target.length() else 0L

        val connection = try {
            openWithRedirects(URL(model.url), rangeStart = existing.takeIf { it > 0L })
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "${model.id}: connect failed", t)
            emit(DownloadEvent.Failed(t.message ?: "connect failed", t))
            return@flow
        }

        try {
            val code = connection.responseCode
            when (code) {
                HttpURLConnection.HTTP_PARTIAL,
                HttpURLConnection.HTTP_OK -> Unit  // proceed to stream below
                HTTP_REQUESTED_RANGE_NOT_SATISFIABLE -> {
                    // Server says our existing file is already at or past the
                    // resource's full length → treat as complete.
                    Log.d(TAG, "${model.id}: HTTP 416 — already fully downloaded ($existing bytes)")
                    emit(DownloadEvent.Complete)
                    return@flow
                }
                else -> {
                    emit(DownloadEvent.Failed("HTTP $code from ${model.url}"))
                    return@flow
                }
            }
            val resuming = code == HttpURLConnection.HTTP_PARTIAL

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
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "${model.id}: download failed", t)
            emit(DownloadEvent.Failed(t.message ?: "download failed", t))
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Opens [url] following redirects manually so the `Range` header survives
     * every hop. `HttpURLConnection.instanceFollowRedirects = true` will follow
     * redirects but may drop request headers cross-host or downgrade the
     * method silently — risky when correctness of resume depends on Range
     * being attached to the final GET.
     */
    private fun openWithRedirects(url: URL, rangeStart: Long?): HttpURLConnection {
        var current = url
        var remainingRedirects = MAX_REDIRECTS
        while (true) {
            val conn = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                if (rangeStart != null && rangeStart > 0L) {
                    setRequestProperty("Range", "bytes=$rangeStart-")
                }
            }
            val code = conn.responseCode
            if (code in REDIRECT_CODES) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) error("redirect ($code) with no Location header")
                if (--remainingRedirects < 0) error("too many redirects from ${url}")
                current = URL(current, location)
                continue
            }
            return conn
        }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val EMIT_EVERY_BYTES = 256L * 1024L
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_REDIRECTS = 5

        // HttpURLConnection doesn't expose a constant for 416.
        const val HTTP_REQUESTED_RANGE_NOT_SATISFIABLE = 416

        val REDIRECT_CODES = setOf(
            HttpURLConnection.HTTP_MOVED_PERM,   // 301
            HttpURLConnection.HTTP_MOVED_TEMP,   // 302
            HttpURLConnection.HTTP_SEE_OTHER,    // 303
            307, // Temporary Redirect — preserves method + body
            308, // Permanent Redirect — preserves method + body
        )
    }
}
