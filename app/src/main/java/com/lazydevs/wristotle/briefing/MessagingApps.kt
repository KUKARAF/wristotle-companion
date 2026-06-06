package com.lazydevs.wristotle.briefing

/**
 * Substring-token → display-label lookup for messaging apps. Used by
 * the Morning Brief's unread-messages section to bucket active
 * notifications by app.
 *
 * Substring rather than exact package-id match because:
 *  - OEM and ROM variants (Pixel's `com.google.android.apps.messaging`,
 *    Samsung's `com.samsung.android.messaging`, AOSP / GrapheneOS's
 *    `com.android.messaging`) all carry the same `"messaging"` token,
 *    so one entry covers every spelling.
 *  - WhatsApp Business (`com.whatsapp.w4b`) and Telegram web
 *    (`org.telegram.messenger.web`) collapse into the parent label
 *    automatically.
 *
 * Tokens are matched against the lowercased package id; the first
 * matching token wins, so order them most-specific to least-specific
 * if any overlap.
 *
 * Anything not matched here is bucketed as "other notification" by
 * [UnreadMessagesProvider] — the brief still surfaces the count so
 * the user has a sense of how full their notification tray is.
 */
internal object MessagingApps {

    /**
     * Substring tokens. Each (token, label) pair tells the brief
     * "if the package id contains <token>, call it <label>".
     * Order matters when tokens could overlap (e.g. `thoughtcrime`
     * for Signal sits above any short `signal` token to avoid false
     * positives like `com.example.signalprocessing`).
     */
    private val tokens: List<Pair<String, String>> = listOf(
        // Specific-package matches that share generic tokens with
        // other apps — these sit FIRST so the right label wins.
        "thoughtcrime.securesms" to "Signal",      // Signal's bundle id
        "org.telegram"           to "Telegram",
        "dynamite"               to "Google Chat", // package shipped by Google Chat
        // Generic / well-named tokens.
        "whatsapp"               to "WhatsApp",
        "messaging"              to "Messages",     // Pixel / Samsung / AOSP / GrapheneOS
        "messenger"              to "Messages",     // legacy / OEM
        "slack"                  to "Slack",
        "discord"                to "Discord",
        "microsoft.teams"        to "Teams",
        "gmail"                  to "Gmail",
        // Facebook Messenger — historical package id is com.facebook.orca
        "facebook.orca"          to "Messenger",
    )

    /** Display label for the given package id, or null when no token
     *  matches (the caller buckets it as "other"). */
    fun labelOf(packageId: String): String? {
        val lower = packageId.lowercase()
        return tokens.firstOrNull { (tok, _) -> tok in lower }?.second
    }
}
