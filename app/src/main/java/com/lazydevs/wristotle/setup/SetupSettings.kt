package com.lazydevs.wristotle.setup

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistence for setup-flow state — currently just whether the
 * first-launch wizard has been dismissed.
 *
 * Lives in its **own** SharedPrefs file (`setup_state`) deliberately,
 * NOT alongside the other app preferences exported by
 * `BackupExporter.readPrefsBlock`. The dismissed flag never travels
 * in a backup — restoring on a fresh device re-shows the wizard so
 * the user gets nudged about whatever's actually missing in the new
 * environment (rather than skipping setup silently because the
 * previous device dismissed it).
 *
 * See `wristotle-companion/setup-flow.md` § "Backup interaction".
 */
class SetupSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _welcomeWizardDismissed = MutableStateFlow(prefs.getBoolean(KEY_DISMISSED, false))
    /** True once the user has either completed or explicitly skipped
     *  the welcome wizard. Drives the wizard's auto-show gate in
     *  MainActivity. */
    val welcomeWizardDismissed: StateFlow<Boolean> = _welcomeWizardDismissed.asStateFlow()

    /** Set by "Skip wizard" / "Done" / completing every step. */
    fun dismissWelcomeWizard() {
        if (_welcomeWizardDismissed.value) return
        prefs.edit().putBoolean(KEY_DISMISSED, true).apply()
        _welcomeWizardDismissed.value = true
    }

    /** Re-arms the wizard for the next launch. Triggered by the
     *  "Show welcome again" button in the 🌟 Setup card. */
    fun reshowWelcomeWizard() {
        if (!_welcomeWizardDismissed.value) return
        prefs.edit().putBoolean(KEY_DISMISSED, false).apply()
        _welcomeWizardDismissed.value = false
    }

    private companion object {
        const val PREFS_NAME = "setup_state"
        const val KEY_DISMISSED = "welcome_wizard_dismissed"
    }
}
