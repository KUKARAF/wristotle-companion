package com.lazydevs.wristotle.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupManifestCodecTest {

    @Test fun roundTrip_fullManifest() {
        val original = sampleManifest()
        assertEquals(original, BackupManifestCodec.decode(BackupManifestCodec.encode(original)))
    }

    @Test fun roundTrip_emptyPinsAndAliases() {
        val original = sampleManifest().copy(reminderPins = emptyList(), appAliases = emptyMap())
        val decoded = BackupManifestCodec.decode(BackupManifestCodec.encode(original))
        assertEquals(emptyList<BackupManifest.PinRecord>(), decoded.reminderPins)
        assertEquals(emptyMap<String, String>(), decoded.appAliases)
    }

    @Test fun forwardCompat_unknownTopLevelKeysIgnored() {
        // A future backup version might add new top-level keys. The current
        // decoder must skip them silently so older builds can still load
        // newer backups (as long as the per-entity schemas check out).
        val text = BackupManifestCodec.encode(sampleManifest())
        val withExtra = JSONObject(text).apply {
            put("future_field", "ignore me")
            put("future_array", org.json.JSONArray().put("x"))
        }.toString()
        val decoded = BackupManifestCodec.decode(withExtra)
        assertEquals(sampleManifest().stats, decoded.stats)
    }

    @Test fun activeModelIdsRoundTrip() {
        val withModels = sampleManifest().copy(
            prefs = sampleManifest().prefs.copy(
                whisperModels = BackupManifest.ModelPrefs(activeModelId = "base.en-q5_1"),
                nluModels = BackupManifest.ModelPrefs(activeModelId = "minilm-l6-v2-int8"),
            ),
        )
        val decoded = BackupManifestCodec.decode(BackupManifestCodec.encode(withModels))
        assertEquals("base.en-q5_1", decoded.prefs.whisperModels.activeModelId)
        assertEquals("minilm-l6-v2-int8", decoded.prefs.nluModels.activeModelId)
    }

    @Test fun nullActiveModelIdDecodesAsNull() {
        val withNullModels = sampleManifest().copy(
            prefs = sampleManifest().prefs.copy(
                whisperModels = BackupManifest.ModelPrefs(activeModelId = null),
                nluModels = BackupManifest.ModelPrefs(activeModelId = null),
            ),
        )
        val decoded = BackupManifestCodec.decode(BackupManifestCodec.encode(withNullModels))
        assertNull(decoded.prefs.whisperModels.activeModelId)
        assertNull(decoded.prefs.nluModels.activeModelId)
    }

    @Test fun pinWithNullTimeRoundTrips() {
        val original = sampleManifest().copy(
            reminderPins = listOf(BackupManifest.PinRecord("legacy-id", "old-pin", null)),
        )
        val decoded = BackupManifestCodec.decode(BackupManifestCodec.encode(original))
        assertNull(decoded.reminderPins[0].timeMs)
    }

    private fun sampleManifest() = BackupManifest(
        schema = BackupManifest.CURRENT_SCHEMA,
        exportedAtMs = 1_700_000_000_000L,
        appVersionName = "0.5.0",
        appVersionCode = 5,
        androidSdk = 33,
        deviceModel = "Test Phone",
        encrypted = false,
        includeAudio = true,
        dataSchemas = BackupManifest.DataSchemas(notes = 1, tasks = 1, conversations = 1, nlu = 1),
        stats = BackupManifest.Stats(
            notes = 7, tasks = 4, conversations = 148, nluLearned = 37,
            reminders = 1, aliases = 2,
        ),
        prefs = BackupManifest.PrefsBlock(
            notes = BackupManifest.NotesPrefs(keepLast = 100, appendAudioMode = "MERGE"),
            conversationSettings = BackupManifest.ConversationPrefs(retentionDays = 30),
            conversationAudio = BackupManifest.ConversationAudioPrefs(captureEnabled = true),
            nluSettings = BackupManifest.NluPrefs(learningEnabled = true),
            diagnostics = BackupManifest.DiagnosticsPrefs(redactPii = true, includeAudio = false),
            whisperModels = BackupManifest.ModelPrefs(activeModelId = "base.en"),
            nluModels = BackupManifest.ModelPrefs(activeModelId = "minilm"),
        ),
        reminderPins = listOf(
            BackupManifest.PinRecord("pin-1", "gym", 1_700_001_000_000L),
        ),
        appAliases = mapOf("yt" to "com.google.android.youtube"),
    )
}
