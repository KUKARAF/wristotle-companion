package com.lazydevs.wristotle.speech.nlu

import android.content.Context

/**
 * Service-locator that callers (PebbleListenerService, ViewModels) read to
 * get the current [IntentClassifier]. Twin of [com.lazydevs.wristotle.speech.Recognizers]
 * in :speech.
 *
 * The consumer module (`:app`) overrides [provider] in its Application's
 * `onCreate` to install the real classifier — for Phase 1 this is just
 * [StubIntentClassifier]. Defaulting to the stub means modules that don't
 * configure the provider still get a working (always-Unknown) classifier
 * instead of an NPE.
 */
object IntentClassifiers {
    @Volatile
    var provider: (Context) -> IntentClassifier = { StubIntentClassifier() }
}
