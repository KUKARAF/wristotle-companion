// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.codes

/**
 * Turns a [SavedCode] into its render-ready [CodeMatrix], dispatching on format.
 * commonMain — the same matrix feeds the Android + iOS companions' watch-sync
 * path. Returns null when the data can't be encoded in the chosen format (too
 * long for QR, non-encodable chars for Code 128) so callers can surface a clear
 * error instead of crashing.
 */
object CodeGenerator {
    fun matrix(code: SavedCode): CodeMatrix? = when (code.format) {
        CodeFormat.QR_CODE -> runCatching { Qr.encode(code.data) }.getOrNull()
        CodeFormat.CODE_128 -> Code128.encode(code.data)
    }
}
