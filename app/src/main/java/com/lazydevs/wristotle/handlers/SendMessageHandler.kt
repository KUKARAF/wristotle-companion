package com.lazydevs.wristotle.handlers

import android.content.Context
import com.lazydevs.wristotle.messaging.MessagingTargets
import com.lazydevs.wristotle.messaging.isInstalled
import com.lazydevs.wristotle.phone.ContactsRepository
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult

/**
 * Handles [Intent.SendMessage] — the single voice path for sending a
 * message to a contact, regardless of which app delivers it.
 *
 * Two delivery shapes, both expressed uniformly through
 * [com.lazydevs.wristotle.messaging.MessagingTarget.deliver]:
 *  - **SMS** (default when no app is named) — programmatic send via
 *    [android.telephony.SmsManager.sendTextMessage], no user tap.
 *  - **Third-party messaging apps** (WhatsApp / Telegram / Signal) —
 *    assisted-send via ACTION_VIEW deep-link, opens compose pre-filled,
 *    user taps Send. Messaging apps gatekeep their MediaBrowser /
 *    Service / API surfaces against sideloaded callers — only
 *    Google-Assistant-signed apps can drive a programmatic send. The
 *    available pathway is the deep-link scheme.
 *
 * Slots required (from [SendMessageSlots]):
 *   - `app`     — display name of the target (`"SMS"`, `"WhatsApp"`,
 *                  `"Telegram"`, `"Signal"`)
 *   - `contact` — recipient name
 *   - `body`    — message text
 *
 * Failure modes the handler reports back over the watch chat:
 *   - "Don't know that messaging app" — `app` slot missing or unknown
 *   - "<App> isn't installed" — registry has it but not on device
 *     (SMS is exempt — system service, always installed)
 *   - "Contacts permission not granted" — gate before lookup
 *   - "Contact not found: <name>" — ContactsRepository returns null
 *   - delivery-specific failures — surfaced verbatim by the target's
 *     `deliver` lambda ("SMS permission not granted", "Could not open
 *     WhatsApp", etc.)
 */
class SendMessageHandler(
    private val context: Context,
    private val contacts: ContactsRepository,
) : ActionHandler {

    override val tag: String = "send-message"
    override val intent: Intent = Intent.SendMessage

    override suspend fun handle(result: IntentResult): String {
        val appName = (result.slots["app"] as? String)?.trim().orEmpty()
        if (appName.isEmpty()) return "Don't know that messaging app"

        val target = MessagingTargets.findByDisplayName(appName)
            ?: return "Don't know that messaging app: $appName"

        if (!target.isInstalled(context)) return "${target.displayName} isn't installed"

        if (!contacts.hasPermission()) return "Contacts permission not granted"

        val contactName = (result.slots["contact"] as? String)?.trim().orEmpty()
        val body = (result.slots["body"] as? String)?.trim().orEmpty()
        if (contactName.isEmpty()) return "No contact specified"
        if (body.isEmpty()) return "No message body"

        val contact = contacts.findContact(contactName)
            ?: return "Contact not found: $contactName"

        return target.deliver(context, contact.number, body, contact.name)
    }
}
