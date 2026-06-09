// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.reminders

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Formats the pending-reminder list into a watch-friendly message. Pure
 * kotlinx-datetime; unit-testable with an injected `now`.
 *
 * Drops past-due reminders (their pin has already fired and can't be acted on)
 * and caps the visible rows so a long list doesn't blow past the watch chat's
 * display width — extras collapse into an "…and N more" line.
 */
object ReminderListFormatter {

    private const val MAX_SHOWN = 5
    private val MONTH = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )

    /** Full active list (upcoming + fired within the retention window), newest-first. */
    fun format(records: List<ReminderRecord>, now: Long): String {
        val pending = records.filter { PinStoreCodec.isActive(it.timeMs, now) }
        if (pending.isEmpty()) return "You have no reminders"
        return "${countHeader(pending.size)}\n${rows(pending)}" +
            if (pending.size > MAX_SHOWN) "\n…and ${pending.size - MAX_SHOWN} more" else ""
    }

    /**
     * Answer for a specific clock time ("is there a reminder at 2pm"). Matches
     * pending reminders in the same local hour as [targetMs] (minute-level
     * precision is overkill for voice).
     */
    fun formatAtTime(records: List<ReminderRecord>, targetMs: Long, now: Long): String {
        val targetLdt = toLocal(targetMs)
        val targetHour = targetLdt.hour
        val label = formatHourOnly(targetLdt) // "2 PM"
        val matches = records.filter {
            val t = it.timeMs ?: return@filter false
            PinStoreCodec.isActive(t, now) && toLocal(t).hour == targetHour
        }
        if (matches.isEmpty()) return "No reminder around $label"
        val header = if (matches.size == 1) "1 reminder around $label:" else "${matches.size} reminders around $label:"
        return "$header\n${rows(matches)}"
    }

    private fun countHeader(n: Int) = if (n == 1) "You have 1 reminder:" else "You have $n reminders:"

    private fun rows(records: List<ReminderRecord>): String =
        records.take(MAX_SHOWN).joinToString("\n") { r ->
            val time = r.timeMs?.let { formatDateAndTime(toLocal(it)) }
            val tail = if (r.isPersistent) " (persistent)" else ""
            if (time != null) "• ${r.title} — $time$tail" else "• ${r.title}$tail"
        }

    private fun toLocal(epochMs: Long): LocalDateTime =
        Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())

    /** "MMM d 'at' h:mm a" — "May 21 at 3:00 PM". */
    private fun formatDateAndTime(ldt: LocalDateTime): String {
        val month = MONTH[ldt.month.ordinal]
        return "$month ${ldt.dayOfMonth} at ${formatTime(ldt)}"
    }

    /** "h a" — "2 PM". */
    private fun formatHourOnly(ldt: LocalDateTime): String {
        val hour24 = ldt.hour
        val hour12 = ((hour24 + 11) % 12) + 1
        val ampm = if (hour24 < 12) "AM" else "PM"
        return "$hour12 $ampm"
    }

    /** "h:mm a" — "3:00 PM". */
    private fun formatTime(ldt: LocalDateTime): String {
        val hour24 = ldt.hour
        val hour12 = ((hour24 + 11) % 12) + 1
        val ampm = if (hour24 < 12) "AM" else "PM"
        val mm = ldt.minute.toString().padStart(2, '0')
        return "$hour12:$mm $ampm"
    }
}
