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
import net.lingala.zip4j.model.enums.CompressionMethod
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val DATA_ENTRY_NOTES = "data/notes.json"
private const val DATA_ENTRY_CONVERSATIONS = "data/conversations.json"
private const val DATA_ENTRY_NLU = "data/nlu.json"

/**
 * Result of a successful export — surfaced to the UI so the snackbar can
 * report what landed in the ZIP.
 */
data class BackupExportResult(
    val notes: Int,
    val conversations: Int,
    val nluLearned: Int,
    val reminders: Int,
    val aliases: Int,
    val bytes: Long,
)

/**
 * Builds a backup ZIP containing manifest.json + three data/<name>.json row
 * dumps, one per entity, encoded through the per-entity *Json (en|de)coders.
 *
 * Phase A scope: no audio, no encryption. The audio and password parameters
 * land in Phase B as part of [export]'s signature.
 *
 * Why JSON, not raw .db files: the backup format is independent of Room's
 * SQLite layout, so a schema rename / column add / table split is absorbed
 * by the entity's *Json.decode version branch rather than by registering
 * Room Migration objects in two places. Each data file carries its own
 * "schema" integer — the per-entity wire-format version, NOT a Room DB
 * @Database(version). See [NoteJson] etc. for the per-entity contract.
 */
class BackupExporter(private val app: WristotleApplication) {

    suspend fun export(destination: Uri): BackupExportResult = withContext(Dispatchers.IO) {
        val ctx: Context = app.applicationContext

        // Pull all rows through the existing DAOs. Suspending — runs on the
        // IO dispatcher we're already on.
        val notes = app.notesDb.noteDao().allForBackup()
        val conversations = app.conversationDb.conversationDao().allForBackup()
        // ExampleDao.learned() — backups never contain seed rows (they ship
        // bundled with the app).
        val nluLearned = app.nluDb.exampleDao().learned()
        val pinRecords = readPinRecords()
        val aliases = app.aliasStore.all()

        val manifest = BackupManifest(
            schema = BackupManifest.CURRENT_SCHEMA,
            exportedAtMs = System.currentTimeMillis(),
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            androidSdk = Build.VERSION.SDK_INT,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            encrypted = false,
            includeAudio = false,
            dataSchemas = BackupManifest.DataSchemas(
                notes = NoteJson.CURRENT_SCHEMA,
                conversations = ConversationEntryJson.CURRENT_SCHEMA,
                nlu = ExampleEntryJson.CURRENT_SCHEMA,
            ),
            stats = BackupManifest.Stats(
                notes = notes.size,
                conversations = conversations.size,
                nluLearned = nluLearned.size,
                reminders = pinRecords.size,
                aliases = aliases.size,
            ),
            prefs = readPrefsBlock(),
            reminderPins = pinRecords.map {
                BackupManifest.PinRecord(it.id, it.title, it.timeMs)
            },
            appAliases = aliases,
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
            val zip = ZipFile(stagingZip)
            val storeParams = ZipParameters().apply {
                // JSON compresses well, but the ZIP is small (~100 KB for a
                // few hundred rows) so the CPU saved by STOREing is moot.
                // Phase B will keep STORE for the audio blobs (.wav already
                // compressed) — keep the params uniform for now.
                compressionMethod = CompressionMethod.STORE
            }

            writeText(stagingDir, BackupManifest.FILENAME, BackupManifestCodec.encode(manifest))
            writeText(stagingDir, DATA_ENTRY_NOTES,
                encodeRowsJson(NoteJson.CURRENT_SCHEMA, notes) { NoteJson.encode(it) })
            writeText(stagingDir, DATA_ENTRY_CONVERSATIONS,
                encodeRowsJson(ConversationEntryJson.CURRENT_SCHEMA, conversations) { ConversationEntryJson.encode(it) })
            writeText(stagingDir, DATA_ENTRY_NLU,
                encodeRowsJson(ExampleEntryJson.CURRENT_SCHEMA, nluLearned) { ExampleEntryJson.encode(it) })

            addToZip(zip, storeParams, stagingDir, BackupManifest.FILENAME)
            addToZip(zip, storeParams, stagingDir, DATA_ENTRY_NOTES)
            addToZip(zip, storeParams, stagingDir, DATA_ENTRY_CONVERSATIONS)
            addToZip(zip, storeParams, stagingDir, DATA_ENTRY_NLU)

            // Stream into the destination URI (SAF-provided).
            ctx.contentResolver.openOutputStream(destination)?.use { out ->
                stagingZip.inputStream().use { it.copyTo(out) }
            } ?: error("Could not open destination for write")

            BackupExportResult(
                notes = manifest.stats.notes,
                conversations = manifest.stats.conversations,
                nluLearned = manifest.stats.nluLearned,
                reminders = manifest.stats.reminders,
                aliases = manifest.stats.aliases,
                bytes = stagingZip.length(),
            )
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    private fun readPinRecords(): List<com.lazydevs.wristotle.handlers.ReminderRecord> {
        val store = com.lazydevs.wristotle.handlers.PinStore(app.applicationContext)
        return store.all()
    }

    private fun readPrefsBlock(): BackupManifest.PrefsBlock = BackupManifest.PrefsBlock(
        notes = BackupManifest.NotesPrefs(
            keepLast = app.noteSettings.keepLast.value,
            appendAudioMode = app.noteSettings.appendAudioMode.value.name,
        ),
        conversationSettings = BackupManifest.ConversationPrefs(
            retentionDays = app.conversationSettings.retentionDays.value,
        ),
        conversationAudio = BackupManifest.ConversationAudioPrefs(
            captureEnabled = app.conversationAudioSettings.captureEnabled.value,
        ),
        nluSettings = BackupManifest.NluPrefs(
            learningEnabled = app.nluSettings.learningEnabled.value,
        ),
        diagnostics = BackupManifest.DiagnosticsPrefs(
            redactPii = app.diagnosticsSettings.redactPii.value,
            includeAudio = app.diagnosticsSettings.includeAudio.value,
        ),
        whisperModels = BackupManifest.ModelPrefs(activeModelId = app.modelStorage.activeModelId),
        nluModels = BackupManifest.ModelPrefs(activeModelId = app.nluModelStorage.activeModelId),
    )
}

/**
 * Wraps a list of encoded rows into the canonical
 * `{ "schema": N, "rows": [ … ] }` shape that the importer expects.
 * Top-level on purpose so the per-entity files share one container shape.
 */
private inline fun <T> encodeRowsJson(
    schema: Int,
    rows: List<T>,
    encode: (T) -> JSONObject,
): String = JSONObject().apply {
    put("schema", schema)
    put("rows", JSONArray().also { arr -> rows.forEach { arr.put(encode(it)) } })
}.toString(2)

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
 * Adds the file at `<stagingDir>/<entryName>` into [zip] at path [entryName]
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
