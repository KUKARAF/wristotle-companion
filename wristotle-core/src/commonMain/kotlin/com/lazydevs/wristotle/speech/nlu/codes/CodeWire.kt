// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.codes

/**
 * Compact byte serialization of a [CodeMatrix] for the watch AppMessage payload.
 * The watch decodes this and draws each set bit with `fill_rect` — there is no
 * encoder on the watch. Bits are packed MSB-first.
 *
 * Layout:
 * ```
 *   [0]      type: 0 = 1D bars, 1 = 2D grid
 *   1D: [1..2] bar count (uint16, big-endian), then ceil(count/8) packed bytes
 *   2D: [1]    size N (uint8),                  then ceil(N*N/8) packed bytes (row-major)
 * ```
 * A 25×25 QR is ~80 bytes; a ~100-module barcode is ~16 bytes — both well within
 * one AppMessage frame. commonMain so the Android + iOS companions serialize
 * identically; [decode] backs the round-trip test (the watch has its own C
 * decoder matching this layout).
 */
object CodeWire {

    const val TYPE_1D = 0
    const val TYPE_2D = 1

    fun encode(matrix: CodeMatrix): ByteArray = when (matrix) {
        is CodeMatrix.OneD -> {
            val n = matrix.bars.size
            val out = ByteArray(3 + (n + 7) / 8)
            out[0] = TYPE_1D.toByte()
            out[1] = ((n ushr 8) and 0xFF).toByte()
            out[2] = (n and 0xFF).toByte()
            for (i in 0 until n) {
                if (matrix.bars[i]) out[3 + i / 8] = (out[3 + i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
            }
            out
        }
        is CodeMatrix.TwoD -> {
            val n = matrix.size
            val out = ByteArray(2 + (n * n + 7) / 8)
            out[0] = TYPE_2D.toByte()
            out[1] = (n and 0xFF).toByte()
            var k = 0
            for (r in 0 until n) {
                for (c in 0 until n) {
                    if (matrix.dark[r][c]) out[2 + k / 8] = (out[2 + k / 8].toInt() or (0x80 ushr (k % 8))).toByte()
                    k++
                }
            }
            out
        }
    }

    /** Inverse of [encode] — for tests + any companion-side preview from the wire form. */
    fun decode(bytes: ByteArray): CodeMatrix = when (bytes[0].toInt()) {
        TYPE_1D -> {
            val n = ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)
            val bars = BooleanArray(n) { i -> (bytes[3 + i / 8].toInt() and (0x80 ushr (i % 8))) != 0 }
            CodeMatrix.OneD(bars)
        }
        TYPE_2D -> {
            val n = bytes[1].toInt() and 0xFF
            val dark = Array(n) { r ->
                BooleanArray(n) { c ->
                    val k = r * n + c
                    (bytes[2 + k / 8].toInt() and (0x80 ushr (k % 8))) != 0
                }
            }
            CodeMatrix.TwoD(n, dark)
        }
        else -> throw IllegalArgumentException("unknown CodeWire type ${bytes[0]}")
    }
}
