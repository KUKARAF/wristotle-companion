// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.settings

import com.lazydevs.wristotle.speech.nlu.transport.sendBoolSetting
import com.lazydevs.wristotle.speech.nlu.transport.sendIntSetting
import com.lazydevs.wristotle.speech.nlu.transport.sendSettingsRequest
import com.lazydevs.wristotle.logging.WristotleLog as Log
import com.lazydevs.wristotle.speech.nlu.transport.MessageKeys
import com.lazydevs.wristotle.speech.nlu.transport.WatchTransport
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI-facing state of the watch's settings mirror.
 *
 *  - [Unknown]            cold start, no request issued yet
 *  - [Loading]            REQUEST_SETTINGS sent, waiting on the snapshot
 *  - [Loaded]             snapshot received; values are live
 *  - [Stale]              we have last-known values but the most recent
 *                         refresh didn't complete cleanly — surface the
 *                         reason so the UI can render the right hint
 *                         (watch disconnected, watch app too old, etc.)
 *
 * [Stale.last] is null on the first failed refresh and populated thereafter,
 * so the UI can keep showing the previous snapshot while warning.
 */
sealed class WatchSettingsState {
    object Unknown : WatchSettingsState()
    object Loading : WatchSettingsState()
    data class Loaded(val settings: WatchSettings) : WatchSettingsState()
    data class Stale(val last: WatchSettings?, val reason: StaleReason) : WatchSettingsState()
}

enum class StaleReason {
    /** REQUEST_SETTINGS NACKed by the watch — usually means disconnected. */
    NotConnected,
    /** ACKed but no response within [WatchSettingsRepository.RESPONSE_TIMEOUT_MS].
     *  Most likely cause: watch app predates REQUEST_SETTINGS support. */
    OldWatchApp,
}

/**
 * Cache + control plane for the watch-settings mirror UI.
 *
 * Flow:
 *  1. [refresh] sends REQUEST_SETTINGS and starts a timeout. State goes
 *     from current → [Loading].
 *  2. The watch responds with one AppMessage containing all 10 setting
 *     tuples. [PebbleListenerService] detects that and forwards the dict
 *     to [ingest], which decodes into [WatchSettings] and emits [Loaded].
 *  3. If the timeout fires first, emit [Stale(reason = OldWatchApp)] —
 *     watch ACKed but didn't respond, which is the older-app signature.
 *  4. [applyBool] / [applyInt] write back to the watch and optimistically
 *     update the local cache so the toggle/dropdown responds instantly.
 *     The watch will echo the change in its next snapshot, but we don't
 *     wait for that to render the new value.
 *
 *  Coroutine ownership: callers (the ViewModel) pass in a scope so the
 *  repository can be application-scoped without owning its own
 *  CoroutineScope. Outbound sends use Dispatchers.IO via the transport.
 */
