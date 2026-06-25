// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.codes

/**
 * EAN-13 / UPC-A 1D barcode encoder (the common product + loyalty-card symbology).
 *
 * EAN-13 is 95 modules: start guard `101`, six left digits (7 modules each, L or
 * G parity chosen by the first digit), centre guard `01010`, six right digits (R,
 * 7 modules), end guard `101`. UPC-A is exactly an EAN-13 with a leading `0`, so
 * it reuses the same path. Check digit is mod-10 weighted 1/3.
 *
 * Pure commonMain → CodeMatrix.OneD; validated byte-for-byte against a ZXing
 * decode round-trip in :app (EanUpcDecodeTest).
 */
object EanUpc {

    // 7-module patterns per digit (MSB first; '1' = bar).
    private val L = arrayOf(
        "0001101", "0011001", "0010011", "0111101", "0100011",
        "0110001", "0101111", "0111011", "0110111", "0001011",
    )
    private val G = arrayOf(
        "0100111", "0110011", "0011011", "0100001", "0011101",
        "0111001", "0000101", "0010001", "0001001", "0010111",
    )
    private val R = arrayOf(
        "1110010", "1100110", "1101100", "1000010", "1011100",
        "1001110", "1010000", "1000100", "1001000", "1110100",
    )
    // L/G parity of the six left digits, selected by the first (13th) digit.
    private val PARITY = arrayOf(
        "LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG",
        "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL",
    )

    /** Accepts 12 digits (check appended) or a full 13. Non-digits are stripped. */
    fun encodeEan13(raw: String): CodeMatrix.OneD? {
        val digits = raw.filter { it.isDigit() }
        val full = when (digits.length) {
            13 -> digits
            12 -> digits + checkDigit(digits)
            else -> return null
        }
        val n = full.map { it - '0' }
        val sb = StringBuilder()
        sb.append("101")                                   // start guard
        val parity = PARITY[n[0]]
        for (i in 0 until 6) sb.append(if (parity[i] == 'L') L[n[1 + i]] else G[n[1 + i]])
        sb.append("01010")                                 // centre guard
        for (i in 0 until 6) sb.append(R[n[7 + i]])
        sb.append("101")                                   // end guard
        return CodeMatrix.OneD(BooleanArray(sb.length) { sb[it] == '1' })
    }

    /** Accepts 11 digits (check appended) or a full 12; encoded as EAN-13 with a leading 0. */
    fun encodeUpcA(raw: String): CodeMatrix.OneD? {
        val digits = raw.filter { it.isDigit() }
        val upc = when (digits.length) {
            12 -> digits
            11 -> digits + checkDigit("0$digits")          // UPC check == EAN check of 0+11
            else -> return null
        }
        return encodeEan13("0$upc")
    }

    /** EAN-13 mod-10 check digit over the 12 leading digits (odd ×1, even ×3). */
    private fun checkDigit(twelve: String): Char {
        var sum = 0
        for (i in 0 until 12) {
            val d = twelve[i] - '0'
            sum += if (i % 2 == 0) d else d * 3
        }
        return '0' + ((10 - sum % 10) % 10)
    }
}
