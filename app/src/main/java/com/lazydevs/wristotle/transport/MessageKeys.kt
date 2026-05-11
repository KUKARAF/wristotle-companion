package com.lazydevs.wristotle.transport

/**
 * AppMessage key indices shared between the Pebble watch app and this companion.
 *
 * These must stay in sync with the `messageKeys` array in the watch app's
 * `package.json`. The array index (0-based) is the integer key that
 * PebbleDictionary uses on the wire.
 */
object MessageKeys {
    /** Sent by the watch on startup to check whether the companion is running. */
    const val COMPANION_PING     = 9
    /** Sent by the companion in response to a ping, or proactively on service start. */
    const val COMPANION_READY    = 10
    /** Raw voice transcription forwarded from the watch for the companion to handle. */
    const val COMPANION_QUERY    = 11
    /** Result string sent back to the watch after handling a companion_query. */
    const val COMPANION_RESPONSE = 12
}
