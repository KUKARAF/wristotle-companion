package com.lazydevs.wristotle.backup

import android.content.Context
import android.net.Uri
import android.os.Build
import com.lazydevs.wristotle.BuildConfig
import com.lazydevs.wristotle.WristotleApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val DATA_ENTRY_NOTES = "data/notes.json"
private const val DATA_ENTRY_CONVERSATIONS = "data/conversations.json"
private const val DATA_ENTRY_NLU = "data/nlu.json"
private const val DATA_ENTRY_TASKS = "data/tasks.json"
private const val DATA_ENTRY_MCP_SERVERS = "data/mcp_servers.json"
private const val AUDIO_NOTES_PREFIX = "audio/notes/"
private const val AUDIO_CONVERSATIONS_PREFIX = "audio/conversation/"

/**
 * Result of a successful export — surfaced to the UI so the snackbar can
 * report what landed in the ZIP.
 */
data class BackupExportResult(
    val notes: Int,
    val tasks: Int,
    val conversations: Int,
    val nluLearned: Int,
    val reminders: Int,
    val aliases: Int,
    val contactAliases: Int,
    val mcpServers: Int,
    val audioFiles: Int,
    val encrypted: Boolean,
    val bytes: Long,
    /** The selection that actually went into the ZIP — drives the summary
     *  dialog's "settings + secrets included" breakdown so the user can
     *  see which non-entity categories rode along (not just the row
     *  counts). */
    val selection: BackupSelection,
)

/**
 * Builds a backup ZIP containing manifest.json + data/<name>.json row
 * dumps per entity, encoded through the per-entity *Json (en|de)coders.
 *
 * Each entity is gated by a [BackupSelection] field — categories the
 * user didn't tick produce neither a `data/<name>.json` file nor any
 * stats. Secret fields (API keys, MCP auth headers) are stripped from
 * the per-entity JSON when the user didn't tick their secret checkbox,
 * even if the parent category IS selected.
 *
 * The manifest records the [BackupSelection] under `selected:` so the
 * importer knows what's in the ZIP without sniffing each file.
 *
 * Why JSON, not raw .db files: the backup format is independent of
 * Room's SQLite layout, so a schema rename / column add / table split
 * is absorbed by the entity's *Json.decode version branch rather than
 * by registering Room Migration objects in two places. See [NoteJson]
 * etc. for the per-entity contract.
 */
class BackupExporter(private val app: WristotleApplication) {

    /**
     * Cheap per-category snapshot for the export checkbox tree. Uses
     * COUNT(*) queries (not full row reads) — safe to call on dialog
     * open without blocking the UI. The audio directories are walked
     * once here (filtered to `*.wav` so stray files don't inflate the
     * user-visible total) for both the file count and total bytes.
     */
    suspend fun countAll(): BackupCounts = withContext(Dispatchers.IO) {
        val audio = audioFiles()
        BackupCounts(
            notes = app.notesDb.noteDao().count(),
            tasks = app.tasksDb.taskDao().count(),
            conversations = app.conversationDb.conversationDao().count(),
            reminders = readPinRecords().size,
            nluLearned = app.nluDb.exampleDao().countLearned(),
            appAliases = app.aliasStore.all().size,
            contactAliases = app.contactAliasStore.all().size,
            audioRecordings = audio.size,
            audioBytes = audio.sumOf { it.length() },
            mcpServers = app.mcpServerRepository.count(),
        )
    }

