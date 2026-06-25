// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.codes

import com.lazydevs.wristotle.speech.nlu.transport.WatchTransport

/**
 * Pushes the saved codes to the watch (companion → watch), one frame per code,
 * so the watch caches them for OFFLINE display. commonMain over the
 * [WatchTransport] seam, so Android + iOS share it.
 *
 * Unencodable codes are dropped BEFORE the count is computed, so the watch's
 * index/count bookkeeping (index 0 = reset, count = total) stays consistent.
 * An empty set sends a single clear frame.
 */
class CodeSyncSender(
    private val repository: CodeRepository,
    private val transport: WatchTransport,
) {
    suspend fun sync() {
        val frames = repository.all().mapNotNull { code ->
            CodeGenerator.matrix(code)?.let { Frame(code.label, CodeWire.encode(it)) }
        }
        if (frames.isEmpty()) {
            transport.sendCodeFrame(index = 0, count = 0, label = "", matrix = EMPTY)
            return
        }
        frames.forEachIndexed { i, f ->
            transport.sendCodeFrame(index = i, count = frames.size, label = f.label, matrix = f.matrix)
        }
    }

    private class Frame(val label: String, val matrix: ByteArray)

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
