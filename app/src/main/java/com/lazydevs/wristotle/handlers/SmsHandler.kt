package com.lazydevs.wristotle.handlers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.lazydevs.wristotle.phone.ContactsRepository
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult

private const val TAG = "SmsHandler"

/**
 * Handles [Intent.Sms] — sends an SMS via [SmsManager] to `slots["contact"]`
 * with `slots["body"]` as the message. Requires READ_CONTACTS and SEND_SMS
 * permissions.
 *
 * Slot extractor (`SmsSlots`) does the contact + body split with a greedy
 * contact-name lookup against [ContactsRepository], so multi-word contacts
 * resolve correctly (the prior prefix-split-on-first-space approach broke
 * "text john smith hi").
 */
class SmsHandler(
    private val context: Context,
    private val contacts: ContactsRepository,
) : ActionHandler {

    override val tag: String = "sms"
    override val intent: Intent = Intent.Sms

    override suspend fun handle(result: IntentResult): String {
        if (!contacts.hasPermission()) return "Contacts permission not granted"
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) return "SMS permission not granted"

        val contactName = (result.slots["contact"] as? String)?.trim().orEmpty()
        val body = (result.slots["body"] as? String)?.trim().orEmpty()
        if (contactName.isEmpty()) return "No contact specified"
        if (body.isEmpty()) return "No message body"

        val contact = contacts.findContact(contactName)
            ?: return "Contact not found: $contactName"

        return try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            smsManager.sendTextMessage(contact.number, null, body, null, null)
            "Sent to ${contact.name}"
        } catch (e: Exception) {
            Log.w(TAG, "sendTextMessage failed for ${contact.name}", e)
            "Could not send to ${contact.name}"
        }
    }
}
