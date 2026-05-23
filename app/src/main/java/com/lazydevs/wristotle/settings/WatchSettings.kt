package com.lazydevs.wristotle.settings

/**
 * Snapshot of the 10 watch-persisted settings that the companion mirrors.
 * Source of truth is the watch app's `persistent_storage` — this is a
 * cached view that the [WatchSettingsRepository] populates from
 * REQUEST_SETTINGS responses and stays in sync with via subsequent
 * incoming AppMessages.
 *
 * `*Target` fields use the watch's `MsgTarget` enum wire values
 * (0 = AUTO, 1 = COMPANION, 2 = PKJS) — see `config/config.h` in the
 * watch repo.
 */
data class WatchSettings(
    val loggingEnabled: Boolean,
    val dictationConfirmation: Boolean,
    val findPhoneTarget: Int,
    val remindersTarget: Int,
    val cancelTarget: Int,
    val quickLaunchAutoExitSeconds: Int,
    val vibrateOnLaunch: Boolean,
    val vibrateOnQuickLaunch: Boolean,
    val vibrateRespectQuiet: Boolean,
    val skipRetryDialog: Boolean,
    /** Quick Launch action enum value. 0 = dictation (default), 1 = notes. */
    val quickLaunchAction: Int,
    /** Confirm-before-dispatch toggle. When true, destructive intents
     *  (call, sms, reminder, etc.) round-trip through a watch confirm prompt
     *  before the companion runs them. Default false on a fresh install. */
    val confirmBeforeSend: Boolean,
    /** Auto-resolve timeout for the on-watch confirm prompt, in seconds.
     *  0 = "never time out". Default 15. Only meaningful when
     *  [confirmBeforeSend] is on. */
    val confirmTimeoutSeconds: Int,
    /** When true, the confirm prompt timing out auto-dispatches the action;
     *  when false (default) it cancels. Only meaningful when
     *  [confirmBeforeSend] is on. */
    val confirmDefaultSend: Boolean,
)
