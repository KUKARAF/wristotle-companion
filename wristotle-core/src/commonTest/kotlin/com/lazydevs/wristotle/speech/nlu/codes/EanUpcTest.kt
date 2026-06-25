// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.codes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Structural checks; a full ZXing decode round-trip lives in :app (EanUpcDecodeTest). */
class EanUpcTest {

    @Test fun `ean13 appends the check digit and lays out 95 modules with guards`() {
        val m = EanUpc.encodeEan13("400638133393")!! // 12 digits → check computed
        assertEquals(95, m.bars.size)
        val bits = m.bitString()
        assertEquals("101", bits.substring(0, 3))     // start guard
        assertEquals("01010", bits.substring(45, 50)) // centre guard
        assertEquals("101", bits.substring(92, 95))   // end guard
    }

    @Test fun `ean13 accepts a full 13-digit code`() {
        assertNotNull(EanUpc.encodeEan13("4006381333931"))
    }

    @Test fun `upca encodes as a 95-module EAN-13 with a leading zero`() {
        assertEquals(95, EanUpc.encodeUpcA("036000291452")!!.bars.size)
    }

    @Test fun `wrong lengths are rejected`() {
        assertNull(EanUpc.encodeEan13("123"))
        assertNull(EanUpc.encodeUpcA("123456789"))
    }
}
