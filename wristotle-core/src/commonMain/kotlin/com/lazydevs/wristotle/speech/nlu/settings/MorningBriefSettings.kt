// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.settings

import com.lazydevs.wristotle.speech.nlu.briefing.BriefSection
import com.lazydevs.wristotle.speech.nlu.store.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Which [BriefSection]s the Morning Brief includes (Settings → 🔔 Notifications,
 * alongside the brief's notification-log toggle). The handler consults [isEnabled] before both FETCHING and
 * rendering a section, so a disabled section also skips its IO — e.g. turning
 * off Messages drops the Notification-Access read entirely.
 *
 * Stored as the set of DISABLED section keys. Unlike [CardSettings], a fresh
 * install includes EVERYTHING (empty disabled set) — the brief is opt-out, not
 * opt-in. Because only explicit keys are persisted, a section added to
 * [BriefSection] in a later release is auto-included rather than silently
 * hidden. Companion-local; not mirrored to the watch.
 */
class MorningBriefSettings(private val store: KeyValueStore) {

    private val _disabled = MutableStateFlow(readDisabled())
    /** Section keys the user has turned OFF. Everything not in here is included. */
    val disabledSections: StateFlow<Set<String>> = _disabled

    /** Gate the handler checks before fetching + rendering a section. Unknown
     *  (future) sections are included — only explicitly-disabled ones are hidden. */
    fun isEnabled(section: BriefSection): Boolean = section.key !in _disabled.value

    fun setEnabled(section: BriefSection, on: Boolean) {
        val current = _disabled.value
        val next = if (on) current - section.key else current + section.key
        if (current == next) return
        persist(next)
    }

    /** Bulk replace from the set of sections that should be ON — used by the
     *  Settings card's "Select all" / "Deselect all" buttons. */
    fun setEnabledSections(enabled: Set<BriefSection>) {
        val allKeys = BriefSection.entries.map { it.key }.toSet()
        persist(allKeys - enabled.map { it.key }.toSet())
    }

    /** All currently-disabled section keys, for Backup. */
    fun snapshotDisabled(): Set<String> = _disabled.value

    /** Restore from a Backup blob. */
    fun restoreDisabled(disabled: Set<String>) = persist(disabled)

    private fun persist(disabled: Set<String>) {
        if (_disabled.value == disabled) return
        store.putString(KEY_DISABLED, disabled.joinToString(","))
        _disabled.value = disabled
    }

    private fun readDisabled(): Set<String> {
        // Absent key (fresh install) → empty disabled = everything on. Once the
        // user touches the picker the stored value is honoured, including an
        // explicit empty (= all on). The sentinel distinguishes "never written"
        // from "written as all-on" — both happen to be empty here, but keeping
        // the sentinel mirrors CardSettings and guards future default changes.
        val raw = store.getString(KEY_DISABLED, UNSET)
        if (raw == UNSET) return emptySet()
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    companion object {
        const val PREFS_NAME = "morning_brief_settings"
        private const val KEY_DISABLED = "disabled_sections"
        /** Marks "key never written" so the default-stance logic in [readDisabled]
         *  can change without colliding with an explicit empty set. */
        private const val UNSET = " __unset__"
    }
}
