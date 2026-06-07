// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.backup

import com.lazydevs.wristotle.history.ConversationEntry
import org.json.JSONObject
import java.io.File

/**
 * Per-entity JSON (en|de)coder for [ConversationEntry]. Same contract as
 * [NoteJson]: encoder emits [CURRENT_SCHEMA]; decoder accepts 1..[CURRENT_SCHEMA]
 * with explicit branches. Nullable fields are omitted from JSON when null
 * (vs. emitted as JSON `null`) so older decoders treating absence-as-default
 * keep working when we add fields in a future schema.
 */
object ConversationEntryJson {

    const val CURRENT_SCHEMA = 1

    fun encode(entry: ConversationEntry): JSONObject = JSONObject().apply {
        put("id", entry.id)
        put("timestamp_ms", entry.timestampEpochMs)
        put("user_query", entry.userQuery)
        put("response_text", entry.responseText)
        put("handler", entry.handler)
        put("requires_companion", entry.requiresCompanion)
        put("success", entry.success)
        entry.audioDurationMs?.let { put("audio_duration_ms", it) }
        entry.inferenceDurationMs?.let { put("inference_duration_ms", it) }
        entry.audioCtx?.let { put("audio_ctx", it) }
        entry.confidence?.let { put("confidence", it.toDouble()) }
        entry.nluIntent?.let { put("nlu_intent", it) }
        entry.nluConfidence?.let { put("nlu_confidence", it.toDouble()) }
        entry.audioFilePath?.let { put("audio_filename", File(it).name) }
    }

    fun decode(row: JSONObject, schema: Int): ConversationEntry {
        return when (schema) {
            1 -> ConversationEntry(
                id = row.optLong("id", 0L),
                timestampEpochMs = row.optLong("timestamp_ms", 0L),
                userQuery = row.optString("user_query", ""),
                responseText = row.optString("response_text", ""),
                handler = row.optString("handler", "unknown"),
                requiresCompanion = row.optBoolean("requires_companion", false),
                success = row.optBoolean("success", true),
                audioDurationMs = row.optLongOrNull("audio_duration_ms"),
                inferenceDurationMs = row.optLongOrNull("inference_duration_ms"),
                audioCtx = row.optIntOrNull("audio_ctx"),
                confidence = row.optDoubleOrNull("confidence")?.toFloat(),
                nluIntent = row.optString("nlu_intent").takeIf { it.isNotEmpty() },
                nluConfidence = row.optDoubleOrNull("nlu_confidence")?.toFloat(),
                audioFilePath = row.optString("audio_filename").takeIf { it.isNotEmpty() },
            )
            else -> throw IllegalArgumentException(
                "Unsupported ConversationEntryJson schema $schema (max $CURRENT_SCHEMA)"
            )
        }
    }
}

/**
 * `org.json.JSONObject` lacks "nullable" getters that distinguish
 * absent-vs-zero, so these helpers do `has(key) ? value : null`.
 * Kept private to the backup package — not a general-purpose utility.
 */
internal fun JSONObject.optLongOrNull(name: String): Long? =
    if (has(name) && !isNull(name)) getLong(name) else null

internal fun JSONObject.optIntOrNull(name: String): Int? =
    if (has(name) && !isNull(name)) getInt(name) else null

internal fun JSONObject.optDoubleOrNull(name: String): Double? =
    if (has(name) && !isNull(name)) getDouble(name) else null