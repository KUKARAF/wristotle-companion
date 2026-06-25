// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.codes

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The wire layout is the companion↔watch contract, so the round-trip must be
 * exact — the watch's C decoder reconstructs the grid from these same bytes.
 */
class CodeWireTest {

    @Test fun `1D bars round-trip including a partial final byte`() {
        val bars = booleanArrayOf(true, false, true, true, false, false, false, true, true) // 9 bits
        val back = CodeWire.decode(CodeWire.encode(CodeMatrix.OneD(bars))) as CodeMatrix.OneD
        assertEquals(bars.toList(), back.bars.toList())
    }

    @Test fun `2D grid round-trips`() {
        val n = 5
        val dark = Array(n) { r -> BooleanArray(n) { c -> (r + c) % 2 == 0 } }
        val back = CodeWire.decode(CodeWire.encode(CodeMatrix.TwoD(n, dark))) as CodeMatrix.TwoD
        assertEquals(n, back.size)
        for (r in 0 until n) for (c in 0 until n) assertEquals(dark[r][c], back.dark[r][c], "($r,$c)")
    }

    @Test fun `a real QR survives the wire byte-for-byte`() {
        val qr = Qr.encode("https://wristotle.app")
        val back = CodeWire.decode(CodeWire.encode(qr)) as CodeMatrix.TwoD
        assertEquals(qr.size, back.size)
        for (r in 0 until qr.size) for (c in 0 until qr.size) assertEquals(qr.dark[r][c], back.dark[r][c], "($r,$c)")
    }

    @Test fun `a real barcode survives the wire`() {
        val bc = Code128.encode("123456789012")!!
        val back = CodeWire.decode(CodeWire.encode(bc)) as CodeMatrix.OneD
        assertEquals(bc.bitString(), back.bitString())
    }
}
