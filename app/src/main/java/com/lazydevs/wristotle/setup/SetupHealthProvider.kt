// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.setup

import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.ui.SettingsCategory
import com.lazydevs.wristotle.ui.nav.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Derives the list of **pending** recommended setup actions from live
 * device state. Backs the Settings → 🌟 Setup screen and (Phase B) the
 * first-launch wizard.
 *
 * Why not derive reactively from individual `StateFlow`s? A handful of
 * inputs (runtime-permission grants, contacts/SMS) don't have a Flow
 * API on Android — the system delivers them via Activity results and
 * onResume. So instead the provider holds a single `actions`
 * StateFlow, the screen calls [refresh] from a `LaunchedEffect(Unit)`
 * + on resume, and the rest of the app calls `refresh()` after any
 * action that could shift state (model download finishes, alias added,
 * permission granted, etc.).
 *
 * Pure derivation — no persistence of its own. Source of truth stays
 * in each owning subsystem (ModelStorage, AppIndex, etc.).
 */
class SetupHealthProvider(
    private val scope: CoroutineScope,
    /** Reads `ModelFileStorage.activeModelId` for Whisper. */
    private val whisperActiveModelId: () -> String?,
    /** Reads `ModelFileStorage.activeModelId` for the NLU model. */
    private val nluActiveModelId: () -> String?,
    /** Reads `InstalledAppDao.latestScanAt()` — null when never scanned. */
    private val latestAppScanAt: suspend () -> Long?,
    /** Reads `PebbleCompanionDetector.state.value.whisperAppliesToWatchDictation`. */
    private val whisperInWatchPath: () -> Boolean,
    private val appAliasCount: () -> Int,
    private val contactAliasCount: () -> Int,
    private val isSpeechProviderConfigured: () -> Boolean,
    private val isAskAgentConfigured: () -> Boolean,
    /** True when all four runtime permissions Wristotle needs for the
     *  headline flows (Contacts / SMS / Phone / Microphone) are granted. */
    private val hasCorePermissions: () -> Boolean,
    /** True on devices Android flags as memory-constrained — gates the
     *  NLU recommendation (the embedder is the heaviest optional thing
     *  on the menu). */
    private val isLowRamDevice: () -> Boolean,
) {

    private val _actions = MutableStateFlow<List<RecommendedAction>>(emptyList())
    /** Pending actions only — sorted Essential before Quality, then by
     *  [ActionId.ordinal] for stable ordering. */
    val actions: StateFlow<List<RecommendedAction>> = _actions.asStateFlow()

    init { refresh() }

    /**
     * Re-evaluates every action source against the current device state
     * and republishes [actions]. Callers fire this:
     *  - On Setup-screen open and resume (the always-cheap default).
     *  - After any action completes (model download, permission grant,
     *    alias added, etc.) so the screen + wizard reflect progress
     *    without forcing the user to re-enter the surface.
     *
     * IO-bound bits (DAO calls, low-RAM detection) run on
     * [Dispatchers.IO]; the StateFlow update itself is cheap.
     */
    fun refresh() {
        scope.launch {
            _actions.value = derive()
        }
    }

    private suspend fun derive(): List<RecommendedAction> {
        val out = mutableListOf<RecommendedAction>()

        // ── Essentials ──────────────────────────────────────────────
        // Whisper: only nudge under microPebble — Core Devices runs
        // its own dictation pipeline, our Whisper isn't in the path.
        if (whisperInWatchPath() && whisperActiveModelId() == null) {
            out += RecommendedAction(
                id = ActionId.DownloadWhisperModel,
                titleRes = R.string.setup_action_whisper_title,
                rationaleRes = R.string.setup_action_whisper_rationale,
                priority = Priority.Essential,
                drillTarget = SetupDrillTarget.SettingsSub(SettingsCategory.Models),
            )
        }
        // NLU: skip on low-RAM devices — the classifier is gated out
        // there, so nudging the download is pointless. Drill to
        // Learning, where NluModelsCard lives alongside the other NLU
        // controls (learning toggle, saved phrases) — landing on Models
        // buries the NLU picker below WhisperModelsCard.
        if (!isLowRamDevice() && nluActiveModelId() == null) {
            out += RecommendedAction(
                id = ActionId.DownloadNluModel,
                titleRes = R.string.setup_action_nlu_title,
                rationaleRes = R.string.setup_action_nlu_rationale,
                priority = Priority.Essential,
                drillTarget = SetupDrillTarget.SettingsSub(SettingsCategory.Learning),
            )
        }
        if (!hasCorePermissions()) {
            out += RecommendedAction(
                id = ActionId.GrantPermissions,
                titleRes = R.string.setup_action_permissions_title,
                rationaleRes = R.string.setup_action_permissions_rationale,
                priority = Priority.Essential,
                // Permissions has its own bottom-nav tab — not a
                // Settings sub-category. Route there directly.
                drillTarget = SetupDrillTarget.TopLevelTab(Screen.Permissions),
            )
        }
        if (latestAppScanAt() == null) {
            out += RecommendedAction(
                id = ActionId.ScanInstalledApps,
                titleRes = R.string.setup_action_app_scan_title,
                rationaleRes = R.string.setup_action_app_scan_rationale,
                priority = Priority.Essential,
                drillTarget = SetupDrillTarget.SettingsSub(SettingsCategory.Learning),
            )
        }

        // ── Quality wins ────────────────────────────────────────────
        if (contactAliasCount() == 0) {
            out += RecommendedAction(
                id = ActionId.AddContactAlias,
                titleRes = R.string.setup_action_contact_alias_title,
                rationaleRes = R.string.setup_action_contact_alias_rationale,
                priority = Priority.Quality,
                drillTarget = SetupDrillTarget.SettingsSub(SettingsCategory.Learning),
            )
        }
        if (appAliasCount() == 0) {
            out += RecommendedAction(
                id = ActionId.AddAppAlias,
                titleRes = R.string.setup_action_app_alias_title,
                rationaleRes = R.string.setup_action_app_alias_rationale,
                priority = Priority.Quality,
                drillTarget = SetupDrillTarget.SettingsSub(SettingsCategory.Learning),
            )
        }
        if (!isSpeechProviderConfigured()) {
            out += RecommendedAction(
                id = ActionId.ConfigureSpeechProvider,
                titleRes = R.string.setup_action_speech_provider_title,
                rationaleRes = R.string.setup_action_speech_provider_rationale,
                priority = Priority.Quality,
                drillTarget = SetupDrillTarget.SettingsSub(SettingsCategory.Models),
            )
        }
        if (!isAskAgentConfigured()) {
            out += RecommendedAction(
                id = ActionId.SetUpAskAgent,
                titleRes = R.string.setup_action_ask_agent_title,
                rationaleRes = R.string.setup_action_ask_agent_rationale,
                priority = Priority.Quality,
                drillTarget = SetupDrillTarget.SettingsSub(SettingsCategory.AskAgent),
            )
        }

        return out.sortedWith(compareBy({ it.priority.ordinal }, { it.id.ordinal }))
    }

}