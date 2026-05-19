package com.lazydevs.wristotle.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.settings.WatchSettings
import com.lazydevs.wristotle.settings.WatchSettingsRepository
import com.lazydevs.wristotle.settings.WatchSettingsState
import com.lazydevs.wristotle.transport.MessageKeys
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin façade over [WatchSettingsRepository] for the Compose layer.
 * Owns no state of its own — all reads go through `repo.state`, all
 * writes go through `repo.applyBool` / `repo.applyInt`. The
 * repository performs optimistic updates so callbacks here don't
 * need to await round-trips.
 */
class WatchSettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: WatchSettingsRepository =
        (app as WristotleApplication).watchSettingsRepository

    val state: StateFlow<WatchSettingsState> = repo.state

    fun refresh() = repo.refresh()

    fun setLoggingEnabled(v: Boolean) =
        repo.applyBool(MessageKeys.SETTING_LOGGING, v) { it.copy(loggingEnabled = v) }

    fun setDictationConfirmation(v: Boolean) =
        repo.applyBool(MessageKeys.SETTING_DICTATION_CONFIRMATION, v) {
            it.copy(dictationConfirmation = v)
        }

    fun setFindPhoneTarget(v: Int) =
        repo.applyInt(MessageKeys.SETTING_FIND_PHONE_TARGET, v) { it.copy(findPhoneTarget = v) }

    fun setRemindersTarget(v: Int) =
        repo.applyInt(MessageKeys.SETTING_REMINDERS_TARGET, v) { it.copy(remindersTarget = v) }

    fun setCancelTarget(v: Int) =
        repo.applyInt(MessageKeys.SETTING_CANCEL_TARGET, v) { it.copy(cancelTarget = v) }

    fun setQuickLaunchAutoExitSeconds(v: Int) =
        repo.applyInt(MessageKeys.SETTING_QUICK_LAUNCH_AUTO_EXIT, v) {
            it.copy(quickLaunchAutoExitSeconds = v)
        }

    fun setVibrateOnLaunch(v: Boolean) =
        repo.applyBool(MessageKeys.SETTING_VIBRATE_ON_LAUNCH, v) { it.copy(vibrateOnLaunch = v) }

    fun setVibrateOnQuickLaunch(v: Boolean) =
        repo.applyBool(MessageKeys.SETTING_VIBRATE_ON_QUICK_LAUNCH, v) {
            it.copy(vibrateOnQuickLaunch = v)
        }

    fun setVibrateRespectQuiet(v: Boolean) =
        repo.applyBool(MessageKeys.SETTING_VIBRATE_RESPECT_QUIET, v) {
            it.copy(vibrateRespectQuiet = v)
        }

    fun setSkipRetryDialog(v: Boolean) =
        repo.applyBool(MessageKeys.SETTING_SKIP_RETRY_DIALOG, v) { it.copy(skipRetryDialog = v) }
}

/** Stable label/value pair for the routing-target dropdowns. */
data class TargetChoice(val label: String, val value: Int)

/** Stable label/value pair for the quick-launch auto-exit dropdown. */
data class AutoExitChoice(val label: String, val seconds: Int)
