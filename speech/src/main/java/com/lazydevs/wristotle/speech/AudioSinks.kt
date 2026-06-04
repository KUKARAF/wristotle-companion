package com.lazydevs.wristotle.speech

import android.content.Context

/**
 * Service-locator for the optional per-session audio sink.
 *
 * `WhisperRecognitionService` consults [provider] when building the
 * session's `AudioSource`. When non-null, the source is wrapped in a
 * [com.lazydevs.wristotle.speech.audio.CapturingAudioSource] that fires
 * the returned lambda with the full PCM-16 buffer at end of session.
 *
 * Consumer modules (e.g. `:app`) override [provider] in
 * `Application.onCreate` to plug in conversation-audio capture without
 * `:speech` knowing anything about the consumer's storage layer.
 *
 * Returning `null` from the provider skips wrapping entirely — the
 * raw `AudioSource` reaches the recognizer with no overhead. That's
 * the default until a consumer plugs in.
 */
object AudioSinks {
    @Volatile
    var provider: (Context) -> ((ShortArray) -> Unit)? = { null }
}
