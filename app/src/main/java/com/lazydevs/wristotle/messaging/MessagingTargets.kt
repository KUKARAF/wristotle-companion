package com.lazydevs.wristotle.messaging

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log
import com.lazydevs.wristotle.util.hasPermission

private const val TAG = "MessagingTargets"

/**
 * Registry of messaging targets Wristotle knows how to deliver to.
 *
 * Each target's `deliver` lambda was validated empirically — for the
 * intent-based targets, the Phase 0 smoke test confirmed the deep-link
 * lands on a pre-filled compose screen rather than just opening the
 * app. The comments below document each target's chosen pathway:
 *
 * - **SMS** (default fallback target) — programmatic send via
 *   [SmsManager.sendTextMessage] under the `SEND_SMS` runtime
 *   permission. The user dictates "text Mom hi" → message sent, no
 *   tap. Same path SmsHandler used pre-Phase-A2; lifted into the
 *   registry so SendMessageHandler covers it uniformly.
 * - **WhatsApp** uses the native `whatsapp://send?phone=…&text=…`
 *   scheme. Confirmed working: opens the chat with the contact
 *   selected, body in the input box. The phone number must be
 *   normalised to E.164 (`+` then country code then digits, no
 *   separators); we strip anything else from the ContactsRepository
 *   number we hand in.
 * - **Telegram** documents `tg://msg?to=…&text=…` for compose entry,
 *   but the `to=` parameter empirically requires a Telegram username
 *   rather than a phone number for most accounts — phone-based open
 *   lands on the in-app search screen instead. Treated as best-effort;
 *   the user may need to pick the contact. Real validation pending.
 * - **Signal** has no documented deep-link that includes both contact
 *   AND body. We fire ACTION_SEND text/plain with EXTRA_TEXT scoped to
 *   the Signal package — Signal opens its contact picker with the body
 *   queued. User picks the contact, then taps Send. Two-tap UX but
 *   maximum compatibility.
 *
 * Adding a new app: append to [ALL] with a validated `deliver`
 * implementation.
 */
object MessagingTargets {

    /**
     * Default fallback target — picked by [SendMessageSlots] when no
     * explicit app name was named in the query. Sends programmatically
     * via [SmsManager]; no compose screen is shown to the user.
     */
    val Sms = MessagingTarget(
        displayName = "SMS",
        // Empty packageId signals "system service" to isInstalled().
        // SMS has no single app to install-check against (the user's
        // default messaging app varies); SmsManager is what actually
        // sends, and that's always present on a phone.
        packageId = "",
        spokenAliases = emptySet(),
        deliver = { context, phone, body, contactName ->
            when {
                !context.hasPermission(Manifest.permission.SEND_SMS) ->
                    "SMS permission not granted"
                body.isEmpty() -> "No message body"
                else -> try {
                    val smsManager = context.getSystemService(SmsManager::class.java)
                    smsManager.sendTextMessage(phone, null, body, null, null)
                    "Sent to $contactName"
                } catch (e: Exception) {
                    Log.w(TAG, "sendTextMessage failed for $contactName", e)
                    "Could not send to $contactName"
                }
            }
        },
    )

    val WhatsApp = MessagingTarget(
        displayName = "WhatsApp",
        packageId = "com.whatsapp",
        // Whisper occasionally splits or mangles "WhatsApp" — capture
        // the common variants so the slot extractor doesn't fall
        // through on a mistranscription that's obviously meant for
        // WhatsApp. "what's up" is a real-world Whisper output for
        // "WhatsApp" — caught when a user dictated "WhatsApp John, this
        // is a test" and the transcript came back as "What's up John,
        // this is the guest message." Tradeoff: a casual greeting
        // starting with "what's up" could now route to WhatsApp;
        // mitigated because the leading-app match requires the alias at
        // the very start AND something to follow (no body → handler
        // returns "No message body" before anything sends).
        spokenAliases = setOf(
            "whatsapp", "whats app", "whatapp", "watsapp",
            "what's up", "what's app", "whatsap",
        ),
        deliver = { context, phone, body, contactName ->
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(
                    "whatsapp://send?phone=${normalisePhone(phone)}&text=${Uri.encode(body)}"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            launchIntent(context, intent, "WhatsApp", contactName)
        },
    )

    val Telegram = MessagingTarget(
        displayName = "Telegram",
        packageId = "org.telegram.messenger",
        spokenAliases = setOf("telegram"),
        deliver = { context, phone, body, contactName ->
            val intent = Intent(Intent.ACTION_VIEW).apply {
                // Empirically risky — see comment block above. Sticking
                // with the documented form for now; we'll iterate if
                // testing shows it lands on search instead of a
                // contact's chat.
                data = Uri.parse(
                    "tg://msg?to=${normalisePhone(phone)}&text=${Uri.encode(body)}"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            launchIntent(context, intent, "Telegram", contactName)
        },
    )

    val Signal = MessagingTarget(
        displayName = "Signal",
        packageId = "org.thoughtcrime.securesms",
        spokenAliases = setOf("signal"),
        deliver = { context, _, body, contactName ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage("org.thoughtcrime.securesms")
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            launchIntent(context, intent, "Signal", contactName)
        },
    )

    /**
     * All targets a user might name explicitly. SMS is excluded — it's
     * the default fallback, never selected by name from speech (no
     * spoken aliases). [find] only walks this list.
     */
    val NAMED: List<MessagingTarget> = listOf(WhatsApp, Telegram, Signal)

    /** Every target including the default. Used by tests + iteration. */
    val ALL: List<MessagingTarget> = NAMED + Sms

    /**
     * Resolve a spoken app name to a [NAMED] target, or null if no
     * alias matches. Case- and whitespace-insensitive. SMS is NOT
     * returned from here — request it via [Sms] directly or via the
     * `"SMS"` display name (see [findByDisplayName]).
     */
    fun find(spokenName: String): MessagingTarget? {
        val normalised = spokenName.lowercase().trim()
        if (normalised.isEmpty()) return null
        return NAMED.firstOrNull { normalised in it.spokenAliases }
    }

    /**
     * Resolve a target by its display name (`"WhatsApp"`, `"SMS"`,
     * etc.). Used by [com.lazydevs.wristotle.handlers.SendMessageHandler]
     * to look up the target the slot extractor named in
     * `slots["app"]`. The slot extractor emits display names so the
     * handler's lookup is exact and case-insensitive.
     */
    fun findByDisplayName(displayName: String): MessagingTarget? {
        val normalised = displayName.trim()
        if (normalised.isEmpty()) return null
        return ALL.firstOrNull { it.displayName.equals(normalised, ignoreCase = true) }
    }

    /**
     * Common intent-launch helper for the three assisted-send targets —
     * fires the intent, returns the *"Opened X for Y"* response on
     * success, or a graceful failure string if the intent can't start.
     */
    private fun launchIntent(
        context: android.content.Context,
        intent: Intent,
        displayName: String,
        contactName: String,
    ): String = try {
        context.startActivity(intent)
        // "Opened" not "Sent" — the user still has to tap Send in the
        // target app. Don't lie about the state.
        "Opened $displayName for $contactName"
    } catch (e: Exception) {
        Log.w(TAG, "startActivity failed for $displayName / $contactName", e)
        "Could not open $displayName"
    }

    /**
     * Strip everything that isn't a digit, then ensure a leading `+` so
     * the result is E.164. Deep-link schemes (especially WhatsApp's)
     * require this form; ContactsRepository hands us numbers with
     * spaces / parens / dashes depending on how the contact was saved.
     */
    private fun normalisePhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (digits.isNotEmpty()) "+$digits" else ""
    }
}
