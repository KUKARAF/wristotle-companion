package com.lazydevs.wristotle.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.handlers.PinStore
import com.lazydevs.wristotle.handlers.ReminderRecord
import com.lazydevs.wristotle.history.ConversationEntry
import com.lazydevs.wristotle.notes.AppendAudioMode
import com.lazydevs.wristotle.notes.Note
import com.lazydevs.wristotle.notes.NoteAudioPaths
import com.lazydevs.wristotle.phone.ContactRef
import com.lazydevs.wristotle.speech.nlu.bank.ExampleEntry
import com.lazydevs.wristotle.tasks.TaskEntity
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import org.json.JSONObject
import java.io.File

private const val TAG = "BackupImporter"

/**
 * Reads a Wristotle backup ZIP and merges its data into the live app state.
 *
 * Two-step flow so the UI can prompt for a password and show a preview:
 *  1. [peek] — opens the ZIP, checks encryption, reads the manifest.
 *  2. [import] — runs the actual merge using the manifest read by [peek].
 *
 * The importer is deliberately conservative: existing local rows are never
 * destroyed. The merge dedupes per [MergeStrategies]; a row that's "the
 * same" as a local row (per its natural identity) is skipped, not
 * overwritten. Settings prefs are an exception — they overwrite, since
 * scalar settings can't meaningfully merge.
 */
class BackupImporter(private val app: WristotleApplication) {

