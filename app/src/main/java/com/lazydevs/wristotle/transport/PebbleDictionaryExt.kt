// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

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

/**
 * Reads a boolean-shaped flag tolerantly. The watch may write the same logical
 * "0/1" tuple as UInt8, UInt32, or Int32 depending on the call site
 * (`dict_write_uint8` vs `dict_write_int32`), and PebbleKit2 doesn't normalise
 * them. Returns null when the key is absent or carries a non-numeric type.
 */
internal fun PebbleDictionary.boolFlag(key: UInt): Boolean? {
    val item = this[key] ?: return null
    return when (item) {
        is PebbleDictionaryItem.UInt8  -> item.value.toInt() != 0
        is PebbleDictionaryItem.UInt32 -> item.value != 0u
        is PebbleDictionaryItem.Int32  -> item.value != 0
        is PebbleDictionaryItem.Int8   -> item.value.toInt() != 0
        else -> null
    }
}