class WatchSettingsRepository(
    private val transport: WatchTransport,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<WatchSettingsState>(WatchSettingsState.Unknown)
    val state: StateFlow<WatchSettingsState> = _state.asStateFlow()

    private var timeoutJob: Job? = null

    fun refresh() {
        val current = _state.value
        val previous: WatchSettings? = when (current) {
            is WatchSettingsState.Loaded -> current.settings
            is WatchSettingsState.Stale -> current.last
            else -> null
        }
        _state.value = WatchSettingsState.Loading
        timeoutJob?.cancel()
        scope.launch(Dispatchers.IO) {
            runCatching { transport.sendSettingsRequest() }
                .onFailure {
                    Log.w(TAG, "sendSettingsRequest threw", it)
                    _state.value = WatchSettingsState.Stale(previous, StaleReason.NotConnected)
                    return@launch
                }
            // Send returned without throwing; start the response watchdog.
            // If the watch app is too old to know REQUEST_SETTINGS, the send
            // still ACKs but no response message arrives — that's the
            // OldWatchApp signature.
            timeoutJob = scope.launch {
                delay(RESPONSE_TIMEOUT_MS)
                if (_state.value is WatchSettingsState.Loading) {
                    Log.w(TAG, "settings response timed out — assume old watch app")
                    _state.value = WatchSettingsState.Stale(previous, StaleReason.OldWatchApp)
                }
            }
        }
    }

    /** True if [data] carries any of the 10 settings tuples — entry point
     *  from [PebbleListenerService.onMessageReceived] (cheap shortcut so the
     *  listener doesn't have to drag the full decode logic into its switch). */
    fun isSettingsMessage(data: PebbleDictionary): Boolean =
        MessageKeys.SETTING_KEYS.any { data[it] != null }

    /** Decodes a settings-bearing dict into [WatchSettings] and emits Loaded. */
    fun ingest(data: PebbleDictionary) {
        timeoutJob?.cancel()
        timeoutJob = null
        val previous: WatchSettings? = when (val s = _state.value) {
            is WatchSettingsState.Loaded -> s.settings
            is WatchSettingsState.Stale -> s.last
            else -> null
        }
        val merged = WatchSettings(
            loggingEnabled            = data.boolSetting(MessageKeys.SETTING_LOGGING)                 ?: previous?.loggingEnabled            ?: false,
            dictationConfirmation     = data.boolSetting(MessageKeys.SETTING_DICTATION_CONFIRMATION)  ?: previous?.dictationConfirmation     ?: true,
            findPhoneTarget           = data.intSetting (MessageKeys.SETTING_FIND_PHONE_TARGET)       ?: previous?.findPhoneTarget           ?: TARGET_AUTO,
            remindersTarget           = data.intSetting (MessageKeys.SETTING_REMINDERS_TARGET)        ?: previous?.remindersTarget           ?: TARGET_AUTO,
            cancelTarget              = data.intSetting (MessageKeys.SETTING_CANCEL_TARGET)           ?: previous?.cancelTarget              ?: TARGET_AUTO,
            quickLaunchAutoExitSeconds= data.intSetting (MessageKeys.SETTING_QUICK_LAUNCH_AUTO_EXIT)  ?: previous?.quickLaunchAutoExitSeconds?: 5,
            codeDisplaySeconds        = data.intSetting (MessageKeys.SETTING_CODE_DISPLAY_SECONDS)    ?: previous?.codeDisplaySeconds        ?: 60,
            vibrateOnLaunch           = data.boolSetting(MessageKeys.SETTING_VIBRATE_ON_LAUNCH)       ?: previous?.vibrateOnLaunch           ?: true,
            vibrateOnQuickLaunch      = data.boolSetting(MessageKeys.SETTING_VIBRATE_ON_QUICK_LAUNCH) ?: previous?.vibrateOnQuickLaunch      ?: true,
            vibrateRespectQuiet       = data.boolSetting(MessageKeys.SETTING_VIBRATE_RESPECT_QUIET)   ?: previous?.vibrateRespectQuiet       ?: true,
            skipRetryDialog           = data.boolSetting(MessageKeys.SETTING_SKIP_RETRY_DIALOG)       ?: previous?.skipRetryDialog           ?: true,
            quickLaunchAction         = data.intSetting (MessageKeys.SETTING_QUICK_LAUNCH_ACTION)     ?: previous?.quickLaunchAction         ?: MessageKeys.QUICK_LAUNCH_ACTION_DICTATE,
            confirmBeforeSend         = data.boolSetting(MessageKeys.SETTING_CONFIRM_BEFORE_SEND)    ?: previous?.confirmBeforeSend         ?: false,
            confirmTimeoutSeconds     = data.intSetting (MessageKeys.SETTING_CONFIRM_TIMEOUT_SECONDS)?: previous?.confirmTimeoutSeconds     ?: 15,
            confirmDefaultSend        = data.boolSetting(MessageKeys.SETTING_CONFIRM_DEFAULT_SEND)   ?: previous?.confirmDefaultSend        ?: false,
            longPressUpAction         = data.intSetting (MessageKeys.SETTING_LONG_PRESS_UP_ACTION)   ?: previous?.longPressUpAction         ?: MessageKeys.BUTTON_ACTION_MENU,
            longPressDownAction       = data.intSetting (MessageKeys.SETTING_LONG_PRESS_DOWN_ACTION) ?: previous?.longPressDownAction       ?: MessageKeys.BUTTON_ACTION_TASKS,
            selectAction              = data.intSetting (MessageKeys.SETTING_SELECT_ACTION)          ?: previous?.selectAction              ?: MessageKeys.BUTTON_ACTION_DICTATION,
            colorTheme                = data.boolSetting(MessageKeys.SETTING_COLOR_THEME)            ?: previous?.colorTheme                ?: false,
        )
        _state.value = WatchSettingsState.Loaded(merged)
    }

    /** Clears the cached snapshot back to [WatchSettingsState.Unknown]. Called
     *  when the Settings page is left so the section re-fetches fresh on the
     *  next visit instead of showing a possibly-stale snapshot. */
    fun reset() {
        timeoutJob?.cancel()
        timeoutJob = null
        _state.value = WatchSettingsState.Unknown
    }

    /**
     * Writes [updated] to the watch. Diffs against the currently-cached
     * snapshot and only sends the keys that actually changed (the user
     * typically edits one or two). Optimistically promotes the cache to
     * [updated] so the UI reflects the saved values immediately; the watch's
     * next snapshot reconciles if a send was dropped.
     */
    fun save(updated: WatchSettings) {
        val baseline: WatchSettings? = when (val s = _state.value) {
            is WatchSettingsState.Loaded -> s.settings
            is WatchSettingsState.Stale -> s.last
            else -> null
        }
        _state.value = WatchSettingsState.Loaded(updated)
        scope.launch(Dispatchers.IO) {
            suspend fun bool(key: UInt, old: Boolean?, new: Boolean) {
                if (old == new) return
                val ok = runCatching { transport.sendBoolSetting(key, new) }
                    .getOrElse { Log.w(TAG, "send bool $key threw", it); false }
                Log.d(TAG, "wrote bool key=$key value=$new acked=$ok")
            }
            suspend fun int(key: UInt, old: Int?, new: Int) {
                if (old == new) return
                val ok = runCatching { transport.sendIntSetting(key, new) }
                    .getOrElse { Log.w(TAG, "send int $key threw", it); false }
                Log.d(TAG, "wrote int key=$key value=$new acked=$ok")
            }
            bool(MessageKeys.SETTING_LOGGING, baseline?.loggingEnabled, updated.loggingEnabled)
            bool(MessageKeys.SETTING_DICTATION_CONFIRMATION, baseline?.dictationConfirmation, updated.dictationConfirmation)
            int(MessageKeys.SETTING_FIND_PHONE_TARGET, baseline?.findPhoneTarget, updated.findPhoneTarget)
            int(MessageKeys.SETTING_REMINDERS_TARGET, baseline?.remindersTarget, updated.remindersTarget)
            int(MessageKeys.SETTING_CANCEL_TARGET, baseline?.cancelTarget, updated.cancelTarget)
            int(MessageKeys.SETTING_QUICK_LAUNCH_AUTO_EXIT, baseline?.quickLaunchAutoExitSeconds, updated.quickLaunchAutoExitSeconds)
            int(MessageKeys.SETTING_CODE_DISPLAY_SECONDS, baseline?.codeDisplaySeconds, updated.codeDisplaySeconds)
            bool(MessageKeys.SETTING_VIBRATE_ON_LAUNCH, baseline?.vibrateOnLaunch, updated.vibrateOnLaunch)
            bool(MessageKeys.SETTING_VIBRATE_ON_QUICK_LAUNCH, baseline?.vibrateOnQuickLaunch, updated.vibrateOnQuickLaunch)
            bool(MessageKeys.SETTING_VIBRATE_RESPECT_QUIET, baseline?.vibrateRespectQuiet, updated.vibrateRespectQuiet)
            bool(MessageKeys.SETTING_SKIP_RETRY_DIALOG, baseline?.skipRetryDialog, updated.skipRetryDialog)
            int(MessageKeys.SETTING_QUICK_LAUNCH_ACTION, baseline?.quickLaunchAction, updated.quickLaunchAction)
            bool(MessageKeys.SETTING_CONFIRM_BEFORE_SEND, baseline?.confirmBeforeSend, updated.confirmBeforeSend)
            int(MessageKeys.SETTING_CONFIRM_TIMEOUT_SECONDS, baseline?.confirmTimeoutSeconds, updated.confirmTimeoutSeconds)
            bool(MessageKeys.SETTING_CONFIRM_DEFAULT_SEND, baseline?.confirmDefaultSend, updated.confirmDefaultSend)
            int(MessageKeys.SETTING_LONG_PRESS_UP_ACTION, baseline?.longPressUpAction, updated.longPressUpAction)
            int(MessageKeys.SETTING_LONG_PRESS_DOWN_ACTION, baseline?.longPressDownAction, updated.longPressDownAction)
            int(MessageKeys.SETTING_SELECT_ACTION, baseline?.selectAction, updated.selectAction)
            bool(MessageKeys.SETTING_COLOR_THEME, baseline?.colorTheme, updated.colorTheme)
        }
    }

    companion object {
        private const val TAG = "WatchSettingsRepo"
        const val RESPONSE_TIMEOUT_MS = 5_000L

        const val TARGET_AUTO: Int = 0
        const val TARGET_COMPANION: Int = 1
        const val TARGET_PKJS: Int = 2
    }
}

/**
 * Watch sends booleans as `uint8` (PebbleDictionaryItem.UInt8) and target
 * enums + auto-exit seconds as `int32`. PebbleKit2 doesn't widen smaller
 * uints to UInt32 (unlike older PebbleKit), so read the exact wire type.
 */
private fun PebbleDictionary.boolSetting(key: UInt): Boolean? {
    val item = this[key] ?: return null
    return when (item) {
        is PebbleDictionaryItem.UInt8  -> item.value.toInt() != 0
        is PebbleDictionaryItem.UInt32 -> item.value != 0u
        is PebbleDictionaryItem.Int32  -> item.value != 0
        is PebbleDictionaryItem.Int8   -> item.value.toInt() != 0
        else -> null
    }
}

private fun PebbleDictionary.intSetting(key: UInt): Int? {
    val item = this[key] ?: return null
    return when (item) {
        is PebbleDictionaryItem.Int32  -> item.value
        is PebbleDictionaryItem.UInt32 -> item.value.toInt()
        is PebbleDictionaryItem.UInt8  -> item.value.toInt()
        is PebbleDictionaryItem.Int8   -> item.value.toInt()
        else -> null
    }
}