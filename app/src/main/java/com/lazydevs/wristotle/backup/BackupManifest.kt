// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.backup

import com.lazydevs.sportskapi.SportSubject
import com.lazydevs.wristotle.speech.nlu.homeassistant.HomeAssistantClient
import com.lazydevs.wristotle.speech.nlu.settings.AgentRoutingMode
import com.lazydevs.wristotle.speech.nlu.settings.AskAgentSettings
import com.lazydevs.wristotle.speech.nlu.settings.HomeAssistantSettings
import com.lazydevs.wristotle.speech.nlu.settings.ReminderSettings
import com.lazydevs.wristotle.phone.ContactRef
import org.json.JSONArray
import org.json.JSONObject
import com.lazydevs.wristotle.speech.nlu.backup.BackupSelection

/**
 * Top-level shape of `manifest.json` inside a Wristotle backup ZIP.
 *
 * Everything other than the three data/<name>.json row dumps (and, in Phase B,
 * the audio blobs) is normalised into this manifest — settings prefs, pin
 * store, aliases. Cross-Android-version readable; codec lives in
 * [BackupManifestCodec] so it's unit-testable without Room or Context.
 *
 * `schema` is the backup format version (how we'd evolve the ZIP layout);
 * `dataSchemas` mirrors each data/<name>.json's "schema" field — the
 * per-entity wire-format version, NOT a Room @Database(version). The
 * importer compares each to the live decoder's CURRENT_SCHEMA and refuses
 * individual entities that are too new.
 */
