package com.lazydevs.wristotle.messaging

import android.content.Intent
import android.net.Uri

/**
 * Registry of messaging apps Wristotle knows how to deep-link into.
 *
 * Each target's `buildIntent` was validated empirically in the Phase 0
 * smoke test (see `project_watch_launch_bal_wall` memory + the v0.8.3 BAL
 * findings) to confirm the deep-link delivers a pre-filled compose screen
 * rather than just opening the app. The body of this comment block
 * documents each app's intent shape so future debugging starts from "this
 * is the path we verified" rather than "let's guess again":
 *
 * - **WhatsApp** uses the native `whatsapp://send?phone=…&text=…` scheme.
 *   Confirmed working: opens the chat with the contact selected, body
 *   in the input box. The phone number must be normalised to E.164
 *   (`+` then country code then digits, no separators); we strip
 *   anything else from the ContactsRepository number we hand in.
 * - **Telegram** documents `tg://msg?to=…&text=…` for compose entry, but
 *   the `to=` parameter empirically requires a Telegram username rather
 *   than a phone number for most accounts — phone-based open lands on
 *   the in-app search screen instead. Treated as a best-effort; the
 *   user may need to pick the contact. Real validation pending; if it
 *   doesn't work reliably we'll either drop Telegram or fall back to
 *   ACTION_SEND + the contact picker, same as Signal.
 * - **Signal** has no documented deep-link that includes both contact
 *   AND body. We fire ACTION_SEND text/plain with EXTRA_TEXT scoped to
 *   the Signal package — Signal opens its contact picker with the body
 *   queued. User picks the contact, then taps Send. Two-tap UX but
 *   maximum compatibility.
 *
 * Adding a new app: append to [ALL] with the validated intent shape.
 */
object MessagingTargets {

    val WhatsApp = MessagingTarget(
        displayName = "WhatsApp",
        packageId = "com.whatsapp",
        // Whisper occasionally splits or mangles "WhatsApp" — capture the
        // common variants so the slot extractor doesn't fall through on a
        // mistranscription that's obviously meant for WhatsApp.
        // "what's up" is a real-world Whisper output for "WhatsApp" — caught
        // when a user dictated "WhatsApp John, this is a test" and the
        // transcript came back as "What's up John, this is the guest
        // message." Tradeoff: a casual greeting starting with "what's up"
        // could now route to WhatsApp; mitigated because the leading-app
        // match requires the alias at the very start AND something to
        // follow (no body → handler returns "No message body" before
        // anything sends).
        spokenAliases = setOf(
            "whatsapp", "whats app", "whatapp", "watsapp",
            "what's up", "what's app", "whatsap",
        ),
        buildIntent = { phone, body ->
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(
                    "whatsapp://send?phone=${normalisePhone(phone)}&text=${Uri.encode(body)}"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        },
    )

    val Telegram = MessagingTarget(
        displayName = "Telegram",
        packageId = "org.telegram.messenger",
        spokenAliases = setOf("telegram"),
        buildIntent = { phone, body ->
            Intent(Intent.ACTION_VIEW).apply {
                // Empirically risky — see comment block above. Sticking with
                // the documented form for now; we'll iterate if testing
                // shows it lands on search instead of a contact's chat.
                data = Uri.parse(
                    "tg://msg?to=${normalisePhone(phone)}&text=${Uri.encode(body)}"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        },
    )

    val Signal = MessagingTarget(
        displayName = "Signal",
        packageId = "org.thoughtcrime.securesms",
        spokenAliases = setOf("signal"),
        buildIntent = { _, body ->
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage("org.thoughtcrime.securesms")
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        },
    )

    /** Order matters when iterating — first match wins. Aliases are
     *  disjoint across the targets so order isn't load-bearing today,
     *  but keep the popular apps first for readability. */
    val ALL: List<MessagingTarget> = listOf(WhatsApp, Telegram, Signal)

    /** Resolve a spoken app name to a target, or null if no alias matches. */
    fun find(spokenName: String): MessagingTarget? {
        val normalised = spokenName.lowercase().trim()
        if (normalised.isEmpty()) return null
        return ALL.firstOrNull { normalised in it.spokenAliases }
    }

    /**
     * Strip everything that isn't a digit, then ensure a leading `+` so the
     * result is E.164. Deep-link schemes (especially WhatsApp's) require
     * this form; ContactsRepository hands us numbers with spaces / parens /
     * dashes depending on how the contact was saved.
     */
    private fun normalisePhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (digits.isNotEmpty()) "+$digits" else ""
    }
}
