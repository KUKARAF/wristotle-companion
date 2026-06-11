// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.lazydevs.wristotle.speech.nlu.tts.TtsProvider
import com.lazydevs.wristotle.speech.nlu.tts.TtsResult
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "LocalTtsProvider"

/**
 * Wraps Android's built-in [TextToSpeech] as a [TtsProvider]. Prefers
 * Google TTS (`com.google.android.tts`) when installed, falls back to
 * whatever the device default is — relevant on GrapheneOS / minimal-AOSP
 * setups where only `app.grapheneos.speechservices` is available.
 *
 * Produces a WAV file via `synthesizeToFile` and reads it back as bytes
 * so the downstream pipeline ([PebblePcmConverter]) doesn't need to know
 * how the audio was generated.
 */
class LocalTtsProvider(context: Context) : TtsProvider {

    private val appContext = context.applicationContext
    override val displayName: String = "Android TTS"

    override suspend fun synthesizeToWav(text: String): TtsResult {
        val wavFile = File(appContext.cacheDir, "tts_local_${System.currentTimeMillis()}.wav")
        if (wavFile.exists()) wavFile.delete()
        val tts = waitForInit()
            ?: return TtsResult.Failure("Android TTS init failed — no engine installed?")
        try {
            val synthCode = synthesizeBlocking(tts, text, wavFile)
            if (synthCode != TextToSpeech.SUCCESS) {
                return TtsResult.Failure("Android TTS synth failed (code=$synthCode)")
            }
            val bytes = runCatching { wavFile.readBytes() }
                .onFailure { Log.w(TAG, "couldn't read local TTS wav", it) }
                .getOrNull()
            return if (bytes == null || bytes.isEmpty()) {
                TtsResult.Failure("Android TTS produced empty WAV")
            } else {
                TtsResult.Success(bytes)
            }
        } finally {
            tts.shutdown()                // always release the binder
            if (wavFile.exists()) wavFile.delete()   // always clean cacheDir
        }
    }

    private suspend fun waitForInit(): TextToSpeech? = suspendCancellableCoroutine { cont ->
        val preferred = "com.google.android.tts"
        var triedFallback = false
        // Captured via array so the cancellation hook can reach the
        // latest `tts` reference even after the fallback chain swaps it.
        val live = arrayOfNulls<TextToSpeech>(1)
        fun build(engine: String?) {
            val tts = TextToSpeech(appContext, { status ->
                val current = live[0] ?: return@TextToSpeech
                if (status == TextToSpeech.SUCCESS) {
                    Log.i(TAG, "engine in use: ${current.defaultEngine}")
                    cont.resume(current)
                } else if (!triedFallback) {
                    triedFallback = true
                    Log.w(TAG, "preferred engine '$engine' failed → fallback default")
                    current.shutdown()
                    live[0] = null
                    build(null)
                } else {
                    Log.w(TAG, "TTS init failed: $status")
                    current.shutdown()
                    live[0] = null
                    cont.resume(null)
                }
            }, engine)
            live[0] = tts
        }
        // If the caller is cancelled while we're waiting for the init
        // callback, the partly-constructed TextToSpeech would otherwise
        // hold a system-TTS binder forever.
        cont.invokeOnCancellation { live[0]?.shutdown() }
        build(preferred)
    }

    /**
     * Returns [TextToSpeech.SUCCESS] on success, or one of:
     *  - the [TextToSpeech] synth-to-file return code when the call
     *    rejects synchronously (e.g. -2 for `ERROR_NOT_INSTALLED_YET`)
     *  - [TextToSpeech.ERROR] when [UtteranceProgressListener.onError] fires
     */
    private suspend fun synthesizeBlocking(
        tts: TextToSpeech,
        text: String,
        out: File,
    ): Int = suspendCancellableCoroutine { cont ->
        val utteranceId = "tts-${System.currentTimeMillis()}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) = Unit
            override fun onDone(id: String) { cont.resume(TextToSpeech.SUCCESS) }
            @Deprecated("legacy callback")
            override fun onError(id: String) { cont.resume(TextToSpeech.ERROR) }
            override fun onError(id: String, errorCode: Int) { cont.resume(errorCode) }
        })
        // Drop the listener reference if we're cancelled mid-synth; the
        // outer `finally` will shut the TTS down but this clears the
        // anonymous-object → continuation edge in case cleanup races.
        cont.invokeOnCancellation { tts.setOnUtteranceProgressListener(null) }
        val r = tts.synthesizeToFile(text, Bundle(), out, utteranceId)
        if (r != TextToSpeech.SUCCESS) cont.resume(r)
    }
}