data class BackupManifest(
    val schema: Int,
    val exportedAtMs: Long,
    val appVersionName: String,
    val appVersionCode: Int,
    val androidSdk: Int,
    val deviceModel: String,
    val encrypted: Boolean,
    val includeAudio: Boolean,
    val dataSchemas: DataSchemas,
    val stats: Stats,
    val prefs: PrefsBlock,
    val reminderPins: List<PinRecord>,
    val appAliases: Map<String, String>,
    val contactAliases: Map<String, ContactRef>,
    /**
     * Records which categories were actually included in this ZIP.
     * Drives the restore UI's "which categories are available to
     * restore" toggle. Defaults to [BackupSelection.LEGACY_FULL] for
     * older schema-1 ZIPs that didn't carry it.
     */
    val selected: BackupSelection = BackupSelection.LEGACY_FULL,
) {
    /**
     * Per-entity (NOT per-Room-DB) schema versions of the data files inside
     * the ZIP. Each field is the `schema:` value of the corresponding
     * `data/<name>.json` wrapper, matching the entity's `*Json.CURRENT_SCHEMA`
     * at export time. The importer compares each field to the live decoder's
     * `CURRENT_SCHEMA` and refuses individual entities that are too new for
     * the current code to understand. Fields are nullable when the entity
     * may be absent from a given ZIP — either because it was unticked at
     * export, or because the feature didn't exist when the ZIP was written.
     */
    data class DataSchemas(
        val notes: Int,
        val tasks: Int?,
        val conversations: Int,
        val nlu: Int,
        val mcpServers: Int? = null,
        val codes: Int? = null,
    )
    data class Stats(
        val notes: Int,
        val tasks: Int,
        val conversations: Int,
        val nluLearned: Int,
        val reminders: Int,
        val aliases: Int,
        val contactAliases: Int = 0,
        val mcpServers: Int = 0,
        val codes: Int = 0,
    )

    /**
     * Everything we back up, keyed by feature. Each sub-block is nullable
     * — when null on export the encoder omits it from the JSON entirely,
     * and on import the decoder reads null as "no change, keep the
     * device's current value." This is what lets per-category opt-in
     * work: an unticked category produces no prefs bytes in the ZIP.
     */
    data class PrefsBlock(
        val notes: NotesPrefs? = null,
        val conversationSettings: ConversationPrefs? = null,
        val conversationAudio: ConversationAudioPrefs? = null,
        val nluSettings: NluPrefs? = null,
        val diagnostics: DiagnosticsPrefs? = null,
        val whisperModels: ModelPrefs? = null,
        val nluModels: ModelPrefs? = null,
        val reminder: ReminderPrefs? = null,
        val morningBrief: MorningBriefPrefs? = null,
        val weather: WeatherPrefs? = null,
        val askAgent: AskAgentPrefs? = null,
        val homeAssistant: HomeAssistantPrefs? = null,
        val sttProvider: SttProviderPrefs? = null,
        val ttsProvider: TtsProviderPrefs? = null,
        val sport: SportPrefs? = null,
        val cards: CardsPrefs? = null,
    )
    /** Watch-card on/off — the set of DISABLED card kinds. Rides with the
     *  `appPreferences` umbrella (non-sensitive UI prefs). */
    data class CardsPrefs(val disabled: List<String>)
    data class NotesPrefs(val keepLast: Int, val appendAudioMode: String)
    data class ConversationPrefs(val retentionDays: Int)
    data class ConversationAudioPrefs(val captureEnabled: Boolean)
    data class NluPrefs(val learningEnabled: Boolean)
    data class DiagnosticsPrefs(val redactPii: Boolean, val includeAudio: Boolean)
    data class ModelPrefs(val activeModelId: String?)

    data class ReminderPrefs(
        val defaultOffsetMin: Int,
        val defaultIntervalMin: Int = ReminderSettings.DEFAULT_INTERVAL_MIN,
        val defaultMaxAttempts: Int = ReminderSettings.DEFAULT_MAX_ATTEMPTS,
    )

    /** Morning Brief preferences. Currently one knob — the opt-in toggle
     *  for the persisted-notification-log path that lets the brief surface
     *  notifications you'd already dismissed earlier today. The DB rows
     *  themselves are NOT backed up; only the user's toggle preference. */
    data class MorningBriefPrefs(
        val notifLogEnabled: Boolean = false,
        /** Disabled BriefSection keys. Default empty (= all sections on) keeps
         *  older backups, which lacked this field, decoding to "all on". */
        val disabledSections: List<String> = emptyList(),
    )

    /** `apiKey` rides only when the user ticks the secret checkbox. */
    data class WeatherPrefs(
        val unit: String,
        val provider: String,
        val apiKey: String? = null,
    )

    /** Per-provider API keys ride only when the user ticks the secret
     *  checkbox. Endpoints/models/system-prompt ride with the
     *  non-sensitive "askAgent setup" category. */
    data class AskAgentPrefs(
        val provider: String,
        val anthropicModel: String,
        val openaiEndpoint: String,
        val openaiModel: String,
        val systemPrompt: String,
        val anthropicApiKey: String? = null,
        val openaiApiKey: String? = null,
        val anthropicWebSearch: Boolean = false,
        val responseTimeoutSec: Int = AskAgentSettings.DEFAULT_RESPONSE_TIMEOUT_SEC,
        val agentRoutingMode: String = AgentRoutingMode.OFF.name,
    )

    /** Home Assistant — base URL + language + timeout ride with the
     *  non-sensitive "home assistant setup" category; the long-lived
     *  [token] rides ONLY when the user ticks the secret checkbox. */
    data class HomeAssistantPrefs(
        val baseUrl: String,
        val language: String = HomeAssistantClient.DEFAULT_LANGUAGE,
        val responseTimeoutSec: Int = HomeAssistantSettings.DEFAULT_RESPONSE_TIMEOUT_SEC,
        val customTriggers: List<String> = emptyList(),
        val token: String? = null,
    )

    /** STT provider — mode + base URL + model travel with the
     *  non-sensitive "STT provider setup" category. `apiKey` rides
     *  only when the secret checkbox is ticked. */
    data class SttProviderPrefs(
        val mode: String,
        val httpBaseUrl: String,
        val httpModel: String,
        val httpApiKey: String? = null,
    )

    /** TTS provider — master toggle + mode + base URL + model + voice +
     *  the comma-joined intent opt-in set travel with the non-sensitive
     *  "TTS provider setup" category. `httpApiKey` rides only when the
     *  secret checkbox is ticked. The intent CSV mirrors the on-disk
     *  shape `TtsProviderSettings` already uses. */
    data class TtsProviderPrefs(
        val enabled: Boolean,
        val mode: String,
        val httpBaseUrl: String,
        val httpModel: String,
        val httpVoice: String,
        val intentsCsv: String,
        val httpApiKey: String? = null,
    )

    /** Sport favorites + the priority-ordered sport list. Non-sensitive —
     *  rides with the "sport settings" category (no secret sub-fields).
     *  Favorites are the library's neutral [SportSubject]; serialised as a
     *  JSON array of their scalar fields so the org.json codec stays simple. */
    data class SportPrefs(
        val favorites: List<SportSubject>,
        val preferredSports: List<String>,
        val excludedSports: List<String> = emptyList(),
    )

    /** Wire-format record matching the manifest JSON, not the Room/PinStore type. */
    data class PinRecord(
        val id: String,
        val title: String,
        val timeMs: Long?,
        val isPersistent: Boolean = false,
        val attemptsRemaining: Int = 0,
    )

    companion object {
        /**
         * Backup manifest schema history:
         *
         * - **1** — original release. No selection block; no per-category
         *   filtering. Decoder assumes everything in the ZIP was wanted.
         * - **2** (2026-05-31) — added on v0.15.0:
         *   * `selected: BackupSelection` records what was opted in.
         *   * Three new prefs blocks: `reminder` / `weather` / `askAgent`.
         *   * `data/mcp_servers.json` (referenced from `dataSchemas.mcpServers`).
         *   * All legacy prefs blocks became nullable so an unticked
         *     category produces no JSON bytes (instead of dummy-default
         *     bytes). Decoder treats null prefs as "no change on restore."
         *   * Audio-prefs key was `wristotle_conversation_audio` in
         *     schema 1; decoder reads both names for back-compat.
         *
         * Decoder is backward-compatible — schema-1 ZIPs decode by
         * falling missing fields back to null / LEGACY_FULL / 0.
         */
        const val CURRENT_SCHEMA = 2
        const val FILENAME = "manifest.json"
    }
}

