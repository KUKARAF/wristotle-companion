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
)
