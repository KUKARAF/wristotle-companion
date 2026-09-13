// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.settings

import com.lazydevs.wristotle.speech.nlu.store.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistence for setup-flow state — currently just whether the
 * first-launch wizard has been dismissed.
 *
 * Lives in its **own** SharedPrefs file (`setup_state`) deliberately,
 * NOT alongside the other app preferences exported by BackupExporter.
 * The dismissed flag never travels in a backup — restoring on a fresh
 * device re-shows the wizard so the user gets nudged about whatever's
 * actually missing in the new environment.
 *
 * R4 batch 10 — lifted from :app onto the [KeyValueStore] seam.
 */
class SetupSettings(private val store: KeyValueStore) {

    private val _welcomeWizardDismissed = MutableStateFlow(store.getBoolean(KEY_DISMISSED, false))
    val welcomeWizardDismissed: StateFlow<Boolean> = _welcomeWizardDismissed.asStateFlow()

    fun dismissWelcomeWizard() {
        if (_welcomeWizardDismissed.value) return
        store.putBoolean(KEY_DISMISSED, true)
        _welcomeWizardDismissed.value = true
    }

    fun reshowWelcomeWizard() {
        if (!_welcomeWizardDismissed.value) return
        store.putBoolean(KEY_DISMISSED, false)
        _welcomeWizardDismissed.value = false
    }

    private val _settingsShowAdvanced = MutableStateFlow(store.getBoolean(KEY_SHOW_ADVANCED, false))
    /** The Settings landing shows only the core categories by default and keeps
     *  the advanced ones (integrations, experimental, diagnostics) collapsed
     *  behind a toggle, so a new user isn't met with a wall of ~20 categories.
     *  A power user flips this once and it sticks. */
    val settingsShowAdvanced: StateFlow<Boolean> = _settingsShowAdvanced.asStateFlow()

    fun setSettingsShowAdvanced(show: Boolean) {
        if (_settingsShowAdvanced.value == show) return
        store.putBoolean(KEY_SHOW_ADVANCED, show)
        _settingsShowAdvanced.value = show
    }

    private val _useClassicSettingsLayout = MutableStateFlow(store.getBoolean(KEY_CLASSIC_LAYOUT, false))
    /** The Settings landing groups categories under section headers and folds a
     *  few near-duplicate categories into their parent (the "grouped" layout,
     *  default). Opting into classic restores the pre-grouping flat list +
     *  "Show advanced" toggle for users who prefer it. */
    val useClassicSettingsLayout: StateFlow<Boolean> = _useClassicSettingsLayout.asStateFlow()

    fun setUseClassicSettingsLayout(classic: Boolean) {
        if (_useClassicSettingsLayout.value == classic) return
        store.putBoolean(KEY_CLASSIC_LAYOUT, classic)
        _useClassicSettingsLayout.value = classic
    }

    companion object {
        const val PREFS_NAME = "setup_state"
        private const val KEY_DISMISSED = "welcome_wizard_dismissed"
        private const val KEY_SHOW_ADVANCED = "settings_show_advanced"
        private const val KEY_CLASSIC_LAYOUT = "settings_classic_layout"
    }
}
