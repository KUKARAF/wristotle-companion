// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.codes

/**
 * A saved-code symbology. [is1D] = a linear barcode (rendered as vertical bars);
 * otherwise a 2D matrix (QR). Names line up with ZXing/ML Kit BarcodeFormat so a
 * scanned code's format maps straight across with no translation table.
 *
 * Pure enum, commonMain — shared by the Android + iOS companions.
 */
enum class CodeFormat(val is1D: Boolean, val displayName: String) {
    QR_CODE(is1D = false, displayName = "QR code"),
    CODE_128(is1D = true, displayName = "Code 128"),
    // EAN_13 / UPC_A / CODE_39 land as their encoders do.
}
