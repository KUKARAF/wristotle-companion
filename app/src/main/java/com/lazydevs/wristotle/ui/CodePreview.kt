// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.lazydevs.wristotle.speech.nlu.codes.CodeGenerator
import com.lazydevs.wristotle.speech.nlu.codes.CodeMatrix
import com.lazydevs.wristotle.speech.nlu.codes.SavedCode

/**
 * Renders a [SavedCode] to a black-on-white Canvas — the same matrix the watch
 * draws ([CodeGenerator]). On-phone preview / fallback; the watch is the primary
 * display. White background + a quiet zone are included so it's scannable off
 * the phone too.
 */
@Composable
fun CodePreview(code: SavedCode, modifier: Modifier = Modifier) {
    val matrix = remember(code.id, code.format, code.data) { CodeGenerator.matrix(code) }
    Canvas(modifier.background(Color.White)) {
        when (matrix) {
            is CodeMatrix.TwoD -> {
                val quiet = 2
                val n = matrix.size + 2 * quiet
                val cell = minOf(size.width, size.height) / n
                val ox = (size.width - cell * n) / 2f + quiet * cell
                val oy = (size.height - cell * n) / 2f + quiet * cell
                for (r in 0 until matrix.size) {
                    for (c in 0 until matrix.size) {
                        if (matrix.dark[r][c]) {
                            drawRect(Color.Black, Offset(ox + c * cell, oy + r * cell), Size(cell, cell))
                        }
                    }
                }
            }
            is CodeMatrix.OneD -> {
                val quiet = 8
                val total = matrix.width + 2 * quiet
                val cell = size.width / total
                val ox = quiet * cell
                for (k in 0 until matrix.width) {
                    if (matrix.bars[k]) {
                        drawRect(Color.Black, Offset(ox + k * cell, 0f), Size(cell, size.height))
                    }
                }
            }
            null -> Unit
        }
    }
}
