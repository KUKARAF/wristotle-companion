// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.logging

/**
 * Minimal multiplatform logging surface — exactly the three levels
 * that commonMain consumers (VoicePipeline, WatchHintRefiner, future
 * lifts) actually use. Mirrors `android.util.Log`'s shape so the
 * Android implementation is a one-line passthrough.
 *
 * The Android impl wires this to `WristotleLog` (the existing ring-
 * buffered logger in :speech, used by the diagnostics export). The iOS
 * impl will likely route to `os_log` / `NSLog` with a parallel buffer
 * if we need diagnostics there too.
 *
 * R3 batch 1 — extracted to make VoicePipeline + WatchHintRefiner
 * portable. They were the last two files in :app/nlu/ that couldn't
 * lift in R2 batch 1 because of `import WristotleLog as Log`.
 *
 * Why a tag per call instead of a tagged factory?
 * Because the existing call sites already pass a tag string (every
 * file owns a `private const val TAG = "..."`). A factory adds
 * ceremony for no functional gain at this scale.
 */
interface Logger {
    fun d(tag: String, msg: String)
    fun w(tag: String, msg: String, throwable: Throwable? = null)
    fun e(tag: String, msg: String, throwable: Throwable? = null)
}

/**
 * No-op logger — handy for tests that don't want to assert logging
 * behaviour and for any pure-logic test that constructs a Logger-
 * accepting class without needing log output.
 */
object NoopLogger : Logger {
    override fun d(tag: String, msg: String) {}
    override fun w(tag: String, msg: String, throwable: Throwable?) {}
    override fun e(tag: String, msg: String, throwable: Throwable?) {}
}
