// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.codes

import qrcode.QRCode
import qrcode.raw.ErrorCorrectionLevel

/**
 * QR generation, delegating to **qrcode-kotlin** (pure Kotlin, multiplatform
 * including a verified iosSimulatorArm64 artifact — so the Android AND iOS
 * companions share it). We take only the raw module grid (no image is rendered
 * here) and hand it to the watch as a [CodeMatrix.TwoD]; the watch draws it with
 * `fill_rect`. Error correction is LOW = fewest modules = biggest cells, which
 * scans best off the small watch screen.
 *
 * (1D barcodes are NOT covered by this library — see [Code128].)
 */
object Qr {
    fun encode(text: String): CodeMatrix.TwoD {
        val raw = QRCode.ofSquares()
            .withErrorCorrectionLevel(ErrorCorrectionLevel.LOW)
            .build(text)
            .rawData
        val size = raw.size
        val dark = Array(size) { row ->
            BooleanArray(size) { col -> raw[row].getOrNull(col)?.dark == true }
        }
        return CodeMatrix.TwoD(size, dark)
    }
}