/**
 * Pure JSON (de)serialization for [BackupManifest]. Uses Android's bundled
 * [org.json] rather than pulling in kotlinx-serialization for one file — the
 * shape is fixed and small.
 *
 * Forward-compatible reads: unknown top-level keys are ignored (so a future
 * backup format that adds fields still loads here, modulo whatever the
 * importer checks); missing optional keys fall back to safe defaults.
 */
object BackupManifestCodec {

    fun encode(m: BackupManifest): String = JSONObject().apply {
        put("schema", m.schema)
        put("exported_at_ms", m.exportedAtMs)
        put("app_version_name", m.appVersionName)
        put("app_version_code", m.appVersionCode)
        put("android_sdk", m.androidSdk)
        put("device_model", m.deviceModel)
        put("encrypted", m.encrypted)
        put("include_audio", m.includeAudio)
        put("data_schemas", JSONObject().apply {
            put("notes", m.dataSchemas.notes)
            if (m.dataSchemas.tasks != null) put("tasks", m.dataSchemas.tasks)
            if (m.dataSchemas.codes != null) put("codes", m.dataSchemas.codes)
            put("conversations", m.dataSchemas.conversations)
            put("nlu", m.dataSchemas.nlu)
            if (m.dataSchemas.mcpServers != null) put("mcp_servers", m.dataSchemas.mcpServers)
        })
        put("stats", JSONObject().apply {
            put("notes", m.stats.notes)
            put("tasks", m.stats.tasks)
            put("codes", m.stats.codes)
            put("conversations", m.stats.conversations)
            put("nlu_learned", m.stats.nluLearned)
            put("reminders", m.stats.reminders)
            put("aliases", m.stats.aliases)
            put("contact_aliases", m.stats.contactAliases)
            put("mcp_servers", m.stats.mcpServers)
        })
        put("selected", JSONObject().apply {
            put("notes", m.selected.notes)
            put("codes", m.selected.codes)
            put("tasks", m.selected.tasks)
            put("conversations", m.selected.conversations)
            put("reminders", m.selected.reminders)
            put("nlu_learned", m.selected.nluLearned)
            put("app_aliases", m.selected.appAliases)
            put("contact_aliases", m.selected.contactAliases)
            put("audio_recordings", m.selected.audioRecordings)
            put("app_preferences", m.selected.appPreferences)
            put("weather_settings", m.selected.weatherSettings)
            put("mcp_servers", m.selected.mcpServers)
            put("ask_agent_setup", m.selected.askAgentSetup)
            put("home_assistant_setup", m.selected.homeAssistantSetup)
            put("stt_provider_setup", m.selected.sttProviderSetup)
            put("tts_provider_setup", m.selected.ttsProviderSetup)
            put("sport_settings", m.selected.sportSettings)
            put("weather_api_key", m.selected.weatherApiKey)
            put("mcp_auth_headers", m.selected.mcpAuthHeaders)
            put("ask_agent_api_keys", m.selected.askAgentApiKeys)
            put("home_assistant_token", m.selected.homeAssistantToken)
            put("stt_provider_api_key", m.selected.sttProviderApiKey)
            put("tts_provider_api_key", m.selected.ttsProviderApiKey)
        })
        // Each block is omitted entirely when null (category wasn't
        // selected on export); the decoder treats absence as "no change
        // on restore." Encoder is gated for every block, not just the
        // schema-2 additions, so an unticked category produces zero
        // pref bytes in the ZIP.
        put("prefs", JSONObject().apply {
            m.prefs.notes?.let { n ->
                put("wristotle_notes", JSONObject().apply {
                    put("keep_last", n.keepLast)
                    put("append_audio_mode", n.appendAudioMode)
                })
            }
            m.prefs.conversationSettings?.let { c ->
                put("wristotle_conversation_settings", JSONObject().apply {
                    put("retention_days", c.retentionDays)
                })
            }
            m.prefs.conversationAudio?.let { ca ->
                put("wristotle_audio_recordings", JSONObject().apply {
                    put("capture_enabled", ca.captureEnabled)
                })
            }
            m.prefs.nluSettings?.let { nl ->
                put("wristotle_nlu_settings", JSONObject().apply {
                    put("learning_enabled", nl.learningEnabled)
                })
            }
            m.prefs.diagnostics?.let { d ->
                put("wristotle_diagnostics", JSONObject().apply {
                    put("redact_pii", d.redactPii)
                    put("include_audio", d.includeAudio)
                })
            }
            m.prefs.whisperModels?.let { wm ->
                put("whisper_models", JSONObject().apply {
                    wm.activeModelId?.let { put("active_model_id", it) }
                })
            }
            m.prefs.nluModels?.let { nm ->
                put("nlu_models", JSONObject().apply {
                    nm.activeModelId?.let { put("active_model_id", it) }
                })
            }
            m.prefs.reminder?.let { r ->
                put("wristotle_reminder_settings", JSONObject().apply {
                    put("default_offset_min", r.defaultOffsetMin)
                    put("default_interval_min", r.defaultIntervalMin)
                    put("default_max_attempts", r.defaultMaxAttempts)
                })
            }
            m.prefs.morningBrief?.let { mb ->
                put("wristotle_notif_log_settings", JSONObject().apply {
                    put("enabled", mb.notifLogEnabled)
                    put("disabled_sections", JSONArray().also { arr ->
                        mb.disabledSections.forEach { arr.put(it) }
                    })
                })
            }
            m.prefs.weather?.let { w ->
                put("weather_settings", JSONObject().apply {
                    put("unit", w.unit)
                    put("provider", w.provider)
                    // api_key only when the secret was selected — exporter
                    // is responsible for not putting it on the block.
                    if (w.apiKey != null) put("api_key", w.apiKey)
                })
            }
            m.prefs.askAgent?.let { a ->
                put("ask_agent_settings", JSONObject().apply {
                    put("provider", a.provider)
                    put("anthropic_model", a.anthropicModel)
                    put("openai_endpoint", a.openaiEndpoint)
                    put("openai_model", a.openaiModel)
                    put("system_prompt", a.systemPrompt)
                    put("anthropic_web_search", a.anthropicWebSearch)
                    put("response_timeout_sec", a.responseTimeoutSec)
                    put("agent_routing_mode", a.agentRoutingMode)
                    if (a.anthropicApiKey != null) put("anthropic_api_key", a.anthropicApiKey)
                    if (a.openaiApiKey != null) put("openai_api_key", a.openaiApiKey)
                })
            }
            m.prefs.homeAssistant?.let { h ->
                put("home_assistant_settings", JSONObject().apply {
                    put("base_url", h.baseUrl)
                    put("language", h.language)
                    put("response_timeout_sec", h.responseTimeoutSec)
                    put("custom_triggers", JSONArray(h.customTriggers))
                    if (h.token != null) put("token", h.token)
                })
            }
            m.prefs.sttProvider?.let { s ->
                put("stt_provider_settings", JSONObject().apply {
                    put("mode", s.mode)
                    put("http_base_url", s.httpBaseUrl)
                    put("http_model", s.httpModel)
                    if (s.httpApiKey != null) put("http_api_key", s.httpApiKey)
                })
            }
            m.prefs.ttsProvider?.let { t ->
                put("tts_provider_settings", JSONObject().apply {
                    put("enabled", t.enabled)
                    put("mode", t.mode)
                    put("http_base_url", t.httpBaseUrl)
                    put("http_model", t.httpModel)
                    put("http_voice", t.httpVoice)
                    put("intents_enabled", t.intentsCsv)
                    if (t.httpApiKey != null) put("http_api_key", t.httpApiKey)
                })
            }
            m.prefs.sport?.let { s ->
                put("sport_settings", JSONObject().apply {
                    put("favorites", JSONArray().also { arr ->
                        s.favorites.forEach { f ->
                            arr.put(
                                JSONObject()
                                    .put("id", f.id)
                                    .put("name", f.name)
                                    .put("sport", f.sport)
                                    .put("league", f.league),
                            )
                        }
                    })
                    put("preferred_sports", JSONArray().also { arr ->
                        s.preferredSports.forEach { arr.put(it) }
                    })
                    put("excluded_sports", JSONArray().also { arr ->
                        s.excludedSports.forEach { arr.put(it) }
                    })
                })
            }
            m.prefs.cards?.let { c ->
                put("cards", JSONObject().apply {
                    put("disabled", JSONArray().also { arr ->
                        c.disabled.forEach { arr.put(it) }
                    })
                })
            }
        })
        put("reminder_pins", JSONArray().apply {
            m.reminderPins.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id)
                    put("title", p.title)
                    if (p.timeMs != null) put("time_ms", p.timeMs)
                    // Persistent fields are only emitted when set so older
                    // backups (pre persistent-reminders) stay byte-clean and
                    // newer ones don't carry default noise on every record.
                    if (p.isPersistent) {
                        put("is_persistent", true)
                        put("attempts_remaining", p.attemptsRemaining)
                    }
                })
            }
        })
        put("app_aliases", JSONObject().apply {
            m.appAliases.forEach { (phrase, pkg) -> put(phrase, pkg) }
        })
        // Contact aliases ride as a JSON array (not object) because the
        // value is structured (lookup key + name snapshot + number
        // snapshot), not a single string like app aliases. Older backups
        // (pre this feature) lack the field; the decoder treats absence
        // as empty.
        put("contact_aliases", JSONArray().apply {
            m.contactAliases.entries
                .sortedBy { it.key }
                .forEach { (phrase, ref) ->
                    put(
                        JSONObject()
                            .put("phrase", phrase)
                            .put("lookup_key", ref.lookupKey)
                            .put("name", ref.nameSnapshot)
                            .put("number", ref.numberSnapshot),
                    )
                }
        })
    }.toString(2)

    fun decode(raw: String): BackupManifest {
        val root = JSONObject(raw)
        val prefs = root.getJSONObject("prefs")
        // Every prefs sub-block is optional — schema 2 made all blocks
        // nullable so an unticked category produces no JSON bytes.
        val notesPrefs = prefs.optJSONObject("wristotle_notes")
        val convPrefs = prefs.optJSONObject("wristotle_conversation_settings")
        // Schema 1 emitted this as `wristotle_conversation_audio`; schema 2
        // renamed it to `wristotle_audio_recordings`. Read both so schema-1
        // ZIPs continue to restore cleanly.
        val convAudio = prefs.optJSONObject("wristotle_audio_recordings")
            ?: prefs.optJSONObject("wristotle_conversation_audio")
        val nluPrefs = prefs.optJSONObject("wristotle_nlu_settings")
        val diagPrefs = prefs.optJSONObject("wristotle_diagnostics")
        val whisper = prefs.optJSONObject("whisper_models")
        val nluModels = prefs.optJSONObject("nlu_models")
        val dataSchemas = root.getJSONObject("data_schemas")
        val stats = root.getJSONObject("stats")
        val pinsArr = root.optJSONArray("reminder_pins") ?: JSONArray()
        val aliasesObj = root.optJSONObject("app_aliases") ?: JSONObject()
        val contactAliasesArr = root.optJSONArray("contact_aliases") ?: JSONArray()

        return BackupManifest(
            schema = root.getInt("schema"),
            exportedAtMs = root.getLong("exported_at_ms"),
            appVersionName = root.optString("app_version_name", ""),
            appVersionCode = root.optInt("app_version_code", 0),
            androidSdk = root.optInt("android_sdk", 0),
            deviceModel = root.optString("device_model", ""),
            encrypted = root.optBoolean("encrypted", false),
            includeAudio = root.optBoolean("include_audio", false),
            dataSchemas = BackupManifest.DataSchemas(
                notes = dataSchemas.getInt("notes"),
                tasks = if (dataSchemas.has("tasks")) dataSchemas.getInt("tasks") else null,
                conversations = dataSchemas.getInt("conversations"),
                nlu = dataSchemas.getInt("nlu"),
                mcpServers = if (dataSchemas.has("mcp_servers")) dataSchemas.getInt("mcp_servers") else null,
                codes = if (dataSchemas.has("codes")) dataSchemas.getInt("codes") else null,
            ),
            stats = BackupManifest.Stats(
                notes = stats.optInt("notes", 0),
                tasks = stats.optInt("tasks", 0),
                conversations = stats.optInt("conversations", 0),
                nluLearned = stats.optInt("nlu_learned", 0),
                reminders = stats.optInt("reminders", 0),
                aliases = stats.optInt("aliases", 0),
                contactAliases = stats.optInt("contact_aliases", 0),
                mcpServers = stats.optInt("mcp_servers", 0),
                codes = stats.optInt("codes", 0),
            ),
            prefs = BackupManifest.PrefsBlock(
                notes = notesPrefs?.let {
                    BackupManifest.NotesPrefs(
                        keepLast = it.optInt("keep_last", 0),
                        appendAudioMode = it.optString("append_audio_mode", "MERGE"),
                    )
                },
                conversationSettings = convPrefs?.let {
                    BackupManifest.ConversationPrefs(
                        retentionDays = it.optInt("retention_days", 10),
                    )
                },
                conversationAudio = convAudio?.let {
                    BackupManifest.ConversationAudioPrefs(
                        captureEnabled = it.optBoolean("capture_enabled", false),
                    )
                },
                nluSettings = nluPrefs?.let {
                    BackupManifest.NluPrefs(
                        learningEnabled = it.optBoolean("learning_enabled", true),
                    )
                },
                diagnostics = diagPrefs?.let {
                    BackupManifest.DiagnosticsPrefs(
                        redactPii = it.optBoolean("redact_pii", true),
                        includeAudio = it.optBoolean("include_audio", false),
                    )
                },
                whisperModels = whisper?.let {
                    BackupManifest.ModelPrefs(
                        activeModelId = it.optString("active_model_id").takeIf { s -> s.isNotEmpty() },
                    )
                },
                nluModels = nluModels?.let {
                    BackupManifest.ModelPrefs(
                        activeModelId = it.optString("active_model_id").takeIf { s -> s.isNotEmpty() },
                    )
                },
                reminder = prefs.optJSONObject("wristotle_reminder_settings")?.let { r ->
                    BackupManifest.ReminderPrefs(
                        defaultOffsetMin = r.optInt("default_offset_min", 0),
                        defaultIntervalMin = r.optInt(
                            "default_interval_min",
                            ReminderSettings.DEFAULT_INTERVAL_MIN,
                        ),
                        defaultMaxAttempts = r.optInt(
                            "default_max_attempts",
                            ReminderSettings.DEFAULT_MAX_ATTEMPTS,
                        ),
                    )
                },
                morningBrief = prefs.optJSONObject("wristotle_notif_log_settings")?.let { mb ->
                    val sections = mb.optJSONArray("disabled_sections") ?: JSONArray()
                    BackupManifest.MorningBriefPrefs(
                        notifLogEnabled = mb.optBoolean("enabled", false),
                        disabledSections = (0 until sections.length()).map { sections.getString(it) },
                    )
                },
                weather = prefs.optJSONObject("weather_settings")?.let { w ->
                    BackupManifest.WeatherPrefs(
                        unit = w.optString("unit", "CELSIUS"),
                        provider = w.optString("provider", "OPEN_METEO"),
                        apiKey = w.optString("api_key").takeIf { it.isNotEmpty() },
                    )
                },
                askAgent = prefs.optJSONObject("ask_agent_settings")?.let { a ->
                    BackupManifest.AskAgentPrefs(
                        provider = a.optString("provider", "ANTHROPIC"),
                        anthropicModel = a.optString("anthropic_model", ""),
                        openaiEndpoint = a.optString("openai_endpoint", ""),
                        openaiModel = a.optString("openai_model", ""),
                        systemPrompt = a.optString("system_prompt", ""),
                        anthropicApiKey = a.optString("anthropic_api_key").takeIf { it.isNotEmpty() },
                        openaiApiKey = a.optString("openai_api_key").takeIf { it.isNotEmpty() },
                        anthropicWebSearch = a.optBoolean("anthropic_web_search", false),
                        responseTimeoutSec = a.optInt(
                            "response_timeout_sec",
                            AskAgentSettings.DEFAULT_RESPONSE_TIMEOUT_SEC,
                        ),
                        agentRoutingMode = a.optString(
                            "agent_routing_mode",
                            AgentRoutingMode.OFF.name,
                        ),
                    )
                },
                homeAssistant = prefs.optJSONObject("home_assistant_settings")?.let { h ->
                    val triggers = h.optJSONArray("custom_triggers") ?: JSONArray()
                    BackupManifest.HomeAssistantPrefs(
                        baseUrl = h.optString("base_url", ""),
                        language = h.optString("language", HomeAssistantClient.DEFAULT_LANGUAGE),
                        responseTimeoutSec = h.optInt(
                            "response_timeout_sec",
                            HomeAssistantSettings.DEFAULT_RESPONSE_TIMEOUT_SEC,
                        ),
                        customTriggers = (0 until triggers.length()).map { triggers.getString(it) },
                        token = h.optString("token").takeIf { it.isNotEmpty() },
                    )
                },
                sttProvider = prefs.optJSONObject("stt_provider_settings")?.let { s ->
                    BackupManifest.SttProviderPrefs(
                        mode = s.optString("mode", "LOCAL_ONLY"),
                        httpBaseUrl = s.optString("http_base_url", ""),
                        httpModel = s.optString("http_model", ""),
                        httpApiKey = s.optString("http_api_key").takeIf { it.isNotEmpty() },
                    )
                },
                ttsProvider = prefs.optJSONObject("tts_provider_settings")?.let { t ->
                    BackupManifest.TtsProviderPrefs(
                        enabled = t.optBoolean("enabled", false),
                        mode = t.optString("mode", "LOCAL_ONLY"),
                        httpBaseUrl = t.optString("http_base_url", ""),
                        httpModel = t.optString("http_model", ""),
                        httpVoice = t.optString("http_voice", ""),
                        intentsCsv = t.optString("intents_enabled", ""),
                        httpApiKey = t.optString("http_api_key").takeIf { it.isNotEmpty() },
                    )
                },
                sport = prefs.optJSONObject("sport_settings")?.let { s ->
                    val favArr = s.optJSONArray("favorites") ?: JSONArray()
                    val prefArr = s.optJSONArray("preferred_sports") ?: JSONArray()
                    val exclArr = s.optJSONArray("excluded_sports") ?: JSONArray()
                    BackupManifest.SportPrefs(
                        favorites = (0 until favArr.length()).mapNotNull { i ->
                            val o = favArr.optJSONObject(i) ?: return@mapNotNull null
                            val id = o.optString("id").takeIf { it.isNotEmpty() }
                                ?: return@mapNotNull null
                            SportSubject(
                                id = id,
                                name = o.optString("name"),
                                sport = o.optString("sport"),
                                league = o.optString("league"),
                            )
                        },
                        preferredSports = (0 until prefArr.length()).map { prefArr.getString(it) },
                        excludedSports = (0 until exclArr.length()).map { exclArr.getString(it) },
                    )
                },
                cards = prefs.optJSONObject("cards")?.let { c ->
                    val arr = c.optJSONArray("disabled") ?: JSONArray()
                    BackupManifest.CardsPrefs(
                        disabled = (0 until arr.length()).map { arr.getString(it) },
                    )
                },
            ),
            reminderPins = (0 until pinsArr.length()).map { i ->
                val p = pinsArr.getJSONObject(i)
                BackupManifest.PinRecord(
                    id = p.getString("id"),
                    title = p.optString("title", ""),
                    timeMs = if (p.has("time_ms")) p.getLong("time_ms") else null,
                    isPersistent = p.optBoolean("is_persistent", false),
                    attemptsRemaining = p.optInt("attempts_remaining", 0),
                )
            },
            appAliases = aliasesObj.keys().asSequence().associateWith { aliasesObj.getString(it) },
            contactAliases = (0 until contactAliasesArr.length()).mapNotNull { i ->
                val obj = contactAliasesArr.optJSONObject(i) ?: return@mapNotNull null
                val phrase = obj.optString("phrase").takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val lookupKey = obj.optString("lookup_key").takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val number = obj.optString("number").takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                phrase to ContactRef(
                    lookupKey = lookupKey,
                    nameSnapshot = obj.optString("name"),
                    numberSnapshot = number,
                )
            }.toMap(),
            selected = root.optJSONObject("selected")?.let { sel ->
                BackupSelection(
                    notes = sel.optBoolean("notes", true),
                    codes = sel.optBoolean("codes", true),
                    tasks = sel.optBoolean("tasks", true),
                    conversations = sel.optBoolean("conversations", true),
                    reminders = sel.optBoolean("reminders", true),
                    nluLearned = sel.optBoolean("nlu_learned", true),
                    appAliases = sel.optBoolean("app_aliases", true),
                    contactAliases = sel.optBoolean("contact_aliases", true),
                    audioRecordings = sel.optBoolean("audio_recordings", true),
                    appPreferences = sel.optBoolean("app_preferences", true),
                    weatherSettings = sel.optBoolean("weather_settings", true),
                    mcpServers = sel.optBoolean("mcp_servers", true),
                    askAgentSetup = sel.optBoolean("ask_agent_setup", true),
                    homeAssistantSetup = sel.optBoolean("home_assistant_setup", true),
                    sttProviderSetup = sel.optBoolean("stt_provider_setup", true),
                    ttsProviderSetup = sel.optBoolean("tts_provider_setup", true),
                    sportSettings = sel.optBoolean("sport_settings", true),
                    weatherApiKey = sel.optBoolean("weather_api_key", false),
                    mcpAuthHeaders = sel.optBoolean("mcp_auth_headers", false),
                    askAgentApiKeys = sel.optBoolean("ask_agent_api_keys", false),
                    homeAssistantToken = sel.optBoolean("home_assistant_token", false),
                    sttProviderApiKey = sel.optBoolean("stt_provider_api_key", false),
                    ttsProviderApiKey = sel.optBoolean("tts_provider_api_key", false),
                )
            } ?: BackupSelection.LEGACY_FULL,
        )
    }
}