package com.lazydevs.wristotle.logging

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Thin wrapper over [android.util.Log] that also appends every call
 * to an in-memory ring buffer so a user-triggered diagnostics export
 * can capture recent app activity. Android 10+ blocks third-party
 * apps from reading their own logcat, so we mirror at the source.
 *
 * Lives in `:speech` (not `:app`) so every module — the Whisper
 * recognizer, the NLU embedder, the per-package media handlers —
 * can route through one buffer; inference timing + routing decisions
 * are the most useful signal for bug reports.
 *
 * Behaviour is identical to `android.util.Log.{d,w,e}` from the
 * caller's perspective — drop in as a replacement for high-signal
 * log sites whose output is worth surfacing in bug reports.
 *
 * The buffer is process-scoped; a crash loses everything since the
 * last process start. That's an acceptable trade for the alpha — the
 * user reproduces the issue, then exports. File-backed log retention
 * is a follow-up if crashes become routine.
 */
object WristotleLog {

    /**
     * Max retained lines. ~500 covers a few minutes of busy dispatch
     * activity at ~50–100 lines/minute and stays well under 100 KB
     * resident.
     */
    private const val BUFFER_LIMIT = 500

    private val buffer = ArrayDeque<String>(BUFFER_LIMIT)
    private val lock = Any()

    // ISO-8601-style without the date — chronology within a single
    // export is obvious from order, and the date noise on every line
    // would inflate the bundle size.
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        append('D', tag, msg, null)
    }

    fun w(tag: String, msg: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w(tag, msg, throwable) else Log.w(tag, msg)
        append('W', tag, msg, throwable)
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(tag, msg, throwable) else Log.e(tag, msg)
        append('E', tag, msg, throwable)
    }

    /**
     * Snapshot of the last [maxLines] entries (or all if fewer
     * buffered), oldest first so the result reads top-to-bottom as
     * chronological history. Safe to call from any thread.
     */
    fun dumpRecent(maxLines: Int = BUFFER_LIMIT): String = synchronized(lock) {
        if (buffer.isEmpty()) return ""
        val skip = (buffer.size - maxLines).coerceAtLeast(0)
        buffer.asSequence().drop(skip).joinToString("\n")
    }

    /** Wipe the buffer — used by tests. */
    fun clear() = synchronized(lock) { buffer.clear() }

    private fun append(level: Char, tag: String, msg: String, throwable: Throwable?) {
        val ts = synchronized(timeFormat) { timeFormat.format(Date()) }
        val line = buildString {
            append(ts); append(' '); append(level); append(' '); append(tag); append("  ").append(msg)
            if (throwable != null) {
                append("\n    ")
                append(throwable.javaClass.simpleName)
                throwable.message?.let { append(": ").append(it) }
            }
        }
        synchronized(lock) {
            if (buffer.size >= BUFFER_LIMIT) buffer.removeFirst()
            buffer.addLast(line)
        }
    }
}