    /**
     * Stages the ZIP locally + reads the manifest. Cheap; no row writes,
     * no audio extraction. UI calls this to drive the password prompt
     * and the preview dialog.
     *
     * Cleans up its own staging file on completion / failure.
     */
    suspend fun peek(source: Uri, password: String? = null): PeekResult = withContext(Dispatchers.IO) {
        val staging = stage(source) ?: return@withContext PeekResult.Error(STAGING_FAILED)
        try {
            val tempZip = File(staging, INCOMING_ZIP)
            val zipFirstOpen = ZipFile(tempZip)
            if (zipFirstOpen.isEncrypted && password.isNullOrEmpty()) {
                return@withContext PeekResult.NeedsPassword
            }
            val zip = if (zipFirstOpen.isEncrypted) {
                ZipFile(tempZip, password!!.toCharArray())
            } else zipFirstOpen
            val manifest = runCatching { readManifest(zip) }
                .getOrElse { return@withContext PeekResult.Error(it.message ?: "manifest read failed") }
            PeekResult.Ok(manifest)
        } catch (badPw: net.lingala.zip4j.exception.ZipException) {
            // Most commonly: wrong password (MAC failure). zip4j surfaces this
            // as a generic ZipException at read time; treat as bad password
            // when the user gave one.
            if (!password.isNullOrEmpty()) PeekResult.WrongPassword
            else PeekResult.Error(badPw.message ?: "ZIP read failed")
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Runs the full import using the [manifest] [peek] already validated.
     * Re-stages the ZIP (peek's staging dir is gone by now) and processes
     * every entry.
     */
    suspend fun import(
        source: Uri,
        password: String? = null,
        manifest: BackupManifest,
    ): BackupImportResult = withContext(Dispatchers.IO) {
        val staging = stage(source) ?: error("Could not stage incoming ZIP")
        try {
            val tempZip = File(staging, INCOMING_ZIP)
            val zip = if (password.isNullOrEmpty()) ZipFile(tempZip)
                     else ZipFile(tempZip, password.toCharArray())
            // Extract everything (manifest already parsed, but harmless to
            // re-extract; the audio path needs the extracts in place).
            val extractDir = File(staging, "extract").apply { mkdirs() }
            zip.extractAll(extractDir.absolutePath)

            val schemaSkips = mutableListOf<String>()
            val audio = importAudio(extractDir, manifest.exportedAtMs)
            val notes = importNotes(extractDir, audio.map, schemaSkips)
            val tasks = importTasks(extractDir, schemaSkips)
            val conversations = importConversations(extractDir, audio.map, schemaSkips)
            val nlu = importNlu(extractDir, schemaSkips)
            applyPrefs(manifest.prefs)
            val pins = applyPins(manifest.reminderPins)
            val aliases = applyAliases(manifest.appAliases)
            val contactAliases = applyContactAliases(manifest.contactAliases)

            BackupImportResult(
                notes = notes,
                tasks = tasks,
                conversations = conversations,
                nlu = nlu,
                audio = audio.stats,
                pins = pins,
                aliases = aliases,
                contactAliases = contactAliases,
                schemaSkipped = schemaSkips.toList(),
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    // ── Staging ───────────────────────────────────────────────────────────

    /** Copies the SAF [source] into `cacheDir/import-staging/incoming.zip`
     *  so zip4j has a real File to work with. Returns the staging dir, or
     *  null on read failure. Caller is responsible for cleanup. */
    private fun stage(source: Uri): File? {
        return runCatching {
            val dir = File(app.cacheDir, "import-staging").apply {
                deleteRecursively()
                mkdirs()
            }
            val target = File(dir, INCOMING_ZIP)
            app.applicationContext.contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: return@runCatching null
            dir
        }.onFailure { Log.w(TAG, "stage failed", it) }.getOrNull()
    }

    // ── Manifest ──────────────────────────────────────────────────────────

    private fun readManifest(zip: ZipFile): BackupManifest {
        val entry = zip.getFileHeader(BackupManifest.FILENAME)
            ?: throw IllegalStateException("Backup is missing manifest.json")
        val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
        return BackupManifestCodec.decode(text)
    }

    // ── Audio ─────────────────────────────────────────────────────────────

    /**
     * Moves each extracted .wav into its final on-device dir under filesDir/.
     *
     * Collision rule: if a file with the same basename already exists locally
     * and differs in content (size + first-1KB byte compare), the incoming
     * file is renamed to `<basename>-imported-<exportedAtMs>.<ext>` so the
     * existing local audio is preserved. The map's key is the **original**
     * basename (as it appears in the JSON row's `audio_filename`), so the
     * row-rewrite step can resolve renames transparently.
     *
     * Returns originalBasename → finalAbsolutePath.
     */
    private fun importAudio(extractDir: File, exportedAtMs: Long): AudioImportOutcome {
        val map = mutableMapOf<String, String>()
        var imported = 0
        var duplicates = 0
        var failed = 0
        val notesSrc = File(extractDir, "audio/notes")
        val notesDst = app.notesAudioStore.dir.apply { mkdirs() }
        val convSrc = File(extractDir, "audio/conversation")
        val convDst = app.conversationAudioStore.dir.apply { mkdirs() }

        fun moveAudio(src: File, dst: File) {
            if (!src.exists() || !src.isDirectory) return
            for (file in src.listFiles().orEmpty()) {
                if (!file.isFile) continue
                val natural = File(dst, file.name)
                // Same-name + same-content = idempotent re-import for this file.
                if (natural.exists() && sameContent(file, natural)) {
                    map[file.name] = natural.absolutePath
                    duplicates++
                    continue
                }
                val finalFile = chooseTargetFile(file, dst, exportedAtMs)
                runCatching {
                    file.copyTo(finalFile, overwrite = false)
                    map[file.name] = finalFile.absolutePath
                    imported++
                }.onFailure {
                    Log.w(TAG, "audio copy ${file.name} failed", it)
                    failed++
                }
            }
        }
        moveAudio(notesSrc, notesDst)
        moveAudio(convSrc, convDst)
        return AudioImportOutcome(
            map = map,
            stats = EntityStats(imported = imported, duplicates = duplicates, failed = failed),
        )
    }

    private data class AudioImportOutcome(
        val map: Map<String, String>,
        val stats: EntityStats,
    )

    /**
     * Determines the final on-device path for an incoming audio file. If no
     * collision (no local file with the same basename), return it as-is. If
     * the local file is byte-identical, also return it as-is — the import
     * is idempotent for that file. Only when the local file exists AND
     * differs do we rename incoming to `<basename>-imported-<ts>.<ext>`.
     */
    private fun chooseTargetFile(source: File, dstDir: File, exportedAtMs: Long): File {
        val natural = File(dstDir, source.name)
        if (!natural.exists()) return natural
        if (sameContent(source, natural)) return natural
        val dot = source.name.lastIndexOf('.')
        val base = if (dot > 0) source.name.substring(0, dot) else source.name
        val ext = if (dot > 0) source.name.substring(dot) else ""
        return File(dstDir, "$base-imported-$exportedAtMs$ext")
    }

    private fun sameContent(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        val sample = 1024
        val ba = ByteArray(sample)
        val bb = ByteArray(sample)
        a.inputStream().use { it.read(ba) }
        b.inputStream().use { it.read(bb) }
        return ba.contentEquals(bb)
    }

    // ── Notes ─────────────────────────────────────────────────────────────

    private suspend fun importNotes(
        extractDir: File,
        audioMap: Map<String, String>,
        schemaSkips: MutableList<String>,
    ): EntityStats {
        val rows = readRows(extractDir, "data/notes.json", NoteJson.CURRENT_SCHEMA, "notes", schemaSkips)
            { row, schema -> NoteJson.decode(row, schema) }
            ?: return EntityStats()
        val resolved = rows.map { note ->
            // The decoded row's audioFilePath holds SEP-joined basenames.
            // Resolve each to a local absolute path (or drop when the audio
            // file wasn't included in the backup) and rejoin.
            val basenames = NoteAudioPaths.parse(note.audioFilePath)
            val finalPaths = basenames.mapNotNull(audioMap::get)
            note.copy(audioFilePath = NoteAudioPaths.encode(finalPaths))
        }
        val dao = app.notesDb.noteDao()
        val existing = dao.allForBackup()
        val toInsert = MergeStrategies.mergeNotes(existing, resolved)
        val duplicates = resolved.size - toInsert.size
        var imported = 0
        var failed = 0
        for (row in toInsert) {
            try { dao.insert(row); imported++ } catch (t: Throwable) {
                Log.w(TAG, "note insert failed", t); failed++
            }
        }
        return EntityStats(imported = imported, duplicates = duplicates, failed = failed)
    }

    // ── Tasks ─────────────────────────────────────────────────────────────

    private suspend fun importTasks(
        extractDir: File,
        schemaSkips: MutableList<String>,
    ): EntityStats {
        val rows = readRows(extractDir, "data/tasks.json", TaskJson.CURRENT_SCHEMA, "tasks", schemaSkips)
            { row, schema -> TaskJson.decode(row, schema) }
            ?: return EntityStats()
        val dao = app.tasksDb.taskDao()
        val existing = dao.allForBackup()
        val toInsert = MergeStrategies.mergeTasks(existing, rows)
        val duplicates = rows.size - toInsert.size
        var imported = 0
        var failed = 0
        for (row in toInsert) {
            try { dao.insert(row); imported++ } catch (t: Throwable) {
                Log.w(TAG, "task insert failed", t); failed++
            }
        }
        return EntityStats(imported = imported, duplicates = duplicates, failed = failed)
    }

    // ── Conversations ─────────────────────────────────────────────────────

    private suspend fun importConversations(
        extractDir: File,
        audioMap: Map<String, String>,
        schemaSkips: MutableList<String>,
    ): EntityStats {
        val rows = readRows(extractDir, "data/conversations.json", ConversationEntryJson.CURRENT_SCHEMA, "conversations", schemaSkips)
            { row, schema -> ConversationEntryJson.decode(row, schema) }
            ?: return EntityStats()
        val resolved = rows.map { entry ->
            val finalPath = entry.audioFilePath?.let(audioMap::get)
            entry.copy(audioFilePath = finalPath)
        }
        val dao = app.conversationDb.conversationDao()
        val existing = dao.allForBackup()
        val toInsert = MergeStrategies.mergeConversations(existing, resolved)
        val duplicates = resolved.size - toInsert.size
        var imported = 0
        var failed = 0
        for (row in toInsert) {
            try { dao.insert(row); imported++ } catch (t: Throwable) {
                Log.w(TAG, "conversation insert failed", t); failed++
            }
        }
        return EntityStats(imported = imported, duplicates = duplicates, failed = failed)
    }

    // ── NLU examples ──────────────────────────────────────────────────────

    private suspend fun importNlu(
        extractDir: File,
        schemaSkips: MutableList<String>,
    ): EntityStats {
        val rows = readRows(extractDir, "data/nlu.json", ExampleEntryJson.CURRENT_SCHEMA, "nlu", schemaSkips)
            { row, schema -> ExampleEntryJson.decode(row, schema) }
            ?: return EntityStats()
        val dao = app.nluDb.exampleDao()
        val existing = dao.learned()
        val toInsert = MergeStrategies.mergeNluExamples(existing, rows)
        val duplicates = rows.size - toInsert.size
        var imported = 0
        var failed = 0
        for (row in toInsert) {
            try { dao.insert(row); imported++ } catch (t: Throwable) {
                Log.w(TAG, "nlu insert failed", t); failed++
            }
        }
        return EntityStats(imported = imported, duplicates = duplicates, failed = failed)
    }

    // ── JSON row reader ───────────────────────────────────────────────────

    /**
     * Reads `{ schema, rows: [...] }` from [extractDir]/[entryName] and decodes
     * each row via [decode]. Returns null when the file is missing or when
     * its `schema:` is higher than [maxSupportedSchema] (records the entity
     * label in [schemaSkips] so the UI can surface it).
     */
    private inline fun <T> readRows(
        extractDir: File,
        entryName: String,
        maxSupportedSchema: Int,
        entityLabel: String,
        schemaSkips: MutableList<String>,
        decode: (JSONObject, Int) -> T,
    ): List<T>? {
        val file = File(extractDir, entryName)
        if (!file.exists()) return null
        val root = JSONObject(file.readText())
        val schema = root.getInt("schema")
        if (schema > maxSupportedSchema) {
            schemaSkips += entityLabel
            return null
        }
        val arr = root.getJSONArray("rows")
        return (0 until arr.length()).map { i -> decode(arr.getJSONObject(i), schema) }
    }

    // ── Prefs / pins / aliases ────────────────────────────────────────────

    private fun applyPrefs(p: BackupManifest.PrefsBlock) {
        app.noteSettings.setKeepLast(p.notes.keepLast)
        runCatching { AppendAudioMode.valueOf(p.notes.appendAudioMode) }
            .onSuccess { app.noteSettings.setAppendAudioMode(it) }
        app.conversationSettings.setRetentionDays(p.conversationSettings.retentionDays)
        app.conversationAudioSettings.setCaptureEnabled(p.conversationAudio.captureEnabled)
        app.nluSettings.setLearningEnabled(p.nluSettings.learningEnabled)
        app.diagnosticsSettings.setRedactPii(p.diagnostics.redactPii)
        app.diagnosticsSettings.setIncludeAudio(p.diagnostics.includeAudio)
        app.modelStorage.activeModelId = p.whisperModels.activeModelId
        app.nluModelStorage.activeModelId = p.nluModels.activeModelId
    }

    private fun applyPins(incoming: List<BackupManifest.PinRecord>): EntityStats {
        if (incoming.isEmpty()) return EntityStats()
        val store = PinStore(app.applicationContext)
        val existing = store.all()
        val toMerge = incoming.map { ReminderRecord(it.id, it.title, it.timeMs) }
        val merged = MergeStrategies.mergePins(existing, toMerge)
        store.replaceAll(merged)
        val imported = merged.size - existing.size
        return EntityStats(imported = imported, duplicates = incoming.size - imported)
    }

    /** Aliases overwrite on key collision (backup wins). For the per-entity
     *  stats we count `imported` as net-new keys and `duplicates` as keys
     *  that already existed locally (and were overwritten by the backup's
     *  value). */
    private fun applyAliases(incoming: Map<String, String>): EntityStats {
        if (incoming.isEmpty()) return EntityStats()
        val existing = app.aliasStore.all()
        val newKeys = incoming.keys - existing.keys
        val overwrittenKeys = incoming.keys.intersect(existing.keys)
        val merged = MergeStrategies.mergeAliases(existing, incoming)
        app.aliasStore.replaceAll(merged)
        return EntityStats(imported = newKeys.size, duplicates = overwrittenKeys.size)
    }

    /**
     * Contact aliases overwrite on key collision (same rule as app
     * aliases). After the merge, walk every alias and re-link any whose
     * `lookupKey` no longer resolves: try the snapshot name, then the
     * snapshot number, against the device's current Contacts. This is
     * the common case after backup-restore on a new device — same
     * humans in Contacts but freshly aggregated, so their lookup keys
     * are different.
     *
     * Dead links left as-is when both name and number queries miss; the
     * Settings card surfaces them as "(not found)" so the user can prune.
     */
    private fun applyContactAliases(incoming: Map<String, ContactRef>): EntityStats {
        if (incoming.isEmpty()) return EntityStats()
        val existing = app.contactAliasStore.all()
        val newKeys = incoming.keys - existing.keys
        val overwrittenKeys = incoming.keys.intersect(existing.keys)
        val merged = MergeStrategies.mergeContactAliases(existing, incoming)
        val relinked = merged.mapValues { (_, ref) ->
            MergeStrategies.relinkContactRef(
                ref = ref,
                isCurrent = ::isLookupKeyResolvable,
                byName = ::findLookupKeyByName,
                byNumber = ::findLookupKeyByNumber,
            )
        }
        app.contactAliasStore.replaceAll(relinked)
        return EntityStats(imported = newKeys.size, duplicates = overwrittenKeys.size)
    }

    /** Returns true if the lookup key resolves to a live contact. */
    private fun isLookupKeyResolvable(lookupKey: String): Boolean {
        val lookupUri = ContactsContract.Contacts.getLookupUri(0L, lookupKey)
            ?: return false
        return ContactsContract.Contacts.lookupContact(app.contentResolver, lookupUri) != null
    }

    /** Exact-name match (case-insensitive). Returns the new lookup key
     *  iff EXACTLY ONE contact matches — ambiguous matches are left
     *  for the user to disambiguate. */
    private fun findLookupKeyByName(name: String): String? {
        if (name.isBlank()) return null
        val cursor = app.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.LOOKUP_KEY),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} = ? COLLATE NOCASE",
            arrayOf(name),
            null,
        ) ?: return null
        return cursor.use { c ->
            if (c.count != 1) return@use null
            if (!c.moveToFirst()) return@use null
            c.getString(0)
        }
    }

    /** Phone-number lookup via `PhoneLookup` so the framework's number
     *  normalisation (E.164 + format-insensitive comparison) does the
     *  heavy lifting. Returns the new lookup key iff exactly one match. */
    private fun findLookupKeyByNumber(number: String): String? {
        if (number.isBlank()) return null
        val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI
            .buildUpon()
            .appendPath(number)
            .build()
        val cursor = app.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.LOOKUP_KEY),
            null, null, null,
        ) ?: return null
        return cursor.use { c ->
            if (c.count != 1) return@use null
            if (!c.moveToFirst()) return@use null
            c.getString(0)
        }
    }

    private companion object {
        const val INCOMING_ZIP = "incoming.zip"
        const val STAGING_FAILED = "Could not read the backup file"
    }
}

/** What [BackupImporter.peek] returns to the UI before the full import runs. */
sealed interface PeekResult {
    data class Ok(val manifest: BackupManifest) : PeekResult
    object NeedsPassword : PeekResult
    object WrongPassword : PeekResult
    data class Error(val message: String) : PeekResult
}

/** Per-entity import counts. Same shape across all entities so the result
 *  dialog renders one consistent row per entity. */
data class EntityStats(
    /** Rows newly added to the local DB / store. */
    val imported: Int = 0,
    /** Rows skipped because a matching local row already exists (or an audio
     *  file was byte-identical). For aliases: count of keys that existed
     *  locally and were overwritten by the backup's value. */
    val duplicates: Int = 0,
    /** Rows that errored during insert / copy. Should be 0 on a healthy run. */
    val failed: Int = 0,
)

/** Summary of a completed import for the result dialog. */
data class BackupImportResult(
    val notes: EntityStats,
    val tasks: EntityStats,
    val conversations: EntityStats,
    val nlu: EntityStats,
    val audio: EntityStats,
    val pins: EntityStats,
    val aliases: EntityStats,
    val contactAliases: EntityStats,
    /** Entity labels skipped because their per-entity schema in the ZIP is
     *  higher than the current code knows how to decode (forward backup).
     *  Empty in the common case. */
    val schemaSkipped: List<String>,
)
