// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.codes

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Reader
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.oned.Code128Reader
import com.google.zxing.oned.EAN13Reader
import com.google.zxing.oned.UPCAReader
import com.google.zxing.qrcode.QRCodeReader
import com.lazydevs.wristotle.speech.nlu.codes.Code128
import com.lazydevs.wristotle.speech.nlu.codes.EanUpc
import com.lazydevs.wristotle.speech.nlu.codes.Qr
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The decisive correctness test for code generation: encode with our Kotlin
 * generators (qrcode-kotlin for QR, [Code128] for 1D), rasterise the matrix, and
 * decode it back with a real, independent scanner (ZXing). A pass proves the
 * output is genuinely scannable AND correctly oriented — exactly what matters
 * once it's rendered on the watch. ZXing is a test-only dependency.
 */
class CodeDecodeTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    /** Rasterise a 2D module grid (with quiet zone) and decode it. */
    private fun decode2D(dark: Array<BooleanArray>, reader: Reader, scale: Int = 8, quiet: Int = 4): String {
        val n = dark.size
        val dim = (n + 2 * quiet) * scale
        val px = IntArray(dim * dim) { white }
        for (r in 0 until n) for (c in 0 until n) {
            if (dark[r][c]) fill(px, dim, (quiet + c) * scale, (quiet + r) * scale, scale, scale)
        }
        return reader.decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(dim, dim, px)))).text
    }

    /** Rasterise a 1D bar pattern (full height, with quiet zones) and decode it. */
    private fun decode1D(bars: BooleanArray, reader: Reader, scale: Int = 3, quiet: Int = 10, height: Int = 60): String {
        val width = (bars.size + 2 * quiet) * scale
        val px = IntArray(width * height) { white }
        for (k in bars.indices) {
            if (bars[k]) fill(px, width, (quiet + k) * scale, 0, scale, height)
        }
        return reader.decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, px)))).text
    }

    private fun fill(px: IntArray, stride: Int, x0: Int, y0: Int, w: Int, h: Int) {
        for (y in y0 until y0 + h) for (x in x0 until x0 + w) px[y * stride + x] = black
    }

    @Test fun `QR round-trips through a real decoder`() {
        for (text in listOf("https://wristotle.app", "WIFI:T:WPA;S:CafeNet;P:pebble12345;;", "HELLO 123")) {
            assertEquals(text, decode2D(Qr.encode(text).dark, QRCodeReader()))
        }
    }

    @Test fun `Code128 round-trips through a real decoder`() {
        for (data in listOf("123456789012", "AB-12", "Clubcard99")) {
            assertEquals(data, decode1D(Code128.encode(data)!!.bars, Code128Reader()))
        }
    }

    @Test fun `EAN-13 round-trips through a real decoder`() {
        // 12 digits in → decoder reads back the full 13 (with computed check digit).
        assertEquals("4006381333931", decode1D(EanUpc.encodeEan13("400638133393")!!.bars, EAN13Reader()))
    }

    @Test fun `UPC-A round-trips through a real decoder`() {
        // 11 digits in → check computed → 12-digit UPC read back.
        assertEquals("036000291452", decode1D(EanUpc.encodeUpcA("03600029145")!!.bars, UPCAReader()))
    }
}
