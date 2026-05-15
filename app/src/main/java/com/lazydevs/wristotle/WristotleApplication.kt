package com.lazydevs.wristotle

import android.app.Application

/**
 * Process-wide setup hook.
 *
 * Phase 1: the default [com.lazydevs.wristotle.speech.recognizer.StubRecognizer]
 * is used, so no provider override is needed here.
 *
 * Phase 2 will swap in a real backend, e.g.
 * ```
 * Recognizers.provider = { ctx -> WhisperRecognizer(ctx, modelPath = ...) }
 * ```
 * This is the only place that change needs to land — the :speech module and the
 * RecognitionService class never reference a concrete recognizer.
 */
class WristotleApplication : Application()
