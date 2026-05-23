package com.lazydevs.wristotle.backup

import com.lazydevs.wristotle.history.ConversationEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationEntryJsonTest {

    @Test fun roundTrip_allFieldsPopulated() {
        val original = ConversationEntry(
            id = 42,
            timestampEpochMs = 1_700_000_000_000L,
            userQuery = "call mom",
            responseText = "calling Mom",
            handler = "call",
            requiresCompanion = true,
            success = true,
            audioDurationMs = 1200,
            inferenceDurationMs = 450,
            audioCtx = 512,
            confidence = 0.92f,
            nluIntent = "Call",
            nluConfidence = 0.88f,
            audioFilePath = "/data/data/x/files/conversation-audio/123-456.wav",
        )
        val decoded = ConversationEntryJson.decode(
            ConversationEntryJson.encode(original),
            schema = 1,
        )
        // audioFilePath is reduced to basename — the importer re-prefixes.
        assertEquals(original.copy(audioFilePath = "123-456.wav"), decoded)
    }

    @Test fun nullableFieldsRoundTripAsNull() {
        val original = ConversationEntry(
            id = 1,
            timestampEpochMs = 0,
            userQuery = "x",
            responseText = "y",
            handler = "test",
            requiresCompanion = false,
            success = true,
            // all nullable fields null
        )
        val encoded = ConversationEntryJson.encode(original)
        // Nullable fields are OMITTED rather than emitted as JSON null.
        // (Lets older decoders that treat absence-as-default keep working.)
        assertEquals(false, encoded.has("audio_duration_ms"))
        assertEquals(false, encoded.has("inference_duration_ms"))
        assertEquals(false, encoded.has("audio_ctx"))
        assertEquals(false, encoded.has("confidence"))
        assertEquals(false, encoded.has("nlu_intent"))
        assertEquals(false, encoded.has("nlu_confidence"))
        assertEquals(false, encoded.has("audio_filename"))

        val decoded = ConversationEntryJson.decode(encoded, schema = 1)
        assertNull(decoded.audioDurationMs)
        assertNull(decoded.confidence)
        assertNull(decoded.nluIntent)
        assertNull(decoded.audioFilePath)
    }

    @Test fun zeroConfidenceRoundTripsAsZeroNotNull() {
        // Defensive: a real recorded zero (not "no value") survives.
        val original = ConversationEntry(
            id = 1, timestampEpochMs = 0, userQuery = "x", responseText = "y",
            handler = "test", requiresCompanion = true, success = true,
            confidence = 0.0f, nluConfidence = 0.0f,
        )
        val decoded = ConversationEntryJson.decode(
            ConversationEntryJson.encode(original),
            schema = 1,
        )
        assertEquals(0.0f, decoded.confidence!!, 0.0001f)
        assertEquals(0.0f, decoded.nluConfidence!!, 0.0001f)
    }
}
