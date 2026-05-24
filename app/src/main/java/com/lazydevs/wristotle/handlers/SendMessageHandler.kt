package com.lazydevs.wristotle.handlers

import android.content.Context
import android.util.Log
import com.lazydevs.wristotle.messaging.MessagingTargets
import com.lazydevs.wristotle.messaging.isInstalled
import com.lazydevs.wristotle.phone.ContactsRepository
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult

private const val TAG = "SendMessageHandler"

/**
 * Handles [Intent.SendMessage] — opens a third-party messaging app's compose
 * screen with the contact + body pre-filled. The user only has to tap Send.
 *
 * This is the **assisted-send** path. Unlike [SmsHandler], which sends
 * programmatically via [android.telephony.SmsManager.sendTextMessage] with
 * the SEND_SMS runtime permission, messaging apps gatekeep their
 * MediaBrowser / Service / API surfaces against sideloaded callers — only
 * Google-Assistant-signed apps can drive `transportControls.play()` or
 * equivalent send actions. The available pathway is ACTION_VIEW with the
 * app's deep-link scheme, which lands the user on a pre-filled compose
 * screen. One tap to confirm + send.
 *
 * Slots required (from [SendMessageSlots]):
 *   - `app`     — display name of the target ("WhatsApp", "Telegram", "Signal")
 *   - `contact` — recipient name
 *   - `body`    — message text
 *
 * Failure modes the handler reports back over the watch chat:
 *   - "Don't know that messaging app" — `app` slot missing or no registry match
 *   - "<App> isn't installed" — registry has it but the user doesn't
 *   - "Contacts permission not granted" — gate before lookup
 *   - "Contact not found: <name>" — ContactsRepository returns null
 *   - "Could not open <App>" — startActivity threw (rare; usually means
 *     the deep-link scheme isn't honored on this device)
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

        val target = MessagingTargets.find(appName)
            ?: return "Don't know that messaging app: $appName"

        if (!target.isInstalled(context)) return "${target.displayName} isn't installed"

        if (!contacts.hasPermission()) return "Contacts permission not granted"

        val contactName = (result.slots["contact"] as? String)?.trim().orEmpty()
        val body = (result.slots["body"] as? String)?.trim().orEmpty()
        if (contactName.isEmpty()) return "No contact specified"
        if (body.isEmpty()) return "No message body"

        val contact = contacts.findContact(contactName)
            ?: return "Contact not found: $contactName"

        return try {
            val intent = target.buildIntent(contact.number, body)
            context.startActivity(intent)
            // We say "Opened <App>" rather than "Sent" because the user
            // still has to tap Send. Don't lie about the state.
            "Opened ${target.displayName} for ${contact.name}"
        } catch (e: Exception) {
            Log.w(TAG, "startActivity failed for ${target.displayName} / ${contact.name}", e)
            "Could not open ${target.displayName}"
        }
    }
}
