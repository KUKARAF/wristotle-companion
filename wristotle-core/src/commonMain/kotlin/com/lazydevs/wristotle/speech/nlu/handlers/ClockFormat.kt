// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.handlers

/**
 * Formats a 24-hour [hour]:[minute] as a 12-hour clock string ("3:45 PM").
 * Shared by the alarm handlers (SetAlarm / CancelAlarm) which were carrying
 * byte-identical copies. Manual padding (not `String.format`) so it stays in
 * commonMain / KMP-safe.
 */
fun formatClock12h(hour: Int, minute: Int): String {
    val period = if (hour < 12) "AM" else "PM"
    val h12 = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return "$h12:${minute.toString().padStart(2, '0')} $period"
}
