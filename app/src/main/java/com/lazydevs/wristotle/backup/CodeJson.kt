// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.backup

import com.lazydevs.wristotle.codes.CodeEntity
import org.json.JSONObject

/**
 * Per-entity JSON (en|de)coder for [CodeEntity] — same contract as [TaskJson]:
 * encoder always emits the current [CURRENT_SCHEMA] shape; decoder accepts every
 * schema from 1 up to [CURRENT_SCHEMA]. No assets, so it's just the columns.
 */
object CodeJson {

    const val CURRENT_SCHEMA = 1

    fun encode(code: CodeEntity): JSONObject = JSONObject().apply {
        put("id", code.id)
        put("label", code.label)
        put("alias", code.alias)
        put("format", code.format)
        put("data", code.data)
        put("created_at_ms", code.createdAtEpochMs)
    }

    fun decode(row: JSONObject, schema: Int): CodeEntity = when (schema) {
        1 -> CodeEntity(
            id = row.optString("id", ""),
            label = row.optString("label", ""),
            alias = row.optString("alias", ""),
            format = row.optString("format", ""),
            data = row.optString("data", ""),
            createdAtEpochMs = row.optLong("created_at_ms", 0L),
        )
        else -> throw IllegalArgumentException("Unsupported CodeJson schema $schema (max $CURRENT_SCHEMA)")
    }
}
