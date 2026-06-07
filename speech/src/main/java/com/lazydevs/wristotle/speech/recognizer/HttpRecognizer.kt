// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.recognizer

import android.speech.SpeechRecognizer
import com.lazydevs.wristotle.logging.WristotleLog as Log
import com.lazydevs.wristotle.speech.audio.AudioSource
import com.lazydevs.wristotle.speech.audio.flatten
import com.lazydevs.wristotle.speech.util.HttpFailureBucket
import com.lazydevs.wristotle.speech.util.SimpleHttp
import com.lazydevs.wristotle.speech.util.bucketFor
import com.lazydevs.wristotle.speech.util.providerErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

private const val TAG = "HttpRecognizer"

/**
 * Speech-to-text via any HTTP endpoint that speaks the OpenAI
 * `/v1/audio/transcriptions` shape.
 *
 * Covers all the providers worth supporting v1 with one client:
 *  - OpenAI (`https://api.openai.com/v1`)
 *  - Groq (`https://api.groq.com/openai/v1`)
 *  - Cloudflare Workers AI (`https://api.cloudflare.com/.../ai/v1`)
 *  - Self-hosted whisper.cpp HTTP server (`http://host:port/v1`)
 *  - Self-hosted Speaches / faster-whisper-server
 *  - Anything LiteLLM/LocalAI proxies in front of
 *
 * Same `base URL + bearer key + model` config triple as the agent
 * feature's `OpenAiCompatibleLlmClient`. Empty key → Authorization
 * header is omitted, so self-hosted servers behind a Tailnet without
 * an auth shim still work.
 *
 * ## Wire format
 *
 * `POST {baseUrl}/audio/transcriptions` with
 * `Content-Type: multipart/form-data; boundary=...` and parts:
 *   - `file` — the utterance WAV (16 kHz mono PCM-16), filename
 *     `audio.wav`, content-type `audio/wav`
 *   - `model` — [model]
 *   - `response_format` — `json` (we read `text` off the response)
 *   - `language` — [language] when non-blank (helps low-volume locales)
 *
 * The whole request body is built in memory. With an 8s 16 kHz mono
 * utterance that's ~256 KB — small enough that streaming the upload
 * isn't worth the failover complexity.
 *
 * ## Cancellation
 *
 * Coroutine cancellation aborts the upload-and-wait path. There's no
 * server-side "stop" signal in the OpenAI transcriptions API — once the
 * file is uploaded the request must run to completion (or hit the read
 * timeout). [requestAbort] sets a flag the upload loop checks between
 * chunks, so a cancel during the upload itself bails fast.
 */
