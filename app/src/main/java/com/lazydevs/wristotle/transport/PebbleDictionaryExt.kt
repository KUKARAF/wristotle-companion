package com.lazydevs.wristotle.transport

import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem

/**
 * Typed reads from an AppMessage [PebbleDictionary], returning null when the
 * key is absent or the value's runtime type doesn't match.
 *
 * Bare `dict[key] as? PebbleDictionaryItem.Text` chains were repeating in
 * every handler dispatch site; these extensions keep the call sites short and
 * make the intended type explicit.
 *
 * Note on integer types: PebbleKit2 delivers every numeric value as `Int32` or
 * `UInt32` regardless of the watch-side declared width (a `uint8` on the watch
 * arrives as `UInt32` on Android). Read accordingly.
 */
internal fun PebbleDictionary.text(key: UInt): String? =
    (this[key] as? PebbleDictionaryItem.Text)?.value

internal fun PebbleDictionary.int32(key: UInt): Int? =
    (this[key] as? PebbleDictionaryItem.Int32)?.value?.toInt()

internal fun PebbleDictionary.uint32(key: UInt): UInt? =
    (this[key] as? PebbleDictionaryItem.UInt32)?.value
