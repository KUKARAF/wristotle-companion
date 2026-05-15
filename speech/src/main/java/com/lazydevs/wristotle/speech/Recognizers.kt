package com.lazydevs.wristotle.speech

import android.content.Context
import com.lazydevs.wristotle.speech.recognizer.Recognizer
import com.lazydevs.wristotle.speech.recognizer.StubRecognizer

/**
 * Service-locator for the active [Recognizer] backend.
 *
 * The :speech module ships [StubRecognizer] as the default. Consumer modules
 * (e.g. :app) override [provider] in `Application.onCreate` to plug in a real
 * backend without touching :speech.
 *
 * Replace the whole provider lambda; do not mutate state inside it. Writes happen
 * once at process start, reads happen on the recognition service's worker thread.
 * The @Volatile is sufficient for that publish-then-read pattern.
 */
object Recognizers {
    @Volatile
    var provider: (Context) -> Recognizer = { StubRecognizer() }
}
