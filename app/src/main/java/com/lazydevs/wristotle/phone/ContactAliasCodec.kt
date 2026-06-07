// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.phone

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Pure (de)serialization for [ContactAliasStore] — a
 * `normalized phrase → ContactRef` map. Split out so it's unit-testable
 * without an Android `Context`.
 *
 * Wire format: a JSON array of objects, one per alias. Picked over the
 * app-side TAB-delimited format because [ContactRef.nameSnapshot] can
 * contain Unicode + punctuation that we'd otherwise have to escape.
 * Backup integration (Phase C) uses the same `org.json` lib as
 * [com.lazydevs.wristotle.backup.BackupManifest], so this stays
 * consistent.
 *
 * Decoder is forgiving — malformed JSON, missing fields, blank values
 * all skip the offending row rather than throwing. The prefs blob is
 * user data we never want to lose entirely on a single bad entry.
 */
internal object ContactAliasCodec {

    private const val KEY_PHRASE = "phrase"
    private const val KEY_LOOKUP = "lookup_key"
    private const val KEY_NAME = "name"
    private const val KEY_NUMBER = "number"

    fun encode(aliases: Map<String, ContactRef>): String {
        val arr = JSONArray()
        aliases.entries
            .sortedBy { it.key } // stable ordering — keeps prefs-diff churn down
            .forEach { (phrase, ref) ->
                arr.put(
                    JSONObject()
                        .put(KEY_PHRASE, phrase)
                        .put(KEY_LOOKUP, ref.lookupKey)
                        .put(KEY_NAME, ref.nameSnapshot)
                        .put(KEY_NUMBER, ref.numberSnapshot),
                )
            }
        return arr.toString()
    }

    fun decode(raw: String): Map<String, ContactRef> {
        if (raw.isEmpty()) return emptyMap()
        val arr = try {
            JSONArray(raw)
        } catch (_: JSONException) {
            return emptyMap()
        }
        val out = LinkedHashMap<String, ContactRef>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val phrase = obj.optString(KEY_PHRASE).takeIf { it.isNotEmpty() } ?: continue
            val lookup = obj.optString(KEY_LOOKUP).takeIf { it.isNotEmpty() } ?: continue
            val name = obj.optString(KEY_NAME)
            val number = obj.optString(KEY_NUMBER).takeIf { it.isNotEmpty() } ?: continue
            out[phrase] = ContactRef(lookup, name, number)
        }
        return out
    }
}