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
private const val AUDIO_NOTES_PREFIX = "audio/notes/"
private const val AUDIO_CONVERSATIONS_PREFIX = "audio/conversation/"

/** Pre-export estimate of audio inclusion's cost — shown in the export dialog. */
data class AudioInventory(val files: Int, val bytes: Long)

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
    val audioFiles: Int,
    val encrypted: Boolean,
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

    /**
     * Counts files + summed bytes under both audio directories. Cheap (one
     * listFiles per dir) so the UI can call it on dialog open to render
     * "Include audio recordings (N files, M MB)". Filenames not matching
     * `*.wav` are skipped so a stray non-audio file in the dir doesn't get
     * counted in the user-visible total.
     */
    suspend fun audioInventory(): AudioInventory = withContext(Dispatchers.IO) {
        val files = audioFiles()
        AudioInventory(files = files.size, bytes = files.sumOf { it.length() })
    }

    /**
     * Runs the export.
     *
     * @param destination SAF URI to stream the final ZIP into.
     * @param includeAudio When true, every .wav under notes-audio/ and
     *                     conversation-audio/ is added under audio/notes/
     *                     and audio/conversation/ in the ZIP.
     * @param password    When non-null + non-empty, the ZIP is AES-256-
     *                    encrypted with this passphrase; otherwise plain.
     *                    Caller is responsible for confirming + erasing.
     */
    suspend fun export(
        destination: Uri,
        includeAudio: Boolean = false,
        password: String? = null,
    ): BackupExportResult = withContext(Dispatchers.IO) {
        val ctx: Context = app.applicationContext
        val encryptPassword = password?.takeIf { it.isNotEmpty() }

        // Pull all rows through the existing DAOs. Suspending — runs on the
        // IO dispatcher we're already on.
        val notes = app.notesDb.noteDao().allForBackup()
        val conversations = app.conversationDb.conversationDao().allForBackup()
        // ExampleDao.learned() — backups never contain seed rows (they ship
        // bundled with the app).
        val nluLearned = app.nluDb.exampleDao().learned()
        val pinRecords = readPinRecords()
        val aliases = app.aliasStore.all()
        val audioFiles = if (includeAudio) audioFiles() else emptyList()

        val manifest = BackupManifest(
            schema = BackupManifest.CURRENT_SCHEMA,
            exportedAtMs = System.currentTimeMillis(),
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            androidSdk = Build.VERSION.SDK_INT,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            encrypted = encryptPassword != null,
            includeAudio = includeAudio,
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
            // zip4j attaches the password to the ZipFile instance itself —
            // ZipParameters then opts each entry in via isEncryptFiles.
            val zip = if (encryptPassword != null) {
                ZipFile(stagingZip, encryptPassword.toCharArray())
            } else {
                ZipFile(stagingZip)
            }
            val params = ZipParameters().apply {
                // JSON would compress well, but the ZIP is small and the audio
                // blobs in Phase B are already-compressed-ish PCM. STORE keeps
                // export fast and the implementation uniform across entries.
                compressionMethod = CompressionMethod.STORE
                if (encryptPassword != null) {
                    isEncryptFiles = true
                    encryptionMethod = EncryptionMethod.AES
                    aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                }
            }

            writeText(stagingDir, BackupManifest.FILENAME, BackupManifestCodec.encode(manifest))
            writeText(stagingDir, DATA_ENTRY_NOTES,
                encodeRowsJson(NoteJson.CURRENT_SCHEMA, notes) { NoteJson.encode(it) })
            writeText(stagingDir, DATA_ENTRY_CONVERSATIONS,
                encodeRowsJson(ConversationEntryJson.CURRENT_SCHEMA, conversations) { ConversationEntryJson.encode(it) })
            writeText(stagingDir, DATA_ENTRY_NLU,
                encodeRowsJson(ExampleEntryJson.CURRENT_SCHEMA, nluLearned) { ExampleEntryJson.encode(it) })

            addToZip(zip, params, stagingDir, BackupManifest.FILENAME)
            addToZip(zip, params, stagingDir, DATA_ENTRY_NOTES)
            addToZip(zip, params, stagingDir, DATA_ENTRY_CONVERSATIONS)
            addToZip(zip, params, stagingDir, DATA_ENTRY_NLU)

            // Audio files: each .wav goes in at audio/notes/<basename> or
            // audio/conversation/<basename> — the importer reads them by
            // prefix and re-prefixes paths to the target filesDir on restore.
            // Files are added directly from their source location (not via
            // staging) — zip4j reads through, so we don't need to copy.
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
                conversations = manifest.stats.conversations,
                nluLearned = manifest.stats.nluLearned,
                reminders = manifest.stats.reminders,
                aliases = manifest.stats.aliases,
                audioFiles = audioFiles.size,
                encrypted = encryptPassword != null,
                bytes = stagingZip.length(),
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
