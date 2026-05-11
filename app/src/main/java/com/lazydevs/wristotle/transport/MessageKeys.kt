package com.lazydevs.wristotle.transport

/**
 * AppMessage key indices shared between the Pebble watch app and this companion.
 *
 * These must stay in sync with the `messageKeys` array in the watch app's
 * `package.json`. The array index (0-based) is the integer key used on the wire.
 *
 * Keys are UInt because PebbleKit2 uses Map<UInt, PebbleDictionaryItem>.
 */
object MessageKeys {
    val COMPANION_PING: UInt = 9u
    val COMPANION_READY: UInt = 10u
    val COMPANION_QUERY: UInt = 11u
    val COMPANION_RESPONSE: UInt = 12u
}
