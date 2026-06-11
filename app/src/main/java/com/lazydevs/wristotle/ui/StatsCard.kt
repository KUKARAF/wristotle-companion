// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.speech.nlu.stats.Stats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings → 📊 Stats card. Renders 6 sub-cards built from the
 * [StatsViewModel]'s last snapshot:
 *
 *  1. Streak + lifetime totals (the "hook").
 *  2. Top intents (last 30 days) with ASCII bars.
 *  3. Active hour heatmap + busiest day.
 *  4. Success rate (30d AND lifetime, side-by-side) + hardest intent.
 *  5. Personalisation counts (learned phrases / aliases).
 *  6. Lifetime tally (notes / tasks / reminders / alarms).
 *
 * Sub-10-query installs collapse to a single "Day 1" stub so a fresh
 * install never reads as "0% / 0 words / no data". Snapshot refreshes
 * on every screen entry via [LaunchedEffect].
 *
 * Everything in this file is pure presentation — the numbers come
 * from `:wristotle-core`'s [Stats]. The handler-tag → display-label
 * map ([handlerDisplayName]) is the only thing that's Android-side
 * by convention; lifting it would need a domain dictionary in core
 * that doesn't earn its keep yet.
 */
@Composable
fun StatsCard(vm: StatsViewModel) {
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }

    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        when (val s = state) {
            is StatsUiState.Loading -> LoadingPanel()
            is StatsUiState.Loaded -> {
                val stats = s.stats
                if (stats.lifetime.totalQueries < EMPTY_THRESHOLD) {
                    EmptyPanel(stats)
                } else {
                    StreakPanel(stats)
                    TopIntentsPanel(stats.window30d)
                    ActiveHoursPanel(stats.window30d)
                    SuccessRatePanel(lifetime = stats.lifetime, window30d = stats.window30d)
                    PersonalisationPanel(stats.personalisation)
                    LifetimeTallyPanel(stats.tally)
                }
            }
        }
    }
}

// ─── Panels ─────────────────────────────────────────────────────────

