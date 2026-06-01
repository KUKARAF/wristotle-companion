package com.lazydevs.wristotle.backup

import com.lazydevs.wristotle.phone.ContactRef
import org.json.JSONArray
import org.json.JSONObject

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
     * the current code to understand.
     */
    /**
     * `tasks` is nullable on the data class level because backups
     * exported BEFORE Phase D of the Tasks feature won't carry the
     * field. The decoder falls back to null in that case; the importer
     * treats null as "no tasks to import" (older backup, before the
     * feature existed).
     */
    data class DataSchemas(
        val notes: Int,
        val tasks: Int?,
        val conversations: Int,
        val nlu: Int,
        // Schema 2+. Nullable for the same reason `tasks` is: a backup
        // exported before the field existed won't carry it.
        val mcpServers: Int? = null,
    )
    data class Stats(
        val notes: Int,
        val tasks: Int,
        val conversations: Int,
        val nluLearned: Int,
        val reminders: Int,
        val aliases: Int,
        // Defaulted so older code (or older backups deserialized into
        // this dataclass via something other than the codec) compile
        // cleanly. The decoder reads `contact_aliases` from stats and
        // falls back to 0 when the field is absent in pre-feature
        // backups.
        val contactAliases: Int = 0,
        val mcpServers: Int = 0,
    )

    /**
     * All prefs we back up, keyed by SharedPreferences name so the importer
     * can write them back into the same store. Each value is a typed
     * sub-object — keeping the same shape across versions matters more than
     * field-by-field shapelessness, so we model them.
     */
    data class PrefsBlock(
        val notes: NotesPrefs,
        val conversationSettings: ConversationPrefs,
        val conversationAudio: ConversationAudioPrefs,
        val nluSettings: NluPrefs,
        val diagnostics: DiagnosticsPrefs,
        val whisperModels: ModelPrefs,
        val nluModels: ModelPrefs,
        // Added in schema 2. Nullable so reading a schema-1 backup
        // doesn't fail — encoder writes them only when the matching
        // category was selected; decoder defaults to null when absent.
        val reminder: ReminderPrefs? = null,
        val weather: WeatherPrefs? = null,
        val askAgent: AskAgentPrefs? = null,
    )
    data class NotesPrefs(val keepLast: Int, val appendAudioMode: String)
    data class ConversationPrefs(val retentionDays: Int)
    data class ConversationAudioPrefs(val captureEnabled: Boolean)
    data class NluPrefs(val learningEnabled: Boolean)
    data class DiagnosticsPrefs(val redactPii: Boolean, val includeAudio: Boolean)
    data class ModelPrefs(val activeModelId: String?)

    /** Reminder feature prefs — single int, lives in its own SharedPrefs file. */
    data class ReminderPrefs(val defaultOffsetMin: Int)

    /** Weather feature prefs. `apiKey` rides only when the user ticks
     *  the secret checkbox AND has set a key for the OpenWeather provider. */
    data class WeatherPrefs(
        val unit: String,
        val provider: String,
        val apiKey: String? = null,
    )

    /** AskAgent feature prefs. Per-provider API keys ride only when the
     *  user ticks the secret checkbox. Endpoints/models/system-prompt
     *  ride with the non-sensitive "askAgent setup" category. */
    data class AskAgentPrefs(
        val provider: String,
        val anthropicModel: String,
        val openaiEndpoint: String,
        val openaiModel: String,
        val systemPrompt: String,
        val anthropicApiKey: String? = null,
        val openaiApiKey: String? = null,
    )

    /** Wire-format record matching the manifest JSON, not the Room/PinStore type. */
    data class PinRecord(val id: String, val title: String, val timeMs: Long?)

    companion object {
        /**
         * Backup manifest schema:
         *
         * - **1** — original release. No selection block; no per-category
         *   filtering. Decoder assumes everything in the ZIP was wanted.
         * - **2** (2026-05-31) — adds `selected: BackupSelection`, plus
         *   `prefs.reminder` / `prefs.weather` / `prefs.askAgent`, plus
         *   `data/mcp_servers.json` (referenced from `dataSchemas.mcpServers`).
         *   Decoder defaults the new fields to null / LEGACY_FULL when
         *   reading a schema-1 ZIP — backward compatible.
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
            put("conversations", m.dataSchemas.conversations)
            put("nlu", m.dataSchemas.nlu)
            if (m.dataSchemas.mcpServers != null) put("mcp_servers", m.dataSchemas.mcpServers)
        })
        put("stats", JSONObject().apply {
            put("notes", m.stats.notes)
            put("tasks", m.stats.tasks)
            put("conversations", m.stats.conversations)
            put("nlu_learned", m.stats.nluLearned)
            put("reminders", m.stats.reminders)
            put("aliases", m.stats.aliases)
            put("contact_aliases", m.stats.contactAliases)
            put("mcp_servers", m.stats.mcpServers)
        })
        put("selected", JSONObject().apply {
            put("notes", m.selected.notes)
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
            put("weather_api_key", m.selected.weatherApiKey)
            put("mcp_auth_headers", m.selected.mcpAuthHeaders)
            put("ask_agent_api_keys", m.selected.askAgentApiKeys)
        })
        put("prefs", JSONObject().apply {
            put("wristotle_notes", JSONObject().apply {
                put("keep_last", m.prefs.notes.keepLast)
                put("append_audio_mode", m.prefs.notes.appendAudioMode)
            })
            put("wristotle_conversation_settings", JSONObject().apply {
                put("retention_days", m.prefs.conversationSettings.retentionDays)
            })
            put("wristotle_audio_recordings", JSONObject().apply {
                put("capture_enabled", m.prefs.conversationAudio.captureEnabled)
            })
            put("wristotle_nlu_settings", JSONObject().apply {
                put("learning_enabled", m.prefs.nluSettings.learningEnabled)
            })
            put("wristotle_diagnostics", JSONObject().apply {
                put("redact_pii", m.prefs.diagnostics.redactPii)
                put("include_audio", m.prefs.diagnostics.includeAudio)
            })
            put("whisper_models", JSONObject().apply {
                m.prefs.whisperModels.activeModelId?.let { put("active_model_id", it) }
            })
            put("nlu_models", JSONObject().apply {
                m.prefs.nluModels.activeModelId?.let { put("active_model_id", it) }
            })
            // Schema 2+. Each block is omitted entirely when null
            // (category wasn't selected on export).
            m.prefs.reminder?.let { r ->
                put("wristotle_reminder_settings", JSONObject().apply {
                    put("default_offset_min", r.defaultOffsetMin)
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
                    if (a.anthropicApiKey != null) put("anthropic_api_key", a.anthropicApiKey)
                    if (a.openaiApiKey != null) put("openai_api_key", a.openaiApiKey)
                })
            }
        })
        put("reminder_pins", JSONArray().apply {
            m.reminderPins.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id)
                    put("title", p.title)
                    if (p.timeMs != null) put("time_ms", p.timeMs)
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
        val notesPrefs = prefs.getJSONObject("wristotle_notes")
        val convPrefs = prefs.getJSONObject("wristotle_conversation_settings")
        val convAudio = prefs.getJSONObject("wristotle_audio_recordings")
        val nluPrefs = prefs.getJSONObject("wristotle_nlu_settings")
        val diagPrefs = prefs.getJSONObject("wristotle_diagnostics")
        val whisper = prefs.getJSONObject("whisper_models")
        val nluModels = prefs.getJSONObject("nlu_models")
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
            ),
            prefs = BackupManifest.PrefsBlock(
                notes = BackupManifest.NotesPrefs(
                    keepLast = notesPrefs.optInt("keep_last", 0),
                    appendAudioMode = notesPrefs.optString("append_audio_mode", "MERGE"),
                ),
                conversationSettings = BackupManifest.ConversationPrefs(
                    retentionDays = convPrefs.optInt("retention_days", 10),
                ),
                conversationAudio = BackupManifest.ConversationAudioPrefs(
                    captureEnabled = convAudio.optBoolean("capture_enabled", false),
                ),
                nluSettings = BackupManifest.NluPrefs(
                    learningEnabled = nluPrefs.optBoolean("learning_enabled", true),
                ),
                diagnostics = BackupManifest.DiagnosticsPrefs(
                    redactPii = diagPrefs.optBoolean("redact_pii", true),
                    includeAudio = diagPrefs.optBoolean("include_audio", false),
                ),
                whisperModels = BackupManifest.ModelPrefs(
                    activeModelId = whisper.optString("active_model_id").takeIf { it.isNotEmpty() },
                ),
                nluModels = BackupManifest.ModelPrefs(
                    activeModelId = nluModels.optString("active_model_id").takeIf { it.isNotEmpty() },
                ),
                reminder = prefs.optJSONObject("wristotle_reminder_settings")?.let { r ->
                    BackupManifest.ReminderPrefs(
                        defaultOffsetMin = r.optInt("default_offset_min", 0),
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
                    )
                },
            ),
            reminderPins = (0 until pinsArr.length()).map { i ->
                val p = pinsArr.getJSONObject(i)
                BackupManifest.PinRecord(
                    id = p.getString("id"),
                    title = p.optString("title", ""),
                    timeMs = if (p.has("time_ms")) p.getLong("time_ms") else null,
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
                    weatherApiKey = sel.optBoolean("weather_api_key", false),
                    mcpAuthHeaders = sel.optBoolean("mcp_auth_headers", false),
                    askAgentApiKeys = sel.optBoolean("ask_agent_api_keys", false),
                )
            } ?: BackupSelection.LEGACY_FULL,
        )
    }
}
