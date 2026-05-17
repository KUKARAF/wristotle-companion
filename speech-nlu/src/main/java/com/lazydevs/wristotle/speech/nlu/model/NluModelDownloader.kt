package com.lazydevs.wristotle.speech.nlu.model

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "NluModelDownloader"

/** Stream of events emitted during an NLU model download. */
sealed interface NluDownloadEvent {
    data class Progress(val bytesDownloaded: Long, val totalBytes: Long?) : NluDownloadEvent
    data object Complete : NluDownloadEvent
    data class Failed(val message: String, val cause: Throwable? = null) : NluDownloadEvent
}

/**
 * Twin of `ModelDownloader` in :speech-whisper — same resumable HTTP +
 * manual-redirect logic, retyped for [NluModelInfo] / [NluModelStorage].
 *
 * Kept as a verbatim copy (not a shared generic) so :speech-nlu doesn't
 * have to depend on :speech-whisper and the two modules can evolve
 * independently. If/when a third downloadable model family appears, the
 * common HTTP logic should be hoisted into :speech and both consumers
 * should switch — until then, duplication is cheaper than a premature
 * abstraction.
 */
class NluModelDownloader(private val storage: NluModelStorage) {

    fun download(model: NluModelInfo): Flow<NluDownloadEvent> = flow {
        val target = storage.modelFile(model.id)
        val existing = if (target.exists()) target.length() else 0L

        val connection = try {
            openWithRedirects(URL(model.url), rangeStart = existing.takeIf { it > 0L })
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "${model.id}: connect failed", t)
            emit(NluDownloadEvent.Failed(t.message ?: "connect failed", t))
            return@flow
        }

        try {
            val code = connection.responseCode
            when (code) {
                HttpURLConnection.HTTP_PARTIAL,
                HttpURLConnection.HTTP_OK -> Unit
                HTTP_REQUESTED_RANGE_NOT_SATISFIABLE -> {
                    Log.d(TAG, "${model.id}: HTTP 416 — already fully downloaded ($existing bytes)")
                    emit(NluDownloadEvent.Complete)
                    return@flow
                }
                else -> {
                    emit(NluDownloadEvent.Failed("HTTP $code from ${model.url}"))
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
            emit(NluDownloadEvent.Progress(downloaded, totalBytes))

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
                            emit(NluDownloadEvent.Progress(downloaded, totalBytes))
                            sinceEmit = 0L
                        }
                    }
                }
            }
            emit(NluDownloadEvent.Progress(downloaded, totalBytes))
            emit(NluDownloadEvent.Complete)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "${model.id}: download failed", t)
            emit(NluDownloadEvent.Failed(t.message ?: "download failed", t))
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

        const val HTTP_REQUESTED_RANGE_NOT_SATISFIABLE = 416

        val REDIRECT_CODES = setOf(
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307,
            308,
        )
    }
}