class HttpRecognizer(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val language: String = "en",
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) : Recognizer {

    /** Set by [requestAbort]; checked between upload chunks. */
    @Volatile
    private var abortRequested: Boolean = false

    override fun transcribe(source: AudioSource): Flow<TranscriptionEvent> = flow {
        val chunks = ArrayList<ShortArray>()
        var totalSamples = 0
        var emittedStart = false
        abortRequested = false

        try {
            source.samples().collect { chunk ->
                if (!emittedStart) {
                    emit(TranscriptionEvent.SpeechStarted)
                    emittedStart = true
                }
                chunks.add(chunk)
                totalSamples += chunk.size
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "cancelled while reading audio")
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "audio source failed after $totalSamples samples", t)
            emit(TranscriptionEvent.Error(SpeechRecognizer.ERROR_AUDIO, "audio source failed: ${t.message}"))
            return@flow
        }

        if (totalSamples == 0) {
            emit(TranscriptionEvent.Error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, "no audio received"))
            return@flow
        }

        emit(TranscriptionEvent.SpeechEnded)

        val flat = chunks.flatten(totalSamples)
        val wav = WavEncoder.encode(flat, source.sampleRate)

        Log.d(TAG, "uploading ${wav.size} bytes (${totalSamples / 16_000.0}s) to $baseUrl (model=$model)")
        val startMs = System.currentTimeMillis()
        try {
            val text = withContext(Dispatchers.IO) { postTranscription(wav) }
            val elapsedMs = System.currentTimeMillis() - startMs
            Log.d(TAG, "transcribed in ${elapsedMs} ms: '$text'")
            if (text.isBlank()) {
                emit(TranscriptionEvent.Error(SpeechRecognizer.ERROR_NO_MATCH, "empty transcript"))
                return@flow
            }
            emit(TranscriptionEvent.Final(text.trim()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpRecognizerException) {
            Log.w(TAG, "transcription request failed: ${e.message}")
            emit(TranscriptionEvent.Error(e.errorCode, e.message))
        } catch (t: Throwable) {
            Log.w(TAG, "unexpected transcription failure", t)
            emit(TranscriptionEvent.Error(SpeechRecognizer.ERROR_NETWORK, t.message ?: "network error"))
        }
    }

    override fun requestAbort() {
        abortRequested = true
    }

    override fun close() = Unit

    /**
     * One-shot multipart POST. Returns the response's `text` field on
     * success, throws [HttpRecognizerException] with an Android
     * `SpeechRecognizer.ERROR_*` code on any failure path (HTTP non-2xx,
     * transport failure, JSON parse failure, abort).
     *
     * Internal so the test harness can spin up a mock HTTP server and
     * call this directly without instantiating an [AudioSource].
     */
    internal fun postTranscription(wavBytes: ByteArray): String {
        if (baseUrl.isBlank()) {
            throw HttpRecognizerException(SpeechRecognizer.ERROR_CLIENT, "no base URL configured")
        }
        val boundary = "----WristotleHttpRecognizer-" + UUID.randomUUID().toString().replace("-", "")
        val body = buildMultipartBody(boundary, wavBytes)

        val response = SimpleHttp.request(
            url = joinUrl(baseUrl, "audio/transcriptions"),
            method = "POST",
            headers = buildMap {
                put("Content-Type", "multipart/form-data; boundary=$boundary")
                put("Accept", "application/json")
                if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
            },
            body = body,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
        ) ?: throw HttpRecognizerException(SpeechRecognizer.ERROR_NETWORK, "network failure")

        if (abortRequested) {
            throw HttpRecognizerException(SpeechRecognizer.ERROR_CLIENT, "aborted")
        }

        val (status, payload) = response
        val payloadOrEmpty = payload.orEmpty()
        return if (status in 200..299) {
            parseSuccess(payloadOrEmpty)
        } else {
            val providerMessage = payloadOrEmpty.providerErrorMessage()
                ?: payloadOrEmpty.trim().takeIf { it.isNotBlank() }?.take(200)
            throw HttpRecognizerException(
                errorCode = bucketFor(status).toRecognizerErrorCode(),
                message = providerMessage ?: defaultMessageFor(status),
            )
        }
    }

    private fun buildMultipartBody(boundary: String, wavBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(wavBytes.size + 512)
        fun append(s: String) = out.write(s.toByteArray(Charsets.UTF_8))

        append("--$boundary\r\n")
        append("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n")
        append("Content-Type: audio/wav\r\n\r\n")
        out.write(wavBytes)
        append("\r\n")

        append("--$boundary\r\n")
        append("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
        append(model)
        append("\r\n")

        append("--$boundary\r\n")
        append("Content-Disposition: form-data; name=\"response_format\"\r\n\r\n")
        append("json")
        append("\r\n")

        if (language.isNotBlank()) {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"language\"\r\n\r\n")
            append(language)
            append("\r\n")
        }

        append("--$boundary--\r\n")
        return out.toByteArray()
    }

    /** Read the response body — `inputStream` on 2xx, `errorStream`
     *  otherwise. Either may be null (no body); returns empty string in
     *  that case so callers don't have to null-check downstream. */
    private fun parseSuccess(payload: String): String {
        if (payload.isBlank()) {
            throw HttpRecognizerException(SpeechRecognizer.ERROR_NETWORK, "empty response body")
        }
        return try {
            JSONObject(payload).optString("text", "")
        } catch (t: Throwable) {
            throw HttpRecognizerException(
                SpeechRecognizer.ERROR_NETWORK,
                "couldn't parse provider response: ${t.message}",
            )
        }
    }

    /** Map the coarse HTTP-failure bucket to the Android
     *  `SpeechRecognizer.ERROR_*` constant the flow body translates to a
     *  [TranscriptionEvent.Error]. Other STT clients (LLM agent providers)
     *  fan the same buckets out to their own typed failure shapes. */
    private fun HttpFailureBucket.toRecognizerErrorCode(): Int = when (this) {
        HttpFailureBucket.Auth -> SpeechRecognizer.ERROR_CLIENT
        HttpFailureBucket.RateLimit -> SpeechRecognizer.ERROR_NETWORK
        HttpFailureBucket.Server -> SpeechRecognizer.ERROR_SERVER
        HttpFailureBucket.Other -> SpeechRecognizer.ERROR_NETWORK
    }

    private fun defaultMessageFor(status: Int): String = when {
        status == 401 -> "API key rejected (401)"
        status == 429 -> "rate-limited (429)"
        status in 500..599 -> "server error ($status)"
        else -> "HTTP $status"
    }

    /** Join base + path tolerating a trailing slash on either side.
     *  Don't strip query strings or paths — `https://x/y/v1` should
     *  stay `.../v1/audio/transcriptions`. */
    private fun joinUrl(base: String, path: String): String {
        val trimmedBase = base.trimEnd('/')
        val trimmedPath = path.trimStart('/')
        return "$trimmedBase/$trimmedPath"
    }

    companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        private const val DEFAULT_READ_TIMEOUT_MS = 30_000
    }
}

/** Internal failure carrier — lets [HttpRecognizer.postTranscription]
 *  throw a typed exception with an Android `SpeechRecognizer.ERROR_*`
 *  code that the flow body translates to a [TranscriptionEvent.Error]. */
internal class HttpRecognizerException(
    val errorCode: Int,
    message: String,
) : RuntimeException(message)