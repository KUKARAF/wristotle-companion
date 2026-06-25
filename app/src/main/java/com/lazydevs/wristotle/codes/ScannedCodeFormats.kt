// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.codes

import com.lazydevs.wristotle.speech.nlu.codes.CodeFormat

/**
 * Bridges the camera scanner's ZXing `BarcodeFormat` names to our [CodeFormat].
 * Only formats we can faithfully RE-ENCODE for the watch are accepted: QR, Code
 * 128, and EAN-13 / UPC-A (the common product + loyalty symbologies). Code 39
 * etc. remain a follow-up; the scanner is restricted to [DESIRED] so the user
 * can't capture a code we couldn't display.
 */
object ScannedCodeFormats {
    /** ZXing format name → [CodeFormat], or null if we can't re-render it. */
    fun fromZxing(name: String?): CodeFormat? = when (name) {
        "QR_CODE" -> CodeFormat.QR_CODE
        "CODE_128" -> CodeFormat.CODE_128
        "EAN_13" -> CodeFormat.EAN_13
        "UPC_A" -> CodeFormat.UPC_A
        else -> null
    }

    /** The ZXing format names the scanner should look for. */
    val DESIRED: List<String> = listOf("QR_CODE", "CODE_128", "EAN_13", "UPC_A")
}
