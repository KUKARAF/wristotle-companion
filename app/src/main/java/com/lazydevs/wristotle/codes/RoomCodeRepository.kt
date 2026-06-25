// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.codes

import com.lazydevs.wristotle.speech.nlu.codes.CodeFormat
import com.lazydevs.wristotle.speech.nlu.codes.CodeRepository
import com.lazydevs.wristotle.speech.nlu.codes.SavedCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Android Room-backed [CodeRepository]. Maps [CodeEntity] ↔ [SavedCode]; mints
 * the id (UUID) and timestamp on add (kept out of commonMain). An entity whose
 * stored format is unknown to this build is skipped rather than crashing.
 */
class RoomCodeRepository(private val dao: CodeDao) : CodeRepository {

    override fun observeAll(): Flow<List<SavedCode>> =
        dao.observeAllNewestFirst().map { list -> list.mapNotNull(::toSaved) }

    override suspend fun all(): List<SavedCode> = dao.allNewestFirst().mapNotNull(::toSaved)

    override suspend fun add(label: String, format: CodeFormat, data: String): SavedCode {
        val code = SavedCode(
            id = UUID.randomUUID().toString(),
            label = label,
            format = format,
            data = data,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        dao.insert(CodeEntity(code.id, code.label, code.format.name, code.data, code.createdAtEpochMs))
        return code
    }

    override suspend fun updateLabel(id: String, label: String) = dao.updateLabel(id, label)

    override suspend fun delete(id: String) = dao.deleteById(id)

    private fun toSaved(e: CodeEntity): SavedCode? {
        val fmt = runCatching { CodeFormat.valueOf(e.format) }.getOrNull() ?: return null
        return SavedCode(e.id, e.label, fmt, e.data, e.createdAtEpochMs)
    }
}
