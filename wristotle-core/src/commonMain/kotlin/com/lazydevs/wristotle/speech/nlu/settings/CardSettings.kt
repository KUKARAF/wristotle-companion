// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.settings

import com.lazydevs.wristotle.speech.nlu.store.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Per-card-kind on/off for the watch's full-screen cards (Settings → ⌚ Watch).
 *
 * Each carded response (sports lookups + reminder / task / note CRUD) tags its
 * reply with a `card_kind`. The dispatch site ([com.lazydevs.wristotle.speech.nlu.handler.HandlerRegistry])
 * consults [isEnabled] before forwarding it, so a disabled kind drops the card
 * and the response shows as a plain chat bubble instead.
 *
 * Stored as the set of DISABLED kinds (default empty ⇒ every card on). Storing
 * the *off* set — rather than the on set — means a brand-new card kind shipped
 * by a later release is on by default; only kinds the user has explicitly
 * turned off are suppressed (no sentinel needed). Companion-local; not mirrored
 * to the watch — the card-vs-bubble choice is made here.
 */
class CardSettings(private val store: KeyValueStore) {

    private val _disabled = MutableStateFlow(readDisabled())
    /** Card kinds the user has turned OFF. Everything not in here is shown. */
    val disabledKinds: StateFlow<Set<String>> = _disabled

    /** Gate the dispatch site checks before forwarding a `card_kind`. Unknown
     *  (future) kinds are allowed — only explicitly-disabled kinds are hidden. */
    fun isEnabled(kind: String): Boolean = kind !in _disabled.value

    fun setEnabled(kind: String, on: Boolean) {
        val current = _disabled.value
        val next = if (on) current - kind else current + kind
        if (current == next) return
        persist(next)
    }

    /** Bulk replace from the set of kinds that should be ON (out of [all]) —
     *  used by the Settings card's "Show all" / "Hide all" buttons. */
    fun setEnabledKinds(enabled: Set<String>, all: Set<String> = ALL_KINDS) {
        persist(all - enabled)
    }

    /** All currently-disabled kinds, for Backup. */
    fun snapshotDisabled(): Set<String> = _disabled.value

    /** Restore from a Backup blob. */
    fun restoreDisabled(disabled: Set<String>) = persist(disabled)

    private fun persist(disabled: Set<String>) {
        if (_disabled.value == disabled) return
        store.putString(KEY_DISABLED, disabled.joinToString(","))
        _disabled.value = disabled
    }

    private fun readDisabled(): Set<String> =
        store.getString(KEY_DISABLED, "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    companion object {
        const val PREFS_NAME = "card_settings"
        private const val KEY_DISABLED = "disabled_kinds"

        // Sports
        const val SPORT_SCORE = "sport_score"
        const val SPORT_STANDINGS = "sport_standings"
        const val SPORT_FIXTURE = "sport_fixture"

        // Reminders
        const val REMINDER_CREATE = "reminder_create"
        const val REMINDER_RESCHEDULE = "reminder_reschedule"
        const val REMINDER_CANCEL = "reminder_cancel"
        const val REMINDER_LIST = "reminder_list"

        // Tasks
        const val TASK_CREATE = "task_create"
        const val TASK_COMPLETE = "task_complete"
        const val TASK_DELETE = "task_delete"
        const val TASK_LIST = "task_list"

        // Notes
        const val NOTE_CREATE = "note_create"
        const val NOTE_UPDATE = "note_update"

        // Agent
        const val AGENT_ANSWER = "agent_answer"

        // Alarms
        const val ALARM_CREATE = "alarm_create"
        const val ALARM_CANCEL = "alarm_cancel"

        // Meetings (calendar)
        const val MEETING_CREATE = "meeting_create"
        const val MEETING_LIST = "meeting_list"

        // Weather (rich widget)
        const val WEATHER_CURRENT = "weather_current"

        // Morning brief
        const val BRIEF_READ = "brief_read"

        /**
         * Every user-toggleable card kind. `sport_text` (the F1 / cricket /
         * "no games" sports fallback) is intentionally omitted — it rides on
         * whether sports cards make sense at all and stays on.
         */
        val ALL_KINDS: Set<String> = setOf(
            SPORT_SCORE, SPORT_STANDINGS, SPORT_FIXTURE,
            REMINDER_CREATE, REMINDER_RESCHEDULE, REMINDER_CANCEL, REMINDER_LIST,
            TASK_CREATE, TASK_COMPLETE, TASK_DELETE, TASK_LIST,
            NOTE_CREATE, NOTE_UPDATE,
            AGENT_ANSWER,
            ALARM_CREATE, ALARM_CANCEL,
            MEETING_CREATE, MEETING_LIST,
            WEATHER_CURRENT,
            BRIEF_READ,
        )
    }
}
