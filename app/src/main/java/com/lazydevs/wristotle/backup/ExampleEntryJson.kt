// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.backup

import com.lazydevs.wristotle.speech.nlu.bank.ExampleEntry
import org.json.JSONObject

/**
 * Per-entity JSON (en|de)coder for [ExampleEntry] (NLU learned examples).
 *
 * Only `source = "learned"` rows are exported — seed examples ship bundled
 * with the app and would just bloat backups. The exporter pulls via
 * `ExampleDao.learned()` to enforce that at read time too.
 */
object ExampleEntryJson {

    const val CURRENT_SCHEMA = 1

    fun encode(entry: ExampleEntry): JSONObject = JSONObject().apply {
        put("id", entry.id)
        put("intent", entry.intent)
        put("raw_text", entry.rawText)
        put("normalized_text", entry.normalizedText)
        put("source", entry.source)
        put("added_at_ms", entry.addedAtEpochMs)
        put("usage_count", entry.usageCount)
    }

    fun decode(row: JSONObject, schema: Int): ExampleEntry {
        return when (schema) {
            1 -> ExampleEntry(
                id = row.optLong("id", 0L),
                intent = row.optString("intent", ""),
                rawText = row.optString("raw_text", ""),
                normalizedText = row.optString("normalized_text", ""),
                source = row.optString("source", "learned"),
                addedAtEpochMs = row.optLong("added_at_ms", 0L),
                usageCount = row.optInt("usage_count", 1),
            )
            else -> throw IllegalArgumentException(
                "Unsupported ExampleEntryJson schema $schema (max $CURRENT_SCHEMA)"
            )
        }
    }
}