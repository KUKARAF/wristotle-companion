package com.lazydevs.wristotle.backup

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

    // Secrets — default OFF
    val weatherApiKey: Boolean = false,
    val mcpAuthHeaders: Boolean = false,
    val askAgentApiKeys: Boolean = false,
) {
    /** True when every category is selected. Drives the "Select all" master checkbox. */
    val allSelected: Boolean
        get() = notes && tasks && conversations && reminders && nluLearned &&
            appAliases && contactAliases && audioRecordings &&
            appPreferences && weatherSettings && mcpServers && askAgentSetup &&
            weatherApiKey && mcpAuthHeaders && askAgentApiKeys

    /** True when nothing is selected — used to disable the Export button. */
    val noneSelected: Boolean
        get() = !(notes || tasks || conversations || reminders || nluLearned ||
            appAliases || contactAliases || audioRecordings ||
            appPreferences || weatherSettings || mcpServers || askAgentSetup ||
            weatherApiKey || mcpAuthHeaders || askAgentApiKeys)

    /** True when any secret category is ticked — used to gate the
     *  "plaintext secrets?" confirm dialog when no password is set. */
    val anySecretSelected: Boolean
        get() = weatherApiKey || mcpAuthHeaders || askAgentApiKeys

    companion object {
        /** Every category on — what the master "Select all" produces. */
        val ALL = BackupSelection(
            weatherApiKey = true,
            mcpAuthHeaders = true,
            askAgentApiKeys = true,
        )

        /** Every category off — used by the master deselect. */
        val NONE = BackupSelection(
            notes = false, tasks = false, conversations = false, reminders = false,
            nluLearned = false, appAliases = false, contactAliases = false,
            audioRecordings = false,
            appPreferences = false, weatherSettings = false, mcpServers = false,
            askAgentSetup = false,
        )

        /** Older ZIPs (schema < 2) didn't carry a selection field — treat
         *  them as if everything WAS selected (matching the pre-feature
         *  "everything in the ZIP" behaviour). */
        val LEGACY_FULL = ALL
    }
}
