// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.setup

import androidx.annotation.StringRes
import com.lazydevs.wristotle.ui.SettingsCategory
import com.lazydevs.wristotle.ui.nav.Screen

/**
 * One recommended setup action surfaced by [SetupHealthProvider].
 *
 * The provider emits only **pending** actions — items already complete
 * for the current device fall out of the list. The UI doesn't need to
 * filter; it iterates whatever the provider returns.
 *
 * Two priority buckets, [Priority.Essential] vs [Priority.Quality],
 * drive (a) the first-launch wizard's gating (essentials only) and
 * (b) the Setup category's section grouping.
 */
data class RecommendedAction(
    val id: ActionId,
    @param:StringRes val titleRes: Int,
    @param:StringRes val rationaleRes: Int,
    val priority: Priority,
    /** Where the "Open" button lands. Most actions point to a Settings
     *  sub-category; a few (e.g. permissions) live on a top-level tab
     *  outside the Settings drill-down. The user finishes the action
     *  with the existing familiar UI rather than a wizard re-skin of
     *  the same controls. */
    val drillTarget: SetupDrillTarget,
)

/** Either a Settings sub-screen or a top-level bottom-nav tab.
 *
 *  Two variants because most recommended actions are exposed as cards
 *  inside the Settings drill-down (Models, Learning, Weather…) — but
 *  [Screen.Permissions] is its own top-level tab, so an action that
 *  routes to it can't be expressed as a [SettingsCategory]. */
sealed interface SetupDrillTarget {
    data class SettingsSub(val category: SettingsCategory) : SetupDrillTarget
    data class TopLevelTab(val screen: Screen) : SetupDrillTarget
}

/**
 * Stable identifier for each recommendation. Used as the persistence key
 * for any per-action state we add later (e.g. "user dismissed this
 * specific tip but not the whole wizard") and as the key in tests.
 */
enum class ActionId {
    DownloadWhisperModel,
    DownloadNluModel,
    GrantPermissions,
    ScanInstalledApps,
    AddContactAlias,
    AddAppAlias,
    ConfigureSpeechProvider,
    SetUpAskAgent,
    ActivateVoiceService,
}

/**
 * Two-tier prioritization.
 *
 * [Essential] = without this the app's core experience feels broken
 * (no transcription, no intent classification, no permissions).
 * The first-launch wizard steps through these one-by-one.
 *
 * [Quality] = the app works without these but is noticeably better
 * with them. They're shown in the persistent Setup category but the
 * wizard skips them.
 */
enum class Priority { Essential, Quality }