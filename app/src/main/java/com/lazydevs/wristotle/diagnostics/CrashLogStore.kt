// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.diagnostics

import com.lazydevs.wristotle.BuildConfig
import com.lazydevs.wristotle.logging.SensitiveScrub
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catches uncaught Kotlin/Java exceptions from any thread and writes
 * the stack trace to a dedicated file before chaining to the platform
 * default handler (which kills the process). Survives the process
 * death so the next [DiagnosticsBuilder] export can attach the trace
 * to a bug report.
 *
 * Caveats:
 * - Native SEGVs in whisper.cpp / ONNX / MiniLM bypass this — they
 *   tombstone before the JVM handler runs. Catching those would need
 *   `acra-native` or a signal handler.
 * - ANRs (main thread blocked > 5 s) aren't caught here either. Same
 *   story — out of scope for the lighter alternative to ACRA.
 *
 * Lives in `:app` not `:wristotle-core` — JVM Thread API + Android
 * `filesDir`. iOS would need NSSetUncaughtExceptionHandler.
 */
object CrashLogStore {

    private const val MAX_RETAINED = 5
    private const val DIR_NAME = "crashes"
    private const val FILE_PREFIX = "crash-"
    private const val FILE_SUFFIX = ".txt"

    /**
     * Installs the global handler. Pass the app's `filesDir` directly
     * — we don't hold a Context reference to avoid leaking it into the
     * UncaughtExceptionHandler closure for the process lifetime.
     * Idempotent on subsequent calls (replaces the previous chain
     * link), but in practice called once from Application.onCreate.
     */
    fun install(filesDir: File) {
        val dir = File(filesDir, DIR_NAME).apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(dir, thread, throwable) }
            // Always chain — letting the platform handler kill the
            // process is the correct end state; suppressing it would
            // leave the app in a broken state with no UI feedback.
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Newest-first list of stored crash files, up to [limit]. */
    fun recent(filesDir: File, limit: Int = MAX_RETAINED): List<File> =
        dir(filesDir).listFiles()
            ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) }
            // Epoch-in-name → lexical sort == chronological sort.
            ?.sortedByDescending { it.name }
            ?.take(limit)
            ?: emptyList()

    /** Wipe stored crash files. */
    fun clear(filesDir: File) {
        dir(filesDir).listFiles()?.forEach { it.delete() }
    }

    private fun dir(filesDir: File): File =
        File(filesDir, DIR_NAME).apply { mkdirs() }

    internal fun write(
        dir: File,
        thread: Thread,
        throwable: Throwable,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) {
        val file = File(dir, "$FILE_PREFIX$nowEpochMs$FILE_SUFFIX")
        val sw = StringWriter()
        sw.write(header(nowEpochMs, thread, throwable))
        throwable.printStackTrace(PrintWriter(sw))
        // Defence in depth: scrub anything key-shaped from message or
        // stack-frame strings before it lands on disk. Per the standing
        // "no API keys in logs" rule — even though :app code never logs
        // keys, third-party message strings (HTTP / SQLite / etc.) can
        // surface them indirectly.
        file.writeText(SensitiveScrub.redact(sw.toString()))
        prune(dir)
    }

    private fun header(epochMs: Long, thread: Thread, throwable: Throwable): String = buildString {
        append("Wristotle ").append(BuildConfig.VERSION_NAME)
            .append(" (code ").append(BuildConfig.VERSION_CODE).append(")\n")
        append("Build type: ").append(BuildConfig.BUILD_TYPE).append('\n')
        append("Time: ").append(ISO.format(Date(epochMs))).append('\n')
        append("Thread: ").append(thread.name).append('\n')
        append("Throwable: ").append(throwable.javaClass.name)
        throwable.message?.let { append(": ").append(it) }
        append("\n\n")
    }

    private fun prune(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) }
            ?.sortedByDescending { it.name } ?: return
        files.drop(MAX_RETAINED).forEach { it.delete() }
    }

    private val ISO = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
}
