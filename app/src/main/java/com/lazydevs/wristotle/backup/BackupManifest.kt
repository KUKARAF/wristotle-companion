package com.lazydevs.wristotle.backup

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
) {
    /**
     * Per-entity (NOT per-Room-DB) schema versions of the data files inside
     * the ZIP. Each field is the `schema:` value of the corresponding
     * `data/<name>.json` wrapper, matching the entity's `*Json.CURRENT_SCHEMA`
     * at export time. The importer compares each field to the live decoder's
     * `CURRENT_SCHEMA` and refuses individual entities that are too new for
     * the current code to understand.
     */
    data class DataSchemas(val notes: Int, val conversations: Int, val nlu: Int)
    data class Stats(
        val notes: Int,
        val conversations: Int,
        val nluLearned: Int,
        val reminders: Int,
        val aliases: Int,
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
    )
    data class NotesPrefs(val keepLast: Int, val appendAudioMode: String)
    data class ConversationPrefs(val retentionDays: Int)
    data class ConversationAudioPrefs(val captureEnabled: Boolean)
    data class NluPrefs(val learningEnabled: Boolean)
    data class DiagnosticsPrefs(val redactPii: Boolean, val includeAudio: Boolean)
    data class ModelPrefs(val activeModelId: String?)

    /** Wire-format record matching the manifest JSON, not the Room/PinStore type. */
    data class PinRecord(val id: String, val title: String, val timeMs: Long?)

    companion object {
        const val CURRENT_SCHEMA = 1
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
            put("conversations", m.dataSchemas.conversations)
            put("nlu", m.dataSchemas.nlu)
        })
        put("stats", JSONObject().apply {
            put("notes", m.stats.notes)
            put("conversations", m.stats.conversations)
            put("nlu_learned", m.stats.nluLearned)
            put("reminders", m.stats.reminders)
            put("aliases", m.stats.aliases)
        })
        put("prefs", JSONObject().apply {
            put("wristotle_notes", JSONObject().apply {
                put("keep_last", m.prefs.notes.keepLast)
                put("append_audio_mode", m.prefs.notes.appendAudioMode)
            })
            put("wristotle_conversation_settings", JSONObject().apply {
                put("retention_days", m.prefs.conversationSettings.retentionDays)
            })
            put("wristotle_conversation_audio", JSONObject().apply {
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
    }.toString(2)

    fun decode(raw: String): BackupManifest {
        val root = JSONObject(raw)
        val prefs = root.getJSONObject("prefs")
        val notesPrefs = prefs.getJSONObject("wristotle_notes")
        val convPrefs = prefs.getJSONObject("wristotle_conversation_settings")
        val convAudio = prefs.getJSONObject("wristotle_conversation_audio")
        val nluPrefs = prefs.getJSONObject("wristotle_nlu_settings")
        val diagPrefs = prefs.getJSONObject("wristotle_diagnostics")
        val whisper = prefs.getJSONObject("whisper_models")
        val nluModels = prefs.getJSONObject("nlu_models")
        val dataSchemas = root.getJSONObject("data_schemas")
        val stats = root.getJSONObject("stats")
        val pinsArr = root.optJSONArray("reminder_pins") ?: JSONArray()
        val aliasesObj = root.optJSONObject("app_aliases") ?: JSONObject()

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
                conversations = dataSchemas.getInt("conversations"),
                nlu = dataSchemas.getInt("nlu"),
            ),
            stats = BackupManifest.Stats(
                notes = stats.optInt("notes", 0),
                conversations = stats.optInt("conversations", 0),
                nluLearned = stats.optInt("nlu_learned", 0),
                reminders = stats.optInt("reminders", 0),
                aliases = stats.optInt("aliases", 0),
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
        )
    }
}
