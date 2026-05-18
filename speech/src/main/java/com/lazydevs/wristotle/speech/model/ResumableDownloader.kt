package com.lazydevs.wristotle.speech.model

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "ResumableDownloader"

/**
 * HTTPS file download with resumable Range support and a throttled
 * progress stream. Shared by every model-family downloader; the
 * per-family wrappers used to inline this and divergence was a real
 * risk.
 *
 * Resume protocol: if the local file exists and has bytes, the GET
 * sends `Range: bytes=N-`. Servers that refuse (reply 200) overwrite
 * from byte 0; HTTP 416 ("already satisfied") is treated as Complete.
 *
 * Redirects are followed manually so the `Range` header survives every
 * hop — `HttpURLConnection`'s built-in redirect handling can silently
 * drop request headers cross-host (HuggingFace 302→CDN), which would
 * corrupt a resumed download.
 *
 * Cancelling the collecting coroutine aborts cleanly; the partial file
 * stays on disk for a future resume.
 */
class ResumableDownloader {

    fun download(url: String, target: File): Flow<DownloadStreamEvent> = flow {
        val existing = if (target.exists()) target.length() else 0L

        val connection = try {
            openWithRedirects(URL(url), rangeStart = existing.takeIf { it > 0L })
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "connect failed for $url", t)
            emit(DownloadStreamEvent.Failed(t.message ?: "connect failed", t))
            return@flow
        }

        try {
            val code = connection.responseCode
            when (code) {
                HttpURLConnection.HTTP_PARTIAL,
                HttpURLConnection.HTTP_OK -> Unit
                HTTP_REQUESTED_RANGE_NOT_SATISFIABLE -> {
                    Log.d(TAG, "HTTP 416 — already fully downloaded ($existing bytes): $url")
                    emit(DownloadStreamEvent.Complete)
                    return@flow
                }
                else -> {
                    emit(DownloadStreamEvent.Failed("HTTP $code from $url"))
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
            emit(DownloadStreamEvent.Progress(downloaded, totalBytes))

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
                            emit(DownloadStreamEvent.Progress(downloaded, totalBytes))
                            sinceEmit = 0L
                        }
                    }
                }
            }
            emit(DownloadStreamEvent.Progress(downloaded, totalBytes))
            emit(DownloadStreamEvent.Complete)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "download failed for $url", t)
            emit(DownloadStreamEvent.Failed(t.message ?: "download failed", t))
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

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
                if (--remainingRedirects < 0) error("too many redirects from $url")
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
