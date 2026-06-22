// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.backup

import com.lazydevs.wristotle.speech.nlu.backup.*

import com.lazydevs.wristotle.nlu.learning.ExampleEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleEntryJsonTest {

    @Test fun roundTrip() {
        val original = ExampleEntry(
            id = 9,
            intent = "Call",
            rawText = "give Mom a ring",
            normalizedText = "give mom a ring",
            source = "learned",
            addedAtEpochMs = 1_700_000_000_000L,
            usageCount = 5,
        )
        assertEquals(
            original,
            ExampleEntryJson.decode(ExampleEntryJson.encode(original), schema = 1),
        )
    }

    @Test fun usageCountDefaultsToOneIfMissing() {
        // Defensive default — a hand-edited backup that omits usage_count
        // shouldn't insert a row with `usageCount = 0` (which would never get
        // pruned by the learned-cap logic and look like a phantom row).
        val json = org.json.JSONObject().apply {
            put("id", 1)
            put("intent", "Call")
            put("raw_text", "x")
            put("normalized_text", "x")
            put("source", "learned")
            put("added_at_ms", 0)
        }
        assertEquals(1, ExampleEntryJson.decode(json, schema = 1).usageCount)
    }

    @Test fun sourceDefaultsToLearnedIfMissing() {
        // The exporter only ever writes learned rows, so a row missing `source`
        // (hand-edited backup) should decode as "learned", not empty.
        val json = org.json.JSONObject().apply {
            put("id", 1); put("intent", "Call")
            put("raw_text", "x"); put("normalized_text", "x"); put("added_at_ms", 0)
        }
        assertEquals("learned", ExampleEntryJson.decode(json, schema = 1).source)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedSchemaThrows() {
        // Decoder accepts 1..CURRENT_SCHEMA; a newer/unknown schema must fail
        // loudly rather than silently mis-decode.
        ExampleEntryJson.decode(org.json.JSONObject(), schema = ExampleEntryJson.CURRENT_SCHEMA + 1)
    }
}