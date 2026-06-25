// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.codes

import kotlinx.coroutines.flow.Flow

/**
 * Persistence for saved codes. commonMain interface so the iOS companion can
 * supply its own impl; the Android impl is Room-backed in `:app`. Rows are tiny
 * ([SavedCode]) — the render matrix is regenerated on demand via [CodeGenerator]
 * and never stored.
 */
interface CodeRepository {
    /** All saved codes, newest first, observed live. */
    fun observeAll(): Flow<List<SavedCode>>

    /** One-shot snapshot (e.g. the watch sync). */
    suspend fun all(): List<SavedCode>

    suspend fun add(label: String, format: CodeFormat, data: String): SavedCode
    suspend fun updateLabel(id: String, label: String)
    suspend fun delete(id: String)
}
