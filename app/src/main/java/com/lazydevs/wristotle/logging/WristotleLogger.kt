// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.logging

import com.lazydevs.wristotle.speech.nlu.logging.Logger

/**
 * Android-side adapter that fulfils the multiplatform [Logger] contract
 * by forwarding every call to the existing ring-buffered [WristotleLog]
 * (in `:speech`), so commonMain code keeps the same diagnostics-export
 * behaviour callers have today.
 *
 * Lives in `:app` rather than `:speech-nlu` so the dependency arrow
 * stays clean: `:speech-nlu` declares the interface in commonMain;
 * `:app` (which already depends on both `:speech-nlu` and `:speech`)
 * is where the wires meet.
 *
 */
object WristotleLogger : Logger {
    override fun d(tag: String, msg: String) = WristotleLog.d(tag, msg)
    override fun w(tag: String, msg: String, throwable: Throwable?) = WristotleLog.w(tag, msg, throwable)
    override fun e(tag: String, msg: String, throwable: Throwable?) = WristotleLog.e(tag, msg, throwable)
}
