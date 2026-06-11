// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.backup

/**
 * Per-category opt-in for what lands in a backup ZIP — set on export by
 * the user via checkboxes on `BackupCard`, also recorded inside the
 * manifest so import can pre-tick what's actually in the ZIP.
 *
 * Data + non-sensitive settings default to `true`; **secrets default to
 * `false`** so a "just hit Export" flow doesn't silently leak API keys
 * into a plaintext ZIP. The user opts secrets in deliberately, ideally
 * paired with the ZIP-encryption toggle.
 *
 * Categories live in three groups for the UI (Content / Settings /
 * Secrets) but they're flat fields here — the grouping is a Compose
 * concern, not a data-shape one.
 */
data class BackupSelection(
    // Content
    val notes: Boolean = true,
    val tasks: Boolean = true,
    val conversations: Boolean = true,
    val reminders: Boolean = true,
    val nluLearned: Boolean = true,
    val appAliases: Boolean = true,
    val contactAliases: Boolean = true,
    val audioRecordings: Boolean = true,

    // Settings (non-sensitive)
    val appPreferences: Boolean = true,
    val weatherSettings: Boolean = true,
    val mcpServers: Boolean = true,
    val askAgentSetup: Boolean = true,
    val sttProviderSetup: Boolean = true,
    val ttsProviderSetup: Boolean = true,

    // Secrets — default OFF
    val weatherApiKey: Boolean = false,
    val mcpAuthHeaders: Boolean = false,
    val askAgentApiKeys: Boolean = false,
    val sttProviderApiKey: Boolean = false,
    val ttsProviderApiKey: Boolean = false,
) {
    /** True when EVERY category — content, settings, and secrets — is ticked.
     *  [ALL] is the only value that satisfies this. */
    val allSelected: Boolean
        get() = notes && tasks && conversations && reminders && nluLearned &&
            appAliases && contactAliases && audioRecordings &&
            appPreferences && weatherSettings && mcpServers && askAgentSetup && sttProviderSetup &&
            ttsProviderSetup &&
            weatherApiKey && mcpAuthHeaders && askAgentApiKeys && sttProviderApiKey &&
            ttsProviderApiKey

    /** True when every content + settings box is ticked, regardless of
     *  whether the user opted any secrets in. Drives the master "Select
     *  all" checkbox's *displayed* state — secrets default OFF, so requiring
     *  them in `allSelected` would leave the master row visually unchecked
     *  even when the user has ticked everything they expected to. */
    val allContentAndSettingsSelected: Boolean
        get() = notes && tasks && conversations && reminders && nluLearned &&
            appAliases && contactAliases && audioRecordings &&
            appPreferences && weatherSettings && mcpServers && askAgentSetup && sttProviderSetup &&
            ttsProviderSetup

    /** True when nothing is selected — used to disable the Export button. */
    val noneSelected: Boolean
        get() = !(notes || tasks || conversations || reminders || nluLearned ||
            appAliases || contactAliases || audioRecordings ||
            appPreferences || weatherSettings || mcpServers || askAgentSetup || sttProviderSetup ||
            ttsProviderSetup ||
            weatherApiKey || mcpAuthHeaders || askAgentApiKeys || sttProviderApiKey ||
            ttsProviderApiKey)

    /** True when any secret category is ticked — used to gate the
     *  "plaintext secrets?" confirm dialog when no password is set. */
    val anySecretSelected: Boolean
        get() = weatherApiKey || mcpAuthHeaders || askAgentApiKeys || sttProviderApiKey ||
            ttsProviderApiKey

    /** Per-field AND of two selections. Used by the restore preview to
     *  clamp the user's requested selection against what's actually in
     *  the ZIP (`requested and available`). One source of truth so a
     *  future field addition only updates [BackupSelection.kt] instead of
     *  having to also touch `BackupViewModel.clamp` / `allSelected` / etc. */
    infix fun and(other: BackupSelection): BackupSelection = BackupSelection(
        notes = notes && other.notes,
        tasks = tasks && other.tasks,
        conversations = conversations && other.conversations,
        reminders = reminders && other.reminders,
        nluLearned = nluLearned && other.nluLearned,
        appAliases = appAliases && other.appAliases,
        contactAliases = contactAliases && other.contactAliases,
        audioRecordings = audioRecordings && other.audioRecordings,
        appPreferences = appPreferences && other.appPreferences,
        weatherSettings = weatherSettings && other.weatherSettings,
        mcpServers = mcpServers && other.mcpServers,
        askAgentSetup = askAgentSetup && other.askAgentSetup,
        sttProviderSetup = sttProviderSetup && other.sttProviderSetup,
        ttsProviderSetup = ttsProviderSetup && other.ttsProviderSetup,
        weatherApiKey = weatherApiKey && other.weatherApiKey,
        mcpAuthHeaders = mcpAuthHeaders && other.mcpAuthHeaders,
        askAgentApiKeys = askAgentApiKeys && other.askAgentApiKeys,
        sttProviderApiKey = sttProviderApiKey && other.sttProviderApiKey,
        ttsProviderApiKey = ttsProviderApiKey && other.ttsProviderApiKey,
    )

    companion object {
        /** Every category on — what the master "Select all" produces. */
        val ALL = BackupSelection(
            weatherApiKey = true,
            mcpAuthHeaders = true,
            askAgentApiKeys = true,
            sttProviderApiKey = true,
            ttsProviderApiKey = true,
        )

        /** Every category off — used by the master deselect. */
        val NONE = BackupSelection(
            notes = false, tasks = false, conversations = false, reminders = false,
            nluLearned = false, appAliases = false, contactAliases = false,
            audioRecordings = false,
            appPreferences = false, weatherSettings = false, mcpServers = false,
            askAgentSetup = false, sttProviderSetup = false, ttsProviderSetup = false,
        )

        /** Older ZIPs (schema < 2) didn't carry a selection field — treat
         *  them as if everything WAS selected (matching the pre-feature
         *  "everything in the ZIP" behaviour).
         *
         *  Spelled out as an explicit literal rather than `= ALL` so that
         *  if the "Select all" UX ever changes (e.g. ALL stops including
         *  secrets), schema-1 restore semantics don't silently shift —
         *  schema-1 ZIPs predate the secrets split and DID contain
         *  whatever was in the user's backup, so we always treat them as
         *  fully-available regardless of how ALL evolves. */
        val LEGACY_FULL = BackupSelection(
            notes = true, tasks = true, conversations = true, reminders = true,
            nluLearned = true, appAliases = true, contactAliases = true,
            audioRecordings = true,
            appPreferences = true, weatherSettings = true, mcpServers = true,
            askAgentSetup = true, sttProviderSetup = true, ttsProviderSetup = true,
            weatherApiKey = true, mcpAuthHeaders = true, askAgentApiKeys = true,
            sttProviderApiKey = true, ttsProviderApiKey = true,
        )
    }
}