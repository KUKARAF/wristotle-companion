package com.lazydevs.wristotle.backup

import com.lazydevs.wristotle.speech.nlu.bank.ExampleEntry
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
}
