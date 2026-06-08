// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.sync

import com.lazydevs.wristotle.speech.nlu.settings.FileSyncSettings
import com.lazydevs.wristotle.speech.nlu.settings.FileSyncFormat
import com.lazydevs.wristotle.speech.nlu.settings.FileSyncGranularity
import com.lazydevs.wristotle.speech.nlu.settings.FileSyncFormatOptions
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Drives the actual write loop for one entity type. Generic over `T` so
 * the same coordinator class serves Notes today and Conversations later.
 * Instantiated once per entity at app startup; survives the process
 * lifetime.
 *
 * Behaviour (Phase A3):
 * - Subscribes to [entitiesFlow] when [FileSyncSettings.enabled] is on
 *   AND [FileSyncSettings.folderUri] is set. Auto-resubscribes when
 *   either changes (the user picks a new folder or flips the toggle).
 * - On each emission of the entity list, renders every entity through
 *   the injected [FileSyncRenderer] and writes the result to the SAF
 *   tree. Two modes:
 *     - `OneFilePerEntity` — one SAF file per entity, overwritten on
 *       each emission. Attachments copied to the per-renderer
 *       sub-directory.
 *     - `AppendToSingleFile` — all entity renders concatenated and
 *       written as the entire file each time. Cheap rewrite for the
 *       few-thousand-note scale; lets deletes / updates land without
 *       any append-truncation gymnastics.
 * - Tracks the set of synced ids in `last_sync_ids` SharedPrefs so the
 *   next phase (A4) can detect deletions and cascade them to disk.
 * - Sync passes are serialised via [Mutex] — emissions arriving
 *   mid-write get the next pass; no overlapping writes against the
 *   same SAF tree.
 *
 * SAF write semantics:
 * - Sub-directories created lazily via [DocumentFile.createDirectory]
 *   on first use; subsequent passes just re-find them.
 * - Files overwritten via `contentResolver.openOutputStream(uri, "wt")`
 *   — the "wt" mode truncates before writing so we don't append
 *   garbage when the new content is shorter than the old.
 * - Audio attachments copied byte-for-byte; skip when the source file
 *   doesn't exist (race condition: note deleted between Flow emission
 *   and our pass).
 * - All failures (permission revoked, provider unavailable, out of
 *   space) get logged and swallowed; the next emission retries. Phase
 *   A2's settings card surfaces no error UI yet — A5 will add the
 *   re-pick chip when the failure mode is permission-revoked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileSyncCoordinator<T>(
    private val context: Context,
    private val settings: FileSyncSettings,
    private val renderer: FileSyncRenderer<T>,
    private val entitiesFlow: Flow<List<T>>,
    private val idOf: (T) -> String,
    private val scope: String,
    private val appScope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("file_sync_state_$scope", Context.MODE_PRIVATE)
    private val writeMutex = Mutex()

    /**
     * True while a sync pass is in flight — drives the "Sync now"
     * button's disabled + spinner state. Set by both the reactive
     * subscription and [syncNow]; the [writeMutex] guarantees only one
     * pass executes at a time so the flag is never racy.
     */
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    /**
     * Outcome of the most recent sync pass — drives the small status
     * line under the "Sync now" button. Null until the first pass; set
     * by every pass (reactive or manual).
     */
    private val _lastResult = MutableStateFlow<LastSyncResult?>(null)
    val lastResult: StateFlow<LastSyncResult?> = _lastResult

    fun start() {
        appScope.launch {
            combine(
                settings.enabled,
                settings.folderUri,
            ) { enabled, uri -> enabled to uri }
                .flatMapLatest { (enabled, uri) ->
                    if (!enabled || uri.isBlank()) emptyFlow()
                    else entitiesFlow.map { entities -> Triple(uri, settings.formatOptions(), entities) }
                }
                .collect { (uri, options, entities) ->
                    runCatching { sync(uri, options, entities) }
                        .onFailure { Log.w(TAG, "sync pass failed for scope=$scope", it) }
                }
        }
    }

    /**
     * Manual "Sync now" — runs one pass with the current settings and
     * current entity snapshot, regardless of whether the reactive
     * subscription would have fired. Used by the Settings card's
     * "Sync now" button, which is the user's escape hatch when:
     *  - they changed format / granularity and want the vault to
     *    re-render immediately (these knobs don't gate the reactive
     *    subscription on purpose — they only matter at write time);
     *  - they want to force a re-write after fixing a permission
     *    issue or moving the folder;
     *  - they're impatient (we don't judge).
     *
     * No-op (with a `Skipped` result) when sync isn't enabled or no
     * folder is picked; surfaces a `Failed` result on exception so the
     * UI can show what went wrong.
     */
    suspend fun syncNow() {
        val uri = settings.folderUri.value
        if (!settings.enabled.value || uri.isBlank()) {
            _lastResult.value = LastSyncResult.Skipped
            return
        }
        val options = settings.formatOptions()
        val entities = runCatching { entitiesFlow.first() }
            .getOrElse {
                _lastResult.value = LastSyncResult.Failed(it.message ?: "snapshot failed")
                return
            }
        runCatching { sync(uri, options, entities) }
            .onFailure { _lastResult.value = LastSyncResult.Failed(it.message ?: "sync failed") }
    }

    private suspend fun sync(uri: String, options: FileSyncFormatOptions, entities: List<T>) {
        writeMutex.withLock {
            _isSyncing.value = true
            try {
                withContext(Dispatchers.IO) { syncLocked(uri, options, entities) }
            } finally {
                _isSyncing.value = false
            }
        }
    }

    private fun syncLocked(uri: String, options: FileSyncFormatOptions, entities: List<T>) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(uri))
        if (root == null || !root.canWrite()) {
            Log.w(TAG, "scope=$scope: tree URI not writable — permission may be revoked")
            _lastResult.value = LastSyncResult.Failed("folder permission revoked or moved")
            return
        }
        val rendered = entities.map { idOf(it) to renderer.render(it, options) }
        val currentPaths = rendered.toMap()
        val previousPaths = loadLastSyncPaths()
        val removedIds = previousPaths.keys - currentPaths.keys

        // No collision guard needed — NoteRenderer's filenames are
        // derived from each note's createdAtEpochMs (filesystem-safe ISO
        // timestamp), so a fresh install's new notes land at new
        // timestamps and can't clobber old files written by a previous
        // install. Within-install collisions on the same millisecond
        // are astronomical at human dictation rates.

        // Write the survivors first so a crash between phases leaves
        // the vault in a "current + stale" state rather than "missing".
        when (options.granularity) {
            FileSyncGranularity.OneFilePerEntity -> writePerEntity(root, rendered.map { it.second })
            FileSyncGranularity.AppendToSingleFile -> writeAppended(root, rendered.map { it.second })
        }

        if (settings.deleteCascades.value) {
            // Delete files for removed ids — only meaningful in
            // OneFilePerEntity mode (Append rewrites the whole single
            // file each pass, so a deleted note simply doesn't appear
            // in the new content).
            if (options.granularity == FileSyncGranularity.OneFilePerEntity) {
                removedIds.forEach { id ->
                    previousPaths[id]?.let { path -> deleteAt(root, path) }
                }
            }
            cleanupOrphanAttachments(root, rendered.flatMap { it.second.attachments })
        }

        saveLastSyncPaths(currentPaths.mapValues { it.value.relativePath })
        _lastResult.value = LastSyncResult.Success(
            wrote = rendered.size,
            removed = removedIds.size,
            atEpochMs = System.currentTimeMillis(),
        )
        Log.d(
            TAG,
            "scope=$scope: wrote ${rendered.size} entries, removed ${removedIds.size} " +
                "(${options.granularity})",
        )
    }

    /**
     * Walk the renderer's attachment sub-directory and delete any file
     * whose name is no longer claimed by a current note. Catches the
     * "user deleted a note whose audio file was the only thing pointing
     * to that basename" case. No-op when the attachments directory
     * doesn't exist yet (first sync, no audio notes).
     */
    private fun cleanupOrphanAttachments(root: DocumentFile, current: List<Attachment>) {
        // Group current attachments by their parent directory so we only
        // walk vault sub-trees the renderer actually wrote to. Generic
        // across entity types: NoteRenderer writes to "Wristotle/notes/
        // attachments/", a future ConversationRenderer might use
        // "Wristotle/conversations/attachments/", etc.
        val attachmentDirsToNames: Map<String, Set<String>> = current
            .groupBy(
                keySelector = { it.relativePath.substringBeforeLast('/') },
                valueTransform = { it.relativePath.substringAfterLast('/') },
            )
            .mapValues { it.value.toSet() }

        for ((dirPath, kept) in attachmentDirsToNames) {
            val dir = findExisting(root, dirPath) ?: continue
            if (!dir.isDirectory) continue
            for (child in dir.listFiles()) {
                val name = child.name ?: continue
                if (name !in kept) {
                    runCatching { child.delete() }
                        .onFailure { Log.w(TAG, "orphan delete failed for $name", it) }
                }
            }
        }
    }

    private fun deleteAt(root: DocumentFile, relativePath: String) {
        val file = findExisting(root, relativePath) ?: return
        runCatching { file.delete() }.onFailure { Log.w(TAG, "delete failed for $relativePath", it) }
    }

    /** Like [ensureFile] but never creates anything — returns null when
     *  any segment is missing. Used by delete + orphan-cleanup paths. */
    private fun findExisting(root: DocumentFile, relativePath: String): DocumentFile? {
        val parts = relativePath.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var cursor: DocumentFile = root
        for (name in parts) {
            cursor = cursor.findFile(name) ?: return null
        }
        return cursor
    }

    private fun writePerEntity(root: DocumentFile, rendered: List<RenderedEntry>) {
        for (entry in rendered) {
            val file = ensureFile(root, entry.relativePath, MIME_OPAQUE) ?: continue
            writeText(file, entry.content)
            copyAttachments(root, entry.attachments)
        }
    }

    private fun writeAppended(root: DocumentFile, rendered: List<RenderedEntry>) {
        if (rendered.isEmpty()) return
        // All fragments share the same target path in Append mode — the
        // renderer puts the path on every fragment so the coordinator
        // doesn't need to know which file to write to.
        val targetPath = rendered.first().relativePath
        val concatenated = rendered.joinToString(separator = "\n") { it.content }
        val file = ensureFile(root, targetPath, MIME_OPAQUE) ?: return
        writeText(file, concatenated)
        // Attachments aggregated across every fragment.
        copyAttachments(root, rendered.flatMap { it.attachments })
    }

    private fun copyAttachments(root: DocumentFile, attachments: List<Attachment>) {
        for (att in attachments) {
            if (!att.sourceFile.exists()) continue
            val target = ensureFile(root, att.relativePath, MIME_OPAQUE) ?: continue
            copyBytes(att.sourceFile, target)
        }
    }

    /**
     * Resolve a slash-separated [relativePath] (e.g. `Wristotle/notes/42.md`)
     * to a SAF [DocumentFile], creating intermediate directories and the
     * file itself as needed. Returns the file, or null if any segment
     * couldn't be created (out of space, permission revoked, etc.).
     */
    private fun ensureFile(root: DocumentFile, relativePath: String, mime: String): DocumentFile? {
        val parts = relativePath.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var cursor = root
        for (i in 0 until parts.size - 1) {
            val name = parts[i]
            cursor = cursor.findFile(name)?.takeIf { it.isDirectory }
                ?: cursor.createDirectory(name)
                ?: return null
        }
        val fileName = parts.last()
        return cursor.findFile(fileName) ?: cursor.createFile(mime, fileName)
    }

    private fun writeText(file: DocumentFile, content: String) {
        // "wt" = write + truncate. Without truncate, shorter new content
        // would leave trailing bytes from the previous write.
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        }
    }

    private fun copyBytes(source: java.io.File, target: DocumentFile) {
        // Cheap skip when the target already exists AND has the same
        // byte length — full content compare is overkill for our sizes
        // and length-equality is a strong enough proxy for "we copied
        // this last pass".
        if (target.length() == source.length()) return
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        }
    }

    /**
     * Persisted `id → relativePath` map of the last successful sync.
     * Backed by a single SharedPrefs string keyed [KEY_LAST_SYNC_PATHS],
     * encoded as `id\tpath\nid\tpath…` — no JSON dep needed, no Room
     * migration risk on schema bumps. ids and paths never contain `\n`
     * or `\t` (entity ids are numeric / kebab; paths are file-system-
     * safe by construction), so the framing is safe.
     */
    internal fun loadLastSyncPaths(): Map<String, String> {
        val raw = prefs.getString(KEY_LAST_SYNC_PATHS, null) ?: return emptyMap()
        return raw.lineSequence()
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1)
            }
            .toMap()
    }

    internal fun saveLastSyncPaths(paths: Map<String, String>) {
        val encoded = paths.entries.joinToString(separator = "\n") { "${it.key}\t${it.value}" }
        prefs.edit().putString(KEY_LAST_SYNC_PATHS, encoded).apply()
    }

    private companion object {
        const val TAG = "FileSyncCoordinator"
        const val KEY_LAST_SYNC_PATHS = "last_sync_paths"

        // SAF's DocumentsProvider auto-appends an extension matching
        // the MIME type when the provided display name doesn't already
        // carry a recognised extension for that MIME. The external-
        // storage provider only knows a small fixed mapping (text/plain
        // → .txt, image/png → .png, etc.) and does NOT recognise .md /
        // .json, so passing `text/plain` for a "42.md" display name
        // lands the file as "42.md.txt".
        //
        // Universal fix: always use `application/octet-stream` — the
        // catch-all "no specific extension" MIME — so SAF leaves the
        // display name alone. We know the right extension (it's part
        // of the path the renderer computed); we don't need SAF to
        // guess. File managers honour the extension on the display
        // name when deciding what app opens the file.
        const val MIME_OPAQUE = "application/octet-stream"
    }
}