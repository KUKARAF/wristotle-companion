// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
                    StumblesPanel(stats.lifetime)
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
        if (first != null) {
            Text(
                "Since ${shortDate(first)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        val minutes = (stats.lifetime.wordsDictated / WORDS_PER_MINUTE).toInt()
        StatTileRow(
            tiles = buildList {
                add(StatTileSpec(formatInt(stats.lifetime.totalQueries), "queries"))
                add(StatTileSpec(formatInt(stats.lifetime.wordsDictated), "words"))
                if (minutes > 0) add(StatTileSpec(formatDuration(minutes), "dictated"))
            },
        )
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

        HourBarChart(hours)

        Row(modifier = Modifier.fillMaxWidth()) {
            Text("12 AM", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text("12 PM", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Text("11 PM", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }

        if (days.sum() > 0) {
            val busiestDay = days.withIndex().maxByOrNull { it.value }?.index ?: 0
            Text("Busiest day: ${DAY_LABELS[busiestDay]}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * 24-bar chart, one bar per hour-of-day in local time. Each bar takes
 * equal width via Row weighting so the chart aligns to the labels
 * underneath regardless of font metrics. Empty hours render as a thin
 * baseline so the column structure stays visible.
 */
@Composable
private fun HourBarChart(hours: List<Int>) {
    val max = hours.max().coerceAtLeast(1)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAR_CHART_HEIGHT_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (count in hours) {
            val fraction = if (count == 0) BAR_EMPTY_FRACTION else (count.toFloat() / max).coerceAtLeast(BAR_MIN_FILLED_FRACTION)
            val color = if (count == 0) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.primary
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(fraction)
                    .background(color, RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun SuccessRatePanel(lifetime: Stats.Aggregate, window30d: Stats.Aggregate) {
    StatsPanel {
        Text("How well it works", style = MaterialTheme.typography.titleSmall)
        StatTileRow(
            tiles = buildList {
                add(StatTileSpec(percent(window30d.successRate), "last 30 days"))
                add(StatTileSpec(percent(lifetime.successRate), "lifetime"))
                lifetime.avgNluConfidence?.let {
                    add(StatTileSpec("%.2f".format(it), "NLU confidence"))
                }
            },
        )
    }
}

/**
 * Top-3 lifetime intents with the lowest success rate — surfaced as
 * a dedicated panel so the user can see *what* to teach Wristotle
 * about, not just a single anonymous "hardest intent" tag.
 *
 * Each row carries the success rate as a severity-tinted bar (error
 * / tertiary / primary depending on the band), the absolute breakdown
 * ("X of Y succeeded"), and a percentage on the right. We hide the
 * panel entirely when there isn't enough data to be useful
 * (every candidate handler needs ≥[HARDEST_MIN_SAMPLES] queries).
 */
@Composable
private fun StumblesPanel(lifetime: Stats.Aggregate) {
    val hardest = lifetime.handlerSuccess
        .filter { it.total >= HARDEST_MIN_SAMPLES && it.handler !in HANDLER_BLOCKLIST && it.rate != null }
        .sortedBy { it.rate!! }
        .take(STUMBLES_LIMIT)
    if (hardest.isEmpty()) return
    StatsPanel {
        Text("Where Wristotle stumbles", style = MaterialTheme.typography.titleSmall)
        Text(
            "Lifetime intents with the lowest success rate. Add phrasings under Settings → 🎓 Learning to improve.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (h in hardest) {
            StumbleRow(handler = h.handler, total = h.total, successful = h.successful, rate = h.rate!!)
        }
    }
}

@Composable
private fun StumbleRow(handler: String, total: Int, successful: Int, rate: Float) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(INTENT_BAR_WIDTH_DP.dp)
                .height(INTENT_BAR_HEIGHT_DP.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp)),
        ) {
            val fillColor = when {
                rate < SEVERITY_HIGH -> MaterialTheme.colorScheme.error
                rate < SEVERITY_LOW -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.primary
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth(rate.coerceAtLeast(INTENT_BAR_MIN_FRACTION))
                    .fillMaxHeight()
                    .background(fillColor, RoundedCornerShape(3.dp)),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(handlerDisplayName(handler), style = MaterialTheme.typography.bodyMedium)
            Text(
                "$successful of $total succeeded",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            percent(rate),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun PersonalisationPanel(p: Stats.Personalisation) {
    if (p.learnedNluPhrases == 0 && p.appAliases == 0 && p.contactAliases == 0) return
    StatsPanel {
        Text("Personalised for you", style = MaterialTheme.typography.titleSmall)
        StatTileRow(
            tiles = listOf(
                StatTileSpec(formatInt(p.learnedNluPhrases), "phrasings"),
                StatTileSpec(formatInt(p.appAliases), "app aliases"),
                StatTileSpec(formatInt(p.contactAliases), "contact aliases"),
            ),
        )
    }
}

@Composable
private fun LifetimeTallyPanel(t: Stats.LifetimeTally) {
    StatsPanel {
        // "On your phone" is an intentionally honest name — these are
        // current-state counts (deleting a task or cancelling a
        // reminder reduces them), not lifetime-create totals. See
        // RoomStatsSource — every input here comes from a "right now"
        // query, never from a persistent counter.
        Text("On your phone", style = MaterialTheme.typography.titleSmall)
        StatTileRow(
            tiles = listOf(
                StatTileSpec(formatInt(t.notes), "notes"),
                StatTileSpec(formatInt(t.tasksCompleted), "tasks done"),
                StatTileSpec(formatInt(t.reminders), "reminders"),
                StatTileSpec(formatInt(t.alarms), "alarms"),
            ),
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

/**
 * One stat tile — big number on top, small label underneath. Used by
 * panels that have 2-4 datapoints to surface. The big-number-first
 * shape reads faster than narrative text and keeps the panels
 * uniform without dropping any data.
 */
private data class StatTileSpec(val value: String, val label: String)

@Composable
private fun StatTileRow(tiles: List<StatTileSpec>) {
    if (tiles.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        for (t in tiles) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    t.value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    t.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
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

/**
 * One row of the "Top intents" panel: a fixed-width horizontal bar
 * (filled proportionally to count/max), the handler's display label
 * taking the rest of the width, and a right-aligned count. All three
 * use Row weighting / fixed widths so they line up across rows
 * regardless of label length or font metrics.
 */
@Composable
private fun BarLine(label: String, count: Int, max: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(INTENT_BAR_WIDTH_DP.dp)
                .height(INTENT_BAR_HEIGHT_DP.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp)),
        ) {
            val fraction = if (max == 0) 0f
                           else (count.toFloat() / max).coerceAtLeast(INTENT_BAR_MIN_FRACTION)
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            formatInt(count),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
        )
    }
}

// ─── Pure helpers ───────────────────────────────────────────────────

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
private const val WORDS_PER_MINUTE = 135L
private const val DAY_MS = 24L * 60 * 60 * 1000
private const val BAR_CHART_HEIGHT_DP = 48
private const val BAR_EMPTY_FRACTION = 0.05f
private const val BAR_MIN_FILLED_FRACTION = 0.12f
private const val INTENT_BAR_WIDTH_DP = 96
private const val INTENT_BAR_HEIGHT_DP = 10
private const val INTENT_BAR_MIN_FRACTION = 0.04f
private const val STUMBLES_LIMIT = 3
private const val SEVERITY_HIGH = 0.5f
private const val SEVERITY_LOW = 0.75f