@Composable
private fun LoadingPanel() {
    StatsPanel {
        Text("📊 Stats", style = MaterialTheme.typography.titleMedium)
        PrivacyChip()
        Spacer(Modifier.width(0.dp))
        Text("Computing…", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EmptyPanel(stats: Stats) {
    StatsPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📊 Stats", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            PrivacyChip()
        }
        val day = stats.firstUseEpochMs?.let { daysSince(it) + 1 } ?: 1
        Text("Day $day with Wristotle", style = MaterialTheme.typography.titleSmall)
        Text(
            "Come back after using Wristotle for a few days — there isn't enough activity yet to surface anything interesting.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun StreakPanel(stats: Stats) {
    StatsPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📊 Stats", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            PrivacyChip()
        }
        val first = stats.firstUseEpochMs
        val dayLabel = if (first != null) "🔥 Day ${daysSince(first) + 1} with Wristotle"
                       else "🔥 Wristotle"
        Text(dayLabel, style = MaterialTheme.typography.titleSmall)

        val totals = "${formatInt(stats.lifetime.totalQueries)} voice queries · " +
            "${formatInt(stats.lifetime.wordsDictated)} words"
        Text(totals, style = MaterialTheme.typography.bodyMedium)

        val minutes = (stats.lifetime.wordsDictated / WORDS_PER_MINUTE).toInt()
        if (minutes > 0) {
            Text("≈ ${formatDuration(minutes)} of dictation", style = MaterialTheme.typography.bodySmall)
        }
        if (first != null) {
            Text(
                "Since ${shortDate(first)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TopIntentsPanel(window: Stats.Aggregate) {
    if (window.handlerCounts.isEmpty()) return
    StatsPanel {
        Text("Top intents (last 30 days)", style = MaterialTheme.typography.titleSmall)
        val top = window.handlerCounts
            .filter { it.handler !in HANDLER_BLOCKLIST }
            .take(TOP_INTENTS_LIMIT)
        if (top.isEmpty()) {
            Text("No usable intents in the last 30 days.", style = MaterialTheme.typography.bodySmall)
            return@StatsPanel
        }
        val max = top.maxOf { it.count }
        for (row in top) {
            BarLine(
                label = handlerDisplayName(row.handler),
                count = row.count,
                max = max,
            )
        }
    }
}

@Composable
private fun ActiveHoursPanel(window: Stats.Aggregate) {
    val hours = window.hourBuckets
    val days = window.dayOfWeekBuckets
    if (hours.sum() == 0) return
    StatsPanel {
        Text("When you talk to Wristotle", style = MaterialTheme.typography.titleSmall)
        val peakHour = hours.withIndex().maxByOrNull { it.value }?.index ?: 0
        Text("Peak hour: ${formatHour(peakHour)}", style = MaterialTheme.typography.bodyMedium)
        Text(renderHourStrip(hours),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
        Text("0          12          23",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        if (days.sum() > 0) {
            val busiestDay = days.withIndex().maxByOrNull { it.value }?.index ?: 0
            Text("Busiest day: ${DAY_LABELS[busiestDay]}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SuccessRatePanel(lifetime: Stats.Aggregate, window30d: Stats.Aggregate) {
    StatsPanel {
        Text("How well it works", style = MaterialTheme.typography.titleSmall)
        Text(
            "30-day: ${percent(window30d.successRate)}   ·   Lifetime: ${percent(lifetime.successRate)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        lifetime.avgNluConfidence?.let {
            Text(
                "Average NLU confidence: ${"%.2f".format(it)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val hardest = lifetime.handlerSuccess
            .filter { it.total >= HARDEST_MIN_SAMPLES && it.handler !in HANDLER_BLOCKLIST && it.rate != null }
            .minByOrNull { it.rate!! }
        if (hardest != null) {
            Text(
                "Hardest intent: ${handlerDisplayName(hardest.handler)} (${percent(hardest.rate)})",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PersonalisationPanel(p: Stats.Personalisation) {
    if (p.learnedNluPhrases == 0 && p.appAliases == 0 && p.contactAliases == 0) return
    StatsPanel {
        Text("Personalised for you", style = MaterialTheme.typography.titleSmall)
        Text(
            "${pluralN(p.learnedNluPhrases, "phrasing", "phrasings")} learned",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "${pluralN(p.appAliases, "app alias", "app aliases")} · " +
                "${pluralN(p.contactAliases, "contact alias", "contact aliases")}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun LifetimeTallyPanel(t: Stats.LifetimeTally) {
    StatsPanel {
        Text("Lifetime tally", style = MaterialTheme.typography.titleSmall)
        Text(
            "${pluralN(t.notes, "note", "notes")} · " +
                "${pluralN(t.tasksCompleted, "task done", "tasks done")}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "${pluralN(t.reminders, "reminder pending", "reminders pending")} · " +
                "${pluralN(t.alarms, "alarm", "alarms")}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ─── Building blocks ────────────────────────────────────────────────

@Composable
private fun StatsPanel(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
private fun PrivacyChip() {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            "on-device · private 🔒",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun BarLine(label: String, count: Int, max: Int) {
    val filled = if (max == 0) 0 else (count.toDouble() * BAR_WIDTH / max).toInt().coerceAtLeast(1)
    val bar = "█".repeat(filled) + "░".repeat(BAR_WIDTH - filled)
    Text(
        "$bar  ${label.padEnd(LABEL_WIDTH).take(LABEL_WIDTH)} ${formatInt(count).padStart(5)}",
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
    )
}

// ─── Pure helpers ───────────────────────────────────────────────────

private fun renderHourStrip(buckets: List<Int>): String {
    val max = buckets.max()
    if (max == 0) return " ".repeat(24)
    return buildString {
        for (n in buckets) append(densityChar(n, max))
    }
}

private fun densityChar(count: Int, max: Int): Char = when {
    count == 0 -> ' '
    count * 4 <= max -> '░'
    count * 2 <= max -> '▒'
    count * 4 <= max * 3 -> '▓'
    else -> '█'
}

private fun daysSince(epochMs: Long): Long {
    val deltaMs = System.currentTimeMillis() - epochMs
    return (deltaMs / DAY_MS).coerceAtLeast(0)
}

private fun shortDate(epochMs: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(epochMs))

private fun formatHour(hour: Int): String = when (hour) {
    0 -> "12 AM"
    in 1..11 -> "$hour AM"
    12 -> "12 PM"
    else -> "${hour - 12} PM"
}

private fun formatInt(n: Int): String = "%,d".format(n)
private fun formatInt(n: Long): String = "%,d".format(n)

private fun formatDuration(minutes: Int): String = when {
    minutes < 60 -> "${minutes}m"
    minutes < 60 * 24 -> "${minutes / 60}h ${minutes % 60}m"
    else -> "${minutes / (60 * 24)}d ${(minutes % (60 * 24)) / 60}h"
}

private fun percent(rate: Float?): String =
    rate?.let { "${(it * 100).toInt()}%" } ?: "—"

private fun pluralN(n: Int, singular: String, plural: String): String =
    "${formatInt(n)} ${if (n == 1) singular else plural}"

/**
 * Map a [com.lazydevs.wristotle.history.ConversationEntry.handler] tag
 * to a user-facing label. Title-cases unknown tags so a future handler
 * still renders readably instead of as raw `snake_case`.
 */
private fun handlerDisplayName(handler: String): String = HANDLER_NAMES[handler]
    ?: handler.split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

private val HANDLER_NAMES = mapOf(
    "call" to "Calls",
    "sms" to "SMS",
    "send_message" to "Messages",
    "reminder" to "Reminders",
    "list_reminders" to "List reminders",
    "cancel" to "Cancel reminder",
    "reschedule" to "Reschedule",
    "find_phone" to "Find phone",
    "time" to "Time",
    "world_time" to "World time",
    "battery" to "Battery",
    "vibrate" to "Vibrate",
    "steps" to "Steps",
    "set_alarm" to "Alarms",
    "cancel_alarm" to "Cancel alarm",
    "set_timer" to "Timer",
    "note" to "Notes",
    "append_note" to "Append note",
    "add_task" to "Tasks",
    "list_tasks" to "List tasks",
    "complete_task" to "Complete task",
    "delete_task" to "Delete task",
    "calendar" to "Calendar",
    "create_event" to "Create event",
    "weather" to "Weather",
    "calculate" to "Calculator",
    "open_app" to "Open app",
    "media_play" to "Play media",
    "media_pause" to "Pause media",
    "media_next" to "Skip track",
    "media_previous" to "Previous track",
    "media_seek_forward" to "Skip forward",
    "media_seek_backward" to "Skip backward",
    "morning_brief" to "Morning brief",
    "ask_agent" to "Ask Agent",
)

/** Handlers we exclude from "Top intents" + "Hardest intent" — they
 *  aren't user-meaningful labels in their own right. */
private val HANDLER_BLOCKLIST = setOf("unknown", "error")

private val DAY_LABELS = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

private const val EMPTY_THRESHOLD = 10
private const val TOP_INTENTS_LIMIT = 5
private const val HARDEST_MIN_SAMPLES = 5
private const val BAR_WIDTH = 14
private const val LABEL_WIDTH = 18
private const val WORDS_PER_MINUTE = 135L
private const val DAY_MS = 24L * 60 * 60 * 1000
