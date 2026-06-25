// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.codes

/**
 * Pure-Kotlin Code 128 encoder → a [CodeMatrix.OneD] bar pattern. commonMain, so
 * the Android AND iOS companions share it (this is exactly why we don't use
 * ZXing for encoding — it's JVM-only and wouldn't port).
 *
 * Charset choice is whole-string: **Code128-C** for all-digit, even-length data
 * (densest — the common loyalty-number case), **Code128-B** for everything else
 * (printable ASCII). Both yield a valid, scannable symbol. The mid-string
 * charset-switching that minimises width is intentionally skipped — not worth
 * the complexity on a render path; a few extra modules never hurt scanning.
 *
 * The [PATTERNS] table + STOP are the canonical Code 128 tables; the C path is
 * validated byte-for-byte against an independent reference encoder (see
 * `Code128Test`).
 */
object Code128 {

    /** Returns the bar pattern, or null if [data] is empty or contains a
     *  non-printable-ASCII char this encoder can't represent. */
    fun encode(data: String): CodeMatrix.OneD? {
        if (data.isEmpty() || data.any { it.code !in 32..126 }) return null
        val symbols = if (data.all { it.isDigit() } && data.length % 2 == 0) {
            encodeC(data)
        } else {
            encodeB(data)
        }
        val bits = StringBuilder()
        for (s in symbols) bits.append(PATTERNS[s])
        bits.append(STOP)
        return CodeMatrix.OneD(BooleanArray(bits.length) { bits[it] == '1' })
    }

    /** start_C, then one symbol per digit pair, then the checksum. */
    private fun encodeC(data: String): IntArray {
        val syms = ArrayList<Int>(data.length / 2 + 2)
        syms.add(START_C)
        var i = 0
        while (i < data.length) {
            syms.add((data[i] - '0') * 10 + (data[i + 1] - '0'))
            i += 2
        }
        syms.add(checksum(syms))
        return syms.toIntArray()
    }

    /** start_B, then one symbol per char (value = ASCII − 32), then the checksum. */
    private fun encodeB(data: String): IntArray {
        val syms = ArrayList<Int>(data.length + 2)
        syms.add(START_B)
        for (ch in data) syms.add(ch.code - 32)
        syms.add(checksum(syms))
        return syms.toIntArray()
    }

    /** (start + Σ value_i × i) mod 103, i = 1 for the first data symbol; the
     *  start symbol itself contributes with weight 1 ([syms]`[0]`). */
    private fun checksum(syms: List<Int>): Int {
        var sum = syms[0]
        for (i in 1 until syms.size) sum += syms[i] * i
        return sum % 103
    }

    private const val START_B = 104
    private const val START_C = 105

    // Code128 symbol patterns (index 0..105), '1'=bar. Canonical table.
    private val PATTERNS = arrayOf(
        "11011001100","11001101100","11001100110","10010011000","10010001100","10001001100",
        "10011001000","10011000100","10001100100","11001001000","11001000100","11000100100",
        "10110011100","10011011100","10011001110","10111001100","10011101100","10011100110",
        "11001110010","11001011100","11001001110","11011100100","11001110100","11101101110",
        "11101001100","11100101100","11100100110","11101100100","11100110100","11100110010",
        "11011011000","11011000110","11000110110","10100011000","10001011000","10001000110",
        "10110001000","10001101000","10001100010","11010001000","11000101000","11000100010",
        "10110111000","10110001110","10001101110","10111011000","10111000110","10001110110",
        "11101110110","11010001110","11000101110","11011101000","11011100010","11011101110",
        "11101011000","11101000110","11100010110","11101101000","11101100010","11100011010",
        "11101111010","11001000010","11110001010","10100110000","10100001100","10010110000",
        "10010000110","10000101100","10000100110","10110010000","10110000100","10011010000",
        "10011000010","10000110100","10000110010","11000010010","11001010000","11110111010",
        "11000010100","10001111010","10100111100","10010111100","10010011110","10111100100",
        "10011110100","10011110010","11110100100","11110010100","11110010010","11011011110",
        "11011110110","11110110110","10101111000","10100011110","10001011110","10111101000",
        "10111100010","11110101000","11110100010","10111011110","10111101110","11101011110",
        "11110101110","11010000100","11010010000","11010011100",
    )
    private const val STOP = "1100011101011"   // stop pattern + final 2 bars
}