    /**
     * Runs the export.
     *
     * @param destination SAF URI to stream the final ZIP into.
     * @param options Per-category opt-in + optional encryption password.
     *   See [BackupOptions]. Categories with `false` are omitted from the
     *   ZIP entirely (no data file, no stats, no prefs block); secret-
     *   categories with `false` strip their fields even when the parent
     *   category is included.
     */
    suspend fun export(
        destination: Uri,
        options: BackupOptions = BackupOptions(),
    ): BackupExportResult = withContext(Dispatchers.IO) {
        val selection = options.selection
        val ctx: Context = app.applicationContext
        val encryptPassword = options.password?.takeIf { it.isNotEmpty() }

        // Per-category collection. Empty lists when the category isn't
        // selected — downstream encoders skip empty lists rather than
        // writing an empty rows array, so the ZIP stays clean.
        val notes = if (selection.notes) app.notesDb.noteDao().allForBackup() else emptyList()
        val tasks = if (selection.tasks) app.tasksDb.taskDao().allForBackup() else emptyList()
        val conversations = if (selection.conversations)
            app.conversationDb.conversationDao().allForBackup() else emptyList()
        // ExampleDao.learned() — backups never contain seed rows (they ship
        // bundled with the app).
        val nluLearned = if (selection.nluLearned) app.nluDb.exampleDao().learned() else emptyList()
        val pinRecords = if (selection.reminders) readPinRecords() else emptyList()
        val aliases = if (selection.appAliases) app.aliasStore.all() else emptyMap()
        val contactAliases = if (selection.contactAliases) app.contactAliasStore.all() else emptyMap()
        val mcpServers = if (selection.mcpServers) app.mcpServerRepository.listAll() else emptyList()
        val audioFiles = if (selection.audioRecordings) audioFiles() else emptyList()

        val manifest = BackupManifest(
            schema = BackupManifest.CURRENT_SCHEMA,
            exportedAtMs = System.currentTimeMillis(),
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            androidSdk = Build.VERSION.SDK_INT,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            encrypted = encryptPassword != null,
            includeAudio = selection.audioRecordings,
            dataSchemas = BackupManifest.DataSchemas(
                notes = NoteJson.CURRENT_SCHEMA,
                tasks = TaskJson.CURRENT_SCHEMA,
                conversations = ConversationEntryJson.CURRENT_SCHEMA,
                nlu = ExampleEntryJson.CURRENT_SCHEMA,
                mcpServers = if (selection.mcpServers) McpServerJson.CURRENT_SCHEMA else null,
            ),
            stats = BackupManifest.Stats(
                notes = notes.size,
                tasks = tasks.size,
                conversations = conversations.size,
                nluLearned = nluLearned.size,
                reminders = pinRecords.size,
                aliases = aliases.size,
                contactAliases = contactAliases.size,
                mcpServers = mcpServers.size,
            ),
            prefs = readPrefsBlock(selection),
            reminderPins = pinRecords.map {
                BackupManifest.PinRecord(it.id, it.title, it.timeMs)
            },
            appAliases = aliases,
            contactAliases = contactAliases,
            selected = selection,
        )

        // Use cacheDir/backup-staging as zip4j's working dir; cleared before
        // each run so a previous failure can't leak files. The final ZIP is
        // also written here first, then streamed to the SAF URI — zip4j
        // requires a File destination.
        val stagingDir = File(ctx.cacheDir, "backup-staging").apply {
            deleteRecursively()
            mkdirs()
        }
        val stagingZip = File(stagingDir, "backup.zip")

        try {
            // zip4j attaches the password to the ZipFile instance itself —
            // ZipParameters then opts each entry in via isEncryptFiles.
            val zip = if (encryptPassword != null) {
                ZipFile(stagingZip, encryptPassword.toCharArray())
            } else {
                ZipFile(stagingZip)
            }
            val params = ZipParameters().apply {
                compressionMethod = CompressionMethod.STORE
                if (encryptPassword != null) {
                    isEncryptFiles = true
                    encryptionMethod = EncryptionMethod.AES
                    aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                }
            }

            writeText(stagingDir, BackupManifest.FILENAME, BackupManifestCodec.encode(manifest))
            addToZip(zip, params, stagingDir, BackupManifest.FILENAME)

            if (selection.notes) {
                writeText(stagingDir, DATA_ENTRY_NOTES,
                    encodeRowsJson(NoteJson.CURRENT_SCHEMA, notes) { NoteJson.encode(it) })
                addToZip(zip, params, stagingDir, DATA_ENTRY_NOTES)
            }
            if (selection.tasks) {
                writeText(stagingDir, DATA_ENTRY_TASKS,
                    encodeRowsJson(TaskJson.CURRENT_SCHEMA, tasks) { TaskJson.encode(it) })
                addToZip(zip, params, stagingDir, DATA_ENTRY_TASKS)
            }
            if (selection.conversations) {
                writeText(stagingDir, DATA_ENTRY_CONVERSATIONS,
                    encodeRowsJson(ConversationEntryJson.CURRENT_SCHEMA, conversations) { ConversationEntryJson.encode(it) })
                addToZip(zip, params, stagingDir, DATA_ENTRY_CONVERSATIONS)
            }
            if (selection.nluLearned) {
                writeText(stagingDir, DATA_ENTRY_NLU,
                    encodeRowsJson(ExampleEntryJson.CURRENT_SCHEMA, nluLearned) { ExampleEntryJson.encode(it) })
                addToZip(zip, params, stagingDir, DATA_ENTRY_NLU)
            }
            if (selection.mcpServers) {
                writeText(stagingDir, DATA_ENTRY_MCP_SERVERS,
                    encodeRowsJson(McpServerJson.CURRENT_SCHEMA, mcpServers) {
                        McpServerJson.encode(it, includeAuthHeader = selection.mcpAuthHeaders)
                    })
                addToZip(zip, params, stagingDir, DATA_ENTRY_MCP_SERVERS)
            }

            // Audio files: each .wav goes in at audio/notes/<basename> or
            // audio/conversation/<basename> — the importer reads them by
            // prefix and re-prefixes paths to the target filesDir on restore.
            for (audio in audioFiles) {
                val entryName = audioEntryName(audio) ?: continue
                addFileDirectlyToZip(zip, params, audio, entryName)
            }

            // Stream into the destination URI (SAF-provided).
            ctx.contentResolver.openOutputStream(destination)?.use { out ->
                stagingZip.inputStream().use { it.copyTo(out) }
            } ?: error("Could not open destination for write")

            BackupExportResult(
                notes = manifest.stats.notes,
                tasks = manifest.stats.tasks,
                conversations = manifest.stats.conversations,
                nluLearned = manifest.stats.nluLearned,
                reminders = manifest.stats.reminders,
                aliases = manifest.stats.aliases,
                contactAliases = manifest.stats.contactAliases,
                mcpServers = manifest.stats.mcpServers,
                audioFiles = audioFiles.size,
                encrypted = encryptPassword != null,
                bytes = stagingZip.length(),
                selection = selection,
            )
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    /**
     * All .wav files across both audio directories. Order is deterministic
     * (alphabetical by basename) so successive exports of the same state
     * produce identical ZIPs modulo timestamp metadata.
     */
    private fun audioFiles(): List<File> {
        val notes = app.notesAudioStore.dir.listFiles { _, n -> n.endsWith(".wav") }?.toList().orEmpty()
        val conversation = app.conversationAudioStore.dir.listFiles { _, n -> n.endsWith(".wav") }?.toList().orEmpty()
        return (notes + conversation).sortedBy { it.absolutePath }
    }

    /**
     * Maps an audio File on disk to its ZIP entry name. Returns null when the
     * file isn't under either expected directory (defensive — shouldn't happen
     * given [audioFiles] only enumerates those two dirs).
     */
    private fun audioEntryName(audio: File): String? {
        val notesDir = app.notesAudioStore.dir.absolutePath
        val convDir = app.conversationAudioStore.dir.absolutePath
        val parent = audio.parentFile?.absolutePath ?: return null
        return when (parent) {
            notesDir -> "$AUDIO_NOTES_PREFIX${audio.name}"
            convDir -> "$AUDIO_CONVERSATIONS_PREFIX${audio.name}"
            else -> null
        }
    }

    private fun readPinRecords(): List<com.lazydevs.wristotle.handlers.ReminderRecord> {
        val store = com.lazydevs.wristotle.handlers.PinStore(app.applicationContext)
        return store.all()
    }

    /**
     * Reads the PrefsBlock from live app state, honouring per-section
     * selection. Each sub-block is null when its category isn't ticked —
     * the codec then omits it from the JSON entirely and the importer
     * treats absence as "no change on restore."
     *
     * `appPreferences` is the umbrella for the historical non-sensitive
     * prefs bundle (Notes / Conversation / NLU / Diagnostics / model
     * prefs / Reminder); each of `weatherSettings` / `askAgentSetup`
     * has its own gate, and their secret sub-fields ride only when the
     * matching secret checkbox is ticked.
     */
    private fun readPrefsBlock(sel: BackupSelection): BackupManifest.PrefsBlock =
        BackupManifest.PrefsBlock(
            notes = if (sel.appPreferences) BackupManifest.NotesPrefs(
                keepLast = app.noteSettings.keepLast.value,
                appendAudioMode = app.noteSettings.appendAudioMode.value.name,
            ) else null,
            conversationSettings = if (sel.appPreferences) BackupManifest.ConversationPrefs(
                retentionDays = app.conversationSettings.retentionDays.value,
            ) else null,
            conversationAudio = if (sel.appPreferences) BackupManifest.ConversationAudioPrefs(
                captureEnabled = app.conversationAudioSettings.captureEnabled.value,
            ) else null,
            nluSettings = if (sel.appPreferences) BackupManifest.NluPrefs(
                learningEnabled = app.nluSettings.learningEnabled.value,
            ) else null,
            diagnostics = if (sel.appPreferences) BackupManifest.DiagnosticsPrefs(
                redactPii = app.diagnosticsSettings.redactPii.value,
                includeAudio = app.diagnosticsSettings.includeAudio.value,
            ) else null,
            whisperModels = if (sel.appPreferences) BackupManifest.ModelPrefs(
                activeModelId = app.modelStorage.activeModelId,
            ) else null,
            nluModels = if (sel.appPreferences) BackupManifest.ModelPrefs(
                activeModelId = app.nluModelStorage.activeModelId,
            ) else null,
            reminder = if (sel.appPreferences) BackupManifest.ReminderPrefs(
                defaultOffsetMin = app.reminderSettings.defaultOffsetMin.value,
            ) else null,
            weather = if (sel.weatherSettings) BackupManifest.WeatherPrefs(
                unit = app.weatherSettings.unit.value.name,
                provider = app.weatherSettings.provider.value.name,
                apiKey = app.weatherSettings.apiKey.value
                    .takeIf { sel.weatherApiKey && it.isNotEmpty() },
            ) else null,
            askAgent = if (sel.askAgentSetup) BackupManifest.AskAgentPrefs(
                provider = app.askAgentSettings.provider.value.name,
                anthropicModel = app.askAgentSettings.anthropicModel.value,
                openaiEndpoint = app.askAgentSettings.openaiEndpoint.value,
                openaiModel = app.askAgentSettings.openaiModel.value,
                systemPrompt = app.askAgentSettings.systemPrompt.value,
                anthropicApiKey = app.askAgentSettings.anthropicApiKey.value
                    .takeIf { sel.askAgentApiKeys && it.isNotEmpty() },
                openaiApiKey = app.askAgentSettings.openaiApiKey.value
                    .takeIf { sel.askAgentApiKeys && it.isNotEmpty() },
            ) else null,
            sttProvider = if (sel.sttProviderSetup) BackupManifest.SttProviderPrefs(
                mode = app.sttProviderSettings.mode.value.name,
                httpBaseUrl = app.sttProviderSettings.httpBaseUrl.value,
                httpModel = app.sttProviderSettings.httpModel.value,
                httpApiKey = app.sttProviderSettings.httpApiKey.value
                    .takeIf { sel.sttProviderApiKey && it.isNotEmpty() },
            ) else null,
        )
}

/**
 * Wraps a list of encoded rows into the canonical
 * `{ "schema": N, "rows": [ … ] }` shape that the importer expects.
 * Top-level on purpose so the per-entity files share one container shape.
 *
 * Compact (no `toString(2)` pretty-print) — these files are machine-read
 * by [BackupImporter] and never edited by hand, so the ~30-50 % size +
 * 2-3× encode-time tax of pretty-printing buys nothing. The manifest
 * itself is still pretty-printed (in [BackupManifestCodec.encode]) since
 * it's small and useful when debugging a malformed backup.
 */
private inline fun <T> encodeRowsJson(
    schema: Int,
    rows: List<T>,
    encode: (T) -> JSONObject,
): String = JSONObject().apply {
    put("schema", schema)
    put("rows", JSONArray().also { arr -> rows.forEach { arr.put(encode(it)) } })
}.toString()

/**
 * Writes [content] to <dir>/<relativePath>, creating parent directories
 * as needed. Used both for the manifest at the root of the staging dir and
 * for nested data/<name>.json files.
 */
private fun writeText(dir: File, relativePath: String, content: String) {
    val target = File(dir, relativePath)
    target.parentFile?.mkdirs()
    target.writeText(content)
}

/**
 * Adds the file at <stagingDir>/<entryName> into [zip] at path [entryName]
 * (relative-to-staging is the same as relative-to-zip). Skips files that
 * don't exist or are empty — currently impossible for our four entries, but
 * defensive.
 */
private fun addToZip(zip: ZipFile, base: ZipParameters, stagingDir: File, entryName: String) {
    val source = File(stagingDir, entryName)
    if (!source.exists() || source.length() == 0L) return
    val params = ZipParameters(base).apply { fileNameInZip = entryName }
    zip.addFile(source, params)
}

/**
 * Adds [source] (a file already on disk, e.g. a notes-audio WAV) directly
 * into [zip] at the given entry name, without going through the staging dir.
 * Skips silently when the file vanished between enumeration and write (rare
 * race when a note is deleted mid-export).
 */
private fun addFileDirectlyToZip(zip: ZipFile, base: ZipParameters, source: File, entryName: String) {
    if (!source.exists() || source.length() == 0L) return
    val params = ZipParameters(base).apply { fileNameInZip = entryName }
    zip.addFile(source, params)
}
