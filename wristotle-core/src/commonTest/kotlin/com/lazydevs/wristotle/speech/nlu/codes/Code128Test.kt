// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.codes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Byte-for-byte validation of the pure-Kotlin Code 128 encoder. The C-path
 * oracle comes from an INDEPENDENT reference encoder (python-barcode); the
 * B-path oracle is computed straight from the canonical Code 128 table — both
 * catch any transcription / checksum / index error in the Kotlin.
 */
class Code128Test {

    private fun bits(data: String) = Code128.encode(data)!!.bitString()

    @Test fun `Code128-C numeric matches the reference encoder`() {
        // Code128-C (digit pairs) for "123456789012" — densest numeric form.
        assertEquals(
            "11010011100101100111001000101100011100010110110000101001101111011010110011100111010110001100011101011",
            bits("123456789012"),
        )
    }

    @Test fun `Code128-B alphanumeric matches the canonical table`() {
        // Mixed letters/punctuation/digits -> whole-string Code128-B.
        assertEquals(
            "110100100001010001100010001011000100110111001001110011011001110010101000111101100011101011",
            bits("AB-12"),
        )
    }

    @Test fun `odd-length digits fall to the B path and still encode`() {
        // All digits but odd length -> not eligible for C; B path, start_B pattern.
        assertTrue(bits("12345").startsWith("11010010000"))   // PATTERNS[104] = start_B
    }

    @Test fun `empty data is rejected`() = assertNull(Code128.encode(""))

    @Test fun `non-ASCII data is rejected`() = assertNull(Code128.encode("café"))
}
