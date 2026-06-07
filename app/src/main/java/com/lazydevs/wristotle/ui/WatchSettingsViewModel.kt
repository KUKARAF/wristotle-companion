// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.settings.WatchSettings
import com.lazydevs.wristotle.settings.WatchSettingsRepository
import com.lazydevs.wristotle.settings.WatchSettingsState
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin façade over [WatchSettingsRepository] for the Compose layer.
 *
 * Edits are NOT auto-saved: the card holds a local draft and only commits
 * via [save] when the user taps Save. [reset] is called when the Settings
 * page is left so the section re-loads fresh on the next visit.
 */
class WatchSettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: WatchSettingsRepository =
        (app as WristotleApplication).watchSettingsRepository

    val state: StateFlow<WatchSettingsState> = repo.state

    fun refresh() = repo.refresh()

    fun reset() = repo.reset()

    fun save(updated: WatchSettings) = repo.save(updated)
}