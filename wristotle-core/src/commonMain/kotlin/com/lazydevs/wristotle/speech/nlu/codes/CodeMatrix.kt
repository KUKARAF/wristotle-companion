// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.codes

/**
 * The render-ready output of code generation: the literal dark/light modules,
 * with NO quiet zone (the renderer adds that). The watch draws this directly
 * (one `fill_rect` per dark module) — there is no encoder on the watch.
 *
 * [OneD] is a single row of bars (linear barcode, drawn full-height); [TwoD] is
 * a square grid (QR). commonMain so the same matrix feeds the Android + iOS
 * companions' watch-sync path identically.
 */
sealed interface CodeMatrix {
    /** Linear barcode: [bars]`[i]` true = a dark module. */
    class OneD(val bars: BooleanArray) : CodeMatrix {
        val width: Int get() = bars.size
        /** Compact "1010…" debug/serialisation form. */
        fun bitString(): String = buildString(bars.size) { for (b in bars) append(if (b) '1' else '0') }
    }

    /** Square 2D matrix: [dark]`[row][col]` true = a dark module. */
    class TwoD(val size: Int, val dark: Array<BooleanArray>) : CodeMatrix
}
