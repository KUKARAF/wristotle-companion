// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.briefing

/**
 * The sections a Morning Brief can surface, in render priority order
 * (the handler/renderer drops from the END when the brief overshoots the
 * watch's chat budget). Each carries a stable [key] persisted by
 * [com.lazydevs.wristotle.speech.nlu.settings.MorningBriefSettings] (never
 * rename) and a user-facing [label] for the Settings picker.
 */
enum class BriefSection(val key: String, val label: String) {
    MEETINGS("meetings", "Meetings"),
    ALARMS("alarms", "Alarms"),
    // Covers ALL active notifications, not just messaging apps: the renderer
    // shows "Messages: N from <app>…" for recognised messaging apps and
    // "Notifications: N other" for the rest — both gated by this one section.
    MESSAGES("messages", "Messages & notifications"),
    REMINDERS("reminders", "Reminders"),
    TASKS("tasks", "Tasks"),
    NOTES("notes", "Notes");

    companion object {
        fun fromKey(key: String): BriefSection? = entries.firstOrNull { it.key == key }
    }
}
