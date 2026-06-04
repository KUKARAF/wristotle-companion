package com.lazydevs.wristotle.speech

/**
 * Service-locator for the optional per-session audio sink. Mirrors the
 * [Recognizers] / [com.lazydevs.wristotle.speech.nlu.IntentClassifiers]
 * pattern: `:speech` declares the slot, the consumer module (`:app`)
 * plugs in a sink at `Application.onCreate` time. Returning `null`
 * skips capture entirely.
 */
object AudioSinks {
    @Volatile
    var provider: () -> ((ShortArray) -> Unit)? = { null }
}
