// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.backup

import com.lazydevs.wristotle.speech.nlu.backup.*

import com.lazydevs.wristotle.phone.ContactRef
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
        val original = sampleManifest().copy(
            reminderPins = emptyList(),
            appAliases = emptyMap(),
            contactAliases = emptyMap(),
        )
        val decoded = BackupManifestCodec.decode(BackupManifestCodec.encode(original))
        assertEquals(emptyList<BackupManifest.PinRecord>(), decoded.reminderPins)
        assertEquals(emptyMap<String, String>(), decoded.appAliases)
        assertEquals(emptyMap<String, ContactRef>(), decoded.contactAliases)
    }

    @Test fun contactAliases_roundTrip() {
        val original = sampleManifest().copy(
            contactAliases = mapOf(
                "mom" to ContactRef(
                    lookupKey = "0r1-2A3B4C",
                    nameSnapshot = "Aparna Smith",
                    numberSnapshot = "+15551234567",
                ),
                "boss" to ContactRef(
                    lookupKey = "0r5-9X8Y",
                    nameSnapshot = "Jane Doe",
                    numberSnapshot = "+15559876543",
                ),
            ),
        )
        val decoded = BackupManifestCodec.decode(BackupManifestCodec.encode(original))
        assertEquals(original.contactAliases, decoded.contactAliases)
    }

    @Test fun preFeatureBackup_decodesContactAliasesAsEmpty() {
        // Older backups (before this feature shipped) won't have the
        // `contact_aliases` key. Decoder must treat absence as empty
        // rather than throwing, same forward-compat rule as `tasks`.
        val text = BackupManifestCodec.encode(sampleManifest())
        val withoutField = JSONObject(text).apply { remove("contact_aliases") }.toString()
        val decoded = BackupManifestCodec.decode(withoutField)
        assertEquals(emptyMap<String, ContactRef>(), decoded.contactAliases)
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
        assertEquals("base.en-q5_1", decoded.prefs.whisperModels?.activeModelId)
        assertEquals("minilm-l6-v2-int8", decoded.prefs.nluModels?.activeModelId)
    }

    @Test fun nullActiveModelIdDecodesAsNull() {
        val withNullModels = sampleManifest().copy(
            prefs = sampleManifest().prefs.copy(
                whisperModels = BackupManifest.ModelPrefs(activeModelId = null),
                nluModels = BackupManifest.ModelPrefs(activeModelId = null),
            ),
        )
        val decoded = BackupManifestCodec.decode(BackupManifestCodec.encode(withNullModels))
        assertNull(decoded.prefs.whisperModels?.activeModelId)
        assertNull(decoded.prefs.nluModels?.activeModelId)
    }

    @Test fun pinWithNullTimeRoundTrips() {
        val original = sampleManifest().copy(
            reminderPins = listOf(BackupManifest.PinRecord("legacy-id", "old-pin", null)),
        )
        val decoded = BackupManifestCodec.decode(BackupManifestCodec.encode(original))
        assertNull(decoded.reminderPins[0].timeMs)
    }

    // ── Schema 2: selection block ────────────────────────────────────────────

    @Test fun selectionBlock_roundTrips() {
        // A user who picked a non-default selection (some secrets on,
        // some content off). Must come back exactly equal.
        val original = sampleManifest().copy(
            selected = BackupSelection(
                notes = true,
                tasks = false,
                conversations = true,
                reminders = false,
                nluLearned = true,
                appAliases = true,
                contactAliases = false,
                audioRecordings = true,
                appPreferences = true,
                weatherSettings = true,
                mcpServers = true,
                askAgentSetup = true,
                weatherApiKey = true,
                mcpAuthHeaders = false,
                askAgentApiKeys = true,
            ),
        )
        val decoded = BackupManifestCodec.decode(BackupManifestCodec.encode(original))
        assertEquals(original.selected, decoded.selected)
    }

    @Test fun schema1Backup_decodesAsLegacyFullSelection() {
        // Schema-1 ZIPs predate the `selected` block entirely. The decoder
        // must treat their absence as "everything was ticked" so older
        // backups restore the same way they always did.
        val text = BackupManifestCodec.encode(sampleManifest())
        val schema1 = JSONObject(text).apply {
            put("schema", 1)
            remove("selected")
        }.toString()
        val decoded = BackupManifestCodec.decode(schema1)
        assertEquals(BackupSelection.LEGACY_FULL, decoded.selected)
        assertEquals(1, decoded.schema)
    }

    // ── Schema 2: new prefs blocks ───────────────────────────────────────────

    @Test fun reminderPrefs_roundTrip() {
        val original = sampleManifest().copy(
            prefs = sampleManifest().prefs.copy(
                reminder = BackupManifest.ReminderPrefs(defaultOffsetMin = 45),
            ),
        )
        val decoded = BackupManifestCodec.decode(BackupManifestCodec.encode(original))
        assertEquals(45, decoded.prefs.reminder?.defaultOffsetMin)
    }

    @Test fun weatherPrefs_withApiKey_roundTrip() {
        val original = sampleManifest().copy(
            prefs = sampleManifest().prefs.copy(
                weather = BackupManifest.WeatherPrefs(
                    unit = "FAHRENHEIT",
                    provider = "OPEN_WEATHER",
                    apiKey = "k_abc",
                ),
            ),
        )
        val decoded = BackupManifestCodec.decode(BackupManifestCodec.encode(original))
        assertEquals(original.prefs.weather, decoded.prefs.weather)
    }

    @Test fun weatherPrefs_withoutApiKey_decodesNullKey() {
        val original = sampleManifest().copy(
            prefs = sampleManifest().prefs.copy(
                weather = BackupManifest.WeatherPrefs(
                    unit = "CELSIUS",
                    provider = "OPEN_METEO",
                    apiKey = null,
                ),
            ),
        )
        val decoded = BackupManifestCodec.decode(BackupManifestCodec.encode(original))
        assertNull(decoded.prefs.weather?.apiKey)
        assertEquals("CELSIUS", decoded.prefs.weather?.unit)
    }

    @Test fun askAgentPrefs_withKeys_roundTrip() {
        val original = sampleManifest().copy(
            prefs = sampleManifest().prefs.copy(
                askAgent = BackupManifest.AskAgentPrefs(
                    provider = "ANTHROPIC",
                    anthropicModel = "claude-sonnet-4-6",
                    openaiEndpoint = "https://api.openai.com/v1/chat/completions",
                    openaiModel = "gpt-4o-mini",
                    systemPrompt = "Be brief.",
                    anthropicApiKey = "sk-ant-x",
                    openaiApiKey = "sk-o-y",
                ),
            ),
        )
        val decoded = BackupManifestCodec.decode(BackupManifestCodec.encode(original))
        assertEquals(original.prefs.askAgent, decoded.prefs.askAgent)
    }

    @Test fun askAgentPrefs_withoutKeys_decodesNullKeys() {
        val original = sampleManifest().copy(
            prefs = sampleManifest().prefs.copy(
                askAgent = BackupManifest.AskAgentPrefs(
                    provider = "OPENAI_COMPATIBLE",
                    anthropicModel = "claude-sonnet-4-6",
                    openaiEndpoint = "https://api.openai.com/v1/chat/completions",
                    openaiModel = "gpt-4o-mini",
                    systemPrompt = "",
                    anthropicApiKey = null,
                    openaiApiKey = null,
                ),
            ),
        )
        val decoded = BackupManifestCodec.decode(BackupManifestCodec.encode(original))
        assertNull(decoded.prefs.askAgent?.anthropicApiKey)
        assertNull(decoded.prefs.askAgent?.openaiApiKey)
        assertEquals("", decoded.prefs.askAgent?.systemPrompt)
    }

    @Test fun sportPrefs_roundTrip() {
        val original = sampleManifest().copy(
            prefs = sampleManifest().prefs.copy(
                sport = BackupManifest.SportPrefs(
                    favorites = listOf(
                        com.lazydevs.sportskapi.SportSubject("123", "City", "soccer", "eng.1"),
                        com.lazydevs.sportskapi.SportSubject("456", "Lakers", "basketball", "nba"),
                    ),
                    preferredSports = listOf("basketball", "soccer", "baseball"),
                    excludedSports = listOf("cricket", "racing"),
                ),
            ),
        )
        val decoded = BackupManifestCodec.decode(BackupManifestCodec.encode(original))
        assertEquals(original.prefs.sport, decoded.prefs.sport)
    }

    @Test fun sportPrefs_emptyLists_roundTrip() {
        val original = sampleManifest().copy(
            prefs = sampleManifest().prefs.copy(
                sport = BackupManifest.SportPrefs(favorites = emptyList(), preferredSports = emptyList()),
            ),
        )
        val decoded = BackupManifestCodec.decode(BackupManifestCodec.encode(original))
        assertEquals(emptyList<com.lazydevs.sportskapi.SportSubject>(), decoded.prefs.sport?.favorites)
        assertEquals(emptyList<String>(), decoded.prefs.sport?.preferredSports)
    }

    @Test fun schema1Backup_decodesPrefsBlocksAsNull() {
        // A schema-1 ZIP has no reminder/weather/askAgent prefs at all.
        val text = BackupManifestCodec.encode(sampleManifest().copy(
            prefs = sampleManifest().prefs.copy(
                reminder = BackupManifest.ReminderPrefs(99),
                weather = BackupManifest.WeatherPrefs("X", "Y"),
                askAgent = BackupManifest.AskAgentPrefs("X", "y", "z", "w", "p"),
            ),
        ))
        // Strip the schema-2 blocks from the encoded JSON to mimic
        // a schema-1 ZIP that doesn't have them.
        val root = JSONObject(text)
        val prefs = root.getJSONObject("prefs")
        prefs.remove("wristotle_reminder_settings")
        prefs.remove("weather_settings")
        prefs.remove("ask_agent_settings")
        root.put("schema", 1)
        root.remove("selected")
        val decoded = BackupManifestCodec.decode(root.toString())
        assertNull(decoded.prefs.reminder)
        assertNull(decoded.prefs.weather)
        assertNull(decoded.prefs.askAgent)
    }

    // ── Schema 2: MCP servers in dataSchemas + stats ─────────────────────────

    @Test fun mcpServersDataSchema_roundTrip() {
        val original = sampleManifest().copy(
            dataSchemas = sampleManifest().dataSchemas.copy(mcpServers = McpServerJson.CURRENT_SCHEMA),
            stats = sampleManifest().stats.copy(mcpServers = 3),
        )
        val decoded = BackupManifestCodec.decode(BackupManifestCodec.encode(original))
        assertEquals(McpServerJson.CURRENT_SCHEMA, decoded.dataSchemas.mcpServers)
        assertEquals(3, decoded.stats.mcpServers)
    }

    @Test fun schema1Backup_mcpServersStatAndDataSchema_default() {
        val text = BackupManifestCodec.encode(sampleManifest())
        val root = JSONObject(text)
        root.put("schema", 1)
        root.remove("selected")
        root.getJSONObject("data_schemas").remove("mcp_servers")
        root.getJSONObject("stats").remove("mcp_servers")
        val decoded = BackupManifestCodec.decode(root.toString())
        assertNull(decoded.dataSchemas.mcpServers)
        assertEquals(0, decoded.stats.mcpServers)
    }

    // ── Schema-1 audio-prefs key compat ──────────────────────────────────────

    @Test fun schema1Backup_legacyAudioPrefsKey_decodes() {
        // Schema 1 emitted the audio prefs under `wristotle_conversation_audio`;
        // schema 2 renamed it. Decoder must read both names so older ZIPs
        // continue to restore cleanly.
        val text = BackupManifestCodec.encode(sampleManifest())
        val root = JSONObject(text)
        val prefs = root.getJSONObject("prefs")
        val convAudio = prefs.getJSONObject("wristotle_audio_recordings")
        prefs.remove("wristotle_audio_recordings")
        prefs.put("wristotle_conversation_audio", convAudio)
        root.put("schema", 1)
        root.remove("selected")
        val decoded = BackupManifestCodec.decode(root.toString())
        assertEquals(true, decoded.prefs.conversationAudio?.captureEnabled)
    }

    // ── Nullable prefs blocks (any unticked category produces no JSON bytes) ──

    @Test fun nullPrefsBlocks_roundTrip() {
        val original = sampleManifest().copy(
            prefs = BackupManifest.PrefsBlock(
                // Everything null — what an export with no categories
                // selected would produce.
            ),
        )
        val decoded = BackupManifestCodec.decode(BackupManifestCodec.encode(original))
        assertNull(decoded.prefs.notes)
        assertNull(decoded.prefs.conversationSettings)
        assertNull(decoded.prefs.conversationAudio)
        assertNull(decoded.prefs.nluSettings)
        assertNull(decoded.prefs.diagnostics)
        assertNull(decoded.prefs.whisperModels)
        assertNull(decoded.prefs.nluModels)
        assertNull(decoded.prefs.reminder)
        assertNull(decoded.prefs.weather)
        assertNull(decoded.prefs.askAgent)
    }

    @Test fun mixedPrefsBlocks_roundTrip() {
        // Mix of present / absent — e.g. a user who unticked App Preferences
        // but kept Weather Settings on.
        val original = sampleManifest().copy(
            prefs = BackupManifest.PrefsBlock(
                weather = BackupManifest.WeatherPrefs("CELSIUS", "OPEN_METEO"),
            ),
        )
        val decoded = BackupManifestCodec.decode(BackupManifestCodec.encode(original))
        assertEquals("CELSIUS", decoded.prefs.weather?.unit)
        assertNull(decoded.prefs.notes)
        assertNull(decoded.prefs.diagnostics)
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
        contactAliases = mapOf(
            "mom" to ContactRef(
                lookupKey = "0r1-SAMPLE",
                nameSnapshot = "Sample Contact",
                numberSnapshot = "+15550000000",
            ),
        ),
    )
}